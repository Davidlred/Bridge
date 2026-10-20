package app.thebridge

import app.thebridge.data.NetworkMonitor.Companion.isOnline
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMonitorTest {
    @Test fun `no network is offline whatever the server last said`() {
        assertFalse(isOnline(internet = false, validated = false, server = true))
    }

    @Test fun `before any request, Android's own check decides`() {
        assertTrue(isOnline(internet = true, validated = true, server = null))
        assertFalse(isOnline(internet = true, validated = false, server = null))
    }

    @Test fun `our server's answer wins over Android's check`() {
        // Captive portal after sign-in, a network that blocks Google, a device whose clock jumped: not validated, but reachable.
        assertTrue(isOnline(internet = true, validated = false, server = true))
        // Validated, but our server can't be reached (it's down, or this network blocks it).
        assertFalse(isOnline(internet = true, validated = true, server = false))
    }
}
