package app.projectsteps.core.next

import app.projectsteps.core.model.TaskFlag
import app.projectsteps.core.model.TaskStatus
import java.time.Instant
import java.time.ZoneId

/** Minimal view of a task needed to pick the next action. */
data class ActionCandidate(
    val taskId: String,
    val projectId: String,
    val projectPriority: Int,
    val projectLastActivity: Long,
    val sectionPosition: Double,
    val position: Double,
    val status: TaskStatus,
    val flags: Set<TaskFlag>,
    val due: Instant?,
    val blockedByDependency: Boolean,
    val hasOpenChildren: Boolean,
)

object NextActionPicker {

    fun isBlocked(c: ActionCandidate): Boolean =
        c.blockedByDependency || c.status == TaskStatus.BLOCKED ||
            TaskFlag.BLOCKED in c.flags || TaskFlag.WAITING_ON in c.flags

    fun isActionable(c: ActionCandidate, includeBlocked: Boolean): Boolean =
        c.status != TaskStatus.DONE && !c.hasOpenChildren && (includeBlocked || !isBlocked(c))

    /**
     * Orders unfinished tasks from active projects: overdue first, then due today, then tasks
     * already in progress, then the most important / most recently active project, in document order.
     */
    fun rank(candidates: List<ActionCandidate>, now: Instant, zone: ZoneId, includeBlocked: Boolean = false): List<ActionCandidate> {
        val today = now.atZone(zone).toLocalDate()
        fun group(c: ActionCandidate): Int = when {
            c.due != null && c.due.isBefore(now) -> 0
            c.due != null && c.due.atZone(zone).toLocalDate() == today -> 1
            c.status == TaskStatus.IN_PROGRESS -> 2
            else -> 3
        }
        return candidates.filter { isActionable(it, includeBlocked) }.sortedWith(
            compareBy<ActionCandidate> { group(it) }
                .thenBy { if (group(it) <= 1) it.due else null }
                .thenBy { if (isBlocked(it)) 1 else 0 }
                .thenByDescending { it.projectPriority }
                .thenByDescending { it.projectLastActivity }
                .thenBy { it.projectId }
                .thenBy { it.sectionPosition }
                .thenBy { it.position }
        )
    }
}
