import SwiftUI
import SwiftData
import Network

@main
struct BridgeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var session = SessionStore()
    @State private var router = AppRouter()
    @State private var network = NetworkStatus()
    @State private var toaster = Toaster()
    @State private var goals = GoalStore()
    @State private var coach = CoachModel()
    @State private var social = SocialStore()
    @State private var dms = DmStore()
    @State private var billing = BillingStore()
    @AppStorage("themeOverride") private var themeOverride: String = "system"
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .bridgeTypeScale()
                .environment(session)
                .environment(router)
                .environment(network)
                .environment(toaster)
                .environment(goals)
                .environment(coach)
                .environment(social)
                .environment(dms)
                .environment(billing)
                .task { billing.configure(); Analytics.setup() }
                .onAppear { Appearance.apply(themeOverride) }
                .onChange(of: themeOverride) { _, mode in Appearance.apply(mode) }
                .tint(Theme.acc)
                .onOpenURL { router.handle(url: $0) }
        }
        .modelContainer(Persistence.container)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                RhythmStore.recordOpen(wifi: network.wifi)          // only with "Let Arc learn my routine"
                Task { await SyncEngine.shared.drain(reason: .foreground); await NotificationScheduler.shared.reschedule() }
            }
        }
        .backgroundTask(.appRefresh("app.thebridge.sync")) {
            await SyncEngine.shared.drain(reason: .background)
        }
    }
}

@MainActor @Observable
final class SessionStore {
    /// `.onboarding`: signed in, first goal not set up yet (persisted, so a restart resumes it).
    enum Phase: Equatable { case launching, signedOut, onboarding, ready, expired }
    var phase: Phase = .launching
    var user: UserDTO?

    private static let userKey = "sessionUser"
    private static let onboardingKey = "sessionOnboarding"

    /// Reads local state only; never blocks on the network. Expired refresh → `.expired` (local data kept).
    func bootstrap() async {
        guard phase == .launching else { return }
        await SyncEngine.shared.configure(container: Persistence.container)
        user = UserDefaults.standard.data(forKey: Self.userKey).flatMap { try? JSONDecoder().decode(UserDTO.self, from: $0) }
        phase = user == nil ? .signedOut : TokenStore.read() == nil ? .expired : UserDefaults.standard.bool(forKey: Self.onboardingKey) ? .onboarding : .ready
        if phase == .ready || phase == .onboarding { await SyncEngine.shared.drain(reason: .foreground) }
    }

    func signedIn(_ r: AuthResponse) {
        // Unsynced work belongs to whoever made it: only a different account starts from an empty database.
        if user?.id != r.user.id { try? Persistence.wipe() }
        TokenStore.write(Tokens(access: r.accessToken, refresh: r.refreshToken))
        UserDefaults.standard.set(try? JSONEncoder().encode(r.user), forKey: Self.userKey)
        user = r.user
        if r.isNew { UserDefaults.standard.set(true, forKey: Self.onboardingKey) }
        phase = UserDefaults.standard.bool(forKey: Self.onboardingKey) ? .onboarding : .ready
        Task { await SyncEngine.shared.drain(reason: .foreground) }
    }

    func expired() { if phase == .ready || phase == .onboarding { phase = .expired } }

    /// First goal created: show the tabs from now on.
    func onboardingDone() {
        UserDefaults.standard.removeObject(forKey: Self.onboardingKey)
        if phase == .onboarding { phase = .ready }
    }

    /// Re-reads the profile (photo link, name) after it changes on the server.
    func refreshUser() async {
        guard let fresh = try? await APIClient.shared.me() else { return }
        user = fresh
        UserDefaults.standard.set(try? JSONEncoder().encode(fresh), forKey: Self.userKey)
    }

    func logOut() async {
        try? await APIClient.shared.logOut()        // best effort; the refresh token is dropped locally either way
        clearLocal()
    }

    /// Throws when offline or the server refuses, so the UI can keep the account and say why.
    func deleteAccount() async throws {
        try await APIClient.shared.deleteAccount()
        clearLocal()
    }

    private func clearLocal() {
        TokenStore.write(nil)
        try? Persistence.wipe()
        UserDefaults.standard.removeObject(forKey: Self.userKey)
        UserDefaults.standard.removeObject(forKey: Self.onboardingKey)
        UserDefaults.standard.removeObject(forKey: "onboardingDraft")
        Task { await NotificationScheduler.shared.reschedule() }
        user = nil
        phase = .signedOut
    }
}

/// Whether the device has a connection. Drives the offline banner.
@MainActor @Observable
final class NetworkStatus {
    var online = true
    /// On Wi-Fi (or a cable), on mobile data, or unknown: a rough "at home or out" signal for the routine, never a location.
    var wifi: Bool?
    @ObservationIgnored private let monitor = NWPathMonitor()
    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let ok = path.status == .satisfied
            let wifi: Bool? = !ok ? nil : path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet) ? true : path.usesInterfaceType(.cellular) ? false : nil
            Task { @MainActor in self?.online = ok; self?.wifi = wifi }
        }
        monitor.start(queue: .global(qos: .utility))
    }
}

/// App-wide confirmations: one at a time, bottom-centre above the tab bar, 2.6s hold.
@MainActor @Observable
final class Toaster {
    enum Kind { case ok, warn, info }
    struct Toast: Equatable { let text: String; let kind: Kind; let id = UUID() }
    var current: Toast?
    func show(_ text: String, _ kind: Kind = .info) {
        let toast = Toast(text: text, kind: kind)
        current = toast
        UIAccessibility.post(notification: .announcement, argument: text)
        Task { try? await Task.sleep(for: .seconds(2.6)); if current == toast { current = nil } }
    }
}

struct RootView: View {
    @Environment(SessionStore.self) private var session
    @Environment(NetworkStatus.self) private var network
    @Environment(Toaster.self) private var toaster
    @Environment(SocialStore.self) private var social
    @Environment(BillingStore.self) private var billing
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var auth = AuthModel()
    @State private var mustUpgrade = false
    @State private var queued = 0
    @State private var splashHeld = false

    var body: some View {
        Group {
            if mustUpgrade { UpgradeView() } else {
                VStack(spacing: 0) {
                    if !network.online && session.phase != .launching { OfflineBanner(queued: queued) }
                    switch session.phase {
                    case .launching: SplashView()
                    case .signedOut: AuthFlow()
                    case .onboarding: OnboardingFlow(adding: false, onExit: nil, onFinished: {})
                    case .ready, .expired: MainTabs()
                    }
                }
            }
        }
        .overlay {
            if !splashHeld || session.phase == .launching {
                SplashView().transition(reduceMotion ? .opacity : .move(edge: .top)).zIndex(1)
            }
        }
        .environment(auth)
        .background(Theme.bg)
        .animation(.easeOut(duration: 0.2), value: session.phase)
        .animation(reduceMotion ? .easeOut(duration: 0.2) : .timingCurve(0.2, 0.8, 0.2, 1, duration: 0.56), value: splashHeld)
        .animation(.easeOut(duration: 0.2), value: network.online)
        .overlay(alignment: .bottom) {
            if let toast = toaster.current {
                ToastView(toast: toast).padding(.horizontal, 20).padding(.bottom, session.phase == .signedOut ? 20 : 70)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .offset(y: 12)))
            }
        }
        .animation(.easeOut(duration: 0.22), value: toaster.current)
        .task { auth.attach(session: session, toaster: toaster); await session.bootstrap() }
        .task { try? await Task.sleep(for: .milliseconds(1300)); splashHeld = true }
        .onReceive(NotificationCenter.default.publisher(for: .sessionExpired)) { _ in session.expired() }
        .onReceive(NotificationCenter.default.publisher(for: .upgradeRequired)) { _ in mustUpgrade = true }
        .onChange(of: session.phase) { _, phase in
            if phase == .ready { Task { await social.registerDevice(); if let id = session.user?.id { await billing.identify(id.uuidString.lowercased()); Analytics.identify(id.uuidString.lowercased()) } } }
            if phase == .signedOut { Task { await billing.signedOut() }; Analytics.reset() }
        }
        .onChange(of: network.online) { was, now in
            if now && !was {
                Task { await social.flushQueued() }                // alliance chat written offline
                let n = queued
                toaster.show(n > 0 ? String(localized: "Back online. Synced \(n) changes.") : String(localized: "Back online."), .ok)
            } else { queued = Persistence.outboxCount() }
        }
        .sheet(isPresented: .constant(session.phase == .expired)) { ReauthSheet().environment(auth).interactiveDismissDisabled() }
    }
}
