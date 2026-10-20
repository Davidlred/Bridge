package app.thebridge

import app.thebridge.data.InviteReferrer.Companion.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteReferrerTest {
    @Test fun `reads the invite code the Play Store install carried`() {
        assertEquals("CALC-2026", parse("join=CALC-2026"))
        assertEquals("CALC-2026", parse("join%3Dcalc-2026"))                                     // encoded, lower case
        assertEquals("DAWN-4821", parse("utm_source=google-play&join=DAWN-4821&utm_medium=x"))   // among other parameters
    }

    @Test fun `ignores installs that didn't come from an invite`() {
        assertNull(parse(null))
        assertNull(parse("utm_source=google-play&utm_medium=organic"))
        assertNull(parse("join=<script>"))
    }
}
