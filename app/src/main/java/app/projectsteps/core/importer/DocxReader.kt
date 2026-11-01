package app.projectsteps.core.importer

/**
 * Reads the visible structure of a .docx file: headings, paragraphs, list items (with their
 * numbering/bullet glyphs), real checkboxes (content controls and legacy form fields),
 * tables and hyperlinks. Tracked deletions, field instructions, hidden text, images and
 * macros are never treated as content; macros are never run.
 */
object DocxReader {

    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL_NS_STRICT = "http://purl.oclc.org/ooxml/officeDocument/relationships"

    fun isLegacyOle(bytes: ByteArray): Boolean {
        val sig = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        if (bytes.size < sig.size) return false
        return sig.indices.all { (bytes[it].toInt() and 0xff) == sig[it] }
    }

    fun read(bytes: ByteArray, fileName: String, limits: ImportLimits = ImportLimits()): ParsedDocument {
        if (isLegacyOle(bytes)) {
            throw ImportException("“$fileName” is a legacy .doc file or a password-protected Word document. Save it as a normal .docx (no password) and import it again.")
        }
        if (!SafeZip.looksLikeZip(bytes)) throw ImportException("“$fileName” is not a valid .docx file.")
        return read(SafeZip.open(bytes, limits, fileName), fileName, limits)
    }

    fun isDocxPackage(zip: SafeZip): Boolean =
        zip.has("[Content_Types].xml") && (zip.has("word/document.xml") || zip.entries.any { it.name.endsWith("/document.xml") && it.name.startsWith("word") })

    fun read(zip: SafeZip, fileName: String, limits: ImportLimits): ParsedDocument {
        val warnings = ArrayList<String>()
        val mainPath = findMainPart(zip, limits, fileName)
        val mainEntry = zip.entry(mainPath)
            ?: throw ImportException("“$fileName” is not a Word document (main document part is missing).")
        val document = MiniXml.parse(zip.read(mainEntry), limits, fileName)

        val dir = mainPath.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + mainPath.substringAfterLast('/') + ".rels"
        val rels = readRels(zip, relsPath, limits, fileName)

        fun partFor(typeSuffix: String, defaultName: String): String? {
            val target = rels.values.firstOrNull { it.type.endsWith(typeSuffix) && !it.external }?.target
            val path = if (target != null) resolve(dir, target) else (if (dir.isEmpty()) defaultName else "$dir/$defaultName")
            return path?.takeIf { zip.has(it) }
        }

        val styles = partFor("/styles", "styles.xml")
            ?.let { Styles.parse(MiniXml.parse(zip.read(zip.entry(it)!!), limits, fileName)) } ?: Styles.EMPTY
        val numbering = partFor("/numbering", "numbering.xml")
            ?.let { Numbering.parse(MiniXml.parse(zip.read(zip.entry(it)!!), limits, fileName)) } ?: Numbering.EMPTY

        if (zip.entries.any { it.name.endsWith("vbaProject.bin") }) {
            warnings.add("Macros in “$fileName” were ignored. Project Steps never runs anything from a document.")
        }
        val body = document.child("body") ?: throw ImportException("“$fileName” has no document body.")
        val walker = BodyWalker(fileName, styles, numbering, rels, limits)
        walker.walkContainer(body)
        if (walker.imageCount > 0) {
            warnings.add("${walker.imageCount} image(s) or embedded object(s) in “$fileName” were not imported (text only).")
        }
        warnings.addAll(walker.warnings)
        return ParsedDocument(fileName, SourceFormat.DOCX, walker.blocks, warnings)
    }

    // ---------------------------------------------------------------- package parts

    private class Rel(val id: String, val type: String, val target: String, val external: Boolean)

    private fun findMainPart(zip: SafeZip, limits: ImportLimits, fileName: String): String {
        val root = zip.entry("_rels/.rels")
        if (root != null) {
            val xml = MiniXml.parse(zip.read(root), limits, fileName)
            for (r in xml.childrenNamed("Relationship")) {
                val type = r.attr("Type") ?: continue
                if (type.endsWith("/officeDocument")) {
                    val target = r.attr("Target") ?: continue
                    resolve("", target)?.let { if (zip.has(it)) return it }
                }
            }
        }
        return "word/document.xml"
    }

    private fun readRels(zip: SafeZip, path: String, limits: ImportLimits, fileName: String): Map<String, Rel> {
        val entry = zip.entry(path) ?: return emptyMap()
        val xml = MiniXml.parse(zip.read(entry), limits, fileName)
        val out = HashMap<String, Rel>()
        for (r in xml.childrenNamed("Relationship")) {
            val id = r.attr("Id") ?: continue
            out[id] = Rel(id, r.attr("Type") ?: "", r.attr("Target") ?: "", r.attr("TargetMode").equals("External", true))
        }
        return out
    }

    /** Resolves a package-relative target; returns null if it would escape the package root. */
    private fun resolve(baseDir: String, target: String): String? {
        val raw = if (target.startsWith("/")) target.substring(1) else (if (baseDir.isEmpty()) target else "$baseDir/$target")
        val out = ArrayList<String>()
        for (part in raw.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }

    // ---------------------------------------------------------------- styles

    private class StyleDef(
        val id: String,
        val type: String,
        val name: String,
        val basedOn: String?,
        val outlineLvl: Int?,
        val numId: String?,
        val ilvl: Int?,
        val bold: Boolean?,
        val mono: Boolean,
    )

    private class HeadingInfo(val level: Int, val title: Boolean, val subtitle: Boolean)

    private class Styles(private val map: Map<String, StyleDef>, val defaultParagraphStyle: String?) {

        private fun chain(id: String?): List<StyleDef> {
            val out = ArrayList<StyleDef>()
            var cur = id
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur) && out.size < 12) {
                val def = map[cur] ?: break
                out.add(def)
                cur = def.basedOn
            }
            return out
        }

        fun name(id: String?): String? = id?.let { map[it]?.name }

        fun heading(id: String?): HeadingInfo {
            for (def in chain(id)) {
                val n = def.name.lowercase().trim()
                val i = def.id.lowercase()
                if (n == "title" || i == "title") return HeadingInfo(0, title = true, subtitle = false)
                if (n == "subtitle" || i == "subtitle") return HeadingInfo(0, title = false, subtitle = true)
                HEADING.matchEntire(n)?.let { return HeadingInfo(it.groupValues[1].toInt(), false, false) }
                HEADING.matchEntire(i)?.let { return HeadingInfo(it.groupValues[1].toInt(), false, false) }
                def.outlineLvl?.let { if (it in 0..8) return HeadingInfo(it + 1, false, false) }
            }
            return HeadingInfo(0, false, false)
        }

        fun numPr(id: String?): Pair<String, Int>? {
            for (def in chain(id)) if (def.numId != null) return def.numId to (def.ilvl ?: 0)
            return null
        }

        fun isCode(id: String?): Boolean = chain(id).any { def ->
            val n = def.name.lowercase()
            def.mono || CODE_WORDS.any { n.contains(it) }
        }

        fun isQuote(id: String?): Boolean = chain(id).any { it.name.lowercase().contains("quote") }

        fun charBold(id: String?): Boolean? = chain(id).firstNotNullOfOrNull { it.bold }

        fun charMono(id: String?): Boolean = chain(id).any { it.mono }

        companion object {
            val EMPTY = Styles(emptyMap(), null)
            private val HEADING = Regex("^heading\\s*([1-9])$")
            private val CODE_WORDS = listOf("code", "preformatted", "source", "console", "verbatim", "terminal", "command")

            fun parse(root: XmlElement): Styles {
                val map = HashMap<String, StyleDef>()
                var default: String? = null
                for (s in root.childrenNamed("style")) {
                    val id = s.attr("styleId") ?: continue
                    val type = s.attr("type") ?: "paragraph"
                    val pPr = s.child("pPr")
                    val rPr = s.child("rPr")
                    val numPr = pPr?.child("numPr")
                    val font = rPr?.child("rFonts")?.let { it.attr("ascii") ?: it.attr("hAnsi") }
                    map[id] = StyleDef(
                        id = id,
                        type = type,
                        name = s.child("name")?.attr("val") ?: id,
                        basedOn = s.child("basedOn")?.attr("val"),
                        outlineLvl = pPr?.child("outlineLvl")?.attr("val")?.toIntOrNull(),
                        numId = numPr?.child("numId")?.attr("val"),
                        ilvl = numPr?.child("ilvl")?.attr("val")?.toIntOrNull(),
                        bold = rPr?.child("b")?.let { !isOff(it) },
                        mono = font != null && isMonoFont(font),
                    )
                    if (type == "paragraph" && (s.attr("default") == "1" || s.attr("default").equals("true", true))) default = id
                }
                return Styles(map, default)
            }
        }
    }

    // ---------------------------------------------------------------- numbering

    private class NumLevel(val fmt: String, val text: String, val font: String?)

    private class Numbering(
        private val abstracts: Map<String, Map<Int, NumLevel>>,
        private val nums: Map<String, Pair<String, Map<Int, NumLevel>>>,
    ) {
        fun level(numId: String, ilvl: Int): NumLevel? {
            val (abstractId, overrides) = nums[numId] ?: return null
            return overrides[ilvl] ?: abstracts[abstractId]?.get(ilvl)
        }

        companion object {
            val EMPTY = Numbering(emptyMap(), emptyMap())

            private fun parseLevel(lvl: XmlElement): NumLevel = NumLevel(
                fmt = lvl.child("numFmt")?.attr("val") ?: "decimal",
                text = lvl.child("lvlText")?.attr("val") ?: "",
                font = lvl.child("rPr")?.child("rFonts")?.let { it.attr("ascii") ?: it.attr("hAnsi") },
            )

            fun parse(root: XmlElement): Numbering {
                val abstracts = HashMap<String, Map<Int, NumLevel>>()
                for (a in root.childrenNamed("abstractNum")) {
                    val id = a.attr("abstractNumId") ?: continue
                    val levels = HashMap<Int, NumLevel>()
                    for (lvl in a.childrenNamed("lvl")) {
                        val ilvl = lvl.attr("ilvl")?.toIntOrNull() ?: continue
                        levels[ilvl] = parseLevel(lvl)
                    }
                    abstracts[id] = levels
                }
                val nums = HashMap<String, Pair<String, Map<Int, NumLevel>>>()
                for (n in root.childrenNamed("num")) {
                    val id = n.attr("numId") ?: continue
                    val abstractId = n.child("abstractNumId")?.attr("val") ?: continue
                    val overrides = HashMap<Int, NumLevel>()
                    for (o in n.childrenNamed("lvlOverride")) {
                        val ilvl = o.attr("ilvl")?.toIntOrNull() ?: continue
                        o.child("lvl")?.let { overrides[ilvl] = parseLevel(it) }
                    }
                    nums[id] = abstractId to overrides
                }
                return Numbering(abstracts, nums)
            }
        }
    }

    // ---------------------------------------------------------------- body

    private class FieldState {
        val instr = StringBuilder()
        var inResult = false
        var check: CheckState? = null
    }

    private class Acc {
        val sb = StringBuilder()
        var check = CheckState.NONE
        val links = ArrayList<String>()
        var boldAll = true
        var monoAll = true
        var textChars = 0
        val fields = ArrayList<FieldState>()
        fun inInstruction(): Boolean = fields.isNotEmpty() && !fields.last().inResult
    }

    private class BodyWalker(
        val fileName: String,
        val styles: Styles,
        val numbering: Numbering,
        val rels: Map<String, Rel>,
        val limits: ImportLimits,
    ) {
        val blocks = ArrayList<DocBlock>()
        val warnings = ArrayList<String>()
        var imageCount = 0
        private var tableCounter = 0
        private var truncated = false

        private fun add(b: DocBlock) {
            if (blocks.size >= limits.maxBlocksPerDocument) {
                if (!truncated) {
                    warnings.add("“$fileName” is very long; only the first ${limits.maxBlocksPerDocument} paragraphs were read.")
                    truncated = true
                }
                return
            }
            blocks.add(b.copy(index = blocks.size))
        }

        fun walkContainer(el: XmlElement) {
            for (c in el.elements()) {
                when (c.local) {
                    "p" -> paragraph(c)?.let { add(it) }
                    "tbl" -> table(c)
                    "sdt" -> c.child("sdtContent")?.let { walkContainer(it) }
                    "customXml", "ins", "moveTo", "smartTag" -> walkContainer(c)
                    "altChunk" -> warnings.add("An embedded document chunk in “$fileName” was not imported.")
                    else -> {}
                }
            }
        }

        private fun paragraph(p: XmlElement): DocBlock? {
            val pPr = p.child("pPr")
            val styleId = pPr?.child("pStyle")?.attr("val") ?: styles.defaultParagraphStyle
            val acc = Acc()
            collectInline(p, acc)
            val text = cleanText(acc.sb.toString())
            if (text.isEmpty() && acc.check == CheckState.NONE) return null

            val heading = styles.heading(styleId)
            var headingLevel = heading.level
            if (headingLevel == 0 && !heading.title && !heading.subtitle) {
                pPr?.child("outlineLvl")?.attr("val")?.toIntOrNull()?.let { if (it in 0..8) headingLevel = it + 1 }
            }

            var numId: String? = null
            var ilvl = 0
            val numPr = pPr?.child("numPr")
            if (numPr != null) {
                numId = numPr.child("numId")?.attr("val")
                ilvl = numPr.child("ilvl")?.attr("val")?.toIntOrNull() ?: 0
            } else {
                styles.numPr(styleId)?.let { numId = it.first; ilvl = it.second }
            }
            var listKind = ListKind.NONE
            val nid = numId
            if (nid != null && nid != "0") {
                val lvl = numbering.level(nid, ilvl)
                listKind = if (lvl == null) ListKind.BULLET else listKindOf(lvl)
            }
            val styleName = styles.name(styleId)
            if (listKind == ListKind.NONE && styleName != null) {
                val n = styleName.lowercase()
                if (n.startsWith("list bullet")) listKind = ListKind.BULLET
                else if (n.startsWith("list number")) listKind = ListKind.NUMBERED
                if (listKind != ListKind.NONE) ilvl = (n.lastOrNull()?.digitToIntOrNull()?.minus(1) ?: 0).coerceAtLeast(0)
            }
            val level = if (listKind != ListKind.NONE) ilvl.coerceIn(0, 8) else indentLevel(pPr)

            return DocBlock(
                index = 0,
                text = text,
                headingLevel = if (heading.title || heading.subtitle) 0 else headingLevel,
                isTitle = heading.title,
                isSubtitle = heading.subtitle,
                listKind = if (headingLevel > 0) ListKind.NONE else listKind,
                level = level,
                checkbox = acc.check,
                boldOnly = headingLevel == 0 && acc.textChars > 0 && acc.boldAll,
                isCode = styles.isCode(styleId) || (acc.textChars > 0 && acc.monoAll),
                isQuote = styles.isQuote(styleId),
                links = acc.links.distinct(),
                styleName = styleName,
            )
        }

        private fun listKindOf(lvl: NumLevel): ListKind {
            val fmt = lvl.fmt.lowercase()
            if (fmt == "none") return ListKind.NONE
            if (fmt != "bullet") return ListKind.NUMBERED
            val glyph = lvl.text.trim().let { t ->
                if (t.isEmpty()) null
                else if (lvl.font?.lowercase()?.contains("wingdings") == true) Glyphs.fromSymbolFont(lvl.font, t[0].code)
                else t[0]
            }
            return when (glyph) {
                null -> ListKind.BULLET
                in Glyphs.UNCHECKED_BOXES -> ListKind.BOX_UNCHECKED_GLYPH
                in Glyphs.CHECKED_BOXES -> ListKind.BOX_CHECKED_GLYPH
                in Glyphs.CHECKMARKS -> ListKind.CHECKMARK_GLYPH
                else -> ListKind.BULLET
            }
        }

        private fun indentLevel(pPr: XmlElement?): Int {
            val ind = pPr?.child("ind") ?: return 0
            val left = (ind.attr("left") ?: ind.attr("start"))?.toIntOrNull() ?: return 0
            return (left / 720).coerceIn(0, 6)
        }

        private fun collectInline(el: XmlElement, acc: Acc) {
            for (c in el.elements()) {
                when (c.local) {
                    "r" -> run(c, acc)
                    "hyperlink" -> {
                        val rid = c.attr("id", REL_NS) ?: c.attr("id", REL_NS_STRICT)
                        val rel = rid?.let { rels[it] }
                        if (rel != null && rel.external) safeLink(rel.target)?.let { acc.links.add(it) }
                        collectInline(c, acc)
                    }
                    "sdt" -> {
                        val checkbox = c.child("sdtPr")?.child("checkbox")
                        if (checkbox != null) {
                            val v = checkbox.child("checked")?.attr("val")
                            acc.check = if (v == "1" || v.equals("true", true)) CheckState.CHECKED else CheckState.UNCHECKED
                        } else {
                            c.child("sdtContent")?.let { collectInline(it, acc) }
                        }
                    }
                    "fldSimple" -> {
                        c.attr("instr")?.let { parseInstruction(it, acc) }
                        collectInline(c, acc)
                    }
                    "smartTag", "customXml", "ins", "moveTo", "dir", "bdo", "sdtContent" -> collectInline(c, acc)
                    else -> {} // pPr, del, moveFrom, bookmarks, proofing marks, comments: not content
                }
            }
        }

        private fun run(r: XmlElement, acc: Acc) {
            val rPr = r.child("rPr")
            val vanish = rPr?.child("vanish")
            if (vanish != null && !isOff(vanish)) return // hidden text is not visible content
            val rStyle = rPr?.child("rStyle")?.attr("val")
            val bold = rPr?.child("b")?.let { !isOff(it) } ?: styles.charBold(rStyle) ?: false
            val font = rPr?.child("rFonts")?.let { it.attr("ascii") ?: it.attr("hAnsi") ?: it.attr("cs") }
            val mono = (font != null && isMonoFont(font)) || styles.charMono(rStyle)
            val symbolFont = if (font?.lowercase()?.contains("wingdings") == true) font else null

            for (c in r.elements()) {
                when (c.local) {
                    "t" -> if (!acc.inInstruction()) appendText(acc, c.textContent(), bold, mono, symbolFont)
                    "tab", "ptab" -> if (!acc.inInstruction()) acc.sb.append(' ')
                    "br", "cr" -> if (!acc.inInstruction()) acc.sb.append('\n')
                    "noBreakHyphen" -> acc.sb.append('-')
                    "sym" -> {
                        val code = c.attr("char")?.toIntOrNull(16)
                        if (code != null) Glyphs.fromSymbolFont(c.attr("font"), code)?.let {
                            acc.sb.append(it)
                            acc.textChars++
                        }
                    }
                    "fldChar" -> fieldChar(c, acc)
                    "instrText" -> acc.fields.lastOrNull()?.instr?.append(c.textContent())
                    "drawing", "pict", "object" -> imageCount++
                    else -> {}
                }
            }
        }

        private fun appendText(acc: Acc, raw: String, bold: Boolean, mono: Boolean, symbolFont: String?) {
            val text = if (symbolFont == null) raw else buildString {
                for (ch in raw) Glyphs.fromSymbolFont(symbolFont, ch.code)?.let { append(it) }
            }
            acc.sb.append(text)
            if (text.isNotBlank()) {
                acc.textChars += text.length
                acc.boldAll = acc.boldAll && bold
                acc.monoAll = acc.monoAll && mono
            }
        }

        private fun fieldChar(c: XmlElement, acc: Acc) {
            when (c.attr("fldCharType")) {
                "begin" -> {
                    val st = FieldState()
                    c.child("ffData")?.child("checkBox")?.let { cb ->
                        val checkedEl = cb.child("checked")
                        st.check = when {
                            checkedEl != null -> if (isOff(checkedEl)) CheckState.UNCHECKED else CheckState.CHECKED
                            cb.child("default")?.attr("val").let { it == "1" || it.equals("true", true) } -> CheckState.CHECKED
                            else -> CheckState.UNCHECKED
                        }
                    }
                    acc.fields.add(st)
                }
                "separate" -> acc.fields.lastOrNull()?.inResult = true
                "end" -> {
                    val st = acc.fields.removeLastOrNull() ?: return
                    parseInstruction(st.instr.toString(), acc)
                    st.check?.let { acc.check = it }
                }
            }
        }

        private fun parseInstruction(instr: String, acc: Acc) {
            HYPERLINK_FIELD.find(instr)?.let { m -> safeLink(m.groupValues[1])?.let { acc.links.add(it) } }
        }

        private fun table(tbl: XmlElement) {
            val rows = rowsOf(tbl)
            if (rows.isEmpty()) return
            // A one-column table is usually a box drawn around normal content: read it as content.
            if (rows.all { cellsOf(it).size <= 1 }) {
                for (tr in rows) for (tc in cellsOf(tr)) walkContainer(tc)
                return
            }
            val tableId = tableCounter++
            rows.forEachIndexed { rowIndex, tr ->
                val texts = ArrayList<String>()
                val checks = ArrayList<CheckState>()
                val links = ArrayList<String>()
                var allBold = true
                var anyText = false
                for (tc in cellsOf(tr)) {
                    val acc = Acc()
                    val parts = ArrayList<String>()
                    for (p in tc.descendants("p")) {
                        val pAcc = Acc()
                        collectInline(p, pAcc)
                        val t = cleanText(pAcc.sb.toString())
                        if (t.isNotEmpty()) parts.add(t)
                        if (pAcc.check != CheckState.NONE && acc.check == CheckState.NONE) acc.check = pAcc.check
                        if (pAcc.textChars > 0) {
                            anyText = true
                            allBold = allBold && pAcc.boldAll
                        }
                        links.addAll(pAcc.links)
                    }
                    texts.add(parts.joinToString("\n"))
                    checks.add(acc.check)
                }
                val header = tr.child("trPr")?.child("tblHeader")?.let { !isOff(it) } ?: false
                add(
                    DocBlock(
                        index = 0,
                        text = texts.filter { it.isNotBlank() }.joinToString(" | "),
                        tableId = tableId,
                        rowIndex = rowIndex,
                        cells = texts,
                        cellChecks = checks,
                        isHeaderRow = header || (rowIndex == 0 && anyText && allBold),
                        links = links.distinct(),
                    )
                )
            }
        }

        private fun rowsOf(tbl: XmlElement): List<XmlElement> {
            val out = ArrayList<XmlElement>()
            fun walk(e: XmlElement) {
                for (c in e.elements()) when (c.local) {
                    "tr" -> out.add(c)
                    "sdt" -> c.child("sdtContent")?.let { walk(it) }
                    "customXml", "ins" -> walk(c)
                    else -> {}
                }
            }
            walk(tbl)
            return out
        }

        private fun cellsOf(tr: XmlElement): List<XmlElement> {
            val out = ArrayList<XmlElement>()
            fun walk(e: XmlElement) {
                for (c in e.elements()) when (c.local) {
                    "tc" -> out.add(c)
                    "sdt" -> c.child("sdtContent")?.let { walk(it) }
                    "customXml" -> walk(c)
                    else -> {}
                }
            }
            walk(tr)
            return out
        }
    }

    // ---------------------------------------------------------------- helpers

    private val HYPERLINK_FIELD = Regex("HYPERLINK\\s+\"([^\"]+)\"")
    private val MONO_FONTS = listOf(
        "consolas", "courier", "cascadia", "menlo", "monaco", "source code", "fira code", "fira mono",
        "jetbrains mono", "lucida console", "roboto mono", "sf mono", "ubuntu mono", "dejavu sans mono",
        "liberation mono", "inconsolata", "monospace",
    )

    internal fun isMonoFont(font: String): Boolean {
        val f = font.lowercase()
        return MONO_FONTS.any { f.contains(it) }
    }

    private val OFF_VALUES = setOf("0", "false", "off", "none")

    private fun isOff(el: XmlElement): Boolean = (el.attr("val")?.lowercase() ?: "") in OFF_VALUES

    /** Only web and mail links are kept; anything else (file:, intent:, javascript:) is dropped. */
    internal fun safeLink(raw: String): String? {
        val url = raw.trim()
        if (url.length > 2048) return null
        val lower = url.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) url else null
    }

    internal fun cleanText(raw: String): String =
        raw.replace(' ', ' ').replace("​", "").replace('\r', '\n')
            .split('\n')
            .map { it.replace(WS, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private val WS = Regex("[ \\t\\u2000-\\u200A\\u3000]+")
}

/** Checkbox and check-mark glyphs, including the private-use codes Word uses for Wingdings. */
internal object Glyphs {
    val UNCHECKED_BOXES = setOf('☐', '□', '❑', '❒', '◻', '⬜', '▢', '❏')
    val CHECKED_BOXES = setOf('☑', '☒', '✅')
    val CHECKMARKS = setOf('✓', '✔')

    fun fromSymbolFont(font: String?, code: Int): Char? {
        val c = if (code in 0xF000..0xF0FF) code - 0xF000 else code
        val f = font?.lowercase() ?: ""
        return when {
            f.contains("wingdings 2") -> when (c) {
                0xA3, 0x2A -> '☐'
                0x52 -> '☑'
                0x53, 0x54 -> '☒'
                0x50 -> '✓'
                0x4F -> '✗'
                else -> null
            }
            f.contains("wingdings") -> when (c) {
                0x6F, 0xA8, 0x71, 0x72 -> '☐'
                0xFE -> '☑'
                0xFD -> '☒'
                0xFC -> '✓'
                0xFB -> '✗'
                else -> null
            }
            code in 0xF000..0xF0FF -> null
            else -> code.toChar()
        }
    }
}
