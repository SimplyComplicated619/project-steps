package app.projectsteps.data

import app.projectsteps.core.backup.BCompletion
import app.projectsteps.core.backup.BDependency
import app.projectsteps.core.backup.BProject
import app.projectsteps.core.backup.BReminder
import app.projectsteps.core.backup.BSection
import app.projectsteps.core.backup.BTask
import app.projectsteps.core.schedule.RecurrenceRule
import app.projectsteps.core.schedule.ReminderRuntime
import app.projectsteps.core.schedule.ReminderSpec
import app.projectsteps.core.schedule.RepeatType
import app.projectsteps.core.schedule.RepeatUnit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

private fun Long?.toInstant(): Instant? = this?.let { Instant.ofEpochMilli(it) }

fun ReminderEntity.toSpec(): ReminderSpec = ReminderSpec(
    startDate = runCatching { LocalDate.parse(startDate) }.getOrElse { LocalDate.now() },
    time = runCatching { LocalTime.parse(time) }.getOrElse { LocalTime.of(9, 0) },
    zoneId = zoneId,
    rule = RecurrenceRule(
        type = RepeatType.entries.firstOrNull { it.name == repeatType } ?: RepeatType.NONE,
        interval = interval.coerceIn(1, 365),
        unit = RepeatUnit.entries.firstOrNull { it.name == unit } ?: RepeatUnit.DAYS,
        weekdays = RecurrenceRule.maskToWeekdays(weekdaysMask),
        endDate = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    ),
    repeatUntilCompleted = repeatUntilCompleted,
    nagIntervalMinutes = nagMinutes,
    precise = precise,
)

fun ReminderEntity.toRuntime(itemDone: Boolean = this.itemDone): ReminderRuntime = ReminderRuntime(
    enabled = enabled,
    lastFiredAt = lastFiredAt.toInstant(),
    snoozedUntil = snoozedUntil.toInstant(),
    completedThrough = completedThrough.toInstant(),
    itemDone = itemDone,
    armedAt = armedAt.toInstant(),
)

fun ReminderEntity.withRuntime(rt: ReminderRuntime, now: Long): ReminderEntity = copy(
    enabled = rt.enabled,
    lastFiredAt = rt.lastFiredAt?.toEpochMilli(),
    snoozedUntil = rt.snoozedUntil?.toEpochMilli(),
    completedThrough = rt.completedThrough?.toEpochMilli(),
    itemDone = rt.itemDone,
    armedAt = rt.armedAt?.toEpochMilli(),
    updatedAt = now,
)

val ReminderEntity.isRecurring: Boolean get() = repeatType != RepeatType.NONE.name

// ------------------------------------------------------------------ backup mapping

fun ProjectEntity.toB() = BProject(
    id, name, description, priority, status, createdAt, updatedAt, dueDate, dueTime, dueZoneId, sourceFileName,
    sourceArchiveName, sourceTitle, sourceSha256, sourceKey, importedAt, isDemo, lastActivityAt, position, archivedAt, deletedAt,
)

fun BProject.toEntity() = ProjectEntity(
    id, name, description, priority, status, createdAt, updatedAt, dueDate, dueTime, dueZoneId, sourceFileName,
    sourceArchiveName, sourceTitle, sourceSha256, sourceKey, importedAt, isDemo, lastActivityAt, position, archivedAt, deletedAt,
)

fun SectionEntity.toB() = BSection(
    id, projectId, title, headingLevel, position, collapsed, sourceTitle, sourceSectionPath, sourceBlockIndex,
    sourceText, sourceMissing, createdAt, updatedAt, deletedAt,
)

fun BSection.toEntity() = SectionEntity(
    id, projectId, title, headingLevel, position, collapsed, sourceTitle, sourceSectionPath, sourceBlockIndex,
    sourceText, sourceMissing, createdAt, updatedAt, deletedAt,
)

fun TaskEntity.toB() = BTask(
    id, projectId, sectionId, parentId, kind, title, description, notes, position, level, status, completedAt,
    priority, dueDate, dueTime, dueZoneId, waitingOn, flags, links, sourceFileName, sourceSectionPath,
    sourceBlockIndex, sourceText, sourceTitle, sourceCompleted, sourceMissing, fingerprint, importedAt,
    createdAt, updatedAt, deletedAt,
)

fun BTask.toEntity() = TaskEntity(
    id, projectId, sectionId, parentId, kind, title, description, notes, position, level, status, completedAt,
    priority, dueDate, dueTime, dueZoneId, waitingOn, flags, links, sourceFileName, sourceSectionPath,
    sourceBlockIndex, sourceText, sourceTitle, sourceCompleted, sourceMissing, fingerprint, importedAt,
    createdAt, updatedAt, deletedAt,
)

fun DependencyEntity.toB() = BDependency(taskId, dependsOnId)
fun BDependency.toEntity() = DependencyEntity(taskId, dependsOnId)

fun ReminderEntity.toB() = BReminder(
    id, taskId, projectId, startDate, time, zoneId, repeatType, interval, unit, weekdaysMask, endDate,
    repeatUntilCompleted, nagMinutes, precise, enabled, lastFiredAt, snoozedUntil, completedThrough, itemDone,
    armedAt, createdAt, updatedAt,
)

fun BReminder.toEntity() = ReminderEntity(
    id = id, taskId = taskId, projectId = projectId, startDate = startDate, time = time, zoneId = zoneId,
    repeatType = repeatType, interval = interval, unit = unit, weekdaysMask = weekdaysMask, endDate = endDate,
    repeatUntilCompleted = repeatUntilCompleted, nagMinutes = nagMinutes, precise = precise, enabled = enabled,
    lastFiredAt = lastFiredAt, snoozedUntil = snoozedUntil, completedThrough = completedThrough, itemDone = itemDone,
    armedAt = armedAt, nextFireAt = null, createdAt = createdAt, updatedAt = updatedAt,
)

fun CompletionEntity.toB() = BCompletion(id, taskId, occurrenceAt, completedAt, action)
fun BCompletion.toEntity() = CompletionEntity(id, taskId, occurrenceAt, completedAt, action)
