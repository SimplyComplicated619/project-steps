package app.projectsteps.core

import app.projectsteps.core.schedule.QuietHours
import app.projectsteps.core.schedule.Recurrence
import app.projectsteps.core.schedule.RecurrenceRule
import app.projectsteps.core.schedule.ReminderPlanner
import app.projectsteps.core.schedule.ReminderRuntime
import app.projectsteps.core.schedule.ReminderSpec
import app.projectsteps.core.schedule.RepeatType
import app.projectsteps.core.schedule.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SchedulingTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val ny = ZoneId.of("America/New_York")
    private fun t(s: String): Instant = Instant.parse(s)
    private fun spec(
        date: String, time: String, type: RepeatType = RepeatType.NONE, zone: String? = null,
        weekdays: Set<DayOfWeek> = emptySet(), interval: Int = 1, unit: RepeatUnit = RepeatUnit.DAYS, end: String? = null,
        nag: Boolean = false, nagMinutes: Int = 60,
    ) = ReminderSpec(
        LocalDate.parse(date), LocalTime.parse(time), zone,
        RecurrenceRule(type, interval, unit, weekdays, end?.let(LocalDate::parse)),
        repeatUntilCompleted = nag, nagIntervalMinutes = nagMinutes,
    )

    // ---------------------------------------------------------------- recurrence

    @Test
    fun dailyWeeklyWeekdaysMonthly() {
        val now = t("2026-10-31T17:00:00Z") // Sat 10:00 PDT
        assertEquals(t("2026-11-01T17:00:00Z"), Recurrence.nextOccurrence(spec("2026-10-01", "09:00", RepeatType.DAILY), now, la)) // 09:00 PST
        // Weekly on the start's weekday (2026-10-05 is a Monday)
        assertEquals(t("2026-11-02T17:00:00Z"), Recurrence.nextOccurrence(spec("2026-10-05", "09:00", RepeatType.WEEKLY), now, la))
        // Selected weekdays: Tue + Thu
        val tt = spec("2026-10-01", "08:00", RepeatType.WEEKDAYS, weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))
        assertEquals(LocalDate.parse("2026-11-03"), Recurrence.nextOccurrence(tt, now, la)!!.atZone(la).toLocalDate())
        // Monthly on the 31st clamps to the 30th in November
        val m = Recurrence.nextOccurrence(spec("2026-08-31", "09:00", RepeatType.MONTHLY), t("2026-11-01T00:00:00Z"), la)!!
        assertEquals(LocalDate.parse("2026-11-30"), m.atZone(la).toLocalDate())
    }

    @Test
    fun customRecurrenceAndEndDate() {
        val now = t("2026-10-31T17:00:00Z")
        val every3 = spec("2026-10-01", "09:00", RepeatType.CUSTOM, interval = 3, unit = RepeatUnit.DAYS)
        // Oct 1,4,...,31 -> Oct 31 09:00 PDT already passed at 10:00, next is Nov 3
        assertEquals(LocalDate.parse("2026-11-03"), Recurrence.nextOccurrence(every3, now, la)!!.atZone(la).toLocalDate())
        val biweekly = spec("2026-10-05", "09:00", RepeatType.CUSTOM, interval = 2, unit = RepeatUnit.WEEKS, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        // Active weeks: Oct 5, Oct 19, Nov 2 ...
        val next = Recurrence.nextOccurrence(biweekly, now, la)!!.atZone(la).toLocalDate()
        assertEquals(LocalDate.parse("2026-11-02"), next)
        val ended = spec("2026-10-01", "09:00", RepeatType.DAILY, end = "2026-10-30")
        assertNull(Recurrence.nextOccurrence(ended, now, la))
        val once = spec("2026-10-30", "09:00")
        assertNull(Recurrence.nextOccurrence(once, now, la))
    }

    @Test
    fun dstSpringForwardGapAndFallBackOverlap() {
        // 02:30 doesn't exist on 2026-03-08 in Los Angeles; Java moves it forward to 03:30 PDT.
        val gap = spec("2026-03-08", "02:30")
        assertEquals(t("2026-03-08T10:30:00Z"), Recurrence.occurrenceAt(gap, gap.startDate, la))
        // 01:30 happens twice on 2026-11-01; the earlier (PDT, UTC-7) offset is used.
        val overlap = spec("2026-11-01", "01:30")
        assertEquals(t("2026-11-01T08:30:00Z"), Recurrence.occurrenceAt(overlap, overlap.startDate, la))
    }

    @Test
    fun dailyReminderKeepsWallClockTimeAcrossDst() {
        val daily = spec("2026-10-01", "09:00", RepeatType.DAILY)
        val sat = Recurrence.nextOccurrence(daily, t("2026-10-31T00:00:00Z"), la)!!
        val sun = Recurrence.nextOccurrence(daily, sat, la)!!
        assertEquals(t("2026-10-31T16:00:00Z"), sat) // 09:00 PDT
        assertEquals(t("2026-11-01T17:00:00Z"), sun) // 09:00 PST
        assertEquals(Duration.ofHours(25), Duration.between(sat, sun))
    }

    @Test
    fun timezoneChangeLocalVersusFixed() {
        val now = t("2026-10-31T12:00:00Z")
        val local = spec("2026-11-02", "09:00")
        val fixed = spec("2026-11-02", "09:00", zone = "America/New_York")
        // Device in Los Angeles
        assertEquals(t("2026-11-02T17:00:00Z"), ReminderPlanner.nextTrigger(local, ReminderRuntime(), now, la))
        assertEquals(t("2026-11-02T14:00:00Z"), ReminderPlanner.nextTrigger(fixed, ReminderRuntime(), now, la))
        // User travels: device now in New York. Local follows the device; fixed doesn't move.
        assertEquals(t("2026-11-02T14:00:00Z"), ReminderPlanner.nextTrigger(local, ReminderRuntime(), now, ny))
        assertEquals(t("2026-11-02T14:00:00Z"), ReminderPlanner.nextTrigger(fixed, ReminderRuntime(), now, ny))
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(t("2026-11-02T00:00:00Z"), ReminderPlanner.nextTrigger(local, ReminderRuntime(), now, tokyo))
        assertEquals(t("2026-11-02T14:00:00Z"), ReminderPlanner.nextTrigger(fixed, ReminderRuntime(), now, tokyo))
    }

    // ---------------------------------------------------------------- quiet hours

    @Test
    fun quietHoursWrapMidnightAndSameDayWindows() {
        val q = QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0))
        assertTrue(q.contains(LocalTime.of(23, 30)))
        assertTrue(q.contains(LocalTime.of(6, 59)))
        assertTrue(!q.contains(LocalTime.of(7, 0)))
        // 23:30 PDT on Oct 30 -> 07:00 PDT on Oct 31
        assertEquals(t("2026-10-31T14:00:00Z"), q.adjust(t("2026-10-31T06:30:00Z"), la))
        // 05:00 -> 07:00 same day
        assertEquals(t("2026-10-31T14:00:00Z"), q.adjust(t("2026-10-31T12:00:00Z"), la))
        val lunch = QuietHours(true, LocalTime.of(12, 0), LocalTime.of(13, 0))
        assertEquals(t("2026-10-31T20:00:00Z"), lunch.adjust(t("2026-10-31T19:15:00Z"), la))
        val off = QuietHours(false, LocalTime.of(22, 0), LocalTime.of(7, 0))
        assertEquals(t("2026-10-31T06:30:00Z"), off.adjust(t("2026-10-31T06:30:00Z"), la))
    }

    // ---------------------------------------------------------------- reminder planner

    @Test
    fun createEditCancel() {
        val now = t("2026-10-31T17:00:00Z")
        val s = spec("2026-10-31", "10:05")
        val rt = ReminderRuntime(armedAt = now)
        assertEquals(t("2026-10-31T17:05:00Z"), ReminderPlanner.nextTrigger(s, rt, now, la))
        val edited = s.copy(time = LocalTime.of(11, 0))
        assertEquals(t("2026-10-31T18:00:00Z"), ReminderPlanner.nextTrigger(edited, rt, now, la))
        assertNull(ReminderPlanner.nextTrigger(edited, rt.copy(enabled = false), now, la))
    }

    @Test
    fun completeOneTimeStopsReminder() {
        val now = t("2026-10-31T17:00:00Z")
        val s = spec("2026-10-31", "10:05")
        val (after, occurrence) = ReminderPlanner.onComplete(s, ReminderRuntime(armedAt = now), now, la)
        assertTrue(after.itemDone)
        assertEquals(t("2026-10-31T17:05:00Z"), occurrence)
        assertNull(ReminderPlanner.nextTrigger(s, after, now, la))
    }

    @Test
    fun snoozeThenFireReturnsToSchedule() {
        val fired = t("2026-10-31T16:00:00Z") // daily 09:00 PDT fired
        val s = spec("2026-10-01", "09:00", RepeatType.DAILY)
        var rt = ReminderPlanner.onFired(ReminderRuntime(armedAt = t("2026-10-01T00:00:00Z")), fired)
        rt = ReminderPlanner.onSnooze(rt, fired, Duration.ofMinutes(15))
        assertEquals(t("2026-10-31T16:15:00Z"), ReminderPlanner.nextTrigger(s, rt, fired, la))
        val snoozeFired = t("2026-10-31T16:15:00Z")
        rt = ReminderPlanner.onFired(rt, snoozeFired)
        assertNull(rt.snoozedUntil)
        assertEquals(t("2026-11-01T17:00:00Z"), ReminderPlanner.nextTrigger(s, rt, snoozeFired, la))
    }

    @Test
    fun recurringCompletionCompletesOnlyCurrentOccurrence() {
        val s = spec("2026-10-01", "09:00", RepeatType.DAILY)
        val now = t("2026-10-31T16:30:00Z") // 09:30 PDT, today's 09:00 is due
        val rt0 = ReminderPlanner.onFired(ReminderRuntime(armedAt = t("2026-10-01T00:00:00Z")), t("2026-10-31T16:00:00Z"))
        val (rt1, occ1) = ReminderPlanner.onComplete(s, rt0, now, la)
        assertEquals(t("2026-10-31T16:00:00Z"), occ1)
        assertTrue(!rt1.itemDone)
        assertEquals(t("2026-11-01T17:00:00Z"), ReminderPlanner.nextTrigger(s, rt1, now, la))
        assertEquals(t("2026-11-01T17:00:00Z"), ReminderPlanner.currentOccurrence(s, rt1, now, la))
        // Completing again the same day completes tomorrow's occurrence, not the whole series.
        val (rt2, occ2) = ReminderPlanner.onComplete(s, rt1, now, la)
        assertEquals(t("2026-11-01T17:00:00Z"), occ2)
        assertEquals(t("2026-11-02T17:00:00Z"), ReminderPlanner.currentOccurrence(s, rt2, now, la))
        // Tomorrow is already done, so the next reminder is the day after.
        assertEquals(t("2026-11-02T17:00:00Z"), ReminderPlanner.nextTrigger(s, rt2, now, la))
    }

    @Test
    fun repeatUntilCompletedNagsThenStops() {
        val s = spec("2026-10-01", "09:00", RepeatType.DAILY, nag = true, nagMinutes = 30)
        val fired = t("2026-10-31T16:00:00Z")
        val rt = ReminderPlanner.onFired(ReminderRuntime(armedAt = t("2026-10-01T00:00:00Z")), fired)
        assertEquals(t("2026-10-31T16:30:00Z"), ReminderPlanner.nextTrigger(s, rt, fired.plusSeconds(60), la))
        // Device asleep for a while: next nag is the next 30-minute step after now.
        assertEquals(t("2026-10-31T18:00:00Z"), ReminderPlanner.nextTrigger(s, rt, t("2026-10-31T17:45:00Z"), la))
        val (done, _) = ReminderPlanner.onComplete(s, rt, t("2026-10-31T16:10:00Z"), la)
        assertEquals(t("2026-11-01T17:00:00Z"), ReminderPlanner.nextTrigger(s, done, t("2026-10-31T16:10:00Z"), la))
        val once = spec("2026-10-31", "09:00", nag = true, nagMinutes = 60)
        val onceRt = ReminderPlanner.onFired(ReminderRuntime(armedAt = t("2026-10-30T00:00:00Z")), fired)
        assertEquals(t("2026-10-31T17:00:00Z"), ReminderPlanner.nextTrigger(once, onceRt, fired.plusSeconds(5), la))
    }

    @Test
    fun rebootReconstructionIsIdempotentAndCatchesUpMissedReminders() {
        val s = spec("2026-10-31", "09:00")
        val armed = ReminderRuntime(armedAt = t("2026-10-30T12:00:00Z"))
        val before = ReminderPlanner.nextTrigger(s, armed, t("2026-10-31T10:00:00Z"), la)
        val rebuilt = ReminderPlanner.nextTrigger(s, armed, t("2026-10-31T10:00:00Z"), la)
        assertEquals(before, rebuilt)
        // Phone was off at 09:00; after boot at 11:00 the missed one-time reminder shows once, now.
        val boot = t("2026-10-31T18:00:00Z")
        assertEquals(boot, ReminderPlanner.nextTrigger(s, armed, boot, la))
        val afterShown = ReminderPlanner.onFired(armed, boot)
        assertNull("no duplicate after it was shown", ReminderPlanner.nextTrigger(s, afterShown, boot.plusSeconds(1), la))
    }

    @Test
    fun missedRecurringOccurrenceWithinWindowShowsNowOtherwiseSkips() {
        val s = spec("2026-10-01", "09:00", RepeatType.DAILY)
        val rt = ReminderRuntime(armedAt = t("2026-10-01T00:00:00Z"), lastFiredAt = t("2026-10-30T16:00:00Z"))
        val boot = t("2026-10-31T20:00:00Z") // 13:00 PDT, today's 09:00 was missed
        assertEquals(boot, ReminderPlanner.nextTrigger(s, rt, boot, la))
        val old = rt.copy(lastFiredAt = t("2026-10-20T16:00:00Z"))
        val farBoot = t("2026-11-02T17:30:00Z") // 09:30 PST Nov 2: Nov 2 09:00 missed by 30 min -> catch up
        assertEquals(farBoot, ReminderPlanner.nextTrigger(s, old, farBoot, la))
        // Weekly Monday reminder missed while the phone was off; booting on Wednesday skips it.
        val weekly = spec("2026-10-05", "09:00", RepeatType.WEEKLY)
        val wed = t("2026-11-04T18:00:00Z")
        assertEquals(t("2026-11-09T17:00:00Z"), ReminderPlanner.nextTrigger(weekly, old, wed, la))
    }

    @Test
    fun creatingARecurringReminderAfterTodaysTimeDoesNotFireImmediately() {
        val now = t("2026-10-31T18:00:00Z") // 11:00 PDT
        val s = spec("2026-10-31", "09:00", RepeatType.DAILY)
        assertEquals(t("2026-11-01T17:00:00Z"), ReminderPlanner.nextTrigger(s, ReminderRuntime(armedAt = now), now, la))
    }

    @Test
    fun quietHoursDelayReminders() {
        val q = QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0))
        val s = spec("2026-10-01", "23:00", RepeatType.DAILY)
        val now = t("2026-10-31T17:00:00Z")
        // 23:00 PDT falls in quiet hours -> 07:00 next morning, which is PST after the DST change (15:00Z).
        assertEquals(t("2026-11-01T15:00:00Z"), ReminderPlanner.nextTrigger(s, ReminderRuntime(armedAt = now), now, la, q))
    }

    @Test
    fun weekdayMaskRoundTrip() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY)
        assertEquals(days, RecurrenceRule.maskToWeekdays(RecurrenceRule.weekdaysToMask(days)))
        assertTrue(Recurrence.describe(spec("2026-10-05", "09:00", RepeatType.WEEKLY)).startsWith("Every Mon"))
    }
}
