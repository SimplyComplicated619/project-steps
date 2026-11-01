package app.projectsteps.core.importer

/**
 * Hard limits applied to every imported file. Imported files are treated as untrusted data.
 * Values are generous for real checklist documents (typically a few hundred KB) while
 * stopping ZIP bombs, oversized XML and pathological nesting.
 */
data class ImportLimits(
    /** Largest file accepted from the document picker (ZIP, DOCX or Markdown). */
    val maxInputBytes: Long = 25L * 1024 * 1024,
    /** Maximum number of entries inside one ZIP (outer archive or DOCX package). */
    val maxEntries: Int = 500,
    /** Maximum decompressed size of any single entry. */
    val maxEntryBytes: Long = 25L * 1024 * 1024,
    /** Maximum total decompressed bytes read from one archive. */
    val maxTotalUncompressedBytes: Long = 150L * 1024 * 1024,
    /** Entries larger than this must not exceed [maxCompressionRatio]. */
    val ratioCheckThresholdBytes: Long = 1L * 1024 * 1024,
    val maxCompressionRatio: Long = 200,
    /** Maximum folder depth of a path inside an archive. */
    val maxPathDepth: Int = 8,
    val maxNameLength: Int = 260,
    /** Maximum size of one XML part (e.g. word/document.xml). */
    val maxXmlBytes: Int = 16 * 1024 * 1024,
    val maxXmlDepth: Int = 256,
    val maxXmlNodes: Int = 1_500_000,
    /** Maximum documents imported in one go. */
    val maxDocuments: Int = 100,
    /** Maximum paragraphs/rows taken from one document. */
    val maxBlocksPerDocument: Int = 20_000,
    val maxMarkdownBytes: Int = 5 * 1024 * 1024,
)

/** An import problem with a message that is safe and understandable to show the user. */
class ImportException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

internal fun Long.toMbString(): String = String.format("%.1f MB", this / (1024.0 * 1024.0))
