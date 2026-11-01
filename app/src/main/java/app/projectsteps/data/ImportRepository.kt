package app.projectsteps.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import app.projectsteps.core.importer.DraftItem
import app.projectsteps.core.importer.DraftProject
import app.projectsteps.core.importer.ImportException
import app.projectsteps.core.merge.ExistingItem
import app.projectsteps.core.merge.Fingerprint
import app.projectsteps.core.merge.MergeApplier
import app.projectsteps.core.merge.MergePlanner
import app.projectsteps.core.model.ItemKind
import app.projectsteps.core.model.ProjectStatus
import app.projectsteps.core.model.TaskFlag
import app.projectsteps.core.model.TaskStatus
import app.projectsteps.reminders.ReminderScheduler
import java.io.ByteArrayOutputStream

/** Reading and writing user-picked files through the Storage Access Framework only. */
object FileIo {
    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Reads at most [maxBytes]; larger files are rejected without reading them fully. */
    fun readBytes(context: Context, uri: Uri, maxBytes: Long, label: String): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw ImportException("Couldn't open “$label”.")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw ImportException("“$label” is too large (limit ${maxBytes / (1024 * 1024)} MB).")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    fun write(context: Context, uri: Uri, bytes: ByteArray) {
        val stream = try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            null
        } ?: context.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Couldn't write to the chosen location.")
        stream.use { it.write(bytes) }
    }
}

class ImportRepository(
    private val db: AppDatabase,
    private val scheduler: ReminderScheduler,
) {
    enum class DuplicateAction(val label: String) {
        MERGE("Merge changes"),
        SKIP("Skip"),
        CREATE_COPY("Create copy"),
    }

    data class Duplicate(
        val draftKey: String,
        val draftName: String,
        val existingId: String,
        val existingName: String,
        /** Same file bytes as last time. */
        val identical: Boolean,
    )

    data class Summary(
        var created: Int = 0,
        var merged: Int = 0,
        var skipped: Int = 0,
        var sectionsAdded: Int = 0,
        var tasksAdded: Int = 0,
        var notesAdded: Int = 0,
        var matched: Int = 0,
        var titleConflicts: Int = 0,
        var sourceSaysDone: Int = 0,
        var missingFromSource: Int = 0,
        val createdProjectIds: MutableList<String> = mutableListOf(),
    ) {
        fun describe(): String = buildList {
            if (created > 0) add("Created $created project(s): $tasksAdded tasks, $notesAdded notes, $sectionsAdded sections.")
            if (merged > 0) add("Merged $merged project(s): $matched existing items kept, new items added.")
            if (skipped > 0) add("Skipped $skipped duplicate(s).")
            if (titleConflicts > 0) add("$titleConflicts title(s) you had edited were kept.")
            if (sourceSaysDone > 0) add("$sourceSaysDone item(s) are ticked in the document but not in the app. They were left as they are.")
            if (missingFromSource > 0) add("$missingFromSource item(s) are no longer in the document. They were kept and labelled.")
        }.joinToString("\n").ifBlank { "Nothing to import." }
    }

    suspend fun findDuplicates(drafts: List<DraftProject>): List<Duplicate> {
        val existing = db.projects().all().filter { it.deletedAt == null }
        return drafts.filter { it.include }.mapNotNull { d ->
            val match = existing.firstOrNull { it.sourceKey != null && it.sourceKey == d.key }
                ?: existing.firstOrNull { it.sourceFileName != null && it.sourceFileName.equals(d.fileName, ignoreCase = true) && it.isDemo == d.isDemo }
            match?.let { Duplicate(d.key, d.name, it.id, it.name, it.sourceSha256 == d.fileSha256) }
        }
    }

    suspend fun save(drafts: List<DraftProject>, actions: Map<String, DuplicateAction>, duplicates: List<Duplicate>): Summary {
        val summary = Summary()
        val now = System.currentTimeMillis()
        val dupByKey = duplicates.associateBy { it.draftKey }
        db.withTransaction {
            for (d in drafts.filter { it.include }) {
                val dup = dupByKey[d.key]
                when (if (dup == null) null else actions[d.key] ?: DuplicateAction.MERGE) {
                    null -> insertNew(d, d.name, now, summary)
                    DuplicateAction.SKIP -> summary.skipped++
                    DuplicateAction.CREATE_COPY -> insertNew(d, "${d.name} (copy)", now, summary)
                    DuplicateAction.MERGE -> mergeInto(d, dup!!.existingId, now, summary)
                }
            }
        }
        scheduler.rescheduleAll()
        return summary
    }

    private fun draftToTask(item: DraftItem, projectId: String, sectionId: String, parentId: String?, level: Int, position: Double, now: Long): TaskEntity {
        val isTask = item.kind == ItemKind.TASK
        val done = isTask && item.completed
        return TaskEntity(
            id = newId(),
            projectId = projectId,
            sectionId = sectionId,
            parentId = parentId,
            kind = if (isTask) "TASK" else "NOTE",
            title = item.title,
            description = item.description,
            position = position,
            level = level,
            status = when {
                done -> TaskStatus.DONE.name
                isTask && TaskFlag.BLOCKED in item.flags -> TaskStatus.BLOCKED.name
                else -> TaskStatus.NOT_STARTED.name
            },
            completedAt = if (done) now else null,
            waitingOn = item.waitingOn,
            flags = if (isTask) TaskFlag.encode(item.flags) else "",
            links = item.links.joinToString("\n"),
            sourceFileName = item.source.fileName,
            sourceSectionPath = item.source.sectionPath,
            sourceBlockIndex = item.source.blockIndex,
            sourceText = item.source.originalText,
            sourceTitle = item.title,
            sourceCompleted = done,
            fingerprint = Fingerprint.of(item.source.sectionPath, item.source.originalText),
            importedAt = now,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun sectionFrom(item: DraftItem, projectId: String, position: Double, now: Long) = SectionEntity(
        id = newId(), projectId = projectId, title = item.title, headingLevel = item.headingLevel.coerceAtLeast(1),
        position = position, sourceTitle = item.title, sourceSectionPath = item.source.sectionPath,
        sourceBlockIndex = item.source.blockIndex, sourceText = item.source.originalText, createdAt = now, updatedAt = now,
    )

    private suspend fun insertNew(d: DraftProject, name: String, now: Long, summary: Summary) {
        val projectId = newId()
        val anyDone = d.items.any { it.include && it.kind == ItemKind.TASK && it.completed }
        db.projects().upsert(
            ProjectEntity(
                id = projectId, name = name, description = d.description,
                status = if (anyDone) ProjectStatus.IN_PROGRESS.name else ProjectStatus.NOT_STARTED.name,
                createdAt = now, updatedAt = now, sourceFileName = d.fileName, sourceArchiveName = d.archiveName,
                sourceTitle = d.description.ifBlank { null }, sourceSha256 = d.fileSha256, sourceKey = d.key,
                importedAt = now, isDemo = d.isDemo, lastActivityAt = now,
                position = (db.projects().maxPosition() ?: 0.0) + 1.0,
            )
        )
        val sections = ArrayList<SectionEntity>()
        val tasks = ArrayList<TaskEntity>()
        val completions = ArrayList<CompletionEntity>()
        var current: SectionEntity? = null
        var sectionPos = 0.0
        var taskPos = 0.0
        val stack = ArrayList<Pair<Int, TaskEntity>>() // (source level, task)

        for (item in d.items.filter { it.include }) {
            when (item.kind) {
                ItemKind.SECTION -> {
                    sectionPos += 1.0
                    current = sectionFrom(item, projectId, sectionPos, now).also { sections.add(it) }
                    stack.clear()
                    summary.sectionsAdded++
                }
                ItemKind.TASK, ItemKind.NOTE -> {
                    val section = current ?: SectionEntity(
                        id = newId(), projectId = projectId, title = "General", position = 0.0, createdAt = now, updatedAt = now,
                    ).also { sections.add(it); current = it }
                    var parent: TaskEntity? = null
                    if (item.kind == ItemKind.TASK) {
                        while (stack.isNotEmpty() && stack.last().first >= item.level) stack.removeAt(stack.size - 1)
                        parent = stack.lastOrNull()?.second
                    }
                    taskPos += 1.0
                    val level = if (item.kind == ItemKind.TASK) stack.size else item.level.coerceIn(0, 6)
                    val e = draftToTask(item, projectId, section.id, parent?.id, level, taskPos, now)
                    tasks.add(e)
                    if (item.kind == ItemKind.TASK) {
                        stack.add(item.level to e)
                        summary.tasksAdded++
                        if (item.completed) completions.add(CompletionEntity(newId(), e.id, null, now, "IMPORTED_DONE"))
                    } else {
                        summary.notesAdded++
                    }
                }
                ItemKind.PROJECT -> {}
            }
        }
        if (sections.isEmpty()) {
            sections.add(SectionEntity(id = newId(), projectId = projectId, title = "General", position = 1.0, createdAt = now, updatedAt = now))
        }
        db.sections().upsertAll(sections)
        db.tasks().upsertAll(tasks)
        db.completions().insertAll(completions)
        summary.created++
        summary.createdProjectIds.add(projectId)
    }

    private suspend fun mergeInto(d: DraftProject, projectId: String, now: Long, summary: Summary) {
        val project = db.projects().get(projectId) ?: return insertNew(d, d.name, now, summary)
        val secs = db.sections().forProject(projectId).filter { it.deletedAt == null }
        val tasks = db.tasks().forProject(projectId).filter { it.deletedAt == null }
        val existing =
            secs.filter { it.sourceBlockIndex != null }.map { ExistingItem(it.id, it.sourceSectionPath ?: "", it.sourceBlockIndex!!, it.sourceText ?: it.title, isSection = true) } +
                tasks.filter { it.sourceBlockIndex != null }.map { ExistingItem(it.id, it.sourceSectionPath ?: "", it.sourceBlockIndex!!, it.sourceText ?: it.title) }
        val incoming = d.items.filter { it.include }
        val plan = MergePlanner.plan(existing, incoming)
        val matchOf = plan.matches.associate { it.draftId to it.existingId }

        val sectionById = secs.associateBy { it.id }.toMutableMap()
        val taskById = tasks.associateBy { it.id }.toMutableMap()
        val touchedSections = LinkedHashMap<String, SectionEntity>()
        val touchedTasks = LinkedHashMap<String, TaskEntity>()
        var currentSectionId: String? = null
        var lastSectionPos = 0.0
        val lastPosInSection = HashMap<String, Double>()
        val stack = ArrayList<Pair<Int, TaskEntity>>()

        fun positionAfter(sectionId: String, after: Double): Double {
            val next = taskById.values.filter { it.sectionId == sectionId && it.position > after }.minOfOrNull { it.position }
            return if (next == null) after + 1.0 else (after + next) / 2.0
        }

        for (item in incoming) {
            val matchedId = matchOf[item.id]
            when (item.kind) {
                ItemKind.SECTION -> {
                    val ms = matchedId?.let { sectionById[it] }
                    if (ms != null) {
                        val userEdited = ms.sourceTitle != null && ms.title != ms.sourceTitle
                        val upd = ms.copy(
                            title = if (userEdited) ms.title else item.title, sourceTitle = item.title,
                            sourceSectionPath = item.source.sectionPath, sourceBlockIndex = item.source.blockIndex,
                            sourceText = item.source.originalText, sourceMissing = false, updatedAt = now,
                        )
                        if (userEdited && item.title != ms.sourceTitle) summary.titleConflicts++
                        sectionById[upd.id] = upd
                        touchedSections[upd.id] = upd
                        currentSectionId = upd.id
                        lastSectionPos = upd.position
                        summary.matched++
                    } else {
                        val next = sectionById.values.filter { it.position > lastSectionPos }.minOfOrNull { it.position }
                        val pos = if (next == null) lastSectionPos + 1.0 else (lastSectionPos + next) / 2.0
                        val s = sectionFrom(item, projectId, pos, now)
                        sectionById[s.id] = s
                        touchedSections[s.id] = s
                        currentSectionId = s.id
                        lastSectionPos = pos
                        summary.sectionsAdded++
                    }
                    stack.clear()
                }
                ItemKind.TASK, ItemKind.NOTE -> {
                    val mt = matchedId?.let { taskById[it] }
                    if (mt != null) {
                        val result = MergeApplier.applyToTask(mt.toB(), item, Fingerprint.of(item.source.sectionPath, item.source.originalText), now)
                        val upd = result.task.toEntity()
                        taskById[upd.id] = upd
                        touchedTasks[upd.id] = upd
                        if (result.titleConflict) summary.titleConflicts++
                        if (result.sourceNowSaysDone && item.kind == ItemKind.TASK) summary.sourceSaysDone++
                        summary.matched++
                        if (currentSectionId == null) currentSectionId = upd.sectionId
                        lastPosInSection[upd.sectionId] = maxOf(lastPosInSection[upd.sectionId] ?: 0.0, upd.position)
                        if (item.kind == ItemKind.TASK) {
                            while (stack.isNotEmpty() && stack.last().first >= item.level) stack.removeAt(stack.size - 1)
                            stack.add(item.level to upd)
                        }
                    } else {
                        val sid = currentSectionId ?: secs.minByOrNull { it.position }?.id ?: run {
                            val s = SectionEntity(id = newId(), projectId = projectId, title = "General", position = 0.0, createdAt = now, updatedAt = now)
                            sectionById[s.id] = s
                            touchedSections[s.id] = s
                            s.id
                        }
                        currentSectionId = sid
                        var parent: TaskEntity? = null
                        if (item.kind == ItemKind.TASK) {
                            while (stack.isNotEmpty() && stack.last().first >= item.level) stack.removeAt(stack.size - 1)
                            parent = stack.lastOrNull()?.second?.takeIf { it.sectionId == sid }
                        }
                        val pos = positionAfter(sid, lastPosInSection[sid] ?: 0.0)
                        val level = if (parent != null) parent.level + 1 else 0
                        val e = draftToTask(item, projectId, sid, parent?.id, level, pos, now)
                        taskById[e.id] = e
                        touchedTasks[e.id] = e
                        lastPosInSection[sid] = pos
                        if (item.kind == ItemKind.TASK) {
                            stack.add(item.level to e)
                            summary.tasksAdded++
                        } else {
                            summary.notesAdded++
                        }
                    }
                }
                ItemKind.PROJECT -> {}
            }
        }

        for (id in plan.missingExistingIds) {
            sectionById[id]?.let { if (!it.sourceMissing) touchedSections[id] = it.copy(sourceMissing = true, updatedAt = now) }
            taskById[id]?.let { if (!it.sourceMissing) touchedTasks[id] = it.copy(sourceMissing = true, updatedAt = now) }
        }
        summary.missingFromSource += plan.missingExistingIds.size

        db.projects().upsert(
            project.copy(
                sourceSha256 = d.fileSha256, sourceArchiveName = d.archiveName, sourceKey = d.key,
                importedAt = now, lastActivityAt = now, updatedAt = now,
            )
        )
        db.sections().upsertAll(touchedSections.values.toList())
        db.tasks().upsertAll(touchedTasks.values.toList())
        summary.merged++
    }
}
