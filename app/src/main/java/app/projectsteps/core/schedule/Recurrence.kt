package app.projectsteps.core.schedule

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

enum class RepeatType(val label: String) {
    NONE("One time"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    WEEKDAYS("Selected weekdays"),
    MONTHLY("Monthly"),
    CUSTOM("Custom"),
}

enum class RepeatUnit(val label: String) { DAYS("days"), WEEKS("weeks"), MONTHS("months") }

/**
 * Recurrence definition.
 *  - NONE: a single occurrence on the start date.
 *  - DAILY / WEEKLY / MONTHLY: every day / every week on the start's weekday / every month on the start's day.
 *  - WEEKDAYS: every week on the selected [weekdays].
 *  - CUSTOM: every [interval] [unit]; for WEEKS, optional [weekdays] select days within active weeks.
 * Monthly dates past the end of a month (e.g. the 31st) fall on the month's last day.
 */
data class RecurrenceRule(
    val type: RepeatType = RepeatType.NONE,
    val interval: Int = 1,
    val unit: RepeatUnit = RepeatUnit.DAYS,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val endDate: LocalDate? = null,
) {
    val isRecurring: Boolean get() = type != RepeatType.NONE

    companion object {
        fun weekdaysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }
        fun maskToWeekdays(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
    }
}

/**
 * When a reminder fires. [zoneId] null means "follow the device's current time zone";
 * otherwise the reminder is pinned to that IANA zone (e.g. "America/New_York").
 */
data class ReminderSpec(
    val startDate: LocalDate,
    val time: LocalTime,
    val zoneId: String? = null,
    val rule: RecurrenceRule = RecurrenceRule(),
    /** Keep reminding every [nagIntervalMinutes] after an occurrence fires until it's completed. */
    val repeatUntilCompleted: Boolean = false,
    val nagIntervalMinutes: Int = 60,
    /** User explicitly asked for an exact-time alarm. */
    val precise: Boolean = false,
)

object Recurrence {
    private const val MAX_SCAN_DAYS = 1500L

    fun zoneFor(spec: ReminderSpec, deviceZone: ZoneId): ZoneId =
        spec.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: deviceZone

    /**
     * The instant of an occurrence on [date]. Daylight-saving gaps move the time forward by the
     * gap length (02:30 on a spring-forward day becomes 03:30); overlaps use the earlier offset.
     */
    fun occurrenceAt(spec: ReminderSpec, date: LocalDate, zone: ZoneId): Instant =
        ZonedDateTime.of(date, spec.time, zone).toInstant()

    fun isOccurrenceDate(spec: ReminderSpec, date: LocalDate): Boolean {
        val start = spec.startDate
        val rule = spec.rule
        if (date.isBefore(start)) return false
        rule.endDate?.let { if (date.isAfter(it)) return false }
        val interval = rule.interval.coerceAtLeast(1)
        return when (rule.type) {
            RepeatType.NONE -> date == start
            RepeatType.DAILY -> true
            RepeatType.WEEKLY -> date.dayOfWeek == start.dayOfWeek
            RepeatType.WEEKDAYS -> date.dayOfWeek in rule.weekdays.ifEmpty { setOf(start.dayOfWeek) }
            RepeatType.MONTHLY -> monthlyMatch(start, date, 1)
            RepeatType.CUSTOM -> when (rule.unit) {
                RepeatUnit.DAYS -> ChronoUnit.DAYS.between(start, date) % interval == 0L
                RepeatUnit.WEEKS -> {
                    val weekStart = start.minusDays((start.dayOfWeek.value - 1).toLong())
                    val weekIndex = ChronoUnit.DAYS.between(weekStart, date) / 7
                    weekIndex % interval == 0L && date.dayOfWeek in rule.weekdays.ifEmpty { setOf(start.dayOfWeek) }
                }
                RepeatUnit.MONTHS -> monthlyMatch(start, date, interval)
            }
        }
    }

    private fun monthlyMatch(start: LocalDate, date: LocalDate, interval: Int): Boolean {
        val months = ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(date))
        if (months % interval != 0L) return false
        return date.dayOfMonth == minOf(start.dayOfMonth, date.lengthOfMonth())
    }

    /** First occurrence strictly after [after], or null if the series has ended. */
    fun nextOccurrence(spec: ReminderSpec, after: Instant, deviceZone: ZoneId): Instant? {
        val zone = zoneFor(spec, deviceZone)
        val afterDate = after.atZone(zone).toLocalDate()
        var d = maxOf(spec.startDate, afterDate.minusDays(1))
        val stop = d.plusDays(MAX_SCAN_DAYS)
        while (!d.isAfter(stop)) {
            spec.rule.endDate?.let { if (d.isAfter(it)) return null }
            if (!spec.rule.isRecurring && d.isAfter(spec.startDate)) return null
            if (isOccurrenceDate(spec, d)) {
                val at = occurrenceAt(spec, d, zone)
                if (at.isAfter(after)) return at
            }
            d = d.plusDays(1)
        }
        return null
    }

    /** Latest occurrence at or before [atOrBefore], or null if none yet. */
    fun previousOrSame(spec: ReminderSpec, atOrBefore: Instant, deviceZone: ZoneId): Instant? {
        val zone = zoneFor(spec, deviceZone)
        var d = atOrBefore.atZone(zone).toLocalDate().plusDays(1)
        val stop = d.minusDays(MAX_SCAN_DAYS)
        while (!d.isBefore(spec.startDate) && !d.isBefore(stop)) {
            if (isOccurrenceDate(spec, d)) {
                val at = occurrenceAt(spec, d, zone)
                if (!at.isAfter(atOrBefore)) return at
            }
            d = d.minusDays(1)
        }
        return null
    }

    fun describe(spec: ReminderSpec): String {
        val r = spec.rule
        val base = when (r.type) {
            RepeatType.NONE -> "Once"
            RepeatType.DAILY -> "Every day"
            RepeatType.WEEKLY -> "Every ${spec.startDate.dayOfWeek.display()}"
            RepeatType.WEEKDAYS -> "Every " + r.weekdays.ifEmpty { setOf(spec.startDate.dayOfWeek) }.sorted().joinToString(", ") { it.display() }
            RepeatType.MONTHLY -> "Monthly on day ${spec.startDate.dayOfMonth}"
            RepeatType.CUSTOM -> {
                val n = r.interval.coerceAtLeast(1)
                val every = if (n == 1) "Every ${r.unit.label.dropLast(1)}" else "Every $n ${r.unit.label}"
                if (r.unit == RepeatUnit.WEEKS && r.weekdays.isNotEmpty()) "$every on " + r.weekdays.sorted().joinToString(", ") { it.display() } else every
            }
        }
        val until = r.endDate?.let { " until $it" } ?: ""
        val nag = if (spec.repeatUntilCompleted) ", repeats every ${spec.nagIntervalMinutes} min until done" else ""
        return "$base at ${spec.time}$until$nag"
    }

    private fun DayOfWeek.display(): String = name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
}

/** Quiet hours in local wall-clock time. A window may wrap past midnight (e.g. 22:00–07:00). */
data class QuietHours(val enabled: Boolean, val start: LocalTime, val end: LocalTime) {

    fun contains(t: LocalTime): Boolean = when {
        !enabled || start == end -> false
        start.isBefore(end) -> !t.isBefore(start) && t.isBefore(end)
        else -> !t.isBefore(start) || t.isBefore(end)
    }

    /** Moves an instant that falls inside quiet hours to the end of the window. */
    fun adjust(instant: Instant, zone: ZoneId): Instant {
        if (!enabled) return instant
        val z = instant.atZone(zone)
        val t = z.toLocalTime()
        if (!contains(t)) return instant
        var endDate = z.toLocalDate()
        if (start.isAfter(end) && !t.isBefore(start)) endDate = endDate.plusDays(1)
        return ZonedDateTime.of(endDate, end, zone).toInstant()
    }

    companion object {
        val OFF = QuietHours(false, LocalTime.of(22, 0), LocalTime.of(7, 0))
    }
}

/** Mutable runtime state stored with each reminder. */
data class ReminderRuntime(
    val enabled: Boolean = true,
    val lastFiredAt: Instant? = null,
    val snoozedUntil: Instant? = null,
    /** Recurring: occurrences up to and including this instant are completed. */
    val completedThrough: Instant? = null,
    /** One-time: the item itself is done. */
    val itemDone: Boolean = false,
    /** When the reminder was created or last edited. Occurrences before this are never "caught up". */
    val armedAt: Instant? = null,
)

/**
 * Pure scheduling logic: given a reminder definition and its runtime state, when should the next
 * notification appear? Used for creating, editing, snoozing, completing, and for rebuilding
 * every schedule after reboot or a clock/time-zone change (same inputs give the same answer,
 * so rebuilding never produces duplicates).
 */
object ReminderPlanner {
    /** Missed recurring occurrences older than this are skipped rather than shown late. */
    val CATCH_UP_WINDOW: Duration = Duration.ofHours(24)

    fun nextTrigger(
        spec: ReminderSpec,
        rt: ReminderRuntime,
        now: Instant,
        deviceZone: ZoneId,
        quiet: QuietHours = QuietHours.OFF,
    ): Instant? {
        if (!rt.enabled) return null
        val candidates = ArrayList<Instant>()

        rt.snoozedUntil?.let { snooze ->
            if (snooze.isAfter(now)) candidates.add(snooze)
            else if (rt.lastFiredAt == null || rt.lastFiredAt.isBefore(snooze)) candidates.add(now) // missed while off
        }

        if (!spec.rule.isRecurring) {
            if (rt.itemDone) return null
            val zone = Recurrence.zoneFor(spec, deviceZone)
            val occ = Recurrence.occurrenceAt(spec, spec.startDate, zone)
            val last = rt.lastFiredAt
            if (last == null || last.isBefore(occ)) {
                if (occ.isAfter(now)) candidates.add(occ)
                else if (rt.armedAt == null || !occ.isBefore(rt.armedAt)) candidates.add(now) // missed while off
            } else if (spec.repeatUntilCompleted) {
                candidates.add(nagAfter(last, now, spec.nagIntervalMinutes))
            }
        } else {
            val recent = Recurrence.previousOrSame(spec, now, deviceZone)
            val recentDone = recent != null && rt.completedThrough != null && !rt.completedThrough.isBefore(recent)
            val last = rt.lastFiredAt
            if (recent != null && !recentDone && (last == null || last.isBefore(recent)) &&
                (rt.armedAt == null || !recent.isBefore(rt.armedAt)) &&
                Duration.between(recent, now) <= CATCH_UP_WINDOW
            ) {
                candidates.add(now)
            }
            // Occurrences already completed in advance are skipped.
            val from = rt.completedThrough?.let { if (it.isAfter(now)) it else now } ?: now
            Recurrence.nextOccurrence(spec, from, deviceZone)?.let { candidates.add(it) }
            if (spec.repeatUntilCompleted && recent != null && !recentDone && last != null && !last.isBefore(recent)) {
                candidates.add(nagAfter(last, now, spec.nagIntervalMinutes))
            }
        }

        val next = candidates.minOrNull() ?: return null
        return quiet.adjust(next, deviceZone)
    }

    private fun nagAfter(last: Instant, now: Instant, minutes: Int): Instant {
        val step = Duration.ofMinutes(minutes.coerceAtLeast(5).toLong())
        var t = last.plus(step)
        if (!t.isAfter(now)) {
            val k = Duration.between(last, now).toMillis() / step.toMillis() + 1
            t = last.plus(step.multipliedBy(k))
        }
        return t
    }

    /** State after a notification has been shown at [firedAt]. */
    fun onFired(rt: ReminderRuntime, firedAt: Instant): ReminderRuntime =
        rt.copy(lastFiredAt = firedAt, snoozedUntil = rt.snoozedUntil?.takeIf { it.isAfter(firedAt) })

    fun onSnooze(rt: ReminderRuntime, now: Instant, duration: Duration): ReminderRuntime =
        rt.copy(snoozedUntil = now.plus(duration))

    /**
     * State after the user completes the item. One-time: done. Recurring: only the current
     * occurrence (the latest one due, or the next one if none is due yet) is completed; future
     * occurrences stay scheduled. Returns the new state and the completed occurrence instant.
     */
    fun onComplete(spec: ReminderSpec, rt: ReminderRuntime, now: Instant, deviceZone: ZoneId): Pair<ReminderRuntime, Instant?> {
        if (!spec.rule.isRecurring) {
            val zone = Recurrence.zoneFor(spec, deviceZone)
            return rt.copy(itemDone = true, snoozedUntil = null) to Recurrence.occurrenceAt(spec, spec.startDate, zone)
        }
        val recent = Recurrence.previousOrSame(spec, now, deviceZone)
        val alreadyDone = recent != null && rt.completedThrough != null && !rt.completedThrough.isBefore(recent)
        val target = if (recent != null && !alreadyDone) recent else Recurrence.nextOccurrence(spec, maxOf(now, rt.completedThrough ?: now), deviceZone)
        return rt.copy(completedThrough = target ?: rt.completedThrough, snoozedUntil = null) to target
    }

    /** The occurrence the user is currently working towards (for "due" display), or null if finished. */
    fun currentOccurrence(spec: ReminderSpec, rt: ReminderRuntime, now: Instant, deviceZone: ZoneId): Instant? {
        val zone = Recurrence.zoneFor(spec, deviceZone)
        if (!spec.rule.isRecurring) return if (rt.itemDone) null else Recurrence.occurrenceAt(spec, spec.startDate, zone)
        val from = rt.completedThrough
        return if (from == null) {
            Recurrence.nextOccurrence(spec, Recurrence.occurrenceAt(spec, spec.startDate, zone).minusSeconds(1), deviceZone)
        } else {
            Recurrence.nextOccurrence(spec, from, deviceZone)
        }
    }
}
