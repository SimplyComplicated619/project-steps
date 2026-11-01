package app.projectsteps.core.importer

import java.security.MessageDigest

enum class CandidateType { DOCX, MARKDOWN, TEXT, UNSUPPORTED, SYSTEM_FILE, NESTED_ARCHIVE }

/** A file found in the picked source, shown to the user before anything is parsed. */
data class ImportCandidate(
    val path: String,
    val displayName: String,
    val type: CandidateType,
    val sizeBytes: Long,
    val note: String?,
) {
    val selectable: Boolean get() = type == CandidateType.DOCX || type == CandidateType.MARKDOWN || type == CandidateType.TEXT
}

/** Result of opening a picked file: what it contains, before any document is parsed. */
class InspectedSource internal constructor(
    val sourceName: String,
    val isArchive: Boolean,
    val sha256: String,
    val candidates: List<ImportCandidate>,
    internal val zip: SafeZip?,
    internal val singleBytes: ByteArray?,
)

data class ParseResult(
    val projects: List<DraftProject>,
    /** (file name, user-facing reason) for documents that could not be parsed. */
    val failures: List<Pair<String, String>>,
)

/**
 * Entry point for imports. Two steps so the user can choose files in between:
 * [inspect] validates the archive and lists its documents; [parse] reads only the selected ones.
 */
object ImportService {

    private val SYSTEM_NAMES = setOf(".ds_store", "thumbs.db", "desktop.ini")
    private val ARCHIVE_EXT = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")

    fun inspect(bytes: ByteArray, sourceName: String, limits: ImportLimits = ImportLimits()): InspectedSource {
        if (bytes.size > limits.maxInputBytes) {
            throw ImportException("“$sourceName” is too large (${bytes.size.toLong().toMbString()}, limit ${limits.maxInputBytes.toMbString()}).")
        }
        if (bytes.isEmpty()) throw ImportException("“$sourceName” is empty.")
        val sha = sha256(bytes)
        val lower = sourceName.lowercase()

        if (DocxReader.isLegacyOle(bytes)) {
            throw ImportException("“$sourceName” is a legacy .doc file or a password-protected Word document. Save it as a normal .docx (no password) and import it again.")
        }

        if (SafeZip.looksLikeZip(bytes)) {
            val zip = SafeZip.open(bytes, limits, sourceName)
            if (DocxReader.isDocxPackage(zip)) {
                val c = ImportCandidate(sourceName, sourceName, CandidateType.DOCX, bytes.size.toLong(), null)
                return InspectedSource(sourceName, false, sha, listOf(c), zip, bytes)
            }
            val candidates = zip.entries.filter { !it.isDirectory }.map { e -> candidateFor(e) }
            if (candidates.none { it.selectable }) {
                throw ImportException("“$sourceName” doesn't contain any .docx or Markdown documents.")
            }
            return InspectedSource(sourceName, true, sha, candidates, zip, null)
        }

        if (lower.endsWith(".zip") || lower.endsWith(".docx")) {
            throw ImportException("“$sourceName” is not a valid ${if (lower.endsWith(".zip")) "ZIP" else "DOCX"} file or is damaged.")
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt") || looksLikeText(bytes)) {
            val type = if (lower.endsWith(".txt")) CandidateType.TEXT else CandidateType.MARKDOWN
            val c = ImportCandidate(sourceName, sourceName, type, bytes.size.toLong(), null)
            return InspectedSource(sourceName, false, sha, listOf(c), null, bytes)
        }
        throw ImportException("“$sourceName” isn't a supported file. Choose a .zip, .docx or Markdown (.md) file.")
    }

    private fun candidateFor(e: SafeZip.Entry): ImportCandidate {
        val base = e.baseName
        val lowerBase = base.lowercase()
        val ext = lowerBase.substringAfterLast('.', "")
        val size = e.uncompressedSize
        fun c(type: CandidateType, note: String?) = ImportCandidate(e.name, base, type, size, note)
        return when {
            e.name.startsWith("__MACOSX/") || lowerBase.startsWith("._") || lowerBase in SYSTEM_NAMES ->
                c(CandidateType.SYSTEM_FILE, "System file, ignored")
            lowerBase.startsWith("~$") -> c(CandidateType.SYSTEM_FILE, "Word lock/temporary file, ignored")
            !e.supportedMethod -> c(CandidateType.UNSUPPORTED, "Unsupported compression (${SafeZip.methodName(e.method)})")
            ext == "docx" -> c(CandidateType.DOCX, null)
            ext == "md" || ext == "markdown" -> c(CandidateType.MARKDOWN, null)
            ext == "txt" -> c(CandidateType.TEXT, null)
            ext in ARCHIVE_EXT -> c(CandidateType.NESTED_ARCHIVE, "Archives inside archives are not opened")
            ext == "doc" -> c(CandidateType.UNSUPPORTED, "Legacy .doc is not supported. Save it as .docx")
            ext == "docm" -> c(CandidateType.UNSUPPORTED, "Macro-enabled documents are not supported. Save as .docx")
            else -> c(CandidateType.UNSUPPORTED, "Not a supported document type")
        }
    }

    fun parse(src: InspectedSource, selectedPaths: Set<String>, limits: ImportLimits = ImportLimits()): ParseResult {
        val selected = src.candidates.filter { it.selectable && it.path in selectedPaths }
        if (selected.size > limits.maxDocuments) {
            throw ImportException("Too many documents selected (${selected.size}, limit ${limits.maxDocuments}).")
        }
        val projects = ArrayList<DraftProject>()
        val failures = ArrayList<Pair<String, String>>()
        val archiveName = if (src.isArchive) src.sourceName else null
        for (cand in selected) {
            try {
                val bytes: ByteArray
                val doc: ParsedDocument
                if (src.isArchive) {
                    val zip = src.zip ?: throw ImportException("Archive is no longer available.")
                    val entry = zip.entry(cand.path) ?: throw ImportException("“${cand.displayName}” is missing from the archive.")
                    bytes = zip.read(entry)
                    doc = when (cand.type) {
                        CandidateType.DOCX -> DocxReader.read(bytes, cand.displayName, limits)
                        else -> MarkdownReader.read(bytes, cand.displayName, limits)
                    }
                } else {
                    bytes = src.singleBytes ?: throw ImportException("File is no longer available.")
                    doc = when (cand.type) {
                        CandidateType.DOCX -> {
                            val zip = src.zip ?: SafeZip.open(bytes, limits, cand.displayName)
                            DocxReader.read(zip, cand.displayName, limits)
                        }
                        else -> MarkdownReader.read(bytes, cand.displayName, limits)
                    }
                }
                val key = (archiveName?.let { "$it/" } ?: "") + cand.path
                projects.add(Classifier.classify(doc, archiveName, sha256(bytes), key))
            } catch (e: ImportException) {
                failures.add(cand.displayName to e.userMessage)
            } catch (e: OutOfMemoryError) {
                failures.add(cand.displayName to "Not enough memory to read this document.")
            } catch (e: RuntimeException) {
                failures.add(cand.displayName to "Couldn't read this document (${e.javaClass.simpleName}).")
            }
        }
        return ParseResult(projects, failures)
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.copyOfRange(0, minOf(bytes.size, 4096))
        if (sample.any { it.toInt() == 0 }) return false
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(sample))
            true
        } catch (e: java.nio.charset.CharacterCodingException) {
            // A multi-byte sequence may be cut at the sample boundary; accept if almost all is ASCII.
            sample.count { it.toInt() and 0x80 != 0 } < sample.size / 10
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
