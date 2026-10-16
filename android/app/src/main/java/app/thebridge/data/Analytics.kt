package app.thebridge.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.thebridge.BuildConfig
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The product events we count (docs/ARCHITECTURE.md → Analytics). Nothing else can be sent. */
enum class Event(val wire: String) {
    OnboardingStep("onboarding_step"), PlanGenerated("plan_generated"), PlanEdited("plan_edited"), TaskCompleted("task_completed"),
    DayCompleted("day_completed"), JournalSaved("journal_saved"), CoachMessageSent("coach_message_sent"), CommunityUnlocked("community_unlocked"),
    AllianceCreated("alliance_created"), AllianceJoined("alliance_joined"), GamePlayed("game_played"), PaywallViewed("paywall_viewed"),
    PurchaseStarted("purchase_started"), PurchaseCompleted("purchase_completed"),
}

/**
 * Product analytics (PostHog). Events carry ids, counts and short codes only: never goal text, task titles,
 * journal, chat or DM content. [track] enforces that by accepting only numbers, booleans and lowercase codes.
 * EU/UK users opt in; elsewhere it's on until they switch it off in Profile → Privacy. Without a key nothing is sent.
 */
@Singleton
class Analytics @Inject constructor(private val store: DataStore<Preferences>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ready = false
    private var on = false

    /** The user's choice, or the regional default before they made one. */
    val enabled: Flow<Boolean> = store.data.map { it[CONSENT] ?: defaultOn() }

    fun setup(app: Application) {
        if (BuildConfig.POSTHOG_KEY.isBlank() || ready) return
        PostHogAndroid.setup(app, PostHogAndroidConfig(BuildConfig.POSTHOG_KEY, if (inEurope()) EU_HOST else US_HOST).apply {
            captureScreenViews = false                 // screen names alone could reveal little, but we only send the events above
            captureApplicationLifecycleEvents = false
            captureDeepLinks = false
            sessionReplay = false
        })
        ready = true
        scope.launch { enabled.collect { apply(it) } }
    }

    private fun apply(enabled: Boolean) {
        on = enabled
        if (!ready) return
        if (enabled) PostHog.optIn() else PostHog.optOut()
    }

    suspend fun setEnabled(value: Boolean) { store.edit { it[CONSENT] = value } }

    /** Our user id (a random UUID, not personal data) so events from one person's devices count once. */
    fun identify(userId: String) { if (ready && on) PostHog.identify(userId) }
    fun reset() { if (ready) PostHog.reset() }

    fun track(event: Event, vararg props: Pair<String, Any>) {
        if (!ready || !on) return
        PostHog.capture(event.wire, properties = cleanProps(*props))
    }

    private fun inEurope() = Locale.getDefault().country.uppercase() in EUROPE

    private fun defaultOn() = !inEurope()

    private companion object {
        val CONSENT = booleanPreferencesKey("analytics_consent")
        const val EU_HOST = "https://eu.i.posthog.com"
        const val US_HOST = "https://us.i.posthog.com"
        val EUROPE = setOf("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL",
            "PT", "RO", "SK", "SI", "ES", "SE", "GB", "IS", "LI", "NO", "CH")
    }
}

/** Keeps numbers, booleans and short lowercase codes; drops anything else (free text can never reach analytics). */
internal fun cleanProps(vararg props: Pair<String, Any>): Map<String, Any> {
    val key = Regex("^[a-z_]{1,32}$")
    val code = Regex("^[a-z0-9_]{1,24}$")
    return props.filter { (k, v) -> key.matches(k) && when (v) { is Number, is Boolean -> true; is String -> code.matches(v); else -> false } }.toMap()
}
