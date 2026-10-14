package app.thebridge

import app.thebridge.data.reminders.TaskTime
import app.thebridge.domain.Deadlines
import app.thebridge.domain.GoalText
import app.thebridge.domain.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class GoalsCoreTest {
    private val start = LocalDate.of(2026, 10, 1)
    private fun d(day: Int) = start.plusDays(day.toLong() - 1)

    @Test fun `progress counts finished days plus today's share`() {
        val tasks = mapOf("a" to start, "b" to start)
        val p = Progress.of(start, d(10), d(4), tasks, mapOf("a" to setOf(d(4))))
        assertEquals(4, p.day); assertEquals(10, p.total)
        assertEquals(1, p.doneToday); assertEquals(2, p.tasksToday)
        assertEquals((3 + 0.5f) / 10, p.fraction, 0.0001f)
        assertEquals(7, p.daysLeft)
        assertFalse(p.allDone)
    }

    @Test fun `total never drops below the day index once the deadline has passed`() {
        val p = Progress.of(start, d(5), d(8), mapOf("a" to start), emptyMap())
        assertEquals(8, p.total); assertEquals(7f / 8, p.fraction, 0.0001f)
    }

    @Test fun `streak runs back from today, or from yesterday while today is still open`() {
        val days = setOf(d(1), d(2), d(3), d(5), d(6))
        assertEquals(2, Progress.streak(days, d(6)))
        assertEquals(2, Progress.streak(days, d(7)))       // nothing yet today: yesterday's run still counts
        assertEquals(0, Progress.streak(days, d(8)))
        assertEquals(3, Progress.streak(days, d(4)))
    }

    @Test fun `drift compares the last 7 finished days with the pace target`() {
        val tasks = mapOf("a" to start, "b" to start)
        assertNull(Progress.drift(start, start, tasks, emptyMap()))                 // day 1: no history
        val all = (1..7).map(::d).toSet()
        assertEquals(20, Progress.drift(start, d(8), tasks, mapOf("a" to all, "b" to all)))   // 100% → +20
        assertEquals(-30, Progress.drift(start, d(8), tasks, mapOf("a" to all)))            // 50% → −30
        // A task added on day 5 doesn't count as missed on days 1–4: 7 + 3 planned, 7 + 3 done
        assertEquals(20, Progress.drift(start, d(8), mapOf("a" to start, "b" to d(5)), mapOf("a" to all, "b" to (5..7).map(::d).toSet())))
    }

    @Test fun `deadlines are the user's choice within sensible bounds`() {
        val today = LocalDate.of(2026, 10, 14)
        assertEquals(Deadlines.Problem.Missing, Deadlines.check(null, today, 7))
        assertEquals(Deadlines.Problem.TooSoonWeek, Deadlines.check(today.plusDays(6), today, 7))
        assertNull(Deadlines.check(today.plusDays(7), today, 7))
        assertEquals(Deadlines.Problem.TooSoonDay, Deadlines.check(today, today, 1))
        assertNull(Deadlines.check(today.plusDays(1), today, 1))
        assertEquals(Deadlines.Problem.TooFar, Deadlines.check(today.plusDays(1827), today, 1))
    }

    @Test fun `goal text hints match the prototype`() {
        assertTrue(GoalText.hasDate("Run a marathon by June"))
        assertTrue(GoalText.hasDate("Learn Spanish within 6 months"))
        assertFalse(GoalText.hasDate("Run a half marathon in under 2 hours"))
        assertTrue(GoalText.needsNumber("Get much fitter overall"))
        assertFalse(GoalText.needsNumber("Run 10 km without stopping"))
        assertFalse(GoalText.needsNumber("short"))
        assertEquals("Half marathon", GoalText.autoLabel("Run a half marathon in under 2 hours."))
        assertEquals("Learn conversational", GoalText.autoLabel("learn conversational Spanish, properly"))   // whole words, 24 chars max
        assertEquals("Read 12 books", GoalText.autoLabel("read 12 books this year"))
    }

    @Test fun `task times that can be reminded`() {
        assertEquals(LocalTime.of(7, 0), TaskTime.parse("7:00 AM"))
        assertEquals(LocalTime.of(19, 30), TaskTime.parse("7:30 pm"))
        assertEquals(LocalTime.of(0, 15), TaskTime.parse("12:15 AM"))
        assertEquals(LocalTime.of(12, 0), TaskTime.parse("12pm"))
        assertEquals(LocalTime.of(6, 45), TaskTime.parse("06:45"))
        assertNull(TaskTime.parse("After run"))
        assertNull(TaskTime.parse("25:00"))
        assertNull(TaskTime.parse(null))
    }
}
