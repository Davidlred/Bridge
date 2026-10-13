import SwiftUI
import SwiftData
import Network
@preconcurrency import GoogleSignIn

@main
struct BridgeApp: App {
    @State private var session = SessionStore()
    @State private var router = AppRouter()
    @State private var network = NetworkStatus()
    @State private var toaster = Toaster()
    @AppStorage("themeOverride") private var themeOverride: String = "system"
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(session)
                .environment(router)
                .environment(network)
                .environment(toaster)
                .preferredColorScheme(themeOverride == "light" ? .light : themeOverride == "dark" ? .dark : nil)
                .tint(Theme.acc)
                .onOpenURL { url in
                    if GIDSignIn.sharedInstance.handle(url) { return }
                    router.handle(url: url)
                }
        }
        .modelContainer(Persistence.container)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await SyncEngine.shared.drain(reason: .foreground) } }
        }
        .backgroundTask(.appRefresh("app.thebridge.sync")) {
            await SyncEngine.shared.drain(reason: .background)
        }
    }
}

@MainActor @Observable
final class SessionStore {
    /// Goal onboarding steps arrive with milestone 2; until then a signed-in user goes straight to `.ready`.
    enum Phase: Equatable { case launching, signedOut, ready, expired }
    var phase: Phase = .launching
    var user: UserDTO?

    private static let userKey = "sessionUser"

    /// Reads local state only; never blocks on the network. Expired refresh → `.expired` (local data kept).
    func bootstrap() async {
        guard phase == .launching else { return }
        await SyncEngine.shared.configure(container: Persistence.container)
        user = UserDefaults.standard.data(forKey: Self.userKey).flatMap { try? JSONDecoder().decode(UserDTO.self, from: $0) }
        phase = user == nil ? .signedOut : TokenStore.read() == nil ? .expired : .ready
        if phase == .ready { await SyncEngine.shared.drain(reason: .foreground) }
    }

    func signedIn(_ r: AuthResponse) {
        // Unsynced work belongs to whoever made it: only a different account starts from an empty database.
        if user?.id != r.user.id { try? Persistence.wipe() }
        TokenStore.write(Tokens(access: r.accessToken, refresh: r.refreshToken))
        UserDefaults.standard.set(try? JSONEncoder().encode(r.user), forKey: Self.userKey)
        user = r.user
        phase = .ready
        Task { await SyncEngine.shared.drain(reason: .foreground) }
    }

    func expired() { if phase == .ready { phase = .expired } }

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
        GIDSignIn.sharedInstance.signOut()
        user = nil
        phase = .signedOut
    }
}

/// Whether the device has a connection. Drives the offline banner.
@MainActor @Observable
final class NetworkStatus {
    var online = true
    @ObservationIgnored private let monitor = NWPathMonitor()
    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let ok = path.status == .satisfied
            Task { @MainActor in self?.online = ok }
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var auth = AuthModel()
    @State private var mustUpgrade = false
    @State private var queued = 0

    var body: some View {
        Group {
            if mustUpgrade { UpgradeView() } else {
                VStack(spacing: 0) {
                    if !network.online && session.phase != .launching { OfflineBanner(queued: queued) }
                    switch session.phase {
                    case .launching: SplashView()
                    case .signedOut: AuthFlow()
                    case .ready, .expired: MainTabs()
                    }
                }
            }
        }
        .environment(auth)
        .background(Theme.bg)
        .animation(.easeOut(duration: 0.2), value: session.phase)
        .animation(.easeOut(duration: 0.2), value: network.online)
        .overlay(alignment: .bottom) {
            if let toast = toaster.current {
                ToastView(toast: toast).padding(.horizontal, 20).padding(.bottom, session.phase == .signedOut ? 20 : 70)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .offset(y: 12)))
            }
        }
        .animation(.easeOut(duration: 0.22), value: toaster.current)
        .task { auth.attach(session: session, toaster: toaster); await session.bootstrap() }
        .onReceive(NotificationCenter.default.publisher(for: .sessionExpired)) { _ in session.expired() }
        .onReceive(NotificationCenter.default.publisher(for: .upgradeRequired)) { _ in mustUpgrade = true }
        .onChange(of: network.online) { was, now in
            if now && !was {
                let n = queued
                toaster.show(n > 0 ? String(localized: "Back online. Synced \(n) changes.") : String(localized: "Back online."), .ok)
            } else { queued = Persistence.outboxCount() }
        }
        .sheet(isPresented: .constant(session.phase == .expired)) { ReauthSheet().environment(auth).interactiveDismissDisabled() }
    }
}
