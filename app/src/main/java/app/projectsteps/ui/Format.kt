package app.projectsteps.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

object Format {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
    private val dayYearFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun time(t: LocalTime): String = t.format(timeFmt)

    fun time(i: Instant, zone: ZoneId): String = i.atZone(zone).toLocalTime().format(timeFmt)

    fun date(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> if (d.year == today.year) d.format(dayFmt) else d.format(dayYearFmt)
    }

    fun dateTime(i: Instant, zone: ZoneId): String {
        val z = i.atZone(zone)
        return date(z.toLocalDate(), LocalDate.now(zone)) + " " + z.toLocalTime().format(timeFmt)
    }

    /** "in 25 min", "2 h ago", "in 3 days". */
    fun relative(i: Instant, now: Instant = Instant.now()): String {
        val minutes = ChronoUnit.MINUTES.between(now, i)
        val abs = kotlin.math.abs(minutes)
        val text = when {
            abs < 1 -> return "now"
            abs < 60 -> "$abs min"
            abs < 48 * 60 -> "${abs / 60} h"
            else -> "${abs / (24 * 60)} days"
        }
        return if (minutes > 0) "in $text" else "$text ago"
    }

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> String.format("%.1f MB", n / (1024.0 * 1024.0))
    }
}
