package app.thebridge

import app.thebridge.domain.AppOpen
import app.thebridge.domain.RhythmCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Same cases as the iOS RhythmTests, so both apps learn the same routine from the same week. */
class RhythmTest {
    private val today = LocalDate.of(2026, 10, 23)
    private fun at(daysAgo: Long, h: Int, m: Int = 0) = LocalDateTime.of(today.minusDays(daysAgo), LocalTime.of(h, m))

    @Test fun `too few days says nothing`() {
        val opens = (0L..3L).map { AppOpen(at(it, 21), true) }
        assertNull(RhythmCalc.of(opens, emptyList(), today))
    }

    @Test fun `an evening person gets an evening plan`() {
        // Opens the app around 9 PM (one stray morning), ticks tasks between 8 and 10 PM, once past midnight.
        val opens = (0L..9L).map { AppOpen(at(it, 21, 5), it % 3 != 0L) } + AppOpen(at(2, 8), false)
        val done = (1L..9L).flatMap { listOf(today.minusDays(it) to at(it, 20, 10), today.minusDays(it) to at(it, 21, 40)) } +
            listOf(today.minusDays(4) to LocalDateTime.of(today.minusDays(3), LocalTime.of(0, 30)))   // counted for day 4, hour 24
        val r = RhythmCalc.of(opens, done, today)!!
        assertEquals(10, r.days)
        assertEquals(21, r.openHour)
        assertEquals(20, r.doneHour)            // 20 and 21 tie on raw counts; smoothing and the earlier hour win
        assertEquals(22, r.finishedBy)          // most days done by the end of the 21:00 hour
        assertEquals(6f / 11, r.wifiShare!!, 0.001f)  // days 1, 2, 4, 5, 7, 8 on Wi-Fi
        assertEquals(LocalTime.of(19, 0), r.dailyReminder)
        assertEquals(LocalTime.of(22, 0), r.streakRisk)
    }

    @Test fun `a morning runner is nudged early, and late nights stay within bounds`() {
        val opens = (0L..6L).map { AppOpen(at(it, 6, 20), null) }
        val done = (1L..6L).map { today.minusDays(it) to at(it, 6, 45) }
        val r = RhythmCalc.of(opens, done, today)!!
        assertEquals(LocalTime.of(6, 0), r.dailyReminder)     // 05:00 would be too early: kept at 06:00
        assertEquals(LocalTime.of(17, 0), r.streakRisk)       // done by 07:00 most days: the nudge waits until 17:00
        assertNull(r.wifiShare)
    }

    @Test fun `opens and ticks older than four weeks are forgotten`() {
        val old = (30L..40L).map { AppOpen(at(it, 9), true) }
        assertNull(RhythmCalc.of(old, emptyList(), today))
    }

    @Test fun `the busiest hour wraps around midnight`() {
        assertEquals(23, RhythmCalc.peak(listOf(23, 23, 0, 0, 22)))
    }
}
