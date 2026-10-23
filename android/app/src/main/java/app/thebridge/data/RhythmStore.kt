package app.thebridge.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.thebridge.data.local.BridgeDb
import app.thebridge.data.local.LocalDay
import app.thebridge.domain.AppOpen
import app.thebridge.domain.Rhythm
import app.thebridge.domain.RhythmCalc
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Let Arc learn my routine" (off until the user turns it on): when the app is opened and whether the phone is on Wi-Fi,
 * kept on this device for 28 days. With task completions (already local) it gives a [Rhythm] that times the reminders
 * and is summarised for the coach. Nothing here is sent anywhere raw, and turning it off deletes it.
 */
@Singleton
class RhythmStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: DataStore<Preferences>,
    private val db: BridgeDb,
) {
    val enabled: Flow<Boolean> = store.data.map { it[ON] == true }

    suspend fun setEnabled(on: Boolean) = store.edit { if (on) it[ON] = true else { it.remove(ON); it.remove(OPENS) } }

    /** The app came to the front. Opens within 10 minutes of the last one are the same visit. */
    suspend fun recordOpen(now: Long = System.currentTimeMillis()) {
        if (!enabled.first()) return
        store.edit { p ->
            val cutoff = now - (RhythmCalc.WINDOW_DAYS + 1) * 86_400_000L
            val kept = parse(p[OPENS]).filter { it.first >= cutoff }
            if (kept.lastOrNull()?.let { now - it.first < 10 * 60_000L } == true) return@edit
            p[OPENS] = (kept + (now to wifi())).takeLast(MAX).joinToString(",") { (t, w) -> "$t:" + when (w) { true -> "w"; false -> "c"; null -> "n" } }
        }
    }

    val rhythm: Flow<Rhythm?> = combine(store.data, db.goals().activeCompletions()) { p, completions ->
        if (p[ON] != true) return@combine null
        val zone = ZoneId.systemDefault()
        RhythmCalc.of(
            parse(p[OPENS]).map { (t, w) -> AppOpen(LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone), w) },
            completions.map { LocalDate.parse(it.localDate) to LocalDateTime.ofInstant(Instant.ofEpochMilli(it.completedAt), zone) },
            LocalDate.parse(LocalDay.today()),
        )
    }

    private fun wifi(): Boolean? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> false
            else -> null
        }
    }

    private fun parse(raw: String?): List<Pair<Long, Boolean?>> = raw.orEmpty().split(',').mapNotNull { e ->
        val (t, w) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        (t.toLongOrNull() ?: return@mapNotNull null) to when (w) { "w" -> true; "c" -> false; else -> null }
    }

    private companion object {
        val ON = booleanPreferencesKey("rhythm_on")
        val OPENS = stringPreferencesKey("rhythm_opens")
        const val MAX = 600
    }
}
