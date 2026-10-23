import XCTest
@testable import Bridge

/// Same cases as the Android RhythmTest, so both apps learn the same routine from the same week.
final class RhythmTests: XCTestCase {
    private let today = "2026-10-23"
    private func at(_ daysAgo: Int, _ h: Int, _ m: Int = 0) -> Date {
        var c = Calendar.current.dateComponents([.year, .month, .day], from: Day.parse(Day.add(-daysAgo, to: today))!)
        c.hour = h; c.minute = m
        return Calendar.current.date(from: c)!
    }
    private func day(_ daysAgo: Int) -> String { Day.add(-daysAgo, to: today) }

    func testTooFewDaysSaysNothing() {
        XCTAssertNil(RhythmCalc.of(opens: (0...3).map { AppOpen(at: at($0, 21), wifi: true) }, completions: [], today: today))
    }

    func testAnEveningPersonGetsAnEveningPlan() {
        let opens = (0...9).map { AppOpen(at: at($0, 21, 5), wifi: $0 % 3 != 0) } + [AppOpen(at: at(2, 8), wifi: false)]
        var done = (1...9).flatMap { [(day: day($0), at: at($0, 20, 10)), (day: day($0), at: at($0, 21, 40))] }
        done.append((day: day(4), at: at(3, 0, 30)))       // counted for day 4, hour 24
        let r = RhythmCalc.of(opens: opens, completions: done, today: today)!
        XCTAssertEqual(r.days, 10)
        XCTAssertEqual(r.openHour, 21)
        XCTAssertEqual(r.doneHour, 20)
        XCTAssertEqual(r.finishedBy, 22)
        XCTAssertEqual(r.wifiShare!, 6.0 / 11, accuracy: 0.001)
        XCTAssertEqual(r.dailyReminder.hour, 19)
        XCTAssertEqual(r.streakRisk.hour, 22)
    }

    func testAMorningRunnerIsNudgedEarlyWithinBounds() {
        let r = RhythmCalc.of(opens: (0...6).map { AppOpen(at: at($0, 6, 20), wifi: nil) }, completions: (1...6).map { (day: day($0), at: at($0, 6, 45)) }, today: today)!
        XCTAssertEqual(r.dailyReminder.hour, 6)
        XCTAssertEqual(r.streakRisk.hour, 17)
        XCTAssertNil(r.wifiShare)
    }

    func testOldOpensAreForgotten() {
        XCTAssertNil(RhythmCalc.of(opens: (30...40).map { AppOpen(at: at($0, 9), wifi: true) }, completions: [], today: today))
    }

    func testTheBusiestHourWrapsAroundMidnight() { XCTAssertEqual(RhythmCalc.peak([23, 23, 0, 0, 22]), 23) }
}
