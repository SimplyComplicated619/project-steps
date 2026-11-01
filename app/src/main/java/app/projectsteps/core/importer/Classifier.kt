package app.projectsteps.core.importer

import app.projectsteps.core.model.Confidence
import app.projectsteps.core.model.ItemKind
import app.projectsteps.core.model.TaskFlag

/** Where an imported item came from. Never modified after import. */
data class SourceRef(
    val fileName: String,
    val archiveName: String?,
    val blockIndex: Int,
    val sectionPath: String,
    val originalText: String,
)

/** One classified item in the editable import preview. */
data class DraftItem(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val description: String = "",
    val completed: Boolean = false,
    /** Human description of the explicit marker that set [completed], e.g. "[x]". */
    val completionMarker: String? = null,
    val flags: Set<TaskFlag> = emptySet(),
    val waitingOn: String? = null,
    val level: Int = 0,
    val headingLevel: Int = 0,
    val include: Boolean = true,
    val needsReview: Boolean = false,
    val confidence: Confidence = Confidence.HIGH,
    val reason: String = "",
    val links: List<String> = emptyList(),
    val source: SourceRef,
)

data class DraftProject(
    val key: String,
    val fileName: String,
    val archiveName: String?,
    val fileSha256: String,
    val name: String,
    /** Document title (Word "Title" style or a single top-level heading), kept as description. */
    val description: String,
    val format: SourceFormat,
    val items: List<DraftItem>,
    val warnings: List<String>,
    val isDemo: Boolean = false,
    val include: Boolean = true,
)

data class DraftStats(
    val projects: Int,
    val sections: Int,
    val tasks: Int,
    val notes: Int,
    val completed: Int,
    val needsReview: Int,
    val excluded: Int,
) {
    companion object {
        fun of(projects: List<DraftProject>): DraftStats {
            val included = projects.filter { it.include }
            val items = included.flatMap { it.items }
            val inc = items.filter { it.include }
            return DraftStats(
                projects = included.size,
                sections = inc.count { it.kind == ItemKind.SECTION },
                tasks = inc.count { it.kind == ItemKind.TASK },
                notes = inc.count { it.kind == ItemKind.NOTE },
                completed = inc.count { it.kind == ItemKind.TASK && it.completed },
                needsReview = inc.count { it.needsReview },
                excluded = items.count { !it.include },
            )
        }
    }
}

/**
 * Turns document blocks into PROJECT / SECTION / TASK / NOTE drafts.
 *
 * Rules of thumb:
 *  - Only explicit markers ([x], ☑, ✓, ticked Word checkboxes) mark a task completed.
 *  - Not every paragraph is a task: plain paragraphs become notes.
 *  - Anything ambiguous is kept and marked "needs review" so the user decides in the preview.
 *  - Nothing is dropped silently: excluded rows stay visible in the preview.
 */
object Classifier {

    internal data class Markers(
        val rest: String,
        val box: CheckState,
        val marker: String?,
        val numbered: Boolean,
        val bullet: Boolean,
    )

    private val IC = setOf(RegexOption.IGNORE_CASE)
    private val LEAD_BULLET = Regex("^(?:[•◦▪▫‣⁃∙·●○■◆▶➢➤→]|[-–—*+](?=\\s))\\s*")
    private val BOX_EMPTY = Regex("^(\\[\\s?]|☐|□|❑|❒|◻|⬜|▢|❏)\\s*")
    private val BOX_DONE = Regex("^(\\[[xX✓✔]]|☑\\uFE0F?|☒|✅|✔\\uFE0F?|✓)\\s*")
    private val NUM_DIGIT = Regex("^(?:step\\s*)?(\\d{1,3})(?:[.)]|\\s*[:–—-])\\s+", IC)
    private val NUM_LETTER = Regex("^([a-zA-Z])[.)]\\s+")
    private val TRAILING_DONE = Regex("\\s*(?:[-–—:]\\s*)?(✓|✔\\uFE0F?|✅|☑\\uFE0F?|\\[[xX]])\\s*$")
    private val TRAILING_DONE_WORD = Regex("[-–—(\\[]\\s*(done|completed|complete|finished)\\s*[)\\]]?\\s*$", IC)
    private val APPROVAL = Regex(
        "needs?\\s+approval|approval\\s+(required|needed)|requires?\\s+approval|get\\s+approval|" +
            "ask\\s+(me\\s+)?(first|before)|confirm\\s+(with\\s+me\\s+)?before", IC
    )
    private val DESTRUCTIVE = Regex(
        "destructive|\\bwip(e|es|ing)\\b|factory[\\s-]?(reset|settings|defaults)|\\berase\\b|" +
            "\\bformat(ting)?\\s+(the\\s+)?(drive|disk|partition|card|device|ssd|hdd|usb)|rm\\s+-rf|" +
            "delete\\s+all|data\\s+loss|irreversible|can(not|'t)\\s+be\\s+undone|\\breflash|" +
            "flash\\s+(the\\s+)?(firmware|rom|bios|image)|\\bdd\\s+if=", IC
    )
    private val WAITING = Regex("waiting\\s+(?:on|for)\\s*[:\\-–—]?\\s*(.*)", IC)
    private val BLOCKED = Regex("\\bblocked\\b", IC)
    private val NOTE_PREFIX = Regex(
        "^(note|notes|tip|tips|warning|caution|important|info|fyi|why|context|reference|example|" +
            "goal|purpose|summary|background)\\s*[:\\-–—]", IC
    )
    private val URL = Regex("https?://[^\\s)>\\]]+")
    private val PURE_NUMBER = Regex("^(?:#|no\\.?\\s*|step\\s*)?\\d{1,3}[.):]?$", IC)
    private val STEP_COLUMN = Regex("^(step|steps|task|tasks|action|actions|item|items|to\\s*do|todo|what)$", IC)
    private val STATUS_COLUMN = Regex("^(status|done|complete|completed|state|✓|✔|☑)$", IC)
    private val STATUS_DONE_WORD = Regex("^(done|complete|completed|finished|yes)$", IC)

    fun projectNameFromFile(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        val words = base.replace(Regex("[-_]+"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.joinToString(" ") { w ->
            when {
                w.length > 3 && w.all { !it.isLetter() || it.isUpperCase() } -> w.lowercase().replaceFirstChar { it.uppercase() }
                w.first().isLowerCase() -> w.replaceFirstChar { it.uppercase() }
                else -> w
            }
        }.ifBlank { "Untitled" }
    }

    internal fun parseMarkers(text: String): Markers {
        var s = text.trimStart()
        var bullet = false
        var numbered = false
        var box = CheckState.NONE
        var marker: String? = null

        LEAD_BULLET.find(s)?.let { bullet = true; s = s.substring(it.range.last + 1) }
        fun tryBox(): Boolean {
            BOX_EMPTY.find(s)?.let { box = CheckState.UNCHECKED; marker = it.groupValues[1]; s = s.substring(it.range.last + 1); return true }
            BOX_DONE.find(s)?.let { box = CheckState.CHECKED; marker = it.groupValues[1]; s = s.substring(it.range.last + 1); return true }
            return false
        }
        tryBox()
        (NUM_DIGIT.find(s) ?: NUM_LETTER.find(s))?.let { numbered = true; s = s.substring(it.range.last + 1) }
        if (box == CheckState.NONE && numbered) tryBox()
        return Markers(s.trim(), box, marker, numbered, bullet)
    }

    fun classify(doc: ParsedDocument, archiveName: String?, sha256: String, key: String = doc.fileName): DraftProject {
        val blocks = doc.blocks
        val items = ArrayList<DraftItem>()
        val firstContent = blocks.firstOrNull { it.text.isNotBlank() }
        val h1Count = blocks.count { it.headingLevel == 1 }
        var titleBlock: DocBlock? = blocks.firstOrNull { it.isTitle && it.text.isNotBlank() }
        if (titleBlock == null && firstContent != null && firstContent.headingLevel == 1 && h1Count == 1) {
            // A lone top heading is the document title if the document has sub-headings, or if it
            // names the project (shares a word with the file name). Otherwise it's a real section.
            val nameWords = words(projectNameFromFile(doc.fileName))
            if (blocks.any { it.headingLevel >= 2 } || words(firstContent.text).any { it in nameWords }) {
                titleBlock = firstContent
            }
        }

        val docHasCheckboxes = blocks.any { b ->
            b.checkbox != CheckState.NONE || b.listKind == ListKind.BOX_UNCHECKED_GLYPH ||
                b.listKind == ListKind.BOX_CHECKED_GLYPH || b.cellChecks.any { it != CheckState.NONE } ||
                (!b.isTableRow && !b.isCode && parseMarkers(b.text).box != CheckState.NONE) ||
                b.cells.any { parseMarkers(it).box != CheckState.NONE }
        }
        val checklistTables = blocks.filter { it.isTableRow }.groupBy { it.tableId }
            .mapValues { (_, rows) -> rows.any { rowBox(it).first != CheckState.NONE } }

        data class Heading(val level: Int, val title: String, val synthetic: Boolean)
        val headings = ArrayList<Heading>()
        fun path() = headings.joinToString(" > ") { it.title }
        val tableHeaders = HashMap<Int, List<String>>()

        for (b in blocks) {
            if (b === titleBlock) continue
            if (b.text.isBlank() && b.checkbox == CheckState.NONE && b.cells.all { it.isBlank() }) continue
            val id = "$key#${b.index}"
            val src = SourceRef(doc.fileName, archiveName, b.index, path(), b.raw ?: b.text)
            val links = (b.links + URL.findAll(b.text).map { it.value.trimEnd('.', ',', ';') }).distinct()

            when {
                b.isTableRow -> items.add(tableRow(b, id, src, links, checklistTables[b.tableId] == true, tableHeaders))

                b.headingLevel > 0 -> {
                    while (headings.isNotEmpty() && headings.last().level >= b.headingLevel) headings.removeAt(headings.size - 1)
                    items.add(
                        DraftItem(
                            id = id, kind = ItemKind.SECTION, title = oneLine(b.text), headingLevel = b.headingLevel,
                            reason = "Heading ${b.headingLevel}", source = src, links = links,
                        )
                    )
                    headings.add(Heading(b.headingLevel, oneLine(b.text), false))
                }

                b.isSubtitle -> items.add(note(id, b.text, src, links, "Subtitle", Confidence.HIGH))

                b.isCode -> items.add(
                    note(id, b.text, src, links, "Code or command: shown as text, never run", Confidence.HIGH)
                )

                else -> {
                    val item = paragraph(b, id, src, links, docHasCheckboxes)
                    if (item.kind == ItemKind.SECTION) {
                        val level = (headings.lastOrNull { !it.synthetic }?.level ?: 0) + 1
                        while (headings.isNotEmpty() && headings.last().level >= level) headings.removeAt(headings.size - 1)
                        items.add(item.copy(headingLevel = level))
                        headings.add(Heading(level, item.title, true))
                    } else {
                        items.add(item)
                    }
                }
            }
        }

        return DraftProject(
            key = key,
            fileName = doc.fileName,
            archiveName = archiveName,
            fileSha256 = sha256,
            name = projectNameFromFile(doc.fileName),
            description = titleBlock?.text?.let(::oneLine) ?: "",
            format = doc.format,
            items = items,
            warnings = doc.warnings,
        )
    }

    private fun paragraph(b: DocBlock, id: String, src: SourceRef, links: List<String>, docHasCheckboxes: Boolean): DraftItem {
        val m = parseMarkers(b.text)
        val box = if (b.checkbox != CheckState.NONE) b.checkbox else m.box
        val boxMarker = if (b.checkbox != CheckState.NONE) "Word checkbox" else m.marker

        fun task(reason: String, confidence: Confidence, completed: Boolean = false, marker: String? = null, review: Boolean = false): DraftItem =
            finishTask(
                DraftItem(
                    id = id, kind = ItemKind.TASK, title = "", completed = completed, completionMarker = marker,
                    level = b.level, needsReview = review, confidence = confidence, reason = reason, links = links, source = src,
                ),
                m.rest,
            )

        return when {
            box != CheckState.NONE -> task(
                if (box == CheckState.CHECKED) "Checked box ($boxMarker)" else "Checkbox ($boxMarker)",
                Confidence.HIGH, completed = box == CheckState.CHECKED, marker = if (box == CheckState.CHECKED) boxMarker else null,
            )
            b.listKind == ListKind.BOX_UNCHECKED_GLYPH -> task("Checkbox list item", Confidence.HIGH)
            b.listKind == ListKind.BOX_CHECKED_GLYPH -> task(
                "List style shows a ticked box on every item. Left NOT done; tick the ones that are done.",
                Confidence.LOW, review = true,
            )
            b.listKind == ListKind.CHECKMARK_GLYPH -> task(
                "List uses ✓ as its bullet style. Left NOT done; tick the ones that are done.",
                Confidence.MEDIUM, review = true,
            )
            b.isQuote -> note(id, b.text, src, links, "Quoted text", Confidence.HIGH)
            NOTE_PREFIX.containsMatchIn(m.rest) -> note(id, m.rest, src, links, "Starts with a note label", Confidence.HIGH, b.level)
            b.listKind == ListKind.NUMBERED || m.numbered -> task("Numbered step", Confidence.HIGH)
            b.listKind == ListKind.BULLET || m.bullet ->
                if (docHasCheckboxes) note(
                    id, m.rest, src, links,
                    "Bullet point in a document that uses checkboxes for tasks. Kept as a note; change to Task if needed.",
                    Confidence.MEDIUM, b.level, review = true,
                )
                else task("Bullet point", Confidence.MEDIUM)
            looksLikeHeading(b, m.rest) -> DraftItem(
                id = id, kind = ItemKind.SECTION, title = oneLine(m.rest.trimEnd(':')),
                needsReview = true, confidence = Confidence.LOW,
                reason = "Short ${if (b.boldOnly) "bold" else "capitalised"} line that looks like a heading",
                links = links, source = src,
            )
            TRAILING_DONE.containsMatchIn(m.rest) -> task(
                "Plain paragraph ending with a completion mark. Confirm it's a finished task.",
                Confidence.LOW, completed = true, marker = TRAILING_DONE.find(m.rest)?.groupValues?.get(1), review = true,
            )
            else -> note(id, b.text, src, links, "Plain paragraph", Confidence.MEDIUM, b.level)
        }
    }

    private fun finishTask(item: DraftItem, text: String): DraftItem {
        var t = text
        var completed = item.completed
        var marker = item.completionMarker
        var review = item.needsReview
        var reason = item.reason
        val firstLine = t.substringBefore('\n')
        TRAILING_DONE.find(firstLine)?.let { mt ->
            t = firstLine.substring(0, mt.range.first) + t.substring(firstLine.length)
            if (!completed) {
                completed = true
                marker = mt.groupValues[1]
                review = true
                reason = "$reason. Ends with a completion mark (${mt.groupValues[1]}); confirm"
            }
        }
        if (!completed && TRAILING_DONE_WORD.containsMatchIn(t.substringBefore('\n'))) {
            review = true
            reason = "$reason. Text says it's done, but there's no checkbox. Left NOT done; tick it if it is."
        }
        val (title, description) = splitTitle(t)
        val full = "$title\n$description"
        val flags = LinkedHashSet<TaskFlag>()
        if (APPROVAL.containsMatchIn(full)) flags.add(TaskFlag.NEEDS_APPROVAL)
        if (DESTRUCTIVE.containsMatchIn(full)) flags.add(TaskFlag.DESTRUCTIVE)
        var waitingOn: String? = null
        WAITING.find(full)?.let {
            flags.add(TaskFlag.WAITING_ON)
            waitingOn = it.groupValues[1].substringBefore('\n').trim().trimEnd('.').take(120).ifBlank { null }
        }
        if (BLOCKED.containsMatchIn(full)) flags.add(TaskFlag.BLOCKED)
        return item.copy(
            title = title.ifBlank { item.source.originalText.take(120) },
            description = description,
            completed = completed,
            completionMarker = if (completed) marker else null,
            needsReview = review,
            reason = reason,
            flags = flags,
            waitingOn = waitingOn,
        )
    }

    private fun note(
        id: String, text: String, src: SourceRef, links: List<String>, reason: String,
        confidence: Confidence, level: Int = 0, review: Boolean = false,
    ): DraftItem {
        val (title, description) = splitTitle(text)
        return DraftItem(
            id = id, kind = ItemKind.NOTE, title = title, description = description, level = level,
            needsReview = review, confidence = confidence, reason = reason, links = links, source = src,
        )
    }

    private fun looksLikeHeading(b: DocBlock, text: String): Boolean {
        if (text.contains('\n')) return false
        val t = text.trim()
        if (t.length < 2 || t.length > 80) return false
        if (t.endsWith('.') || t.endsWith('!') || t.endsWith('?') || t.endsWith(',') || t.endsWith(';')) return false
        val letters = t.filter { it.isLetter() }
        val allCaps = letters.length >= 3 && letters.all { it.isUpperCase() }
        return b.boldOnly || allCaps
    }

    /** Returns (state, cell index) of the first checkbox in a table row. */
    private fun rowBox(b: DocBlock): Pair<CheckState, Int> {
        b.cellChecks.forEachIndexed { i, c -> if (c != CheckState.NONE) return c to i }
        b.cells.forEachIndexed { i, c ->
            val m = parseMarkers(c)
            if (m.box != CheckState.NONE) return m.box to i
        }
        return CheckState.NONE to -1
    }

    private fun tableRow(
        b: DocBlock, id: String, src: SourceRef, links: List<String>,
        checklist: Boolean, headers: MutableMap<Int, List<String>>,
    ): DraftItem {
        val (box, boxIdx) = rowBox(b)
        val isHeader = b.isHeaderRow || (b.rowIndex == 0 && checklist && box == CheckState.NONE)
        if (isHeader) {
            headers[b.tableId] = b.cells.map { it.trim() }
            return DraftItem(
                id = id, kind = ItemKind.NOTE, title = b.text, include = false, confidence = Confidence.HIGH,
                reason = "Table header row. Excluded by default; include it if you want it as a note.",
                source = src, links = links,
            )
        }
        val header = headers[b.tableId]
        val cells = b.cells.map { parseMarkers(it).rest }
        fun headerOf(i: Int) = header?.getOrNull(i)?.takeIf { it.isNotBlank() }

        val stepCol = header?.indexOfFirst { STEP_COLUMN.matches(it.trim()) } ?: -1
        val titleIdx = when {
            stepCol >= 0 && cells.getOrNull(stepCol)?.isNotBlank() == true -> stepCol
            else -> cells.indices.firstOrNull { i -> i != boxIdx && cells[i].isNotBlank() && !PURE_NUMBER.matches(cells[i].trim()) } ?: -1
        }
        if (titleIdx < 0) return note(id, b.text, src, links, "Table row without text", Confidence.LOW, review = true)

        val details = cells.indices
            .filter { it != titleIdx && it != boxIdx && cells[it].isNotBlank() && !PURE_NUMBER.matches(cells[it].trim()) }
            .joinToString("\n") { i -> headerOf(i)?.let { "$it: ${cells[i]}" } ?: cells[i] }

        if (!checklist && stepCol < 0) {
            val text = cells.indices.filter { cells[it].isNotBlank() }
                .joinToString("\n") { i -> headerOf(i)?.let { "$it: ${cells[i]}" } ?: cells[i] }
            return note(id, text, src, links, "Table row (table has no checkboxes)", Confidence.MEDIUM)
        }

        val statusIdx = header?.indexOfFirst { STATUS_COLUMN.matches(it.trim()) } ?: -1
        val statusSaysDone = statusIdx >= 0 && STATUS_DONE_WORD.matches(cells.getOrNull(statusIdx)?.trim() ?: "")
        val base = DraftItem(
            id = id, kind = ItemKind.TASK, title = "",
            completed = box == CheckState.CHECKED,
            completionMarker = if (box == CheckState.CHECKED) (b.cellChecks.getOrNull(boxIdx)?.takeIf { it != CheckState.NONE }?.let { "Word checkbox" }
                ?: parseMarkers(b.cells[boxIdx]).marker) else null,
            needsReview = statusSaysDone && box != CheckState.CHECKED,
            confidence = if (box != CheckState.NONE || stepCol >= 0) Confidence.HIGH else Confidence.MEDIUM,
            reason = when {
                statusSaysDone && box != CheckState.CHECKED -> "Status column says done, but there's no ticked box. Left NOT done; confirm."
                box != CheckState.NONE -> "Checklist table row"
                checklist -> "Row in a checklist table (empty checkbox column)"
                else -> "Row in a table with a Step/Task column"
            },
            links = links, source = src,
        )
        val (title, extra) = splitTitle(cells[titleIdx])
        val withText = finishTask(base, title + (if (extra.isNotBlank()) "\n$extra" else ""))
        val desc = listOf(withText.description, details).filter { it.isNotBlank() }.joinToString("\n")
        return withText.copy(description = desc)
    }

    private fun oneLine(s: String): String = s.replace('\n', ' ').trim()

    private fun words(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    /** First line becomes the title (shortened if very long); the rest is the description. */
    internal fun splitTitle(text: String): Pair<String, String> {
        val clean = text.trim()
        val first = clean.substringBefore('\n').trim()
        val rest = clean.substringAfter('\n', "").trim()
        if (first.length <= 160) return first to rest
        val cut = first.indexOf(". ").takeIf { it in 20..150 }?.let { it + 1 } ?: 140
        val title = first.substring(0, cut).trimEnd() + if (cut < first.length && cut == 140) "…" else ""
        return title to clean
    }
}
