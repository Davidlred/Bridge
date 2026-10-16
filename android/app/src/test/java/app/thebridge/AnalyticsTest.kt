package app.thebridge

import app.thebridge.data.cleanProps
import org.junit.Assert.assertEquals
import org.junit.Test

/** Analytics may carry ids, counts and codes, never what people write (CLAUDE.md). */
class AnalyticsTest {
    @Test fun keepsCountsFlagsAndCodes() {
        assertEquals(mapOf("tasks" to 4, "study" to true, "mode" to "shared", "latency_ms" to 1200L),
            cleanProps("tasks" to 4, "study" to true, "mode" to "shared", "latency_ms" to 1200L))
    }

    @Test fun dropsAnythingThatLooksLikeText() {
        assertEquals(emptyMap<String, Any>(), cleanProps(
            "title" to "Run a half marathon",           // spaces: free text
            "goal" to "Pass the JLPT N3",                 // capitals
            "body" to "x".repeat(25),                     // too long for a code
            "list" to listOf("a"),                        // not a scalar
            "Bad Key" to 1,                               // keys are codes too
        ))
    }
}
