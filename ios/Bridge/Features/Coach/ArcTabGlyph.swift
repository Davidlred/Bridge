import SwiftUI
import UIKit

/// Arc, the coach, as the Coach tab icon: the mascot's body and arc antenna in one tint, in a 24-point box.
/// It bobs, blinks, and bubbles rise from the antenna; still, with no bubbles, when motion is reduced.
/// A tab bar item only takes an image, so the loop is pre-drawn as template images and the tab steps through them.
/// Same frame maths as Android (ArcTabIcon.kt).
@MainActor enum ArcTabGlyph {
    static let loop: Double = 3.6
    static let fps: Double = 20
    static let frames: [UIImage] = (0..<Int(loop * fps)).map { image(at: Double($0) / fps) }
    static let still: UIImage = image(at: 0, still: true)

    struct Bubble { var x, y, r, alpha: Double }
    struct Frame { var lift = 0.0, squash = 0.0, blink = 1.0, bubbles: [Bubble] = [] }

    static func frame(at t: Double, still: Bool = false) -> Frame {
        if still { return Frame() }
        let tau = Double.pi * 2
        let s = t.truncatingRemainder(dividingBy: loop)
        let b = 0.5 - 0.5 * cos(tau * s / 1.8)
        let blink = (0.92...0.97).contains(s / loop) ? 0.15 : 1
        // Two bubbles, half a cycle apart: each rises from the antenna, drifts, grows and fades.
        let bubbles = (0...1).map { i -> Bubble in
            let p = (s / 1.8 + Double(i) * 0.5).truncatingRemainder(dividingBy: 1)
            let alpha = min(1, p / 0.15) * min(1, (1 - p) / 0.4)
            return Bubble(x: 12 + (i == 0 ? -0.9 : 0.9) + 0.7 * sin(tau * p + Double(i)), y: 5 - 4.4 * p - b, r: 0.6 + 0.6 * p, alpha: alpha)
        }
        return Frame(lift: -b, squash: 0.03 * b, blink: blink, bubbles: bubbles)
    }

    static func image(at t: Double, still: Bool = false, size: CGFloat = 24) -> UIImage {
        let f = frame(at: t, still: still)
        let img = UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { r in
            let g = r.cgContext
            g.scaleBy(x: size / 24, y: size / 24)
            UIColor.black.setFill()
            for b in f.bubbles {
                g.setAlpha(b.alpha)
                g.fillEllipse(in: CGRect(x: b.x - b.r, y: b.y - b.r, width: b.r * 2, height: b.r * 2))
            }
            g.setAlpha(1)
            g.translateBy(x: 12, y: 21.5 + f.lift)
            g.scaleBy(x: 1 + f.squash, y: 1 - f.squash)
            g.translateBy(x: -12, y: -21.5)

            let antenna = UIBezierPath()
            antenna.move(to: CGPoint(x: 9, y: 9.5))
            antenna.addQuadCurve(to: CGPoint(x: 15, y: 9.5), controlPoint: CGPoint(x: 12, y: 3))
            antenna.lineWidth = 1.8; antenna.lineCapStyle = .round
            UIColor.black.setStroke(); antenna.stroke()

            // Body with the eyes and smile cut out (even-odd fill), so the icon stays one tint.
            let body = UIBezierPath(roundedRect: CGRect(x: 3, y: 9, width: 18, height: 12.5), cornerRadius: 6)
            let h = 2.8 * f.blink
            body.append(UIBezierPath(ovalIn: CGRect(x: 7.9, y: 14 - h / 2, width: 2.2, height: h)))
            body.append(UIBezierPath(ovalIn: CGRect(x: 13.9, y: 14 - h / 2, width: 2.2, height: h)))
            let smile = UIBezierPath()
            smile.move(to: CGPoint(x: 10.4, y: 17))
            smile.addQuadCurve(to: CGPoint(x: 13.6, y: 17), controlPoint: CGPoint(x: 12, y: 19))
            smile.addQuadCurve(to: CGPoint(x: 10.4, y: 17), controlPoint: CGPoint(x: 12, y: 17.6))
            smile.close()
            body.append(smile)
            body.usesEvenOddFillRule = true
            body.fill()
        }
        return img.withRenderingMode(.alwaysTemplate)
    }
}

/// Steps the Coach tab's Arc through its loop while the app is in front. Kept apart from the tab view
/// so only the icon index changes each tick.
@MainActor @Observable final class ArcTabClock {
    var index = 0
    var image: UIImage { ArcTabGlyph.frames[index] }

    func run() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(1 / ArcTabGlyph.fps))
            index = (index + 1) % ArcTabGlyph.frames.count
        }
    }
}
