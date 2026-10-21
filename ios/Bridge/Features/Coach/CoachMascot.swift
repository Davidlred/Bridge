import SwiftUI

enum MascotMood: Equatable { case idle, listening, thinking, talking, happy }

/// The coach character. Drawn in a 120×120 unit space and scaled, so it is crisp at any size.
/// Motion is computed from a single clock (TimelineView), so state changes never restart or stack animations.
struct CoachMascot: View {
    var mood: MascotMood
    var size: CGFloat = 112

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var epoch = Date()
    @State private var moodStart = Date()
    @State private var fromLook = CGPoint.zero

    private static func look(_ m: MascotMood) -> CGPoint {
        switch m { case .listening: CGPoint(x: 0, y: -4); case .thinking: CGPoint(x: 7, y: -6); default: .zero }
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: reduceMotion)) { tl in
            Canvas { ctx, sz in
                let t = reduceMotion ? 0 : tl.date.timeIntervalSince(epoch)
                let sinceMood = reduceMotion ? 10 : tl.date.timeIntervalSince(moodStart)
                draw(&ctx, size: sz, t: t, sinceMood: sinceMood)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
        .onChange(of: mood) { old, _ in
            fromLook = Self.look(old)
            moodStart = Date()
        }
    }

    private func draw(_ ctx: inout GraphicsContext, size sz: CGSize, t: Double, sinceMood: Double) {
        ctx.scaleBy(x: sz.width / 120, y: sz.height / 120)
        let tau = Double.pi * 2
        ctx.fill(Path(ellipseIn: CGRect(x: 30, y: 109, width: 60, height: 8)), with: .color(Theme.track))

        // Body motion
        let period = mood == .talking ? 1.1 : 3.2
        let b = (mood == .thinking || mood == .happy) ? 0 : 0.5 - 0.5 * cos(tau * t / period)
        var hop = 0.0
        if mood == .happy, sinceMood < 1.1 { hop = -14 * sin(.pi * (sinceMood / 0.55).truncatingRemainder(dividingBy: 1)) }
        let tilt = mood == .thinking ? 5 * sin(tau * t / 1.6) : 0

        var body = ctx
        body.translateBy(x: 0, y: -3 * b + hop)
        body.translateBy(x: 60, y: 110)
        body.rotate(by: .degrees(tilt))
        body.scaleBy(x: 1 + 0.02 * b, y: 1 - 0.02 * b)
        body.translateBy(x: -60, y: -110)

        var antenna = Path()
        antenna.move(to: CGPoint(x: 45, y: 40))
        antenna.addQuadCurve(to: CGPoint(x: 75, y: 40), control: CGPoint(x: 60, y: 10))
        body.stroke(antenna, with: .color(Theme.Mascot.ink), style: StrokeStyle(lineWidth: 5, lineCap: .round))
        body.fill(Path(roundedRect: CGRect(x: 18, y: 36, width: 84, height: 74), cornerRadius: 34, style: .continuous), with: .color(Theme.Mascot.body))
        for cx in [35.0, 85.0] {
            body.fill(Path(ellipseIn: CGRect(x: cx - 6, y: 71, width: 12, height: 12)), with: .color(Theme.Mascot.cheek.opacity(0.35)))
        }

        // Face follows gaze with an eased transition from the previous mood
        let k = min(1, sinceMood / 0.35), e = 1 - pow(1 - k, 3)
        let to = Self.look(mood)
        var face = body
        face.translateBy(x: fromLook.x + (to.x - fromLook.x) * e, y: fromLook.y + (to.y - fromLook.y) * e)
        let ink = GraphicsContext.Shading.color(Theme.Mascot.ink)
        let round = StrokeStyle(lineWidth: 3, lineCap: .round)

        // Eyes
        let phase = (t / 4.2).truncatingRemainder(dividingBy: 1)
        let blink = (0.92...0.98).contains(phase) ? 0.1 : 1.0
        for cx in [46.0, 74.0] {
            if mood == .happy {
                var p = Path(); p.move(to: CGPoint(x: cx - 6, y: 64)); p.addQuadCurve(to: CGPoint(x: cx + 6, y: 64), control: CGPoint(x: cx, y: 55))
                face.stroke(p, with: ink, style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
            } else {
                var eye = face
                eye.translateBy(x: 0, y: 62); eye.scaleBy(x: 1, y: blink); eye.translateBy(x: 0, y: -62)
                eye.fill(Path(ellipseIn: CGRect(x: cx - 5, y: 55.5, width: 10, height: 13)), with: ink)
            }
        }

        // Mouth
        switch mood {
        case .talking:
            let m = 0.3 + 0.7 * (0.5 - 0.5 * cos(tau * t / 0.24))
            var mouth = face
            mouth.translateBy(x: 0, y: 80); mouth.scaleBy(x: 1, y: m); mouth.translateBy(x: 0, y: -80)
            mouth.fill(Path(ellipseIn: CGRect(x: 53.5, y: 74.5, width: 13, height: 11)), with: ink)
        case .thinking:
            var p = Path(); p.move(to: CGPoint(x: 54, y: 81)); p.addLine(to: CGPoint(x: 66, y: 79))
            face.stroke(p, with: ink, style: round)
        default:
            let (a, c, z): (CGPoint, CGPoint, CGPoint) = switch mood {
                case .happy: (CGPoint(x: 49, y: 76), CGPoint(x: 60, y: 89), CGPoint(x: 71, y: 76))
                case .listening: (CGPoint(x: 55, y: 79), CGPoint(x: 60, y: 82), CGPoint(x: 65, y: 79))
                default: (CGPoint(x: 53, y: 78), CGPoint(x: 60, y: 84), CGPoint(x: 67, y: 78))
            }
            var p = Path(); p.move(to: a); p.addQuadCurve(to: z, control: c)
            face.stroke(p, with: ink, style: round)
        }
    }
}

/// Header used at the top of the Coach screen.
struct CoachHeader: View {
    let name: String
    let mood: MascotMood

    private var status: (String, Color) {
        switch mood {
        case .idle: ("Here when you need me", Theme.mut2)
        case .listening: ("Listening…", Theme.acc)
        case .thinking: ("Thinking…", Theme.warn)
        case .talking: ("Talking", Theme.ok)
        case .happy: ("Added. Nice one.", Theme.ok)
        }
    }

    var body: some View {
        VStack(spacing: 4) {
            CoachMascot(mood: mood).padding(.top, 6)
            Text(name).font(BFont.display(21)).foregroundStyle(Theme.ink)
            HStack(spacing: 6) {
                Circle().fill(status.1).frame(width: 6, height: 6)
                Text(status.0).font(BFont.body(12, .medium)).foregroundStyle(Theme.mut)
            }
            .animation(.easeOut(duration: 0.3), value: mood)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(name), \(status.0)")
        }
        .frame(maxWidth: .infinity)
        .padding(.bottom, 14)
    }
}

#Preview {
    VStack(spacing: 24) {
        ForEach([MascotMood.idle, .listening, .thinking, .talking, .happy], id: \.self) { CoachMascot(mood: $0, size: 80) }
    }
    .padding().background(Theme.bg)
}
