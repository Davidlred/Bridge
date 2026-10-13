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
        let root = view.environment(session).environment(AuthModel()).environment(Toaster()).environment(AppRouter()).environment(\.accessibilityReduceMotion, true)
        let variants: [(String, UITraitCollection)] = [
            ("light", .init(userInterfaceStyle: .light)), ("dark", .init(userInterfaceStyle: .dark)),
            ("xxxl", .init(traitsFrom: [.init(userInterfaceStyle: .light), .init(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge)])),
        ]
        for (variant, traits) in variants {
            assertSnapshot(of: UIHostingController(rootView: root), as: .image(on: .iPhone13, traits: traits), named: "\(name)-\(variant)", file: file, line: line)
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
    func testOfflineBanner() { verify(OfflineBanner(queued: 3), named: "offline") }
}
