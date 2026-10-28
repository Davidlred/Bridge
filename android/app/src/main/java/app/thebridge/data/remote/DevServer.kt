package app.thebridge.data.remote

import android.content.Context
import android.net.ConnectivityManager
import app.thebridge.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.URI
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the API is. Release builds always use [BuildConfig.API_BASE]. Debug builds talk to a dev server on a PC on the same
 * Wi-Fi, and the router keeps giving that PC a new address: when the saved one stops answering, [rediscover] looks for a
 * Bridge server (its /healthz says "the-bridge") on the same port across the phone's own /24 and remembers it.
 */
object DevServer {
    @Volatile var base: String = BuildConfig.API_BASE
        private set
    private val scanning = AtomicBoolean(false)
    @Volatile private var lastScan = 0L

    fun init(ctx: Context) {
        if (!BuildConfig.DEBUG) return
        prefs(ctx).getString(KEY, null)?.let { base = it }
    }

    /** After a failed connection. True when a server answered at a different address (the caller retries once). */
    fun rediscover(ctx: Context): Boolean {
        if (!BuildConfig.DEBUG || System.currentTimeMillis() - lastScan < 30_000 || !scanning.compareAndSet(false, true)) return false
        lastScan = System.currentTimeMillis()
        try {
            val port = URI(base).port.takeIf { it > 0 } ?: 8080
            val prefix = phoneIp(ctx)?.substringBeforeLast('.') ?: return false
            val client = OkHttpClient.Builder().connectTimeout(500, TimeUnit.MILLISECONDS).readTimeout(800, TimeUnit.MILLISECONDS).build()
            val pool = Executors.newFixedThreadPool(48)
            try {
                val done = ExecutorCompletionService<String?>(pool)
                (1..254).forEach { n -> done.submit { "http://$prefix.$n:$port".takeIf { isBridge(client, it) } } }
                repeat(254) {
                    val found = done.poll(4, TimeUnit.SECONDS)?.get() ?: return@repeat
                    if (found == base) return false
                    base = found
                    prefs(ctx).edit().putString(KEY, found).apply()
                    return true
                }
                return false
            } finally { pool.shutdownNow() }
        } catch (e: Exception) {
            return false
        } finally { scanning.set(false) }
    }

    private fun isBridge(client: OkHttpClient, url: String): Boolean = runCatching {
        client.newCall(Request.Builder().url("$url/healthz").build()).execute().use { it.isSuccessful && it.body?.string().orEmpty().contains("\"the-bridge\"") }
    }.getOrDefault(false)

    private fun phoneIp(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dev_server", Context.MODE_PRIVATE)
    private const val KEY = "base"
}
