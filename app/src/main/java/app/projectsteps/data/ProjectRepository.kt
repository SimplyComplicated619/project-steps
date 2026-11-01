package app.projectsteps.data

import android.content.Context
import androidx.room.withTransaction
import app.projectsteps.core.deps.DependencyGraph
import app.projectsteps.core.model.ProjectStatus
import app.projectsteps.core.model.TaskStatus
import app.projectsteps.core.schedule.ReminderPlanner
import app.projectsteps.reminders.Notifications
import app.projectsteps.reminders.ReminderScheduler
import app.projectsteps.ui.Format
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

typealias Undo = suspend () -> Unit

data class ActionResult(val message: String, val undo: Undo? = null)

/**
 * All user actions on projects, sections, tasks, dependencies and reminders.
 * Destructive or easy-to-mistap actions return an [Undo] that restores the previous rows exactly.
 * Deletes are soft (deletedAt) and purged after [TRASH_DAYS].
 */
class ProjectRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val scheduler: ReminderScheduler,
) {
    val snapshot: Flow<Snapshot> = combine(
        db.projects().observeActive(),
        db.sections().observeActive(),
        db.tasks().observeActive(),
        db.dependencies().observeAll(),
        db.reminders().observeAll(),
    ) { projects, sections, tasks, deps, reminders ->
        val projectIds = projects.map { it.id }.toSet()
        val liveSections = sections.filter { it.projectId in projectIds }
        val sectionIds = liveSections.map { it.id }.toSet()
        val liveTasks = tasks.filter { it.projectId in projectIds && it.sectionId in sectionIds }
        Snapshot(projects, liveSections, liveTasks, deps, reminders, loaded = true)
    }

    fun completionHistory(taskId: String): Flow<List<CompletionEntity>> = db.completions().observeForTask(taskId)

    private fun now() = System.currentTimeMillis()

    private suspend fun touchProject(projectId: String, now: Long, startedWork: Boolean = false): ProjectEntity? {
        val p = db.projects().get(projectId) ?: return null
        val status = if (startedWork && p.status == ProjectStatus.NOT_STARTED.name) ProjectStatus.IN_PROGRESS.name else p.status
        db.projects().upsert(p.copy(lastActivityAt = now, status = status, updatedAt = if (status != p.status) now else p.updatedAt))
        return p
    }

    // ------------------------------------------------------------------ completion

    suspend fun setTaskDone(taskId: String, done: Boolean): ActionResult {
        val t = db.tasks().get(taskId) ?: return ActionResult("That task no longer exists.")
        val now = now()
        val reminders = db.reminders().forTask(taskId)
        val recurring = reminders.firstOrNull { it.enabled && it.isRecurring }
        val zone = ZoneId.systemDefault()

        if (done && recurring != null) {
            val (rt, occurrence) = ReminderPlanner.onComplete(recurring.toSpec(), recurring.toRuntime(), Instant.now(), zone)
            val updatedReminder = recurring.withRuntime(rt, now)
            val history = CompletionEntity(newId(), taskId, occurrence?.toEpochMilli(), now, "COMPLETED")
            var oldProject: ProjectEntity? = null
            db.withTransaction {
                db.reminders().upsert(updatedReminder)
                db.completions().insert(history)
                db.tasks().upsert(t.copy(status = TaskStatus.NOT_STARTED.name, completedAt = now, updatedAt = now))
                oldProject = touchProject(t.projectId, now, startedWork = true)
            }
            Notifications.cancel(context, recurring.id)
            scheduler.rescheduleForTask(taskId)
            val next = ReminderPlanner.currentOccurrence(recurring.toSpec(), rt, Instant.now(), zone)
            val msg = "Done for " + (occurrence?.let { Format.dateTime(it, zone) } ?: "this time") +
                (next?.let { ". Next: " + Format.dateTime(it, zone) } ?: "")
            return ActionResult(msg) {
                db.withTransaction {
                    db.reminders().upsert(recurring)
                    db.completions().delete(history.id)
                    db.tasks().upsert(t)
                    oldProject?.let { db.projects().upsert(it) }
                }
                scheduler.rescheduleForTask(taskId)
            }
        }

        val newStatus = when {
            done -> TaskStatus.DONE.name
            t.status == TaskStatus.DONE.name -> TaskStatus.NOT_STARTED.name
            else -> t.status
        }
        val updated = t.copy(status = newStatus, completedAt = if (done) now else null, updatedAt = now)
        val history = CompletionEntity(newId(), taskId, null, now, if (done) "COMPLETED" else "REOPENED")
        val oneTime = reminders.filter { !it.isRecurring }
        var oldProject: ProjectEntity? = null
        db.withTransaction {
            db.tasks().upsert(updated)
            db.completions().insert(history)
            db.reminders().upsertAll(oneTime.map { it.copy(itemDone = done, snoozedUntil = if (done) null else it.snoozedUntil, updatedAt = now) })
            oldProject = touchProject(t.projectId, now, startedWork = done)
        }
        if (done) reminders.forEach { Notifications.cancel(context, it.id) }
        scheduler.rescheduleForTask(taskId)
        return ActionResult(if (done) "Completed: ${t.title.take(60)}" else "Marked not done") {
            db.withTransaction {
                db.tasks().upsert(t)
                db.completions().delete(history.id)
                db.reminders().upsertAll(oneTime)
                oldProject?.let { db.projects().upsert(it) }
            }
            scheduler.rescheduleForTask(taskId)
        }
    }

    suspend fun setTaskStatus(taskId: String, status: TaskStatus): ActionResult {
        if (status == TaskStatus.DONE) return setTaskDone(taskId, true)
        val t = db.tasks().get(taskId) ?: return ActionResult("That task no longer exists.")
        if (t.status == TaskStatus.DONE.name) {
            val r = setTaskDone(taskId, false)
            db.tasks().get(taskId)?.let { db.tasks().upsert(it.copy(status = status.name)) }
            return r.copy(message = "Status: ${status.label}")
        }
        val now = now()
        db.tasks().upsert(t.copy(status = status.name, updatedAt = now))
        touchProject(t.projectId, now, startedWork = status == TaskStatus.IN_PROGRESS)
        return ActionResult("Status: ${status.label}") { db.tasks().upsert(t) }
    }

    // ------------------------------------------------------------------ tasks

    /**
     * Applies user edits to the task as it is now in the database (so completion or status changes
     * made meanwhile aren't overwritten). Does nothing if the task was deleted.
     */
    suspend fun editTask(taskId: String, transform: (TaskEntity) -> TaskEntity) {
        val current = db.tasks().get(taskId) ?: return
        if (current.deletedAt != null) return
        val edited = transform(current)
        if (edited == current) return
        updateTask(edited)
    }

    suspend fun updateTask(task: TaskEntity) {
        val now = now()
        db.tasks().upsert(task.copy(updatedAt = now))
        touchProject(task.projectId, now)
        scheduler.rescheduleForTask(task.id)
    }

    suspend fun addTask(projectId: String, sectionId: String, title: String, parentId: String? = null, kind: String = "TASK"): String {
        val now = now()
        val siblings = db.tasks().forProject(projectId).filter { it.sectionId == sectionId && it.deletedAt == null }
        val parent = parentId?.let { id -> siblings.firstOrNull { it.id == id } }
        val position = if (parent != null) {
            // Directly after the parent's last descendant.
            val after = siblings.filter { it.parentId == parent.id }.maxOfOrNull { it.position } ?: parent.position
            val next = siblings.filter { it.position > after }.minOfOrNull { it.position }
            if (next == null) after + 1.0 else (after + next) / 2.0
        } else {
            (siblings.maxOfOrNull { it.position } ?: 0.0) + 1.0
        }
        val id = newId()
        db.tasks().upsert(
            TaskEntity(
                id = id, projectId = projectId, sectionId = sectionId, parentId = parent?.id, kind = kind,
                title = title.trim(), position = position, level = (parent?.level ?: -1) + 1,
                createdAt = now, updatedAt = now,
            )
        )
        touchProject(projectId, now)
        return id
    }

    suspend fun deleteTask(taskId: String): ActionResult {
        val all = db.tasks().all()
        val t = all.firstOrNull { it.id == taskId } ?: return ActionResult("That item no longer exists.")
        val ids = HashSet<String>().apply { add(taskId) }
        var grew = true
        while (grew) {
            grew = false
            for (c in all) if (c.parentId in ids && ids.add(c.id)) grew = true
        }
        val victims = all.filter { it.id in ids && it.deletedAt == null }
        val now = now()
        db.tasks().upsertAll(victims.map { it.copy(deletedAt = now, updatedAt = now) })
        victims.forEach { scheduler.rescheduleForTask(it.id) }
        val extra = if (victims.size > 1) " and ${victims.size - 1} sub-item(s)" else ""
        return ActionResult("Deleted “${t.title.take(40)}”$extra") {
            db.tasks().upsertAll(victims)
            victims.forEach { scheduler.rescheduleForTask(it.id) }
        }
    }

    /** Moves a task up (-1) or down (+1) among siblings with the same section and parent. */
    suspend fun moveTask(taskId: String, delta: Int): ActionResult? {
        val t = db.tasks().get(taskId) ?: return null
        val siblings = db.tasks().forProject(t.projectId)
            .filter { it.sectionId == t.sectionId && it.parentId == t.parentId && it.deletedAt == null }
            .sortedBy { it.position }
        val i = siblings.indexOfFirst { it.id == taskId }
        val j = i + delta
        if (i < 0 || j !in siblings.indices) return null
        val other = siblings[j]
        val moved = swapPositions(t.position, other.position)
        // Descendants move with their parent by keeping their relative offset.
        val all = db.tasks().forProject(t.projectId)
        val before = listOf(t, other) + all.filter { it.parentId == t.id || it.parentId == other.id }
        db.withTransaction {
            db.tasks().upsert(t.copy(position = moved.first))
            db.tasks().upsert(other.copy(position = moved.second))
            renumberChildren(t.copy(position = moved.first), all)
            renumberChildren(other.copy(position = moved.second), all)
        }
        return ActionResult("Moved") { db.tasks().upsertAll(before) }
    }

    private fun swapPositions(a: Double, b: Double): Pair<Double, Double> =
        if (a == b) (b + 0.5) to a else b to a

    /** Children of a moved parent sit just after it, in their existing order. */
    private suspend fun renumberChildren(parent: TaskEntity, all: List<TaskEntity>) {
        val kids = all.filter { it.parentId == parent.id && it.deletedAt == null }.sortedBy { it.position }
        kids.forEachIndexed { idx, k -> db.tasks().upsert(k.copy(position = parent.position + (idx + 1) * 0.001)) }
    }

    suspend fun moveTaskToSection(taskId: String, sectionId: String): ActionResult {
        val t = db.tasks().get(taskId) ?: return ActionResult("That task no longer exists.")
        val target = db.sections().get(sectionId) ?: return ActionResult("That section no longer exists.")
        val all = db.tasks().forProject(t.projectId)
        val family = listOf(t) + all.filter { it.deletedAt == null && isDescendant(it, t.id, all) }
        val base = (all.filter { it.sectionId == sectionId && it.deletedAt == null }.maxOfOrNull { it.position } ?: 0.0) + 1.0
        val now = now()
        db.withTransaction {
            family.sortedBy { it.position }.forEachIndexed { idx, f ->
                db.tasks().upsert(
                    f.copy(
                        sectionId = sectionId, projectId = target.projectId,
                        parentId = if (f.id == t.id) null else f.parentId,
                        level = (f.level - t.level).coerceAtLeast(0),
                        position = base + idx * 0.001, updatedAt = now,
                    )
                )
            }
        }
        return ActionResult("Moved to ${target.title}") { db.tasks().upsertAll(family) }
    }

    private fun isDescendant(candidate: TaskEntity, ancestorId: String, all: List<TaskEntity>): Boolean {
        var p = candidate.parentId
        var guard = 0
        while (p != null && guard++ < 50) {
            if (p == ancestorId) return true
            p = all.firstOrNull { it.id == p }?.parentId
        }
        return false
    }

    // ------------------------------------------------------------------ sections

    suspend fun addSection(projectId: String, title: String): String {
        val now = now()
        val pos = (db.sections().forProject(projectId).filter { it.deletedAt == null }.maxOfOrNull { it.position } ?: 0.0) + 1.0
        val id = newId()
        db.sections().upsert(SectionEntity(id = id, projectId = projectId, title = title.trim(), position = pos, createdAt = now, updatedAt = now))
        touchProject(projectId, now)
        return id
    }

    suspend fun renameSection(sectionId: String, title: String) {
        val s = db.sections().get(sectionId) ?: return
        db.sections().upsert(s.copy(title = title.trim(), updatedAt = now()))
    }

    suspend fun setSectionCollapsed(sectionId: String, collapsed: Boolean) {
        val s = db.sections().get(sectionId) ?: return
        db.sections().upsert(s.copy(collapsed = collapsed))
    }

    suspend fun moveSection(sectionId: String, delta: Int): ActionResult? {
        val s = db.sections().get(sectionId) ?: return null
        val list = db.sections().forProject(s.projectId).filter { it.deletedAt == null }.sortedBy { it.position }
        val i = list.indexOfFirst { it.id == sectionId }
        val j = i + delta
        if (i < 0 || j !in list.indices) return null
        val other = list[j]
        val (a, b) = swapPositions(s.position, other.position)
        db.withTransaction {
            db.sections().upsert(s.copy(position = a))
            db.sections().upsert(other.copy(position = b))
        }
        return ActionResult("Section moved") { db.sections().upsertAll(listOf(s, other)) }
    }

    suspend fun deleteSection(sectionId: String): ActionResult {
        val s = db.sections().get(sectionId) ?: return ActionResult("That section no longer exists.")
        val now = now()
        db.sections().upsert(s.copy(deletedAt = now, updatedAt = now))
        scheduler.rescheduleAll()
        return ActionResult("Deleted section “${s.title.take(40)}”") {
            db.sections().upsert(s)
            scheduler.rescheduleAll()
        }
    }

    // ------------------------------------------------------------------ projects

    suspend fun createProject(name: String): String {
        val now = now()
        val id = newId()
        db.withTransaction {
            db.projects().upsert(
                ProjectEntity(
                    id = id, name = name.trim(), createdAt = now, updatedAt = now, lastActivityAt = now,
                    position = (db.projects().maxPosition() ?: 0.0) + 1.0,
                )
            )
            db.sections().upsert(SectionEntity(id = newId(), projectId = id, title = "General", position = 1.0, createdAt = now, updatedAt = now))
        }
        return id
    }

    suspend fun updateProject(p: ProjectEntity) {
        db.projects().upsert(p.copy(updatedAt = now()))
        scheduler.rescheduleAll()
    }

    suspend fun setProjectArchived(projectId: String, archived: Boolean): ActionResult {
        val p = db.projects().get(projectId) ?: return ActionResult("That project no longer exists.")
        val now = now()
        val progress = db.tasks().forProject(projectId).filter { it.deletedAt == null && !it.isNote }
        val restoredStatus = when {
            progress.isNotEmpty() && progress.all { it.isDone } -> ProjectStatus.DONE.name
            progress.any { it.isDone } -> ProjectStatus.IN_PROGRESS.name
            else -> ProjectStatus.NOT_STARTED.name
        }
        db.projects().upsert(
            p.copy(
                archivedAt = if (archived) now else null,
                status = if (archived) ProjectStatus.ARCHIVED.name else restoredStatus,
                updatedAt = now,
            )
        )
        scheduler.rescheduleAll()
        return ActionResult(if (archived) "Archived “${p.name}”" else "Restored “${p.name}”") {
            db.projects().upsert(p)
            scheduler.rescheduleAll()
        }
    }

    suspend fun deleteProject(projectId: String): ActionResult {
        val p = db.projects().get(projectId) ?: return ActionResult("That project no longer exists.")
        val now = now()
        db.projects().upsert(p.copy(deletedAt = now, updatedAt = now))
        scheduler.rescheduleAll()
        return ActionResult("Deleted “${p.name}”") {
            db.projects().upsert(p)
            scheduler.rescheduleAll()
        }
    }

    suspend fun moveProject(projectId: String, delta: Int): ActionResult? {
        val list = db.projects().all().filter { it.deletedAt == null }.sortedBy { it.position }
        val i = list.indexOfFirst { it.id == projectId }
        val j = i + delta
        if (i < 0 || j !in list.indices) return null
        val a = list[i]
        val b = list[j]
        val (pa, pb) = swapPositions(a.position, b.position)
        db.withTransaction {
            db.projects().upsert(a.copy(position = pa))
            db.projects().upsert(b.copy(position = pb))
        }
        return ActionResult("Project moved") { db.projects().upsertAll(listOf(a, b)) }
    }

    // ------------------------------------------------------------------ dependencies

    /** Returns an error message if refused (cycle), or null when added. */
    suspend fun addDependency(taskId: String, dependsOnId: String): String? {
        val edges = db.dependencies().all().map { DependencyGraph.Edge(it.taskId, it.dependsOnId) }
        if (DependencyGraph.wouldCreateCycle(edges, taskId, dependsOnId)) {
            return "Not added: that would create a loop (the other task already depends on this one)."
        }
        db.dependencies().insert(DependencyEntity(taskId, dependsOnId))
        return null
    }

    suspend fun removeDependency(taskId: String, dependsOnId: String) = db.dependencies().delete(taskId, dependsOnId)

    // ------------------------------------------------------------------ reminders

    /** Creates or edits a reminder. Editing restarts its schedule from now. */
    suspend fun saveReminder(r: ReminderEntity) {
        val now = now()
        val existing = db.reminders().get(r.id)
        val taskDone = r.taskId?.let { db.tasks().get(it)?.isDone } ?: false
        db.reminders().upsert(
            r.copy(
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                armedAt = now,
                lastFiredAt = null,
                snoozedUntil = null,
                itemDone = !r.isRecurring && taskDone,
                completedThrough = existing?.completedThrough,
            )
        )
        Notifications.cancel(context, r.id)
        scheduler.reschedule(r.id)
    }

    suspend fun setReminderEnabled(reminderId: String, enabled: Boolean) {
        val r = db.reminders().get(reminderId) ?: return
        db.reminders().upsert(r.copy(enabled = enabled, armedAt = now(), updatedAt = now()))
        if (!enabled) Notifications.cancel(context, reminderId)
        scheduler.reschedule(reminderId)
    }

    suspend fun deleteReminder(reminderId: String): ActionResult {
        val r = db.reminders().get(reminderId) ?: return ActionResult("Reminder already removed.")
        db.reminders().delete(reminderId)
        Notifications.cancel(context, reminderId)
        scheduler.cancel(reminderId)
        return ActionResult("Reminder removed") {
            db.reminders().upsert(r)
            scheduler.reschedule(reminderId)
        }
    }

    suspend fun snoozeReminder(reminderId: String, minutes: Int): String {
        val r = db.reminders().get(reminderId) ?: return "Reminder not found."
        val now = Instant.now()
        val rt = ReminderPlanner.onSnooze(r.toRuntime(), now, Duration.ofMinutes(minutes.toLong()))
        db.reminders().upsert(r.withRuntime(rt, now.toEpochMilli()))
        scheduler.reschedule(reminderId)
        return "Snoozed until " + Format.time(now.plus(Duration.ofMinutes(minutes.toLong())), ZoneId.systemDefault())
    }

    /** Snoozes every reminder of a task (used from the task screen). */
    suspend fun snoozeTask(taskId: String, minutes: Int): String {
        val list = db.reminders().forTask(taskId).filter { it.enabled }
        if (list.isEmpty()) return "This task has no reminder to snooze."
        var msg = ""
        list.forEach { msg = snoozeReminder(it.id, minutes) }
        return msg
    }

    /** The notification's COMPLETE button. */
    suspend fun completeFromReminder(reminderId: String) {
        val r = db.reminders().get(reminderId) ?: return
        if (r.taskId != null) {
            setTaskDone(r.taskId, true)
        } else {
            val now = Instant.now()
            val (rt, _) = ReminderPlanner.onComplete(r.toSpec(), r.toRuntime(), now, ZoneId.systemDefault())
            db.reminders().upsert(r.withRuntime(rt, now.toEpochMilli()))
            scheduler.reschedule(reminderId)
        }
    }

    // ------------------------------------------------------------------ maintenance

    suspend fun purgeTrash() {
        val cutoff = now() - TRASH_DAYS * 24L * 60 * 60 * 1000
        db.tasks().purgeDeleted(cutoff)
        db.sections().purgeDeleted(cutoff)
        db.projects().purgeDeleted(cutoff)
        val taskIds = db.tasks().all().map { it.id }.toSet()
        val projectIds = db.projects().all().map { it.id }.toSet()
        db.reminders().all()
            .filter { (it.taskId != null && it.taskId !in taskIds) || (it.taskId == null && it.projectId !in projectIds) }
            .forEach { db.reminders().delete(it.id); scheduler.cancel(it.id) }
    }

    companion object {
        const val TRASH_DAYS = 30
    }
}
