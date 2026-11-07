import XCTest
@testable import Bridge

/// Same cases as Android (ArcPhoneTest.kt).
final class ArcPhoneTests: XCTestCase {
    func testCallCommandsAreUnderstoodHoweverTheyAreSaid() {
        XCTAssertEqual(ArcCommand.parseCall("call Sharon"), .name("Sharon"))
        XCTAssertEqual(ArcCommand.parseCall("arc call sharon"), .name("sharon"))
        XCTAssertEqual(ArcCommand.parseCall("Hey Arc, call Sharon please."), .name("Sharon"))
        XCTAssertEqual(ArcCommand.parseCall("Ring my mum for me"), .name("my mum"))
        XCTAssertEqual(ArcCommand.parseCall("Can you phone Dr Bello now?"), .name("Dr Bello"))
        XCTAssertEqual(ArcCommand.parseCall("Coachy call Tunde", coachName: "Coachy"), .name("Tunde"))
        XCTAssertEqual(ArcCommand.parseCall("dial 0803 000 0001"), .number("08030000001"))
        XCTAssertEqual(ArcCommand.parseCall("call +234 803 000 0001"), .number("+2348030000001"))
    }

    func testAnythingElseGoesToTheCoach() {
        XCTAssertNil(ArcCommand.parseCall("remind me to call Sharon at 5"))
        XCTAssertNil(ArcCommand.parseCall("call Sharon at 5pm"))
        XCTAssertNil(ArcCommand.parseCall("call Sharon tomorrow"))
        XCTAssertNil(ArcCommand.parseCall("call it a day"))
        XCTAssertNil(ArcCommand.parseCall("call me later"))
        XCTAssertNil(ArcCommand.parseCall("I need to call the bank about my loan before Friday"))
        XCTAssertNil(ArcCommand.parseCall("How do I stay focused?"))
        XCTAssertNil(ArcCommand.parseCall("Calling people makes me nervous"))
    }

    func testTheBestMatchingContactsWin() {
        let book = [
            PhoneContact(name: "Sharon Okafor", number: "+234 803 000 0001", label: "Mobile"),
            PhoneContact(name: "Sharon O.", number: "0803 000 0001", label: "Mobile"),
            PhoneContact(name: "Sharonda Lee", number: "0803 000 0002"),
            PhoneContact(name: "Mum", number: "0803 000 0003"),
            PhoneContact(name: "Dr. Bello", number: "0803 000 0004"),
            PhoneContact(name: "Shola", number: "0803 000 0005"),
        ]
        XCTAssertEqual(ArcCommand.rank("Sharon", book).map(\.name), ["Sharon Okafor"])
        XCTAssertEqual(ArcCommand.rank("my mum", book).map(\.name), ["Mum"])
        XCTAssertEqual(ArcCommand.rank("Dr Bello", book).map(\.name), ["Dr. Bello"])
        XCTAssertEqual(ArcCommand.rank("bello", book).map(\.name), ["Dr. Bello"])
        XCTAssertEqual(ArcCommand.rank("shar", book).map(\.name), ["Sharon Okafor", "Sharonda Lee"])
        XCTAssertEqual(ArcCommand.rank("Kemi", book), [])
        XCTAssertEqual(ArcCommand.norm("  Dr. Bélló "), "dr bello")
    }

    func testReminderTimesAreLocalWallClock() {
        let d = ArcReminders.parse("2026-11-07T17:00")!
        XCTAssertEqual(Calendar.current.dateComponents([.hour, .minute], from: d), DateComponents(hour: 17, minute: 0))
        XCTAssertEqual(ArcReminders.format(d), "2026-11-07T17:00")
        XCTAssertNil(ArcReminders.parse("5pm"))
    }
}
