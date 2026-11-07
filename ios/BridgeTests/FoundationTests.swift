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

    /// 12 and over, same cases as the Android FoundationTest.
    func testBirthDateIsTwelveAndOver() {
        let cal = Calendar(identifier: .gregorian)
        func d(_ y: Int, _ m: Int, _ day: Int) -> Date { cal.date(from: DateComponents(year: y, month: m, day: day, hour: 12))! }
        let today = d(2026, 11, 4)
        XCTAssertNotNil(AuthValidation.birthDate(nil, today: today))
        XCTAssertEqual(AuthValidation.birthDate(d(2014, 11, 5), today: today), "The Bridge is for people 12 and over.")
        XCTAssertNil(AuthValidation.birthDate(d(2014, 11, 4), today: today))
        XCTAssertNotNil(AuthValidation.birthDate(d(2027, 1, 1), today: today))
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
        let root = view.bridgeTypeScale().environment(session).environment(AuthModel()).environment(Toaster()).environment(AppRouter()).environment(GoalStore()).environment(CoachModel()).environment(NetworkStatus()).environment(SocialStore()).environment(DmStore()).environment(BillingStore())
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
                    members: ["Ms Okafor", "Jide", "Funmi", "Sharon", "Lola", "Bayo"].enumerated().map { MemberDTO(userId: "u\($0.offset)", name: $0.element, role: $0.offset == 0 ? "owner" : "member", away: $0.offset == 4, finished: $0.offset == 2) },
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
    // MARK: Edge cases
    private var overdue: GoalState {
        GoalState(id: goal.id, label: "Half marathon", statement: "Run a half marathon in under 2 hours.", startDate: "2026-07-01", deadline: "2026-10-11", colorIndex: 0,
                  routine: nil, realityNote: nil, feasibility: nil, tasks: [], progress: GoalProgress(day: 105, total: 105, doneToday: 0, tasksToday: 4, fraction: 0.99, streak: 6, drift: -20, overdue: true, activeDays: 6), images: [:])
    }
    func testRecap() { verify(RecapCard(goal: overdue, onComplete: {}, onExtend: {}, onArchive: {}).padding(24).background(Theme.bg), named: "today-recap") }
    func testAway() { verify(AwayCard(onAdjust: {}).padding(24).background(Theme.bg), named: "today-away") }
    func testPausedCard() { verify(PausedCard(count: 1, onChoose: {}).padding(24).background(Theme.bg), named: "today-paused") }
    func testChooseGoals() {
        let goals = [GoalChoice(id: goal.id, label: "Half marathon", statement: "Run a half marathon in under 2 hours."),
                     GoalChoice(id: UUID(uuidString: "018b0000-0000-7000-8000-0000000000a2")!, label: "Spanish B1", statement: "Hold a 20-minute conversation in Spanish."),
                     GoalChoice(id: UUID(uuidString: "018b0000-0000-7000-8000-0000000000a3")!, label: "Save £2k", statement: "Save £2,000 for a deposit by June.")]
        verify(ChooseGoalsView(goals: goals, initial: Set(goals.prefix(2).map(\.id)), max: 3, onSave: { _ in }, onUpgrade: {}).background(Theme.s1), named: "choose-goals")
    }
    /// The Coach tab's Arc: rest, bubbles up and mid-blink, large and at tab size, plus the still frame.
    func testArcTab() {
        let row = { (size: CGFloat) in HStack(spacing: 20) {
            ForEach([0, 1.2, 3.4], id: \.self) { t in Image(uiImage: ArcTabGlyph.image(at: t, size: size)).foregroundStyle(Theme.ink) }
            Image(uiImage: ArcTabGlyph.image(at: 0, still: true, size: size)).foregroundStyle(Theme.mut)
        } }
        verify(VStack(alignment: .leading, spacing: 20) { row(72); row(22) }.padding(24).frame(maxWidth: .infinity, alignment: .leading).background(Theme.s0), named: "arc-tab")
    }
    /// Reminders Arc set: on this phone, synced from another phone, notifications off, passed; and a call.
    func testArcActions() {
        let now = DateComponents(calendar: Calendar.current, year: 2026, month: 11, day: 7, hour: 14, minute: 32).date!
        let call = CoachAction(type: "reminder", at: "2026-11-07T17:00", text: "Call Sharon", call: "Sharon")
        let alarm = CoachAction(type: "reminder", at: "2026-11-08T06:00", text: "Wake up for the long run")
        verify(VStack(spacing: 12) {
            ArcActionCard(action: call, pending: true, allowed: true, now: now)
            ArcActionCard(action: alarm, pending: false, allowed: true, now: now)
            ArcActionCard(action: alarm, pending: true, allowed: false, now: now)
            ArcActionCard(action: CoachAction(type: "reminder", at: "2026-11-07T09:00", text: "Call Sharon", call: "Sharon"), pending: false, allowed: true, now: now)
            ArcActionCard(action: CoachAction(type: "call", name: "Mum"), pending: false, allowed: true, now: now)
        }.padding(16).frame(width: 360).background(Theme.bg), named: "arc-actions")
    }
    func testCallSteps() {
        verify(VStack(spacing: 32) {
            CallStepView(step: .needsContacts("Sharon", denied: false))
            CallStepView(step: .pick("Sharon", [PhoneContact(name: "Sharon Okafor", number: "+234 803 000 0001", label: "Mobile"), PhoneContact(name: "Sharon Bello", number: "0803 000 0007", label: "Work")]))
            CallStepView(step: .notFound("Kemi"))
        }.padding(24).background(Theme.bg), named: "call-steps")
    }
    func testGoalDetails() { verify(GoalDetailsView(goal: goal).background(Theme.s1), named: "goal-details") }
    func testDeleteGoal() { verify(DeleteGoalSheet(onDelete: {}, onCancel: {}).background(Theme.s1), named: "goal-delete") }
    func testGoalCrossed() { verify(GoalCrossedView(goal: overdue, onNext: {}, onClose: {}), named: "goal-crossed") }
    func testLinks() {
        verify(VStack(spacing: 8) {
            ResourceLinkRow(link: LinkDTO(title: "The Rust Programming Language", url: "https://doc.rust-lang.org/book/"))
            ResourceLinkRow(link: LinkDTO(title: "Ownership and borrowing explained", url: "https://www.youtube.com/results?search_query=rust%20ownership"))
            ResourceLinkRow(link: LinkDTO(title: "Exercism Rust track", url: "https://exercism.org/tracks/rust"))
        }.padding(24).background(Theme.s1), named: "links")
    }
    func testAppearance() { verify(AppearanceSheet(current: "system") { _ in }.background(Theme.s1), named: "appearance") }
    func testCoachStyles() { verify(CoachStyleSheet(current: .balanced, hasPlus: false) { _ in }.background(Theme.s1), named: "coach-styles") }
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

    // MARK: Students view and check-ins (shared plans led by a teacher or coach)
    private var week: [String] { (0..<7).map { LocalDay.today(now: now.addingTimeInterval(Double($0 - 6) * 86_400)) } }
    private var classView: StudentsDTO {
        func p(_ streak: Int, _ last: String?, _ done: Int, _ due: Int, _ today: DoneCount, _ feel: Double?, _ attention: String?) -> StudentProgressDTO {
            StudentProgressDTO(streak: streak, lastActive: last, week: WeekCount(done: done, due: due), today: today, feel: feel, attention: attention, nudgedToday: false)
        }
        return StudentsDTO(name: "Strength 101", members: 5, sharing: 4, week: WeekCount(done: 41, due: 63), students: [
            StudentDTO(userId: "u1", name: "Bayo", sharing: true, finished: false, startDate: "2026-10-01", progress: p(0, "2026-10-09", 4, 21, DoneCount(done: 0, total: 3), nil, "away")),
            StudentDTO(userId: "u2", name: "Kemi", sharing: true, finished: false, startDate: "2026-10-01", progress: p(3, "2026-10-13", 12, 21, DoneCount(done: 2, total: 3), 1.8, "low_feel")),
            StudentDTO(userId: "u3", name: "Tunde", sharing: true, finished: false, startDate: "2026-10-01", progress: p(9, "2026-10-13", 20, 21, DoneCount(done: 3, total: 3), 4.2, nil)),
            StudentDTO(userId: "u4", name: "Ada", sharing: true, finished: true, startDate: "2026-09-01", progress: p(30, "2026-10-12", 5, 5, DoneCount(), 4.0, nil)),
            StudentDTO(userId: "u5", name: "Lola", sharing: false, finished: false, startDate: "2026-10-05", progress: nil),
        ], tasks: [
            ClassTaskDTO(id: "t1", title: "Squats, 3 sets of 10", measure: "reps", done: 9, due: 21, feel: 2.1),
            ClassTaskDTO(id: "t2", title: "Plank, 60 seconds", measure: nil, done: 15, due: 21, feel: 3.4),
            ClassTaskDTO(id: "t3", title: "Walk 20 minutes", measure: "min", done: 17, due: 21, feel: nil),
        ])
    }
    private var kemi: StudentDetailDTO {
        let squats = week.enumerated().map { i, d in StudentDayDTO(date: d, state: [1, 3].contains(i) ? "missed" : "done", feel: i == 6 ? 1 : i == 5 ? 2 : nil, amount: i >= 5 ? 8 : nil,
                                                                    note: i == 6 ? "Knee hurts on the way down. Can I do wall sits instead?" : nil) }
        let plank = week.enumerated().map { i, d in StudentDayDTO(date: d, state: i % 2 == 0 ? "done" : "missed", feel: i == 4 ? 3 : nil, amount: nil, note: nil) }
        let walk = week.enumerated().map { i, d in StudentDayDTO(date: d, state: i == 6 ? "open" : "done", feel: nil, amount: nil, note: nil) }
        return StudentDetailDTO(userId: "u2", name: "Kemi", startDate: "2026-10-01", finished: false, days: week, streak: 3, lastActive: "2026-10-13", week: WeekCount(done: 12, due: 21),
                                feel: 1.8, attention: "low_feel", nudgedToday: false, tasks: [
                                    StudentTaskDTO(id: "t1", title: "Squats, 3 sets of 10", measure: "reps", days: squats),
                                    StudentTaskDTO(id: "t2", title: "Plank, 60 seconds", measure: nil, days: plank),
                                    StudentTaskDTO(id: "t3", title: "Walk 20 minutes", measure: "min", days: walk)])
    }
    func testStudents() { verify(StudentsContent(state: Loadable(data: classView, loading: false), code: "STRE-4821", today: now), named: "students") }
    func testStudentsEmpty() {
        let empty = StudentsDTO(name: "Strength 101", members: 0, sharing: 0, week: WeekCount(), students: [], tasks: [])
        verify(StudentsContent(state: Loadable(data: empty, loading: false), code: "STRE-4821", today: now), named: "students-empty")
    }
    func testStudentsOffline() { verify(StudentsContent(state: Loadable<StudentsDTO>(data: nil, loading: false, offline: true), code: "STRE-4821", today: now), named: "students-offline") }
    func testStudent() { verify(StudentContent(state: Loadable(data: kemi, loading: false), name: "Kemi", plan: "Strength 101", today: now), named: "student") }
    func testCheckIn() {
        let squats = PlanTask(id: UUID(), title: "Squats, 3 sets of 10", priority: "High", timeOfDay: nil, notes: nil, rationale: nil, origin: "shared", done: true, measure: "reps")
        verify(CheckInSheet(task: squats, existing: Checkin(feel: 2, amount: 8, note: "Knee hurts on the way down."), lead: LeadInfo(name: "Coach Ada", sees: true, isMe: false), onSave: { _ in }, onSkip: {})
            .background(Theme.s1), named: "checkin")
    }
    func testTaskMeasure() { verify(MeasurePicker(value: .constant("reps")).padding(24).background(Theme.s1), named: "task-measure") }
    func testAllianceLead() {
        var lead = calc; lead.role = "owner"; lead.shared?.isLead = true
        verify(community(CommunityUI(goal: goal, community: CommunityDTO(unlocked: true, streak: 9), loading: false, tab: .alliance, alliances: [lead])), named: "alliance-lead")
    }
}
