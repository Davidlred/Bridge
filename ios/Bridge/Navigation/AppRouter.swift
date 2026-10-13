import SwiftUI

/// Typed routes. Community lives inside a goal, so most social routes carry a goal id.
enum Route: Hashable, Codable {
    case goal(UUID)
    case task(UUID)
    case realityCheck(goalId: UUID)
    case community(goalId: UUID)
    case alliance(goalId: UUID, allianceId: UUID)
    case allianceChat(UUID)
    case gameNight(allianceId: UUID)
    case rankings(goalCategory: String)
    case invitePreview(code: String)
    case studyMode(taskId: UUID)
    case journalEntry(goalId: UUID, localDate: String)
    case journalHistory
    case messages
    case dmThread(UUID)
    case hallOfFame
    case notifications
    case settings
}

enum Tab: Hashable { case today, coach, journal, profile }

@MainActor @Observable
final class AppRouter {
    var tab: Tab = .today
    var today = NavigationPath()
    var coach = NavigationPath()
    var journal = NavigationPath()
    var profile = NavigationPath()
    var pendingInvite: String?

    func push(_ r: Route) {
        switch tab {
        case .today: today.append(r)
        case .coach: coach.append(r)
        case .journal: journal.append(r)
        case .profile: profile.append(r)
        }
    }

    /// Universal links: https://thebridge.app/join/CALC-2026
    func handle(url: URL) {
        let parts = url.pathComponents
        if parts.count >= 3, parts[1] == "join" {
            pendingInvite = parts[2].uppercased()
            tab = .today
            today.append(Route.invitePreview(code: parts[2].uppercased()))
        }
    }
}

struct MainTabs: View {
    @Environment(AppRouter.self) private var router

    var body: some View {
        @Bindable var router = router
        TabView(selection: $router.tab) {
            NavigationStack(path: $router.today) { TodayView().routes() }
                .tabItem { Label("Today", systemImage: "sun.max") }.tag(Tab.today)
            NavigationStack(path: $router.coach) { CoachView().routes() }
                .tabItem { Label("Coach", systemImage: "bubble.left.and.text.bubble.right") }.tag(Tab.coach)
            NavigationStack(path: $router.journal) { JournalView().routes() }
                .tabItem { Label("Journal", systemImage: "book.closed") }.tag(Tab.journal)
            NavigationStack(path: $router.profile) { ProfileView().routes() }
                .tabItem { Label("Profile", systemImage: "person.crop.circle") }.tag(Tab.profile)
        }
    }
}

private extension View {
    func routes() -> some View {
        navigationDestination(for: Route.self) { route in
            // Feature screens replace the placeholder as their milestones land (goal detail: 2, study mode: 3, community/game night: 4–5).
            switch route {
            case .studyMode, .gameNight: PlaceholderScreen(title: String(describing: route)).toolbar(.hidden, for: .tabBar)
            default: PlaceholderScreen(title: String(describing: route))
            }
        }
    }
}

/// Temporary stand-in while screens are built from the HTML reference.
struct PlaceholderScreen: View {
    let title: String
    var body: some View {
        Text(title).font(BFont.body(15)).foregroundStyle(Theme.mut)
            .frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.bg)
    }
}
