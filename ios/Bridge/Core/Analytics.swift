import Foundation
import PostHog

/// The product events we count (docs/ARCHITECTURE.md → Analytics). Nothing else can be sent.
enum AnalyticsEvent: String {
    case onboardingStep = "onboarding_step", planGenerated = "plan_generated", planEdited = "plan_edited", taskCompleted = "task_completed"
    case dayCompleted = "day_completed", journalSaved = "journal_saved", coachMessageSent = "coach_message_sent", communityUnlocked = "community_unlocked"
    case allianceCreated = "alliance_created", allianceJoined = "alliance_joined", gamePlayed = "game_played", paywallViewed = "paywall_viewed"
    case purchaseStarted = "purchase_started", purchaseCompleted = "purchase_completed"
}

/**
 Product analytics (PostHog). Events carry ids, counts and short codes only: never goal text, task titles, journal, chat
 or DM content. `track` enforces that by accepting only numbers, booleans and lowercase codes. EU/UK users opt in;
 elsewhere it's on until they switch it off in Profile → Privacy. Without a key (POSTHOG_KEY) nothing is sent.
 */
@MainActor
enum Analytics {
    private static var ready = false
    private static let consentKey = "analyticsConsent"
    private static let europe: Set<String> = ["AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL",
                                              "PT", "RO", "SK", "SI", "ES", "SE", "GB", "IS", "LI", "NO", "CH"]
    private static var inEurope: Bool { europe.contains(Locale.current.region?.identifier.uppercased() ?? "") }

    /// The user's choice, or the regional default before they made one.
    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: consentKey) as? Bool ?? !inEurope }
        set {
            UserDefaults.standard.set(newValue, forKey: consentKey)
            if ready { newValue ? PostHogSDK.shared.optIn() : PostHogSDK.shared.optOut() }
        }
    }

    static func setup() {
        let key = Bundle.main.object(forInfoDictionaryKey: "PostHogKey") as? String ?? ""
        guard !key.isEmpty, !ready else { return }
        let config = PostHogConfig(apiKey: key, host: inEurope ? "https://eu.i.posthog.com" : "https://us.i.posthog.com")
        config.captureApplicationLifecycleEvents = false
        config.captureScreenViews = false
        config.sessionReplay = false
        PostHogSDK.shared.setup(config)
        ready = true
        enabled ? PostHogSDK.shared.optIn() : PostHogSDK.shared.optOut()
    }

    /// Our user id (a random UUID, not personal data) so events from one person's devices count once.
    static func identify(_ userId: String) { if ready && enabled { PostHogSDK.shared.identify(userId) } }
    static func reset() { if ready { PostHogSDK.shared.reset() } }

    static func track(_ event: AnalyticsEvent, _ props: [String: Any] = [:]) {
        guard ready, enabled else { return }
        PostHogSDK.shared.capture(event.rawValue, properties: cleanProps(props))
    }

    private static var journaled: Set<String> = []
    /// Journal autosaves on every pause in typing; count each entry once.
    static func journalSaved(_ key: String, mood: Int?) {
        if journaled.insert(key).inserted { track(.journalSaved, ["mood": mood ?? 0]) }
    }
}

/// Keeps numbers, booleans and short lowercase codes; drops anything else (free text can never reach analytics).
func cleanProps(_ props: [String: Any]) -> [String: Any] {
    props.filter { k, v in
        guard k.wholeMatch(of: #/[a-z_]{1,32}/#) != nil else { return false }
        switch v {
        case is Bool, is Int, is Double: return true
        case let s as String: return s.wholeMatch(of: #/[a-z0-9_]{1,24}/#) != nil
        default: return false
        }
    }
}
