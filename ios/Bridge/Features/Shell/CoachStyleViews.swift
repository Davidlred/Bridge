import SwiftUI
import UIKit

/// Light, dark or the phone's setting (the default). Kept on the device; it survives logging out.
enum Appearance {
    static let modes = ["system", "light", "dark"]
    static func title(_ mode: String) -> String {
        switch mode { case "light": String(localized: "Light"); case "dark": String(localized: "Dark"); default: String(localized: "Match phone") }
    }
    @MainActor static func apply(_ mode: String) {
        let style: UIUserInterfaceStyle = mode == "light" ? .light : mode == "dark" ? .dark : .unspecified
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes { scene.windows.forEach { $0.overrideUserInterfaceStyle = style } }
    }
}

struct AppearanceSheet: View {
    let current: String
    var onPick: (String) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Appearance").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            ForEach(Appearance.modes, id: \.self) { mode in
                let on = mode == current
                Button { onPick(mode) } label: {
                    HStack {
                        Text(Appearance.title(mode)).font(BFont.body(15, .semibold)).foregroundStyle(Theme.ink)
                        Spacer()
                        if on { Image(systemName: "checkmark").foregroundStyle(Theme.ink).font(.system(size: 17, weight: .semibold)) }
                    }
                    .padding(.horizontal, 16).frame(maxWidth: .infinity, minHeight: 56)
                    .background(on ? Theme.accTint : Theme.s1, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? Theme.line2 : Theme.line, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20).padding(.top, 24).padding(.bottom, 24)
    }
}

/// How the coach talks. Balanced is free; the rest come with Plus (the server checks too).
enum CoachStyle: String, CaseIterable, Identifiable {
    case balanced, strict, playful, efficient
    var id: String { rawValue }
    var plus: Bool { self != .balanced }
    var label: LocalizedStringKey {
        switch self { case .balanced: "Balanced"; case .strict: "Strict"; case .playful: "Playful"; case .efficient: "Efficient" }
    }
    var description: LocalizedStringKey {
        switch self {
        case .balanced: "Warm and direct. The default."
        case .strict: "High standards. Holds you to your plan and calls out excuses."
        case .playful: "Light and funny, and still practical."
        case .efficient: "Short and to the point. No small talk."
        }
    }
    /// The same name as plain text (settings rows take a String).
    var title: String {
        switch self { case .balanced: String(localized: "Balanced"); case .strict: String(localized: "Strict"); case .playful: String(localized: "Playful"); case .efficient: String(localized: "Efficient") }
    }
    static func named(_ raw: String?) -> CoachStyle { CoachStyle(rawValue: raw ?? "") ?? .balanced }
}

struct CoachStyleSheet: View {
    let current: CoachStyle
    let hasPlus: Bool
    var onPick: (CoachStyle) -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Coaching style").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("How your coach talks to you. Every style keeps you on track and never shames a missed day.").font(BFont.body(14)).foregroundStyle(Theme.ink2)
                ForEach(CoachStyle.allCases) { style in
                    let on = style == current
                    Button { onPick(style) } label: {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(style.label).font(BFont.body(15, .semibold)).foregroundStyle(Theme.ink)
                                Text(style.description).font(BFont.body(13)).foregroundStyle(Theme.mut).multilineTextAlignment(.leading)
                            }
                            Spacer()
                            if style.plus && !hasPlus {
                                Text("PLUS").bridgeLabel(10, color: Theme.accInk).padding(.horizontal, 8).padding(.vertical, 4).background(Theme.accTint, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                            } else if on {
                                Image(systemName: "checkmark").foregroundStyle(Theme.ink).font(.system(size: 17, weight: .semibold))
                            }
                        }
                        .padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                        .background(on ? Theme.accTint : Theme.s1, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(on ? Theme.line2 : Theme.line, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? [.isSelected] : [])
                    .accessibilityHint(style.plus && !hasPlus ? Text("Part of Plus") : Text(""))
                }
            }
            .padding(.horizontal, 20).padding(.top, 24).padding(.bottom, 24)
        }
    }
}
