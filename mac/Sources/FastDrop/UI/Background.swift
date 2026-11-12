import SwiftUI

/// Window backdrop: the system background with a few slowly drifting color glows.
struct AmbientBackground: View {
    let mood: Mood
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    /// Ambient drift is decoration: stop it while the window isn't in front.
    @Environment(\.controlActiveState) private var activeState

    var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
            if !reduceTransparency {
                TimelineView(.animation(minimumInterval: 1.0 / 10.0, paused: reduceMotion || activeState == .inactive)) { ctx in
                    blobs(t: reduceMotion ? 0 : ctx.date.timeIntervalSinceReferenceDate)
                }
                .transition(.opacity)
            }
        }
        .animation(Motion.gentle(reduceMotion), value: mood)
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }

    private func blobs(t: Double) -> some View {
        let opacity = scheme == .dark ? 0.22 : 0.12
        let c = mood.colors
        let a = c[0], b = c.count > 1 ? c[1] : c[0]
        return GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let r = max(w, h) * 0.6
            ZStack {
                blob(a, radius: r, opacity: opacity)
                    .position(x: w * (0.22 + 0.06 * sin(t * 2 * .pi / 22)),
                              y: h * (0.18 + 0.05 * cos(t * 2 * .pi / 26)))
                blob(b, radius: r * 0.9, opacity: opacity)
                    .position(x: w * (0.82 + 0.05 * cos(t * 2 * .pi / 24)),
                              y: h * (0.78 + 0.06 * sin(t * 2 * .pi / 20)))
                blob(Palette.webB, radius: r * 0.7, opacity: opacity * 0.5)
                    .position(x: w * (0.7 + 0.06 * sin(t * 2 * .pi / 30)),
                              y: h * (0.1 + 0.05 * cos(t * 2 * .pi / 18)))
            }
        }
    }

    private func blob(_ color: Color, radius: CGFloat, opacity: Double) -> some View {
        Circle()
            .fill(RadialGradient(colors: [color.opacity(opacity), color.opacity(0)],
                                 center: .center, startRadius: 0, endRadius: radius))
            .frame(width: radius * 2, height: radius * 2)
    }
}
