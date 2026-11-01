package app.projectsteps.core.merge

import app.projectsteps.core.backup.BTask
import app.projectsteps.core.importer.DraftItem
import app.projectsteps.core.model.TaskFlag

/**
 * Applies a re-imported item onto the task it was matched with.
 *
 * Preserved (never overwritten): identity (so reminders and dependencies stay attached),
 * completion state and timestamp, personal notes, priority, due date/time, status, user flags.
 * Updated: source metadata. Title/description only follow the new source if the user never
 * edited them; otherwise the user's text is kept and the conflict is reported.
 */
object MergeApplier {

    data class Result(val task: BTask, val titleConflict: Boolean, val sourceNowSaysDone: Boolean)

    fun applyToTask(existing: BTask, draft: DraftItem, fingerprint: String, now: Long): Result {
        val userEditedTitle = existing.sourceTitle != null && existing.title != existing.sourceTitle
        val newTitle = if (userEditedTitle) existing.title else draft.title
        val titleConflict = userEditedTitle && draft.title != existing.sourceTitle
        // Descriptions are only filled in when empty; existing text (possibly user-edited) is kept.
        val newDescription = existing.description.ifBlank { draft.description }
        val mergedFlags = TaskFlag.decode(existing.flags) + draft.flags
        return Result(
            task = existing.copy(
                title = newTitle,
                description = newDescription,
                flags = TaskFlag.encode(mergedFlags),
                waitingOn = existing.waitingOn ?: draft.waitingOn,
                links = (existing.links.split('\n') + draft.links).filter { it.isNotBlank() }.distinct().joinToString("\n"),
                sourceFileName = draft.source.fileName,
                sourceSectionPath = draft.source.sectionPath,
                sourceBlockIndex = draft.source.blockIndex,
                sourceText = draft.source.originalText,
                sourceTitle = draft.title,
                sourceCompleted = draft.completed,
                sourceMissing = false,
                fingerprint = fingerprint,
                importedAt = now,
                updatedAt = now,
            ),
            titleConflict = titleConflict,
            sourceNowSaysDone = draft.completed && existing.status != "DONE",
        )
    }
}
