package app.thebridge.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app can reach its server. Drives the offline banner and "network regained" sync.
 *
 * Android's "validated" flag comes from its own check against Google, which fails on some networks that reach our server
 * fine (captive portals after sign-in, networks that block Google, a device whose clock jumped). So once a request to our
 * server has succeeded or failed on the current network, that answer wins; the flag is only the first guess.
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val caps = MutableStateFlow(read(cm.getNetworkCapabilities(cm.activeNetwork)))
    /** Our server's answer since the network last changed: true reached, false unreachable, null not tried yet. */
    private val server = MutableStateFlow<Boolean?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val online: StateFlow<Boolean> = combine(caps, server) { c, s -> isOnline(c.first, c.second, s) }
        .stateIn(scope, SharingStarted.Eagerly, isOnline(caps.value.first, caps.value.second, null))

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { server.value = null }
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { caps.value = read(nc) }
            override fun onLost(network: Network) { caps.value = false to false; server.value = null }
        })
    }

    /** Called by the API client after every request: reached the server, or failed to connect. */
    fun reported(reached: Boolean) { server.value = reached }

    private fun read(nc: NetworkCapabilities?) =
        (nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) to (nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)

    companion object {
        /** Pure, so it is unit-tested: no network means offline; otherwise our server's last answer, else Android's check. */
        fun isOnline(internet: Boolean, validated: Boolean, server: Boolean?) = internet && (server ?: validated)
    }
}
