package app.projectsteps.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builders for in-memory ZIP and DOCX test fixtures. */
object TestFiles {

    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val W14 = "http://schemas.microsoft.com/office/word/2010/wordml"

    fun zip(vararg files: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in files) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Body XML goes inside <w:body>. */
    fun docx(
        body: String,
        styles: String? = DEFAULT_STYLES,
        numbering: String? = null,
        rels: String = "",
        extra: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val doc = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$W" xmlns:r="$R" xmlns:w14="$W14"><w:body>$body<w:sectPr/></w:body></w:document>"""
        val files = ArrayList<Pair<String, ByteArray>>()
        files.add("[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>""".toByteArray())
        files.add("_rels/.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray())
        files.add("word/document.xml" to doc.toByteArray())
        val relItems = StringBuilder()
        if (styles != null) {
            files.add("word/styles.xml" to """<?xml version="1.0"?><w:styles xmlns:w="$W">$styles</w:styles>""".toByteArray())
            relItems.append("""<Relationship Id="rIdS" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        }
        if (numbering != null) {
            files.add("word/numbering.xml" to """<?xml version="1.0"?><w:numbering xmlns:w="$W">$numbering</w:numbering>""".toByteArray())
            relItems.append("""<Relationship Id="rIdN" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>""")
        }
        relItems.append(rels)
        files.add("word/_rels/document.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relItems</Relationships>""".toByteArray())
        files.addAll(extra)
        return zip(*files.toTypedArray())
    }

    fun p(text: String, style: String? = null, numId: Int? = null, ilvl: Int = 0): String {
        val pPr = StringBuilder()
        if (style != null) pPr.append("""<w:pStyle w:val="$style"/>""")
        if (numId != null) pPr.append("""<w:numPr><w:ilvl w:val="$ilvl"/><w:numId w:val="$numId"/></w:numPr>""")
        val ppr = if (pPr.isEmpty()) "" else "<w:pPr>$pPr</w:pPr>"
        return "<w:p>$ppr<w:r><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r></w:p>"
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Word 2010+ checkbox content control followed by text. */
    fun checkboxP(text: String, checked: Boolean): String =
        """<w:p><w:sdt><w:sdtPr><w14:checkbox><w14:checked w14:val="${if (checked) 1 else 0}"/></w14:checkbox></w:sdtPr><w:sdtContent><w:r><w:t>${if (checked) "☒" else "☐"}</w:t></w:r></w:sdtContent></w:sdt><w:r><w:t xml:space="preserve"> ${esc(text)}</w:t></w:r></w:p>"""

    /** Legacy FORMCHECKBOX form field. */
    fun legacyCheckboxP(text: String, checked: Boolean): String =
        """<w:p><w:r><w:fldChar w:fldCharType="begin"><w:ffData><w:checkBox><w:sizeAuto/><w:default w:val="${if (checked) 1 else 0}"/></w:checkBox></w:ffData></w:fldChar></w:r><w:r><w:instrText xml:space="preserve"> FORMCHECKBOX </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r><w:r><w:t xml:space="preserve"> ${esc(text)}</w:t></w:r></w:p>"""

    const val DEFAULT_STYLES = """
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="1"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:rPr><w:rFonts w:ascii="Consolas"/></w:rPr></w:style>
"""

    /** numId 1 = decimal, 2 = bullet (•), 3 = checkbox glyph (☐), 4 = Wingdings box (U+F0A8), 5 = ✓ bullet. */
    const val NUMBERING = """
<w:abstractNum w:abstractNumId="10"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl><w:lvl w:ilvl="1"><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2)"/></w:lvl></w:abstractNum>
<w:abstractNum w:abstractNumId="20"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/></w:lvl><w:lvl w:ilvl="1"><w:numFmt w:val="bullet"/><w:lvlText w:val="o"/></w:lvl></w:abstractNum>
<w:abstractNum w:abstractNumId="30"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="☐"/></w:lvl></w:abstractNum>
<w:abstractNum w:abstractNumId="40"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0A8;"/><w:rPr><w:rFonts w:ascii="Wingdings" w:hAnsi="Wingdings"/></w:rPr></w:lvl></w:abstractNum>
<w:abstractNum w:abstractNumId="50"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="✓"/></w:lvl></w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="10"/></w:num>
<w:num w:numId="2"><w:abstractNumId w:val="20"/></w:num>
<w:num w:numId="3"><w:abstractNumId w:val="30"/></w:num>
<w:num w:numId="4"><w:abstractNumId w:val="40"/></w:num>
<w:num w:numId="5"><w:abstractNumId w:val="50"/></w:num>
"""

    /** Overwrites a little-endian 16-bit field in both the local header and central directory of the first entry. */
    fun patchFirstEntry(zip: ByteArray, localOffset: Int, centralOffset: Int, value: Int, bytes: Int = 2): ByteArray {
        val out = zip.copyOf()
        val loc = indexOf(out, byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        val cen = indexOf(out, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        for (i in 0 until bytes) {
            val b = ((value shr (8 * i)) and 0xff).toByte()
            if (localOffset >= 0) out[loc + localOffset + i] = b
            if (centralOffset >= 0) out[cen + centralOffset + i] = b
        }
        return out
    }

    fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
