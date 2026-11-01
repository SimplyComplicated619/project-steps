package app.projectsteps.core.importer

/**
 * Reads Markdown (and plain text) into [DocBlock]s: ATX/setext headings, bullet and numbered
 * lists with nesting, GitHub task-list checkboxes, tables, block quotes, fenced code blocks
 * and paragraphs. Code blocks are kept as text and are never executed.
 */
object MarkdownReader {

    private val ATX = Regex("^ {0,3}(#{1,6})(?:\\s+(.*?))?\\s*#*\\s*$")
    private val SETEXT_1 = Regex("^ {0,3}=+\\s*$")
    private val SETEXT_2 = Regex("^ {0,3}-+\\s*$")
    private val HR = Regex("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val LIST = Regex("^(\\s*)([-*+•]|\\d{1,3}[.)])\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])\\]\\s*(.*)$")
    private val FENCE = Regex("^\\s{0,3}(```+|~~~+)(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val LINK = Regex("\\[([^\\]]*)]\\((\\S+?)(?:\\s+\"[^\"]*\")?\\)")
    private val AUTOLINK = Regex("<(https?://[^>\\s]+)>")
    private val BOLD_LINE = Regex("^(\\*\\*|__)(.+)\\1:?$")

    fun read(bytes: ByteArray, fileName: String, limits: ImportLimits = ImportLimits()): ParsedDocument {
        if (bytes.size > limits.maxMarkdownBytes) {
            throw ImportException("“$fileName” is too large to import as text (${bytes.size.toLong().toMbString()}).")
        }
        if (bytes.any { it.toInt() == 0 }) throw ImportException("“$fileName” doesn't look like a text file.")
        var text = String(bytes, Charsets.UTF_8)
        if (text.startsWith("﻿")) text = text.substring(1)
        val format = if (fileName.lowercase().endsWith(".txt")) SourceFormat.TEXT else SourceFormat.MARKDOWN
        return parse(text, fileName, format, limits)
    }

    fun parse(text: String, fileName: String, format: SourceFormat = SourceFormat.MARKDOWN, limits: ImportLimits = ImportLimits()): ParsedDocument {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.replace("\t", "    ") }
        val blocks = ArrayList<DocBlock>()
        val warnings = ArrayList<String>()
        var truncated = false
        fun add(b: DocBlock) {
            if (blocks.size >= limits.maxBlocksPerDocument) {
                if (!truncated) warnings.add("“$fileName” is very long; only the first ${limits.maxBlocksPerDocument} blocks were read.")
                truncated = true
                return
            }
            blocks.add(b.copy(index = blocks.size))
        }

        val listIndents = ArrayList<Int>()
        var tableId = -1
        var tableRow = 0
        var i = 0

        // YAML front matter is kept as a note, never interpreted.
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) {
                val body = lines.subList(1, end).joinToString("\n").trim()
                if (body.isNotEmpty()) add(DocBlock(0, body, isCode = true))
                i = end + 1
            }
        }

        val para = ArrayList<String>()
        fun flushPara() {
            if (para.isEmpty()) return
            val raw = para.joinToString(" ").trim()
            para.clear()
            if (raw.isEmpty()) return
            val bold = BOLD_LINE.matchEntire(raw)
            val (clean, links) = inline(if (bold != null) bold.groupValues[2] + (if (raw.endsWith(":")) ":" else "") else raw)
            add(DocBlock(0, clean, boldOnly = bold != null, links = links))
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Fenced code block
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flushPara(); listIndents.clear(); tableId = -1
                val marker = fence.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(marker)) { body.add(lines[i]); i++ }
                i++ // closing fence
                val code = body.joinToString("\n").trimEnd()
                if (code.isNotBlank()) add(DocBlock(0, code, isCode = true))
                continue
            }

            if (trimmed.isEmpty()) {
                flushPara(); tableId = -1
                i++
                continue
            }

            val atx = ATX.matchEntire(line)
            if (atx != null) {
                flushPara(); listIndents.clear(); tableId = -1
                val (clean, links) = inline(atx.groupValues[2])
                if (clean.isNotBlank()) add(DocBlock(0, clean, headingLevel = atx.groupValues[1].length, links = links))
                i++
                continue
            }

            if (HR.matches(line)) { flushPara(); listIndents.clear(); i++; continue }

            // Setext heading (current line followed by === or ---)
            if (para.isEmpty() && i + 1 < lines.size && !LIST.matches(line) && !trimmed.startsWith("|") &&
                !QUOTE.matches(line) &&
                (SETEXT_1.matches(lines[i + 1]) || (SETEXT_2.matches(lines[i + 1]) && lines[i + 1].trim().length >= 2))
            ) {
                val level = if (SETEXT_1.matches(lines[i + 1])) 1 else 2
                val (clean, links) = inline(trimmed)
                add(DocBlock(0, clean, headingLevel = level, links = links))
                listIndents.clear(); tableId = -1
                i += 2
                continue
            }

            // Tables
            if (trimmed.startsWith("|") || (trimmed.contains(" | ") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]))) {
                flushPara(); listIndents.clear()
                if (TABLE_SEP.matches(line)) { i++; continue }
                val isHeader = i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])
                if (tableId < 0 || isHeader) { tableId = (blocks.maxOfOrNull { it.tableId } ?: -1) + 1; tableRow = 0 }
                val cells = trimmed.trim('|').split('|').map { it.trim() }
                val cleaned = cells.map { inline(it).first }
                val links = cells.flatMap { inline(it).second }
                val checks = cleaned.map { c ->
                    val m = TASK.matchEntire(c)
                    if (m == null) CheckState.NONE else if (m.groupValues[1] == " ") CheckState.UNCHECKED else CheckState.CHECKED
                }
                val cellTexts = cleaned.mapIndexed { idx, c -> if (checks[idx] != CheckState.NONE) TASK.matchEntire(c)!!.groupValues[2] else c }
                add(
                    DocBlock(
                        0, cellTexts.filter { it.isNotBlank() }.joinToString(" | "),
                        tableId = tableId, rowIndex = tableRow++, cells = cellTexts, cellChecks = checks,
                        isHeaderRow = isHeader, links = links, raw = trimmed,
                    )
                )
                i++
                continue
            }
            tableId = -1

            val quote = QUOTE.matchEntire(line)
            if (quote != null && para.isEmpty()) {
                val body = ArrayList<String>()
                while (i < lines.size) {
                    val q = QUOTE.matchEntire(lines[i]) ?: break
                    body.add(q.groupValues[1])
                    i++
                }
                val (clean, links) = inline(body.joinToString(" ").trim())
                if (clean.isNotBlank()) add(DocBlock(0, clean, isQuote = true, links = links))
                continue
            }

            val list = LIST.matchEntire(line)
            if (list != null) {
                flushPara()
                val indent = list.groupValues[1].length
                while (listIndents.isNotEmpty() && listIndents.last() > indent) listIndents.removeAt(listIndents.size - 1)
                if (listIndents.isEmpty() || listIndents.last() < indent) listIndents.add(indent)
                val level = (listIndents.size - 1).coerceIn(0, 8)
                val marker = list.groupValues[2]
                var content = list.groupValues[3]
                // Continuation lines (indented, not a new list item)
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j]
                    if (next.isBlank() || LIST.matches(next) || ATX.matches(next) || FENCE.matches(next)) break
                    val nextIndent = next.length - next.trimStart().length
                    if (nextIndent <= indent) break
                    content += "\n" + next.trim()
                    j++
                }
                var check = CheckState.NONE
                TASK.matchEntire(content.substringBefore('\n'))?.let { m ->
                    check = if (m.groupValues[1] == " ") CheckState.UNCHECKED else CheckState.CHECKED
                    content = m.groupValues[2] + content.substringAfter('\n', "").let { if (it.isEmpty()) "" else "\n$it" }
                }
                val kind = if (marker[0].isDigit()) ListKind.NUMBERED else ListKind.BULLET
                val (clean, links) = inline(content)
                add(DocBlock(0, clean, listKind = kind, level = level, checkbox = check, links = links,
                    raw = lines.subList(i, j).joinToString("\n") { it.trim() }))
                i = j
                continue
            }

            listIndents.clear()
            para.add(trimmed)
            i++
        }
        flushPara()
        return ParsedDocument(fileName, format, blocks, warnings)
    }

    /** Strips inline Markdown, returning the visible text and any web links. */
    internal fun inline(raw: String): Pair<String, List<String>> {
        val links = ArrayList<String>()
        var s = LINK.replace(raw) { m ->
            DocxReader.safeLink(m.groupValues[2])?.let { links.add(it) }
            m.groupValues[1]
        }
        s = AUTOLINK.replace(s) { m ->
            DocxReader.safeLink(m.groupValues[1])?.let { links.add(it) }
            m.groupValues[1]
        }
        s = s.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
            .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("~~(.+?)~~"), "$1")
        return DocxReader.cleanText(s) to links.distinct()
    }
}
