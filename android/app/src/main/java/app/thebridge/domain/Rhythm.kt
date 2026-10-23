package app.thebridge.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.ceil

/** One time the app came to the front, and whether the phone was on Wi-Fi (null: offline or unknown). */
data class AppOpen(val at: LocalDateTime, val wifi: Boolean?)

/**
 * When someone actually uses the app, learned from the last [RhythmCalc.WINDOW_DAYS] days on the device (with their
 * permission). Hours are 0..23, except [finishedBy], which can run past midnight (24, 25, 26) because the day closes at 03:00.
 */
data class Rhythm(
    /** Days with an open or a completed task in the window. */
    val days: Int,
    /** The hour the app is most often opened. */
    val openHour: Int,
    /** The hour tasks most often get done; null before any are. */
    val doneHour: Int?,
    /** By this hour most days' last task is done (80% of days); null before any are. */
    val finishedBy: Int?,
    /** Share of opens on Wi-Fi (a rough "at home or work" signal; never the location). */
    val wifiShare: Float?,
) {
    /** Daily reminder: an hour before tasks usually get done (or before the usual open), kept between 06:00 and 21:00. */
    val dailyReminder: LocalTime get() = LocalTime.of(((doneHour ?: openHour) - 1).coerceIn(6, 21), 0)
    /** Streak at risk: by now most days are finished, so nothing done yet deserves a nudge. Kept between 17:00 and 22:00. */
    val streakRisk: LocalTime get() = LocalTime.of((finishedBy ?: ((doneHour ?: openHour) + 2)).coerceIn(17, 22), 0)
}

object RhythmCalc {
    const val WINDOW_DAYS = 28L
    /** Fewer active days than this says too little about a routine. */
    const val MIN_DAYS = 5

    /**
     * @param completions when tasks were ticked, with the local day each counted for (the day closes at 03:00, so a tick
     *   at 01:30 belongs to the day before and counts as hour 25 for [Rhythm.finishedBy]).
     */
    fun of(opens: List<AppOpen>, completions: List<Pair<LocalDate, LocalDateTime>>, today: LocalDate): Rhythm? {
        val from = today.minusDays(WINDOW_DAYS - 1)
        val o = opens.filter { !it.at.toLocalDate().isBefore(from) }
        val c = completions.filter { !it.first.isBefore(from) && !it.first.isAfter(today) }
        val days = (o.map { dayOf(it.at) } + c.map { it.first }).toSet().size
        if (days < MIN_DAYS || o.isEmpty()) return null
        val lastPerDay = c.groupBy { it.first }.values.map { day -> day.maxOf { hourInDay(it.first, it.second) } }.sorted()
        val finishedBy = if (lastPerDay.isEmpty()) null else lastPerDay[(ceil(lastPerDay.size * 0.8).toInt() - 1).coerceIn(0, lastPerDay.lastIndex)] + 1
        val known = o.mapNotNull { it.wifi }
        return Rhythm(
            days = days,
            openHour = peak(o.map { it.at.hour }),
            doneHour = if (c.isEmpty()) null else peak(c.map { it.second.hour }),
            finishedBy = finishedBy,
            wifiShare = if (known.isEmpty()) null else known.count { it }.toFloat() / known.size,
        )
    }

    /** The busiest hour, smoothed with its neighbours (18:58 and 19:02 are the same habit); ties go to the earlier hour. */
    fun peak(hours: List<Int>): Int {
        val n = IntArray(24).also { a -> hours.forEach { a[it.mod(24)]++ } }
        return (0 until 24).maxWith(compareBy<Int> { n[(it + 23) % 24] + 2 * n[it] + n[(it + 1) % 24] }.thenByDescending { it })
    }

    /** The local day an open belongs to: before 03:00 it is still the previous day. */
    private fun dayOf(t: LocalDateTime): LocalDate = if (t.hour < 3) t.toLocalDate().minusDays(1) else t.toLocalDate()

    /** Hours since the start of [day]: a tick at 01:30 on the next calendar date is hour 25. */
    private fun hourInDay(day: LocalDate, t: LocalDateTime): Int = t.hour + 24 * (t.toLocalDate().toEpochDay() - day.toEpochDay()).toInt()
}
