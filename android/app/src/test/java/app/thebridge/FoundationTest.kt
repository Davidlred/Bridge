package app.thebridge

import app.thebridge.data.local.LocalDay
import app.thebridge.feature.auth.AuthValidation
import app.thebridge.util.UuidV7
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.UUID

class FoundationTest {
    @Test fun `day closes at 3am local`() {
        assertEquals("2026-10-13", LocalDay.today(LocalDateTime.of(2026, 10, 13, 23, 59)))
        assertEquals("2026-10-13", LocalDay.today(LocalDateTime.of(2026, 10, 14, 2, 59)))   // late night still counts for the 13th
        assertEquals("2026-10-14", LocalDay.today(LocalDateTime.of(2026, 10, 14, 3, 0)))
        assertEquals("2026-12-31", LocalDay.today(LocalDateTime.of(2027, 1, 1, 1, 30)))
    }

    @Test fun `uuid v7 carries the timestamp and sorts by time`() {
        val a = UuidV7.next(1_700_000_000_000); val b = UuidV7.next(1_700_000_000_001)
        val parsed = UUID.fromString(a)
        assertEquals(7, parsed.version()); assertEquals(2, parsed.variant())
        assertEquals(1_700_000_000_000, parsed.mostSignificantBits ushr 16)
        assertTrue(a < b)
        assertNotEquals(UuidV7.next(5), UuidV7.next(5))
    }

    @Test fun `auth validation matches the prototype rules`() {
        assertEquals(R.string.err_name, AuthValidation.name(" S "))
        assertNull(AuthValidation.name("Sharon"))
        assertEquals(R.string.err_email, AuthValidation.email("sharon@example"))
        assertEquals(R.string.err_email, AuthValidation.email("sha ron@example.com"))
        assertNull(AuthValidation.email(" sharon@example.com "))
        assertEquals(R.string.err_password_short, AuthValidation.newPassword("1234567"))
        assertNull(AuthValidation.newPassword("12345678"))
        assertEquals(R.string.err_credentials, AuthValidation.existingPassword("short"))
    }
}
