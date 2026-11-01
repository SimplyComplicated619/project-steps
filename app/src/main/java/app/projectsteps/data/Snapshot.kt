package app.projectsteps.data

import app.projectsteps.core.deps.DependencyGraph
import app.projectsteps.core.model.ProjectStatus
import app.projectsteps.core.model.TaskFlag
import app.projectsteps.core.model.TaskStatus
import app.projectsteps.core.next.ActionCandidate
import app.projectsteps.core.schedule.ReminderPlanner
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class Progress(val done: Int, val total: Int) {
    val percent: Int get() = if (total == 0) 0 else (done * 100 / total)
}

/**
 * An immutable, in-memory view of all live (non-deleted) data. Personal project lists are small,
 * so every screen derives what it needs from one snapshot that updates when the database changes.
 */
class Snapshot(
    val projects: List<ProjectEntity> = emptyList(),
    val sections: List<SectionEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val dependencies: List<DependencyEntity> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(),
    val loaded: Boolean = false,
) {
    val projectById: Map<String, ProjectEntity> = projects.associateBy { it.id }
    val sectionById: Map<String, SectionEntity> = sections.associateBy { it.id }
    val taskById: Map<String, TaskEntity> = tasks.associateBy { it.id }

    val activeProjects: List<ProjectEntity> =
        projects.filter { it.archivedAt == null && it.status != ProjectStatus.ARCHIVED.name }
    val archivedProjects: List<ProjectEntity> =
        projects.filter { it.archivedAt != null || it.status == ProjectStatus.ARCHIVED.name }

    val sectionsByProject: Map<String, List<SectionEntity>> =
        sections.groupBy { it.projectId }.mapValues { (_, v) -> v.sortedBy { it.position } }
    val tasksBySection: Map<String, List<TaskEntity>> =
        tasks.groupBy { it.sectionId }.mapValues { (_, v) -> v.sortedBy { it.position } }
    val tasksByProject: Map<String, List<TaskEntity>> = tasks.groupBy { it.projectId }
    val childrenOf: Map<String, List<TaskEntity>> =
        tasks.filter { it.parentId != null }.groupBy { it.parentId!! }.mapValues { (_, v) -> v.sortedBy { it.position } }

    val doneIds: Set<String> = tasks.filter { it.isDone }.map { it.id }.toSet()
    private val edges = dependencies.map { DependencyGraph.Edge(it.taskId, it.dependsOnId) }
    val blockedByDeps: Set<String> = DependencyGraph.blockedTaskIds(edges, doneIds, taskById.keys)
    val dependsOn: Map<String, List<String>> = dependencies.groupBy({ it.taskId }, { it.dependsOnId })

    val remindersByTask: Map<String, List<ReminderEntity>> =
        reminders.filter { it.taskId != null }.groupBy { it.taskId!! }
    val remindersByProject: Map<String, List<ReminderEntity>> =
        reminders.filter { it.taskId == null && it.projectId != null }.groupBy { it.projectId!! }

    fun progress(projectId: String): Progress {
        val list = tasksByProject[projectId].orEmpty().filter { !it.isNote }
        return Progress(list.count { it.isDone }, list.size)
    }

    fun hasOpenChildren(taskId: String): Boolean =
        childrenOf[taskId].orEmpty().any { !it.isNote && !it.isDone }

    fun flags(t: TaskEntity): Set<TaskFlag> = TaskFlag.decode(t.flags)

    fun isBlocked(t: TaskEntity): Boolean {
        val f = flags(t)
        return t.id in blockedByDeps || t.status == TaskStatus.BLOCKED.name ||
            TaskFlag.BLOCKED in f || TaskFlag.WAITING_ON in f
    }

    fun recurringReminder(taskId: String): ReminderEntity? =
        remindersByTask[taskId].orEmpty().firstOrNull { it.enabled && it.isRecurring }

    fun isRecurring(taskId: String): Boolean = recurringReminder(taskId) != null

    /** Due instant: the current occurrence for recurring tasks, otherwise the task's own due date/time. */
    fun due(t: TaskEntity, now: Instant, zone: ZoneId): Instant? {
        recurringReminder(t.id)?.let { r ->
            return ReminderPlanner.currentOccurrence(r.toSpec(), r.toRuntime(), now, zone)
        }
        return dueOf(t.dueDate, t.dueTime, t.dueZoneId, zone)
    }

    fun projectDue(p: ProjectEntity, zone: ZoneId): Instant? = dueOf(p.dueDate, p.dueTime, p.dueZoneId, zone)

    fun nextReminderAt(taskId: String): Instant? =
        remindersByTask[taskId].orEmpty().mapNotNull { it.nextFireAt }.minOrNull()?.let { Instant.ofEpochMilli(it) }

    fun descendants(taskId: String): List<TaskEntity> {
        val out = ArrayList<TaskEntity>()
        fun walk(id: String) {
            for (c in childrenOf[id].orEmpty()) {
                out.add(c)
                walk(c.id)
            }
        }
        walk(taskId)
        return out
    }

    fun isProjectActive(projectId: String): Boolean = activeProjects.any { it.id == projectId }

    fun actionCandidates(now: Instant, zone: ZoneId): List<ActionCandidate> {
        val active = activeProjects.filter { it.status != ProjectStatus.DONE.name }.associateBy { it.id }
        return tasks.asSequence()
            .filter { !it.isNote && !it.isDone && it.projectId in active }
            .map { t ->
                val p = active.getValue(t.projectId)
                ActionCandidate(
                    taskId = t.id,
                    projectId = p.id,
                    projectPriority = p.priority,
                    projectLastActivity = p.lastActivityAt,
                    sectionPosition = sectionById[t.sectionId]?.position ?: 0.0,
                    position = t.position,
                    status = TaskStatus.parse(t.status),
                    flags = flags(t),
                    due = due(t, now, zone),
                    blockedByDependency = t.id in blockedByDeps,
                    hasOpenChildren = hasOpenChildren(t.id),
                )
            }.toList()
    }

    companion object {
        fun dueOf(date: String?, time: String?, zoneId: String?, deviceZone: ZoneId): Instant? {
            val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val t = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(23, 59)
            val z = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: deviceZone
            return ZonedDateTime.of(d, t, z).toInstant()
        }
    }
}
