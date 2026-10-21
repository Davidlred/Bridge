package app.thebridge

import app.thebridge.data.CoachRepository
import app.thebridge.data.local.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each goal has its own coach conversation (the user's report: a new goal continued another goal's chat). */
class CoachThreadsTest {
    // UUIDv7 ids sort by creation time.
    private val older = "0190a000-0000-7000-8000-000000000001"
    private val newer = "0190b000-0000-7000-8000-000000000002"
    private val newest = "0190c000-0000-7000-8000-000000000003"

    @Test fun aNewGoalTakesTheCoachEvenIfAnotherWasPicked() {
        // Picked "older" while "newer" was the newest goal; then "newest" was created.
        assertEquals(newest, CoachRepository.focusOf(listOf(older, newer, newest), pick = older, newestAtPick = newer))
    }

    @Test fun aPickStandsUntilAnotherGoalIsCreated() {
        assertEquals(older, CoachRepository.focusOf(listOf(older, newer), pick = older, newestAtPick = newer))
    }

    @Test fun withoutAPickTheNewestGoalIsInFocus() {
        assertEquals(newer, CoachRepository.focusOf(listOf(older, newer), pick = null, newestAtPick = null))
    }

    @Test fun aPickedGoalThatIsNoLongerActiveFallsBackToTheNewest() {
        assertEquals(newer, CoachRepository.focusOf(listOf(older, newer), pick = "gone", newestAtPick = newer))
    }

    @Test fun noGoalsMeansNoFocus() {
        assertEquals(null, CoachRepository.focusOf(emptyList(), pick = older, newestAtPick = older))
    }

    @Test fun messagesFromBeforeThreadsBelongToTheOldestGoal() {
        val legacy = ChatMessageEntity("m1", "c1", "user", "hi")
        val tagged = ChatMessageEntity("m2", "c2", "user", "hi", goalId = newer)
        assertEquals(older, CoachRepository.threadOf(legacy, older))
        assertEquals(newer, CoachRepository.threadOf(tagged, older))
    }
}
