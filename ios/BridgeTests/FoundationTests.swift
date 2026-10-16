import SnapshotTesting
import SwiftUI
import XCTest
@testable import Bridge

final class FoundationTests: XCTestCase {
    func testDayClosesAt3amLocal() {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Africa/Lagos")!
        func at(_ d: Int, _ h: Int, _ m: Int) -> Date { cal.date(from: DateComponents(year: 2026, month: 10, day: d, hour: h, minute: m))! }
        XCTAssertEqual(LocalDay.today(now: at(13, 23, 59), calendar: cal), "2026-10-13")
        XCTAssertEqual(LocalDay.today(now: at(14, 2, 59), calendar: cal), "2026-10-13")   // late night still counts for the 13th
        XCTAssertEqual(LocalDay.today(now: at(14, 3, 0), calendar: cal), "2026-10-14")
    }

    func testUUIDv7CarriesTimestampAndSorts() {
        let a = UUIDv7.next(now: Date(timeIntervalSince1970: 1_700_000_000)), b = UUIDv7.next(now: Date(timeIntervalSince1970: 1_700_000_000.001))
        XCTAssertEqual(a.uuid.6 >> 4, 7)
        XCTAssertEqual(a.uuid.8 >> 6, 2)
        XCTAssertLessThan(a.uuidString, b.uuidString)
    }

    /// Analytics may carry ids, counts and codes, never what people write (CLAUDE.md).
    func testAnalyticsKeepsOnlyCountsAndCodes() {
        let kept = cleanProps(["tasks": 4, "study": true, "mode": "shared", "title": "Run a half marathon", "goal": "Pass the JLPT", "body": String(repeating: "x", count: 25), "Bad Key": 1])
        XCTAssertEqual(Set(kept.keys), ["tasks", "study", "mode"])
    }

    func testAuthValidationMatchesPrototypeRules() {
        XCTAssertNotNil(AuthValidation.name(" S "))
        XCTAssertNil(AuthValidation.name("Sharon"))
        XCTAssertNotNil(AuthValidation.email("sharon@example"))
        XCTAssertNotNil(AuthValidation.email("sha ron@example.com"))
        XCTAssertNil(AuthValidation.email(" sharon@example.com "))
        XCTAssertNotNil(AuthValidation.newPassword("1234567"))
        XCTAssertNil(AuthValidation.newPassword("12345678"))
    }
}

/// Every screen in light, dark and the largest text size. Record once with `withSnapshotTesting(record: .all)`.
@MainActor
final class FoundationSnapshotTests: XCTestCase {
    private func verify(_ view: some View, named name: String, file: StaticString = #file, line: UInt = #line) {
        let session = SessionStore(); session.user = UserDTO(id: UUID(uuidString: "018b0000-0000-7000-8000-000000000001")!, email: "sharon@example.com", displayName: "Sharon", coachName: "Arc", createdAt: 1_760_000_000_000)
        let root = view.environment(session).environment(AuthModel()).environment(Toaster()).environment(AppRouter()).environment(GoalStore()).environment(CoachModel()).environment(NetworkStatus()).environment(SocialStore()).environment(DmStore()).environment(BillingStore())
        let variants: [(String, UITraitCollection)] = [
            ("light", .init(userInterfaceStyle: .light)), ("dark", .init(userInterfaceStyle: .dark)),
            ("xxxl", .init(traitsFrom: [.init(userInterfaceStyle: .light), .init(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge)])),
        ]
        let env = ProcessInfo.processInfo.environment
        // CI passes the folder explicitly: the compiled source path isn't writable from the simulator.
        let dir = env["SNAPSHOT_DIR"]
        // New screens record their first reference (and fail once, so CI publishes it to commit).
        withSnapshotTesting(record: env["SNAPSHOT_RECORD"] == "1" ? .all : .missing) {
            for (variant, traits) in variants {
                if let failure = verifySnapshot(of: UIHostingController(rootView: root), as: .image(on: .iPhone13, precision: 0.99, perceptualPrecision: 0.98, traits: traits),
                                                named: "\(name)-\(variant)", snapshotDirectory: dir, testName: "FoundationSnapshotTests") {
                    XCTFail(failure, file: file, line: line)
                }
            }
        }
    }

    func testWelcome() { verify(WelcomeView(onGetStarted: {}, onLogIn: {}), named: "welcome") }
    func testSignUp() { verify(SignUpView(onLogIn: {}), named: "signup") }
    func testLogIn() { verify(LogInForm(onSignUp: {}).background(Theme.bg), named: "login") }
    func testToday() { verify(TodayView(now: Date(timeIntervalSince1970: 1_760_340_000)), named: "today") }
    func testCoach() { verify(CoachView(), named: "coach") }
    func testJournal() { verify(JournalView(), named: "journal") }
    func testProfile() { verify(ProfileView(), named: "profile") }
    func testDeleteAccount() { verify(DeleteAccountSheet().background(Theme.bg), named: "delete") }
    func testReauth() { verify(ReauthSheet(), named: "reauth") }
    func testStudy() { verify(StudyModeView(), named: "study") }
    func testOfflineBanner() { verify(OfflineBanner(queued: 3), named: "offline") }

    // MARK: Milestone 4
    private let now = Date(timeIntervalSince1970: 1_760_348_460)         // 2026-10-13 09:41 UTC
    private var ms: Double { now.timeIntervalSince1970 * 1000 }
    private let goal = GoalState(id: UUID(uuidString: "018b0000-0000-7000-8000-0000000000a1")!, label: "Half marathon", statement: "Run a half marathon in under 2 hours.",
                                 startDate: "2026-09-20", deadline: "2027-01-10", colorIndex: 0, routine: nil, realityNote: nil, feasibility: nil, tasks: [],
                                 progress: GoalProgress(day: 24, total: 113, doneToday: 2, tasksToday: 4, fraction: 0.208, streak: 9, drift: 6), images: [:])
    private var posts: [PostDTO] { [
        PostDTO(id: "p1", userId: "u1", name: "Jonas", kind: "streak", value: 30, createdAt: ms - 7_200_000, reactions: .init(respect: 12, keepGoing: 4, inspired: 6)),
        PostDTO(id: "p2", userId: "u2", name: "Priya", kind: "day_complete", createdAt: ms - 14_400_000, reactions: .init(respect: 8, keepGoing: 11, inspired: 2), reacted: ["keepGoing"]),
        PostDTO(id: "p3", mine: true, name: "Sharon", kind: "milestone", value: 25, createdAt: ms - 93_600_000, reactions: .init(respect: 5, keepGoing: 2, inspired: 7)),
    ] }
    private var calc: AllianceDTO {
        AllianceDTO(id: "a1", name: "Calc Prep 12B", planMode: "shared", inviteCode: "CALC-2026", startDate: "2026-09-28", role: "member", goalId: "g", streak: 9,
                    members: ["Ms Okafor", "Jide", "Funmi", "Sharon", "Lola", "Bayo"].enumerated().map { MemberDTO(userId: "u\($0.offset)", name: $0.element, role: $0.offset == 0 ? "owner" : "member", away: $0.offset == 4) },
                    memberCount: 6, shared: SharedPlanDTO(lead: "Ms", goal: "Score 75% or higher on the Grade 12 math final.", deadline: "2026-12-04"),
                    feed: [AllianceFeedDTO(name: "Jide", what: "completed", title: "Work 10 past-paper questions", at: ms - 10_800_000), AllianceFeedDTO(name: "Lola", what: "joined", at: ms - 108_000_000)])
    }
    private func community(_ ui: CommunityUI) -> some View { CommunityContent(ui: ui, actions: CommunityActions(), today: now, now: now) }
    func testCommunityLocked() { verify(community(CommunityUI(goal: goal, community: CommunityDTO(unlocked: false, streak: 4), loading: false)), named: "community-locked") }
    func testCommunityPreview() { verify(community(CommunityUI(goal: goal, community: CommunityDTO(unlocked: false, streak: 4), loading: false, previewing: true, tab: .alliance)), named: "community-preview") }
    func testCommunityFeed() { verify(community(CommunityUI(goal: goal, community: CommunityDTO(unlocked: true, streak: 9, posts: posts), loading: false)), named: "community-feed") }
    func testAllianceNone() { verify(community(CommunityUI(goal: goal, community: CommunityDTO(unlocked: true, streak: 9), loading: false, tab: .alliance)), named: "alliance-none") }
    func testAllianceShared() { verify(community(CommunityUI(goal: goal, community: CommunityDTO(unlocked: true, streak: 9), loading: false, tab: .alliance, alliances: [calc])), named: "alliance-shared") }
    func testAllianceChat() {
        let lines = [ChatLine(id: "c1", serverId: "s1", name: "Jide", body: "Did anyone get question 7? The integral looks wrong.", mine: false, createdAt: now.addingTimeInterval(-3000), status: "sent"),
                     ChatLine(id: "c2", serverId: "s2", name: "Sharon", body: "It's by parts twice. Check the sign on the second step.", mine: true, createdAt: now.addingTimeInterval(-2400), status: "sent"),
                     ChatLine(id: "c4", serverId: nil, name: nil, body: "On my way to the library", mine: true, createdAt: now.addingTimeInterval(-60), status: "queued"),
                     ChatLine(id: "c5", serverId: nil, name: nil, body: "See you there", mine: true, createdAt: now, status: "failed")]
        verify(AllianceChatContent(alliance: calc, lines: lines, input: .constant(""), now: now), named: "alliance-chat")
    }
    // MARK: Milestone 5
    private func gameModel(_ phase: String, role: String = "builder", result: Bool = false) -> GameModel {
        let m = GameModel(); m.me = "me"; m.role = role; m.revealed = true; m.mine = 2
        let names = [("me", "Sharon"), ("u1", "Jide"), ("u2", "Funmi"), ("u3", "Bayo")]
        m.game = GameDTO(id: "g1", allianceId: "a1", ranked: true, phase: phase, endsAt: ms + 9_000,
                         players: names.map { GamePlayerDTO(userId: $0.0, name: $0.1, ready: true, role: result ? ($0.0 == "u2" ? "drifter" : "builder") : nil, actions: result ? 2 : nil, funPoints: result ? ($0.0 == "u2" ? 6 : 12) : nil) },
                         planks: ["cracked", "ok", "ok", "ok", "cracked", "ok", "cracked", "ok"], integrity: 63,
                         clues: [ClueDTO(who: "u1", text: "Jide repaired plank 2."), ClueDTO(who: "u2", text: "Plank 1 loosened. Funmi was nearby.")],
                         drifterId: result ? "u2" : nil, ejectedId: result ? "u2" : nil, buildersWon: result ? true : nil, tally: result ? ["u2": 3, "u1": 1] : nil, mvpId: result ? "me" : nil)
        return m
    }
    func testGameRole() { verify(GameNightContent(model: gameModel("role", role: "drifter"), now: now), named: "game-role") }
    func testGameRound() { verify(GameNightContent(model: gameModel("round"), now: now), named: "game-round") }
    func testGameVote() { let m = gameModel("vote"); m.choice = "u2"; verify(GameNightContent(model: m, now: now), named: "game-vote") }
    func testGameResult() { verify(GameNightContent(model: gameModel("result", result: true), now: now), named: "game-result") }
    func testAllianceGame() {
        var ui = CommunityUI(goal: goal, community: CommunityDTO(unlocked: true, streak: 9), loading: false, tab: .alliance, alliances: [calc])
        ui.games = ["a1": GameStatusDTO(unlocked: false, eligible: 4, finished: 3, waiting: ["Bayo"])]
        ui.ratings = ["a1": RatingDTO(overall: 82, tier: "silver", rank: 4, of: 31, band: "2-5", productivity: 86, efficiency: 78, funScore: 64)]
        verify(community(ui), named: "alliance-game")
    }
    func testRankings() {
        verify(RankingsContent(board: BoardDTO(category: "fitness", band: "2-5", rank: 4, of: 31, rows: [
            BoardRowDTO(rank: 1, name: "Iron Mornings", score: 94, tier: "gold", members: 5), BoardRowDTO(rank: 2, name: "Quiet Hustle", score: 88, tier: "gold", members: 4),
            BoardRowDTO(rank: 3, name: "Page a Day", score: 84, tier: "silver", members: 3), BoardRowDTO(rank: 4, name: "Dawn Runners", score: 82, tier: "silver", members: 4, mine: true),
            BoardRowDTO(rank: 5, name: "Slow Savers", score: 71, tier: "bronze", members: 5)]), failed: false), named: "rankings")
    }
    func testHallOfFame() {
        verify(HallOfFameContent(badges: BadgesDTO(public: true, badges: [BadgeDTO(key: "first", earned: true), BadgeDTO(key: "s7", earned: true), BadgeDTO(key: "s21", earned: false, progress: 9, goal: 21),
            BadgeDTO(key: "arch", earned: true), BadgeDTO(key: "s30", earned: false, progress: 9, goal: 30), BadgeDTO(key: "week", earned: false),
            BadgeDTO(key: "ref", earned: false, progress: 12, goal: 30), BadgeDTO(key: "ally", earned: true), BadgeDTO(key: "goal", earned: false)])), named: "hall")
    }
    // MARK: Encrypted DMs
    private var dmRows: [DmMessageRow] { [
        DmMessageRow(id: "d1", peerUserId: "u1", mine: false, body: "Saw your 30-day streak. How do you keep the mornings going?", createdAt: now.addingTimeInterval(-3000)),
        DmMessageRow(id: "d2", peerUserId: "u1", mine: true, body: "Clothes out the night before, and the alarm across the room.", createdAt: now.addingTimeInterval(-2700)),
        DmMessageRow(id: "d3", peerUserId: "u1", mine: false, body: "Trying that tomorrow.", createdAt: now.addingTimeInterval(-1800)),
        DmMessageRow(id: "d4", peerUserId: "u1", mine: true, body: "Tell me how it goes", createdAt: now.addingTimeInterval(-60), status: "queued"),
        DmMessageRow(id: "d5", peerUserId: "u1", mine: true, body: "Good luck!", createdAt: now, status: "failed"),
    ] }
    func testDmInbox() {
        _ = Persistence.container
        let rows = dmRows
        verify(DmInboxContent(rows: [DmRowData(contact: DmContactDTO(userId: "u1", name: "Jide", ready: true), last: rows[2], unread: true, verified: true),
                                     DmRowData(contact: DmContactDTO(userId: "u2", name: "Funmi", ready: true), last: rows[1], unread: false, verified: false),
                                     DmRowData(contact: DmContactDTO(userId: "u3", name: "Bayo"), last: nil, unread: false, verified: false)], loaded: true, now: now), named: "dm-inbox")
    }
    func testDmThread() { _ = Persistence.container; verify(DmThreadContent(name: "Jide", messages: dmRows, verification: .changed, input: .constant(""), now: now), named: "dm-thread") }
    func testDmSafety() {
        verify(SafetySheet(name: "Jide", numbers: [["05211", "84720", "39021", "77310", "10442", "93017", "28150", "64093", "11827", "50306", "72291", "40968"]], onVerify: {}, onDismiss: {}).background(Theme.bg), named: "dm-safety")
    }
    func testPaywall() {
        verify(PaywallContent(product: .alliancePlus, available: true, loading: false, offers: [PriceOffer(id: "m", price: "$3.99", period: .month, trialDays: 7)], selected: .constant("m")).background(Theme.bg), named: "paywall-alliance")
    }
    func testPaywallUnavailable() { verify(PaywallContent(product: .plus, available: false, loading: false, offers: [], selected: .constant(nil)).background(Theme.bg), named: "paywall-plus-off") }
    func testCreateAlliance() {
        let m = CommunityModel(); m.name = "Dawn Runners"; m.shared = true
        verify(CreateAllianceSheet(model: m, goal: goal, onCreate: {}).background(Theme.bg), named: "alliance-create")
    }
    func testJoinPreview() {
        let m = CommunityModel(); m.code = "CALC-2026"
        m.preview = InvitePreviewDTO(id: "a1", name: "Calc Prep 12B", planMode: "shared", lead: "Ms", members: 11, cap: 15, spotsLeft: 4, goal: "Score 75% or higher on the Grade 12 math final.", label: "Math final",
                                     deadline: "2026-12-04", tasks: [PreviewTaskDTO(title: "Work 10 past-paper questions", priority: "High", timeOfDay: "After school")], pace: PaceDTO(groupDay: 16, groupTotal: 68, yourTotal: 53))
        verify(JoinAllianceSheet(model: m, onJoin: {}).background(Theme.bg), named: "alliance-join-preview")
    }
}
