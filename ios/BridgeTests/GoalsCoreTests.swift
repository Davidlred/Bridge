import XCTest
@testable import Bridge

/// Same cases as the Android GoalsCoreTest, so both platforms compute progress identically.
final class GoalsCoreTests: XCTestCase {
    private let start = "2026-10-01"
    private func d(_ day: Int) -> String { Day.add(day - 1, to: start) }
    private let a = UUID(), b = UUID()

    func testProgressCountsFinishedDaysPlusTodaysShare() {
        let p = Progress.of(start: start, deadline: d(10), today: d(4), taskCreated: [a: start, b: start], completions: [a: [d(4)]])
        XCTAssertEqual(p.day, 4); XCTAssertEqual(p.total, 10)
        XCTAssertEqual(p.doneToday, 1); XCTAssertEqual(p.tasksToday, 2)
        XCTAssertEqual(p.fraction, 3.5 / 10, accuracy: 0.0001)
        XCTAssertEqual(p.daysLeft, 7)
        XCTAssertFalse(p.allDone)
    }

    func testTotalNeverDropsBelowTheDayIndex() {
        let p = Progress.of(start: start, deadline: d(5), today: d(8), taskCreated: [a: start], completions: [:])
        XCTAssertEqual(p.total, 8); XCTAssertEqual(p.fraction, 7.0 / 8, accuracy: 0.0001)
    }

    func testStreakRunsBackFromTodayOrYesterday() {
        let days: Set = [d(1), d(2), d(3), d(5), d(6)]
        XCTAssertEqual(Progress.streak(days, today: d(6)), 2)
        XCTAssertEqual(Progress.streak(days, today: d(7)), 2)
        XCTAssertEqual(Progress.streak(days, today: d(8)), 0)
        XCTAssertEqual(Progress.streak(days, today: d(4)), 3)
    }

    func testDriftAgainstPaceTarget() {
        XCTAssertNil(Progress.drift(start: start, today: start, taskCreated: [a: start, b: start], completions: [:]))
        let all = Set((1...7).map(d))
        XCTAssertEqual(Progress.drift(start: start, today: d(8), taskCreated: [a: start, b: start], completions: [a: all, b: all]), 20)
        XCTAssertEqual(Progress.drift(start: start, today: d(8), taskCreated: [a: start, b: start], completions: [a: all]), -30)
        XCTAssertEqual(Progress.drift(start: start, today: d(8), taskCreated: [a: start, b: d(5)], completions: [a: all, b: Set((5...7).map(d))]), 20)
    }

    func testDeadlines() {
        let today = "2026-10-14"
        XCTAssertEqual(Deadlines.check(nil, today: today, minDays: 7), .missing)
        XCTAssertEqual(Deadlines.check(Day.add(6, to: today), today: today, minDays: 7), .tooSoonWeek)
        XCTAssertNil(Deadlines.check(Day.add(7, to: today), today: today, minDays: 7))
        XCTAssertEqual(Deadlines.check(today, today: today, minDays: 1), .tooSoonDay)
        XCTAssertEqual(Deadlines.check(Day.add(1827, to: today), today: today, minDays: 1), .tooFar)
    }

    func testGoalTextHints() {
        XCTAssertTrue(GoalText.hasDate("Run a marathon by June"))
        XCTAssertTrue(GoalText.hasDate("Learn Spanish within 6 months"))
        XCTAssertFalse(GoalText.hasDate("Run a half marathon in under 2 hours"))
        XCTAssertTrue(GoalText.needsNumber("Get much fitter overall"))
        XCTAssertFalse(GoalText.needsNumber("Run 10 km without stopping"))
        XCTAssertEqual(GoalText.autoLabel("Run a half marathon in under 2 hours."), "Half marathon")
        XCTAssertEqual(GoalText.autoLabel("learn conversational Spanish, properly"), "Learn conversational")
        XCTAssertEqual(GoalText.autoLabel("read 12 books this year"), "Read 12 books")
    }

    func testTaskTimes() {
        XCTAssertTrue(TaskTime.parse("7:00 AM")! == (7, 0))
        XCTAssertTrue(TaskTime.parse("7:30 pm")! == (19, 30))
        XCTAssertTrue(TaskTime.parse("12:15 AM")! == (0, 15))
        XCTAssertTrue(TaskTime.parse("12pm")! == (12, 0))
        XCTAssertTrue(TaskTime.parse("06:45")! == (6, 45))
        XCTAssertNil(TaskTime.parse("After run"))
        XCTAssertNil(TaskTime.parse("25:00"))
        XCTAssertNil(TaskTime.parse(nil))
    }
}
