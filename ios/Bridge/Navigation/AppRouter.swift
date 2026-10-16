import SwiftUI

/// Typed routes. Community lives inside a goal, so most social routes carry a goal id.
enum Route: Hashable, Codable {
    case goal(UUID)
    case task(UUID)
    case realityCheck(goalId: UUID)
    /// `code`: opened from an invite link, so the join sheet starts with it.
    case community(goalId: UUID, code: String? = nil)
    case alliance(goalId: UUID, allianceId: UUID)
    case allianceChat(UUID)
    case gameNight(allianceId: UUID)
    case rankings(goalCategory: String)
    case allianceRankings(UUID)
    case invitePreview(code: String)
    case studyMode(taskId: UUID)
    case journalEntry(goalId: UUID, localDate: String)
    case journalHistory
    case messages
    case dmThread(UUID)
    case hallOfFame
    case notifications
    case settings
    /// Add a second or third goal: the onboarding steps, full screen.
    case addGoal
    /// Study Mode from the Coach tab.
    case study
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
            case .addGoal: AddGoalScreen().toolbar(.hidden, for: .tabBar)
            case .study: StudyModeView().toolbar(.hidden, for: .tabBar)
            case .journalEntry(let goalId, _): JournalFromToday(goalId: goalId)
            case .community(let goalId, let code): CommunityScreen(goalId: goalId, code: code)
            case .allianceChat(let id): AllianceChatScreen(allianceId: id.uuidString.lowercased()).toolbar(.hidden, for: .tabBar)
            case .invitePreview(let code): InviteRedirect(code: code)
            case .gameNight(let id): GameNightScreen(allianceId: id.uuidString.lowercased()).toolbar(.hidden, for: .tabBar)
            case .allianceRankings(let id): RankingsScreen(allianceId: id.uuidString.lowercased())
            case .hallOfFame: HallOfFameScreen()
            case .messages: DmInboxScreen()
            case .dmThread(let id): DmThreadScreen(peer: id.uuidString.lowercased())
            case .studyMode: PlaceholderScreen(title: String(describing: route)).toolbar(.hidden, for: .tabBar)
            default: PlaceholderScreen(title: String(describing: route))
            }
        }
    }
}

/// Invite links carry only the code: open the join sheet inside Community for the first active goal.
private struct InviteRedirect: View {
    let code: String
    @Environment(AppRouter.self) private var router
    @Environment(GoalStore.self) private var goals
    @Environment(Toaster.self) private var toaster
    var body: some View {
        Theme.bg.task {
            router.today.removeLast()
            if let g = goals.goals.first { router.today.append(Route.community(goalId: g.id, code: code)) }
            else { toaster.show(String(localized: "Add a goal first, then join with this code.")) }
        }
    }
}

/// The journal pushed from Today ("Lock it in"), with a back link to Today.
private struct JournalFromToday: View {
    let goalId: UUID
    @Environment(AppRouter.self) private var router
    var body: some View { JournalView(goalId: goalId, onBack: { router.today.removeLast() }).navigationBarBackButtonHidden() }
}

private struct AddGoalScreen: View {
    @Environment(AppRouter.self) private var router
    var body: some View {
        OnboardingFlow(adding: true, onExit: { router.today.removeLast() }, onFinished: { router.today.removeLast() })
            .navigationBarBackButtonHidden()
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
