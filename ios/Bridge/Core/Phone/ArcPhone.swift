import Contacts
import Foundation
import UIKit
@preconcurrency import UserNotifications

/// Who to call: a name to find in the phone's contacts, or a number as typed.
enum CallTarget: Equatable, Sendable { case name(String), number(String) }

struct PhoneContact: Equatable, Hashable, Sendable { let name: String; let number: String; var label: String? = nil }

/// Something the coach did on the phone, stored with its reply (`actionJSON`).
/// type "reminder": `at` is local wall time "2026-11-07T17:00", `text` what to say, `call` who to call then.
/// type "call": `name` as the user said it. Numbers are never stored: the phone looks them up when needed.
struct CoachAction: Codable, Equatable, Sendable {
    let type: String
    var at: String? = nil
    var text: String? = nil
    var call: String? = nil
    var name: String? = nil

    var callName: String? { call ?? name }
    /// `at` as a date in the phone's time zone.
    var date: Date? { at.flatMap(ArcReminders.parse) }
}

enum ArcCommand {
    private static let notNow: Set<String> = ["at", "in", "tomorrow", "tonight", "later", "after", "before", "when", "on", "by", "every", "today", "morning", "afternoon", "evening", "o'clock"]
    private static let notAName: Set<String> = ["it", "this", "that", "them", "him", "her", "me", "us", "you", "back", "off", "out", "up", "a", "an", "the", "someone", "somebody", "anyone"]

    /// "call Sharon", "Arc, ring my mum", "hey arc call Dr Bello please", "dial 0803 000 0001" → who to call now.
    /// Anything else (a time, "call it a day", a longer request) is left for the coach. Same rules as Android (ArcPhone.kt).
    static func parseCall(_ text: String, coachName: String = "Arc") -> CallTarget? {
        let names = Set([coachName, "Arc"].filter { !$0.isEmpty }).map(NSRegularExpression.escapedPattern(for:)).joined(separator: "|")
        let pattern = #"^(?:(?:hey|hi|ok|okay)[,\s]+)?(?:(?:"# + names + #")[,:\s]+)?(?:(?:please|can you|could you|would you)[,\s]+)?(?:call|ring|phone|dial)\s+(.+?)(?:[,\s]+(?:for me|now|right now|please))*$"#
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return nil }
        var s = text.trimmingCharacters(in: .whitespacesAndNewlines)
        while let last = s.last, ".!?".contains(last) { s.removeLast() }
        s = s.trimmingCharacters(in: .whitespaces)
        guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)), m.range == NSRange(s.startIndex..., in: s),
              let r = Range(m.range(at: 1), in: s) else { return nil }
        let target = s[r].trimmingCharacters(in: .whitespaces)
        if target.allSatisfy({ $0.isNumber || "+-() ".contains($0) }), target.filter(\.isNumber).count >= 3 {
            return .number(String(target.filter { $0.isNumber || $0 == "+" }))
        }
        let words = target.split(whereSeparator: \.isWhitespace).map { $0.lowercased() }
        guard words.count <= 4, !words.contains(where: notNow.contains), let first = words.first, !notAName.contains(first) else { return nil }
        return .name(target)
    }

    /// Lower case, no accents or punctuation, single spaces: "Dr. Bélló" → "dr bello".
    static func norm(_ s: String) -> String {
        let folded = s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased()
        let cleaned = String(folded.map { $0.isLetter || $0.isNumber || $0.isWhitespace ? $0 : " " })
        return cleaned.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    /// The best matches for `query` ("my mum" also finds "Mum"): the exact name, then the first name, then any whole word,
    /// then a name that starts with it. Only the best tier comes back; the same number saved twice counts once.
    static func rank(_ query: String, _ contacts: [PhoneContact]) -> [PhoneContact] {
        var q = norm(query)
        if q.hasPrefix("my ") { q = String(q.dropFirst(3)) }
        guard !q.isEmpty else { return [] }
        func score(_ c: PhoneContact) -> Int {
            let n = norm(c.name), words = n.split(separator: " ").map(String.init)
            if n == q { return 4 }
            if words.first == q { return 3 }
            if " \(n) ".contains(" \(q) ") { return 2 }
            if n.hasPrefix(q) || words.contains(where: { $0.hasPrefix(q) }) { return 1 }
            return 0
        }
        let scored = contacts.map { ($0, score($0)) }.filter { $0.1 > 0 }
        guard let best = scored.map(\.1).max() else { return [] }
        var seen = Set<String>()
        return scored.filter { $0.1 == best }.map(\.0).filter { seen.insert(String($0.number.filter(\.isNumber).suffix(9))).inserted }
    }
}

/// The phone's contacts, read only after the user allows it. Nothing read here leaves the device.
enum PhoneContacts {
    static var allowed: Bool {
        let s = CNContactStore.authorizationStatus(for: .contacts)
        if s == .authorized { return true }
        if #available(iOS 18.0, *), s == .limited { return true }
        return false
    }
    static var denied: Bool { let s = CNContactStore.authorizationStatus(for: .contacts); return s == .denied || s == .restricted }

    static func requestAccess() async -> Bool { (try? await CNContactStore().requestAccess(for: .contacts)) ?? false }

    static func find(_ name: String) async -> [PhoneContact] {
        guard allowed else { return [] }
        return await Task.detached {
            let store = CNContactStore()
            let keys = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName), CNContactPhoneNumbersKey as CNKeyDescriptor]
            var q = ArcCommand.norm(name); if q.hasPrefix("my ") { q = String(q.dropFirst(3)) }
            let first = q.split(separator: " ").first.map(String.init) ?? q
            guard !first.isEmpty else { return [] }
            let found = (try? store.unifiedContacts(matching: CNContact.predicateForContacts(matchingName: first), keysToFetch: keys)) ?? []
            let all = found.flatMap { c -> [PhoneContact] in
                let n = CNContactFormatter.string(from: c, style: .fullName) ?? ""
                return c.phoneNumbers.map { p in PhoneContact(name: n, number: p.value.stringValue, label: p.label.map { CNLabeledValue<NSString>.localizedString(forLabel: $0) }) }
            }
            return ArcCommand.rank(name, all)
        }.value
    }

    /// iOS always asks "Call …?" itself before dialling; an app can't skip that.
    @MainActor static func call(_ number: String) {
        let digits = number.filter { $0.isNumber || $0 == "+" }
        if let url = URL(string: "tel://\(digits)") { UIApplication.shared.open(url) }
    }
}

/// A call Arc was asked to make, from the chat, a reminder's "Call" button or the coach. The call sheet picks it up.
@MainActor @Observable final class ArcCalls {
    static let shared = ArcCalls()
    var request: CallTarget?
    func call(_ t: CallTarget) { request = t }
}

/// Reminders and alarms set by asking Arc: local notifications with the reminder's id "arc.<reply id>", so they ring
/// offline and survive restarts. A reminder about calling someone has a "Call" button. They live on this phone only
/// (the coach reply that set them syncs, so another phone can set the same one with a tap).
enum ArcReminders {
    static let prefix = "arc."
    static let category = "ARC_REMINDER", callCategory = "ARC_CALL"
    static let callAction = "ARC_CALL", snoozeAction = "ARC_SNOOZE"
    static let snoozeMinutes = 10

    static func parse(_ at: String) -> Date? {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = .current; f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f.date(from: at)
    }
    static func format(_ d: Date) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = .current; f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f.string(from: d)
    }

    /// The Call and Snooze buttons. Registered once at launch.
    static func registerCategories() {
        let snooze = UNNotificationAction(identifier: snoozeAction, title: String(localized: "Snooze 10 min"))
        let call = UNNotificationAction(identifier: callAction, title: String(localized: "Call"), options: [.foreground])
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: category, actions: [snooze], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: callCategory, actions: [call, snooze], intentIdentifiers: [], options: []),
        ])
    }

    /// Asks for notifications if needed (the full prompt: the user just asked for a reminder), then schedules it.
    @discardableResult
    static func set(id: UUID, at: String, text: String, call: String?) async -> Bool {
        guard let date = parse(at), date > .now else { return false }
        let center = UNUserNotificationCenter.current()
        if await center.notificationSettings().authorizationStatus != .authorized { _ = try? await center.requestAuthorization(options: [.alert, .sound]) }
        let content = UNMutableNotificationContent()
        content.title = text
        content.body = String(localized: "Reminder")
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        content.categoryIdentifier = call == nil ? category : callCategory
        content.userInfo = ["id": id.uuidString, "text": text, "call": call ?? ""]
        let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        let request = UNNotificationRequest(identifier: prefix + id.uuidString, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
        do { try await center.add(request) } catch { return false }
        await MainActor.run { NotificationCenter.default.post(name: .arcRemindersChanged, object: nil) }
        return true
    }

    static func cancel(id: UUID) async {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [prefix + id.uuidString])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [prefix + id.uuidString])
        await MainActor.run { NotificationCenter.default.post(name: .arcRemindersChanged, object: nil) }
    }

    /// Reply ids with a reminder still waiting on this phone.
    static func pending() async -> Set<UUID> {
        let ids = await UNUserNotificationCenter.current().pendingNotificationRequests().map(\.identifier)
        return Set(ids.filter { $0.hasPrefix(prefix) }.compactMap { UUID(uuidString: String($0.dropFirst(prefix.count))) })
    }

    static func allowed() async -> Bool {
        let s = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return s == .authorized || s == .provisional || s == .ephemeral
    }
}

extension Notification.Name { static let arcRemindersChanged = Notification.Name("arcRemindersChanged") }

/// Shows Arc's reminders while the app is open, and handles their Call and Snooze buttons.
final class ArcNotificationDelegate: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = ArcNotificationDelegate()

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard response.notification.request.identifier.hasPrefix(ArcReminders.prefix),
              let id = (info["id"] as? String).flatMap(UUID.init(uuidString:)) else { return }
        let text = info["text"] as? String ?? ""
        let call = (info["call"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        switch response.actionIdentifier {
        case ArcReminders.snoozeAction:
            let at = ArcReminders.format(Date.now.addingTimeInterval(TimeInterval(ArcReminders.snoozeMinutes * 60)))
            await ArcReminders.set(id: id, at: at, text: text, call: call)
        case ArcReminders.callAction:
            if let call { await MainActor.run { ArcCalls.shared.call(.name(call)) } }
        default:
            // Tapping a reminder about a call goes straight to calling.
            if let call { await MainActor.run { ArcCalls.shared.call(.name(call)) } }
        }
    }
}
