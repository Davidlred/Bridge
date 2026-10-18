package app.thebridge.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Where a goal stands today. Pure, so it is unit-tested and shared by every screen. */
data class GoalProgress(
    /** 1-based day index from the start date (late joiners count from their own start). */
    val day: Int,
    /** Days from start to deadline inclusive; never less than [day]. */
    val total: Int,
    val doneToday: Int,
    val tasksToday: Int,
    /** 0..1 across the bridge: finished days plus today's share. */
    val fraction: Float,
    val streak: Int,
    /** Percentage points against the pace target, or null before there is any history. */
    val drift: Int?,
    /** Today is after the deadline: the recap replaces the plan header. */
    val overdue: Boolean = false,
    /** Finished days in a row with nothing done, up to yesterday. 3+ shows the gentle re-entry. */
    val awayDays: Int = 0,
    /** Days with at least one completed task, since the start. */
    val activeDays: Int = 0,
) {
    val daysLeft: Int get() = if (overdue) 0 else (total - day + 1).coerceAtLeast(0)
    val away: Boolean get() = awayDays >= Progress.AWAY_DAYS && doneToday == 0
    val todayFraction: Float get() = if (tasksToday == 0) 0f else doneToday.toFloat() / tasksToday
    val allDone: Boolean get() = tasksToday > 0 && doneToday == tasksToday
}

object Progress {
    /** Missed this many days in a row: welcome back, no catching up. */
    const val AWAY_DAYS = 3
    /** The plan is "on pace" when about 4 in 5 planned tasks get done: consistency, not perfection. */
    const val PACE_TARGET = 0.8f

    /**
     * @param taskCreatedDays local date each live task first existed (from its UUIDv7 id), so a task added today
     *   doesn't count as missed on earlier days.
     * @param completions local dates with a completion, per task id.
     */
    fun of(start: LocalDate, deadline: LocalDate, today: LocalDate, taskCreatedDays: Map<String, LocalDate>, completions: Map<String, Set<LocalDate>>): GoalProgress {
        val day = (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceAtLeast(1)
        val total = (ChronoUnit.DAYS.between(start, deadline).toInt() + 1).coerceAtLeast(day)
        val tasks = taskCreatedDays.keys
        val doneToday = tasks.count { today in completions[it].orEmpty() }
        val todayShare = if (tasks.isEmpty()) 0f else doneToday.toFloat() / tasks.size
        val days = completions.values.flatten().toSet()
        val last = days.filter { it.isBefore(today) }.maxOrNull()
        return GoalProgress(
            day = day, total = total, doneToday = doneToday, tasksToday = tasks.size,
            fraction = ((day - 1 + todayShare) / total).coerceIn(0f, 1f),
            streak = streak(completions.values.flatten().toSet(), today),
            drift = drift(start, today, taskCreatedDays, completions),
            overdue = today.isAfter(deadline),
            awayDays = ChronoUnit.DAYS.between(last ?: start.minusDays(1), today).toInt().minus(1).coerceAtLeast(0),
            activeDays = days.count { !it.isAfter(today) },
        )
    }

    /** Consecutive days with at least one completed task, ending today (or yesterday while today is still open). */
    fun streak(days: Set<LocalDate>, today: LocalDate): Int {
        var d = if (today in days) today else today.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }

    /** Last 7 finished days: share of planned tasks done, against [PACE_TARGET]. Today is excluded so mornings don't read as "behind". */
    fun drift(start: LocalDate, today: LocalDate, taskCreatedDays: Map<String, LocalDate>, completions: Map<String, Set<LocalDate>>): Int? {
        var planned = 0; var done = 0
        for (i in 1..7) {
            val d = today.minusDays(i.toLong())
            if (d.isBefore(start)) break
            for ((task, created) in taskCreatedDays) {
                if (created.isAfter(d)) continue
                planned++
                if (d in completions[task].orEmpty()) done++
            }
        }
        if (planned == 0) return null
        return ((done.toFloat() / planned - PACE_TARGET) * 100).roundToInt()
    }
}

/** Target dates: the user picks them, the app only checks they are sensible. */
object Deadlines {
    const val MIN_DAYS_NEW = 7       // onboarding and review: at least a week out
    const val MIN_DAYS_EDIT = 1      // editing an existing goal: any day after today
    const val MAX_DAYS = 1826        // about 5 years

    enum class Problem { Missing, TooSoonWeek, TooSoonDay, TooFar }

    fun check(deadline: LocalDate?, today: LocalDate, minDays: Int): Problem? {
        if (deadline == null) return Problem.Missing
        val n = ChronoUnit.DAYS.between(today, deadline)
        return when {
            n < minDays -> if (minDays > 1) Problem.TooSoonWeek else Problem.TooSoonDay
            n > MAX_DAYS -> Problem.TooFar
            else -> null
        }
    }
}

/** Hints shown under the goal field in onboarding step 1 (same rules as the prototype). */
object GoalText {
    private val dateWords = Regex("""\b(by|before|until)\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|next|the end|end of|spring|summer|autumn|fall|winter|\d)|\b(in|within)\s+\d+\s*(day|week|month|year)s?""", RegexOption.IGNORE_CASE)
    private val timing = Regex("""\b(by|before|until|in)\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|next|the end|\d)""", RegexOption.IGNORE_CASE)
    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 200

    fun hasDate(text: String) = dateWords.containsMatchIn(text)
    /** A goal with no number can't be measured ("get fit"), so nudge for one. */
    fun needsNumber(text: String) = text.trim().length >= MIN_LENGTH && !text.any(Char::isDigit) && !timing.containsMatchIn(text)

    /** Short name for the goal card when the AI didn't provide one. */
    fun autoLabel(goal: String): String {
        if (Regex("half marathon", RegexOption.IGNORE_CASE).containsMatchIn(goal)) return "Half marathon"
        if (Regex("marathon", RegexOption.IGNORE_CASE).containsMatchIn(goal)) return "Marathon"
        var words = goal.replace(Regex("""[^\w\s'-]"""), "").split(Regex("""\s+""")).filter(String::isNotBlank).take(3)
        while (words.size > 1 && words.joinToString(" ").length > 24) words = words.dropLast(1)   // whole words only
        return words.joinToString(" ").take(24).replaceFirstChar(Char::uppercase)
    }
}
