package app.projectsteps.core.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A defensive, in-memory ZIP reader.
 *
 * Nothing is ever extracted to disk. The central directory is parsed and validated up front:
 *  - path traversal (`..`, absolute paths, drive letters), excessive folder depth, NUL bytes
 *  - encrypted entries, multi-disk and ZIP64 archives, unknown compression methods
 *  - duplicate names and overlapping entries (a known non-recursive ZIP bomb technique)
 *  - declared sizes and compression ratios
 * Decompression is capped at the declared size and at [ImportLimits.maxEntryBytes], so a
 * lying header cannot make it expand without bound, and every entry's CRC-32 is verified.
 */
class SafeZip private constructor(
    private val data: ByteArray,
    val label: String,
    val entries: List<Entry>,
    private val limits: ImportLimits,
) {
    class Entry internal constructor(
        val name: String,
        val method: Int,
        val flags: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        internal val dataStart: Int,
    ) {
        val isDirectory: Boolean get() = name.endsWith("/")
        val supportedMethod: Boolean get() = method == METHOD_STORED || method == METHOD_DEFLATED
        val baseName: String get() = name.trimEnd('/').substringAfterLast('/')
    }

    private val byName: Map<String, Entry> = entries.associateBy { it.name }
    private var totalExtracted = 0L

    fun entry(name: String): Entry? = byName[name]

    fun has(name: String): Boolean = byName.containsKey(name)

    /** Reads an entry fully into memory, enforcing all limits. */
    fun read(entry: Entry): ByteArray {
        if (entry.isDirectory) return ByteArray(0)
        if (!entry.supportedMethod) {
            throw ImportException("“${entry.baseName}” in “$label” uses an unsupported compression method (${methodName(entry.method)}).")
        }
        if (entry.uncompressedSize > limits.maxEntryBytes) {
            throw ImportException("“${entry.baseName}” in “$label” is too large (${entry.uncompressedSize.toMbString()}, limit ${limits.maxEntryBytes.toMbString()}).")
        }
        if (totalExtracted + entry.uncompressedSize > limits.maxTotalUncompressedBytes) {
            throw ImportException("“$label” expands to more than ${limits.maxTotalUncompressedBytes.toMbString()}. Import fewer files at once.")
        }
        val expected = entry.uncompressedSize.toInt()
        val out: ByteArray = when (entry.method) {
            METHOD_STORED -> {
                if (entry.compressedSize != entry.uncompressedSize) {
                    throw ImportException("“${entry.baseName}” in “$label” is damaged (stored size mismatch).")
                }
                data.copyOfRange(entry.dataStart, entry.dataStart + expected)
            }
            else -> inflate(entry, expected)
        }
        val crc = CRC32()
        crc.update(out)
        if (crc.value != entry.crc) {
            throw ImportException("“${entry.baseName}” in “$label” is damaged (checksum mismatch).")
        }
        totalExtracted += out.size
        return out
    }

    private fun inflate(entry: Entry, expected: Int): ByteArray {
        val compLen = entry.compressedSize.toInt()
        // Inflater in "nowrap" mode may need one extra dummy byte after the raw deflate stream.
        val input = ByteArray(compLen + 1)
        System.arraycopy(data, entry.dataStart, input, 0, compLen)
        val inflater = Inflater(true)
        try {
            inflater.setInput(input)
            val out = ByteArray(expected)
            var produced = 0
            val scratch = ByteArray(1)
            while (produced < expected) {
                val n = try {
                    inflater.inflate(out, produced, expected - produced)
                } catch (e: DataFormatException) {
                    throw ImportException("“${entry.baseName}” in “$label” is damaged and can't be decompressed.", e)
                }
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput()) break
                    if (inflater.needsDictionary()) throw ImportException("“${entry.baseName}” in “$label” uses an unsupported compression feature.")
                }
                produced += n
            }
            if (produced != expected) {
                throw ImportException("“${entry.baseName}” in “$label” is damaged (size mismatch).")
            }
            // Anything beyond the declared size means the header lied: treat as a ZIP bomb.
            if (!inflater.finished()) {
                val extra = try { inflater.inflate(scratch) } catch (e: DataFormatException) { 0 }
                if (extra > 0) throw ImportException("“${entry.baseName}” in “$label” expands beyond its declared size (possible ZIP bomb). Import rejected.")
            }
            return out
        } finally {
            inflater.end()
        }
    }

    companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
        private const val SIG_EOCD = 0x06054b50L
        private const val SIG_CEN = 0x02014b50L
        private const val SIG_LOC = 0x04034b50L
        private const val SIG_ZIP64_LOCATOR = 0x07064b50L

        fun looksLikeZip(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                (bytes[2].toInt() == 3 && bytes[3].toInt() == 4 || bytes[2].toInt() == 5 && bytes[3].toInt() == 6)

        fun open(bytes: ByteArray, limits: ImportLimits = ImportLimits(), label: String = "archive"): SafeZip {
            if (bytes.size > limits.maxInputBytes) {
                throw ImportException("“$label” is too large (${bytes.size.toLong().toMbString()}, limit ${limits.maxInputBytes.toMbString()}).")
            }
            if (bytes.size < 22) throw ImportException("“$label” is not a valid ZIP file (too short).")
            val r = Reader(bytes, label)

            // --- End of central directory ---
            val eocd = findEocd(bytes) ?: throw ImportException("“$label” is not a valid ZIP file or is damaged (no central directory).")
            if (eocd >= 20 && r.u32(eocd - 20) == SIG_ZIP64_LOCATOR) {
                throw ImportException("“$label” uses the ZIP64 format, which isn't supported.")
            }
            val diskNumber = r.u16(eocd + 4)
            val cdDisk = r.u16(eocd + 6)
            val entriesOnDisk = r.u16(eocd + 8)
            val totalEntries = r.u16(eocd + 10)
            val cdSize = r.u32(eocd + 12)
            val cdOffset = r.u32(eocd + 16)
            if (totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                throw ImportException("“$label” uses the ZIP64 format, which isn't supported.")
            }
            if (diskNumber != 0 || cdDisk != 0 || entriesOnDisk != totalEntries) {
                throw ImportException("“$label” is a split (multi-part) archive, which isn't supported.")
            }
            if (totalEntries > limits.maxEntries) {
                throw ImportException("“$label” contains too many files ($totalEntries, limit ${limits.maxEntries}).")
            }
            if (cdOffset + cdSize > eocd) throw ImportException("“$label” is damaged (central directory out of range).")

            // --- Central directory entries ---
            val entries = ArrayList<Entry>(totalEntries)
            val seen = HashSet<String>()
            var p = cdOffset.toInt()
            var declaredTotal = 0L
            repeat(totalEntries) {
                if (r.u32(p) != SIG_CEN) throw ImportException("“$label” is damaged (bad central directory entry).")
                val flags = r.u16(p + 8)
                val method = r.u16(p + 10)
                val crc = r.u32(p + 16)
                val compSize = r.u32(p + 20)
                val uncompSize = r.u32(p + 24)
                val nameLen = r.u16(p + 28)
                val extraLen = r.u16(p + 30)
                val commentLen = r.u16(p + 32)
                val localOffset = r.u32(p + 42)
                r.check(p + 46, nameLen)
                val rawName = decodeName(bytes, p + 46, nameLen, (flags and 0x800) != 0)
                p += 46 + nameLen + extraLen + commentLen
                if (p > eocd) throw ImportException("“$label” is damaged (central directory overruns).")

                val name = validateName(rawName, label, limits)
                if (!seen.add(name)) throw ImportException("“$label” contains the same path twice (“$name”). Import rejected.")
                if ((flags and 0x1) != 0 || (flags and 0x40) != 0) {
                    throw ImportException("“$label” is encrypted or password-protected. Export an unencrypted copy and try again.")
                }
                if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                    throw ImportException("“$label” uses the ZIP64 format, which isn't supported.")
                }
                if (uncompSize > limits.ratioCheckThresholdBytes) {
                    val ratio = if (compSize == 0L) Long.MAX_VALUE else uncompSize / compSize
                    if (ratio > limits.maxCompressionRatio) {
                        throw ImportException("“$label” contains a file with a suspicious compression ratio (possible ZIP bomb). Import rejected.")
                    }
                }
                declaredTotal += uncompSize

                // Local header
                val lo = localOffset.toInt()
                if (localOffset >= cdOffset) throw ImportException("“$label” is damaged (entry outside data area).")
                if (r.u32(lo) != SIG_LOC) throw ImportException("“$label” is damaged (bad local header).")
                val lNameLen = r.u16(lo + 26)
                val lExtraLen = r.u16(lo + 28)
                val dataStart = lo + 30 + lNameLen + lExtraLen
                val dataEnd = dataStart.toLong() + compSize
                if (dataEnd > cdOffset) throw ImportException("“$label” is damaged (entry data out of range).")
                entries.add(Entry(name, method, flags, crc, compSize, uncompSize, localOffset, dataStart))
            }
            if (declaredTotal > limits.maxTotalUncompressedBytes) {
                throw ImportException("“$label” would expand to ${declaredTotal.toMbString()} (limit ${limits.maxTotalUncompressedBytes.toMbString()}). Import rejected.")
            }

            // Overlapping entries are a ZIP bomb technique (many headers pointing at the same data).
            val sorted = entries.sortedBy { it.localHeaderOffset }
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val prevEnd = prev.dataStart.toLong() + prev.compressedSize
                if (prevEnd > sorted[i].localHeaderOffset) {
                    throw ImportException("“$label” contains overlapping files (a ZIP bomb technique). Import rejected.")
                }
            }
            return SafeZip(bytes, label, entries, limits)
        }

        private fun findEocd(b: ByteArray): Int? {
            val min = maxOf(0, b.size - 22 - 0xFFFF)
            var i = b.size - 22
            while (i >= min) {
                if (b[i].toInt() == 0x50 && b[i + 1].toInt() == 0x4b && b[i + 2].toInt() == 0x05 && b[i + 3].toInt() == 0x06) {
                    val commentLen = (b[i + 20].toInt() and 0xff) or ((b[i + 21].toInt() and 0xff) shl 8)
                    if (i + 22 + commentLen <= b.size) return i
                }
                i--
            }
            return null
        }

        private fun decodeName(b: ByteArray, off: Int, len: Int, utf8Flag: Boolean): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(b, off, len)).toString()
            } catch (e: CharacterCodingException) {
                if (utf8Flag) "�" + String(b, off, len, Charsets.ISO_8859_1) else String(b, off, len, Charsets.ISO_8859_1)
            }
        }

        private val DRIVE = Regex("^[A-Za-z]:")

        /** Returns the normalised name or throws for unsafe paths. */
        internal fun validateName(raw: String, label: String, limits: ImportLimits): String {
            fun unsafe(why: String): Nothing =
                throw ImportException("“$label” contains an unsafe path ($why): “${raw.take(80)}”. Import rejected.")
            if (raw.isEmpty()) unsafe("empty name")
            if (raw.length > limits.maxNameLength) unsafe("name too long")
            if (raw.any { it == '\u0000' }) unsafe("NUL character")
            val name = raw.replace('\\', '/')
            if (name.startsWith("/")) unsafe("absolute path")
            if (DRIVE.containsMatchIn(name)) unsafe("drive letter")
            val parts = name.split('/')
            if (parts.any { it == ".." }) unsafe("path traversal")
            val depth = parts.count { it.isNotEmpty() && it != "." }
            if (depth > limits.maxPathDepth) unsafe("folders nested too deeply")
            return name
        }

        fun methodName(method: Int): String = when (method) {
            0 -> "stored"
            8 -> "deflate"
            9 -> "deflate64"
            12 -> "bzip2"
            14 -> "LZMA"
            93 -> "Zstandard"
            95 -> "XZ"
            99 -> "AES encryption"
            else -> "method $method"
        }
    }

    private class Reader(val b: ByteArray, val label: String) {
        fun check(off: Int, len: Int) {
            if (off < 0 || len < 0 || off.toLong() + len > b.size) throw ImportException("“$label” is damaged (truncated).")
        }
        fun u16(off: Int): Int {
            check(off, 2)
            return (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
        }
        fun u32(off: Int): Long {
            check(off, 4)
            return (u16(off).toLong()) or (u16(off + 2).toLong() shl 16)
        }
    }
}
