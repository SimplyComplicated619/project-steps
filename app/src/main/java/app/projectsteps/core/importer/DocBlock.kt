package app.projectsteps.core.importer

enum class SourceFormat { DOCX, MARKDOWN, TEXT, DEMO }

/** How a list paragraph is marked by its numbering definition. */
enum class ListKind { NONE, BULLET, NUMBERED, BOX_UNCHECKED_GLYPH, BOX_CHECKED_GLYPH, CHECKMARK_GLYPH }

/** State of a real checkbox (Word content control, legacy form field, or Markdown task syntax). */
enum class CheckState { NONE, UNCHECKED, CHECKED }

/**
 * One structural unit of a source document: a paragraph, a list item, a heading or a table row.
 * Readers only describe what is in the file; the [Classifier] decides what it means.
 */
data class DocBlock(
    val index: Int,
    val text: String,
    val headingLevel: Int = 0,
    val isTitle: Boolean = false,
    val isSubtitle: Boolean = false,
    val listKind: ListKind = ListKind.NONE,
    val level: Int = 0,
    val checkbox: CheckState = CheckState.NONE,
    val boldOnly: Boolean = false,
    val isCode: Boolean = false,
    val isQuote: Boolean = false,
    val tableId: Int = -1,
    val rowIndex: Int = -1,
    val cells: List<String> = emptyList(),
    val cellChecks: List<CheckState> = emptyList(),
    val isHeaderRow: Boolean = false,
    val links: List<String> = emptyList(),
    val styleName: String? = null,
    /** Original source text when it differs from [text] (e.g. a Markdown line including "- [ ]"). */
    val raw: String? = null,
) {
    val isTableRow: Boolean get() = tableId >= 0
}

data class ParsedDocument(
    val fileName: String,
    val format: SourceFormat,
    val blocks: List<DocBlock>,
    val warnings: List<String>,
)
