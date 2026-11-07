import SwiftUI

/// Units a task can ask for after ticking.
enum Measure {
    static let all = ["reps", "kg", "min", "km", "pages"]

    static func label(_ m: String?) -> LocalizedStringKey {
        switch m {
        case "reps": "Reps"
        case "kg": "Weight (kg)"
        case "min": "Minutes"
        case "km": "Distance (km)"
        case "pages": "Pages"
        default: "Nothing"
        }
    }

    /// "12 reps", "42.5 kg".
    static func amount(_ value: Double, _ m: String?) -> String {
        let n = value.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(value)) : String(value)
        switch m {
        case "reps": return String(localized: "\(n) reps")
        case "kg": return String(localized: "\(n) kg")
        case "min": return String(localized: "\(n) min")
        case "km": return String(localized: "\(n) km")
        case "pages": return String(localized: "\(n) pages")
        default: return n
        }
    }
}

/// "Very hard" … "Great".
func feelWord(_ feel: Int) -> String {
    switch min(5, max(1, feel)) {
    case 1: String(localized: "Very hard")
    case 2: String(localized: "Hard")
    case 3: String(localized: "Okay")
    case 4: String(localized: "Good")
    default: String(localized: "Great")
    }
}

/// After ticking: how it felt, how many (in the task's unit) and a note. Everything is optional; Skip saves nothing.
struct CheckInSheet: View {
    let task: PlanTask
    let existing: Checkin?
    let lead: LeadInfo?
    var onSave: (Checkin) -> Void
    var onSkip: () -> Void
    @State private var feel: Int?
    @State private var amount: String
    @State private var note: String

    init(task: PlanTask, existing: Checkin?, lead: LeadInfo?, onSave: @escaping (Checkin) -> Void, onSkip: @escaping () -> Void) {
        self.task = task; self.existing = existing; self.lead = lead; self.onSave = onSave; self.onSkip = onSkip
        _feel = State(initialValue: existing?.feel)
        _amount = State(initialValue: existing?.amount.map { $0.truncatingRemainder(dividingBy: 1) == 0 ? String(Int($0)) : String($0) } ?? "")
        _note = State(initialValue: existing?.note ?? "")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("How did it go?").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    Text(task.title).font(BFont.body(14)).foregroundStyle(Theme.mut)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("HOW IT FELT").bridgeLabel(11)
                    FlowLayout(spacing: 6) {
                        ForEach(1...5, id: \.self) { n in
                            ChoiceChip(title: feelWord(n), on: feel == n) { feel = feel == n ? nil : n }
                        }
                    }
                }
                if let m = task.measure {
                    BridgeField(label: Measure.label(m), placeholder: "0", text: Binding(get: { amount }, set: { v in
                        amount = String(v.filter { $0.isNumber || $0 == "." || $0 == "," }.replacingOccurrences(of: ",", with: ".").prefix(7))
                    }), keyboard: .decimalPad)
                }
                VStack(alignment: .leading, spacing: 7) {
                    Text("NOTE").bridgeLabel(11)
                    BigTextArea(text: $note, placeholder: "Anything to add? Optional", minHeight: 76, limit: 200)
                }
                // Who sees it, said plainly before saving.
                Label { Text(whoSees).font(BFont.body(12)) } icon: { Image(systemName: lead.map { $0.sees && !$0.isMe } == true ? "eye" : "lock.fill") }
                    .foregroundStyle(Theme.mut)
                Button("Save check-in") { onSave(Checkin(feel: feel, amount: Double(amount).map { min(99_999, max(0, $0)) }, note: note.isEmpty ? nil : note)) }
                    .buttonStyle(PrimaryButtonStyle())
                Button("Skip", action: onSkip).buttonStyle(SecondaryButtonStyle())
            }
            .padding(.horizontal, 20).padding(.vertical, 24)
        }
    }

    private var whoSees: String {
        guard let lead, !lead.isMe else { return String(localized: "Only you see this.") }
        return lead.sees ? String(localized: "\(lead.name) sees this check-in.") : String(localized: "Only you see this. Sharing with \(lead.name) is off.")
    }
}

/// The unit asked for after ticking, picked on the task.
struct MeasurePicker: View {
    @Binding var value: String?
    private static let options: [String?] = [nil, "reps", "kg", "min", "km", "pages"]
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("LOG AFTER TICKING").bridgeLabel(11)
            FlowLayout(spacing: 6) {
                ForEach(Self.options, id: \.self) { m in
                    ChoiceChip(key: Measure.label(m), on: value == m) { value = m }
                }
            }
            Text("Asked when this task is ticked, with how it felt and a note.").font(BFont.body(12)).foregroundStyle(Theme.mut)
        }
    }
}

/// Today's check-in, shown in the task's details.
struct CheckinSummary: View {
    let checkin: Checkin
    let measure: String?
    var body: some View {
        let parts = [checkin.feel.map(feelWord), checkin.amount.map { Measure.amount($0, measure) }].compactMap { $0 }
        VStack(alignment: .leading, spacing: 4) {
            if !parts.isEmpty { Text(parts.joined(separator: " · ")).font(BFont.body(13, .medium)).foregroundStyle(Theme.ink) }
            if let n = checkin.note { Text(n).font(BFont.body(13)).foregroundStyle(Theme.ink2) }
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.s0, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}

/// A choice chip: 36pt inside a 44pt touch target.
struct ChoiceChip: View {
    private let text: Text
    let on: Bool
    var action: () -> Void
    init(title: String, on: Bool, action: @escaping () -> Void) { text = Text(title); self.on = on; self.action = action }
    init(key: LocalizedStringKey, on: Bool, action: @escaping () -> Void) { text = Text(key); self.on = on; self.action = action }

    var body: some View {
        Button(action: action) {
            text.font(BFont.body(13, .medium)).foregroundStyle(on ? Theme.ink : Theme.ink2).lineLimit(1)
                .padding(.horizontal, 14).frame(minHeight: 36)
                .background(on ? Theme.segOn : .clear, in: Capsule())
                .overlay(Capsule().strokeBorder(on ? Theme.ink2 : Theme.line, lineWidth: on ? 1.5 : 1))
                .frame(minHeight: 44)
        }
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}
