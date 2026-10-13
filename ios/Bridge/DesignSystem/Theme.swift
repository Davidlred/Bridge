import SwiftUI
import UIKit
import CoreText

/// Mirrors tokens/tokens.json. Every color adapts to light/dark automatically.
enum Theme {
    static let page   = Color(light: 0xEFEDE7, dark: 0x050505)
    static let bg     = Color(light: 0xFBFAF7, dark: 0x0C0C0C)
    static let s0     = Color(light: 0xF3F1EB, dark: 0x000000)
    static let s1     = Color(light: 0xFFFFFF, dark: 0x151515)
    static let s2     = Color(light: 0xF5F3EE, dark: 0x1F1F1F)
    static let bubble = Color(light: 0xEAF3E2, dark: 0x262626)
    static let ink    = Color(light: 0x23262B, dark: 0xF2F2F2)
    static let ink2   = Color(light: 0x4A4E55, dark: 0xC8C8C8)
    static let mut    = Color(light: 0x7A7D83, dark: 0x8C8C8C)
    static let mut2   = Color(light: 0xA7A9AD, dark: 0x5E5E5E)
    static let line   = Color(light: 0xE3E0D8, dark: 0xFFFFFF, darkAlpha: 0.12)
    static let line2  = Color(light: 0xD6D2C8, dark: 0xFFFFFF, darkAlpha: 0.22)
    static let track  = Color(light: 0xEEEBE4, dark: 0x262626)
    static let acc    = Color(light: 0x2F3A33, dark: 0xE8643A)
    static let accInk = Color(light: 0x4F8A3A, dark: 0xF08A62)
    static let onAcc  = Color(light: 0xFFFFFF, dark: 0x1A0C06)
    static let accTint = Color(light: 0xE4F2D9, dark: 0xE8643A, darkAlpha: 0.12)
    static let accLine = Color(light: 0xA9D38F, dark: 0xE8643A, darkAlpha: 0.5)
    static let prog   = Color(light: 0x9FD17F, dark: 0xE8643A)
    static let today  = Color(light: 0xF2A97E, dark: 0x8FD17A)
    static let pill   = Color(light: 0xDDEFD0, dark: 0x3A2016)
    static let ok     = Color(light: 0x4F8A3A, dark: 0x8FD17A)
    static let okFill = Color(light: 0xBFE3A6, dark: 0x8FD17A)
    static let onOk   = Color(light: 0x23262B, dark: 0x0C0C0C)
    static let okTint = Color(light: 0xEEF7E8, dark: 0x8FD17A, darkAlpha: 0.1)
    static let bad    = Color(light: 0xD2553F, dark: 0xE5533D)
    static let badTint = Color(light: 0xFCEDE8, dark: 0xE5533D, darkAlpha: 0.1)
    static let warn   = Color(light: 0xB8741C, dark: 0xE9A23B)
    static let warnTint = Color(light: 0xFCF1E2, dark: 0xE9A23B, darkAlpha: 0.1)

    static let goalCards: [Color] = [Color(light: 0xFCE7D9, dark: 0x7E3A20), Color(light: 0xE4F2D9, dark: 0x24564F), Color(light: 0xDFECF6, dark: 0x5A4A1C)]
    static let goalInk = Color(light: 0x23262B, dark: 0xFFFFFF)
    static let goalTrack = Color(light: 0x23262B, dark: 0xFFFFFF, lightAlpha: 0.12, darkAlpha: 0.25)

    enum Priority: String, CaseIterable, Codable { case high = "High", medium = "Medium", low = "Low"
        var fg: Color { switch self {
            case .high: Color(light: 0xB9452E, dark: 0xF2937A)
            case .medium: Color(light: 0x8F6410, dark: 0xE9BC5C)
            case .low: Color(light: 0x3F7262, dark: 0x8DBFAE) } }
        var bg: Color { switch self {
            case .high: Color(light: 0xFBE3DB, dark: 0xF2937A, darkAlpha: 0.16)
            case .medium: Color(light: 0xF7EBCD, dark: 0xE9BC5C, darkAlpha: 0.15)
            case .low: Color(light: 0xDFEEE7, dark: 0x8DBFAE, darkAlpha: 0.15) } }
    }

    enum Mascot {
        static let body = Color(light: 0xF5BE98, dark: 0xE8875A)
        static let ink = Color(light: 0x23262B, dark: 0x1A0C06)
        static let cheek = Color(light: 0xE07A62, dark: 0xB83A22)
    }

    enum Radius { static let check: CGFloat = 8, pill: CGFloat = 11, input: CGFloat = 14, button: CGFloat = 16, card: CGFloat = 20, goalCard: CGFloat = 24, hero: CGFloat = 30 }
    enum Space { static let screen: CGFloat = 20, section: CGFloat = 18, card: CGFloat = 16, stack: CGFloat = 12, tight: CGFloat = 8 }
}

/// Typography. The bundled files are variable fonts (SIL OFL); each weight is an instance on the wght axis.
/// Sizes scale with Dynamic Type; display sizes stop growing at 1.3× so headings never crowd out the content.
enum BFont {
    private static let wghtAxis = 2003265652   // 'wght'
    private static func variable(_ family: String, _ weight: CGFloat, _ size: CGFloat) -> UIFont {
        let d = UIFontDescriptor(fontAttributes: [.family: family, kCTFontVariationAttribute as UIFontDescriptor.AttributeName: [wghtAxis: weight]])
        return UIFont(descriptor: d, size: size)
    }
    static func display(_ size: CGFloat) -> Font {
        Font(UIFontMetrics(forTextStyle: .title1).scaledFont(for: variable("Bricolage Grotesque", 600, size), maximumPointSize: size * 1.3))
    }
    static func body(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        let w: CGFloat = switch weight { case .semibold: 600; case .medium: 500; default: 400 }
        return Font(UIFontMetrics(forTextStyle: .body).scaledFont(for: variable("Geist", w, size)))
    }
    static func label(_ size: CGFloat = 10) -> Font {
        Font(UIFontMetrics(forTextStyle: .caption2).scaledFont(for: variable("Geist Mono", 500, size)))
    }
}

extension View {
    /// Geist Mono uppercase label with design tracking (0.13em).
    func bridgeLabel(_ size: CGFloat = 10, color: Color = Theme.mut) -> some View {
        font(BFont.label(size)).tracking(size * 0.13).textCase(.uppercase).foregroundStyle(color)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(BFont.display(16)).foregroundStyle(Theme.onAcc)
            .frame(maxWidth: .infinity, minHeight: 54)
            .background(Theme.acc, in: RoundedRectangle(cornerRadius: Theme.Radius.button, style: .continuous))
            .opacity(configuration.isPressed ? 0.85 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(BFont.body(15, .medium)).foregroundStyle(Theme.ink)
            .frame(maxWidth: .infinity, minHeight: 48)
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.input, style: .continuous).stroke(Theme.line, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

struct PriorityPill: View {
    let priority: Theme.Priority
    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(priority.fg).frame(width: 6, height: 6)
            Text(priority.rawValue).font(BFont.body(11, .semibold))
        }
        .foregroundStyle(priority.fg)
        .padding(.horizontal, 9).frame(height: 22)
        .background(priority.bg, in: Capsule())
        .accessibilityLabel("\(priority.rawValue) priority")
    }
}

extension Color {
    init(light: UInt32, dark: UInt32, lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) {
        self.init(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark, alpha: darkAlpha) : UIColor(hex: light, alpha: lightAlpha) })
    }
}
extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }
}
