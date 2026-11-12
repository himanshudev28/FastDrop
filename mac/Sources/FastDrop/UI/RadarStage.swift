import FastDropCore
import SwiftUI

/// AirDrop-style radar: This Mac in the middle, nearby devices on an elliptical orbit.
struct RadarStage: View {
    let mood: Mood
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Idle radar motion pauses while the window is in the background (keeps CPU near zero),
    /// but keeps running during a transfer, where it shows activity.
    @Environment(\.controlActiveState) private var activeState
    private var animate: Bool { !reduceMotion && (activeState != .inactive || model.isBusy) }

    var body: some View {
        GeometryReader { geo in
            stage(size: geo.size)
        }
    }

    private func position(index i: Int, count n: Int, center: CGPoint, rx: CGFloat, ry: CGFloat) -> CGPoint {
        let degrees: Double = -90.0 + 30.0 + 360.0 * Double(i) / Double(max(n, 1))
        let angle = degrees * Double.pi / 180
        return CGPoint(x: center.x + rx * CGFloat(cos(angle)), y: center.y + ry * CGFloat(sin(angle)))
    }

    private func stage(size: CGSize) -> some View {
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let rx = min(size.width * 0.36, size.height * 0.52)
        let ry = size.height * 0.34
        let devices = model.devices
        let scale: CGFloat = devices.count > 8 ? 0.82 : 1
        let orbY = center.y + (devices.isEmpty ? 56 : 28)

        return ZStack {
            rings(center: center, rx: rx, ry: ry)
            ForEach(Array(devices.enumerated()), id: \.element.id) { i, peer in
                let p = position(index: i, count: devices.count, center: center, rx: rx, ry: ry)
                // .position makes the row fill the stage, so anchor the pop-in scale at the bubble itself.
                let anchor = UnitPoint(x: p.x / max(size.width, 1), y: p.y / max(size.height, 1))
                DeviceBubble(peer: peer, scale: scale)
                    .position(p)
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.3, anchor: anchor).combined(with: .opacity))
            }
            orbAndEmpty.position(x: center.x, y: orbY)
        }
        .animation(Motion.gentle(reduceMotion), value: model.devices.map(\.id))
    }

    // MARK: Rings

    private func rings(center: CGPoint, rx: CGFloat, ry: CGFloat) -> some View {
        let colors = mood.colors
        let animate = self.animate
        return TimelineView(.animation(minimumInterval: 1.0 / 24.0, paused: !animate)) { ctx in
            let t = reduceMotion ? 0 : ctx.date.timeIntervalSinceReferenceDate
            Canvas { gc, size in
                let c = CGPoint(x: size.width / 2, y: size.height / 2)
                func ellipse(_ f: CGFloat) -> Path {
                    Path(ellipseIn: CGRect(x: c.x - rx * f, y: c.y - ry * f, width: rx * 2 * f, height: ry * 2 * f))
                }
                for f in [0.5, 0.78, 1.06] as [CGFloat] {
                    gc.stroke(ellipse(f), with: .color(colors[0].opacity(0.16)), lineWidth: 1)
                }
                guard !reduceMotion else { return }
                // Pulse waves
                for k in 0..<3 {
                    let p = (t / 3 + Double(k) / 3).truncatingRemainder(dividingBy: 1)
                    let f = 0.15 + p * 1.0
                    gc.stroke(ellipse(f), with: .color(colors[colors.count > 1 ? k % 2 : 0].opacity(0.32 * (1 - p))),
                              lineWidth: 1.5)
                }
                // Rotating sweep
                let rot = Angle.degrees(t / 6 * 360)
                var wedge = Path()
                wedge.move(to: c)
                wedge.addArc(center: c, radius: max(rx, ry) * 1.1, startAngle: .degrees(-50), endAngle: .degrees(0),
                             clockwise: false)
                wedge.closeSubpath()
                var sweep = gc
                sweep.translateBy(x: c.x, y: c.y)
                sweep.scaleBy(x: 1, y: ry / max(rx, 1))
                sweep.rotate(by: rot)
                sweep.translateBy(x: -c.x, y: -c.y)
                sweep.fill(wedge, with: .conicGradient(
                    Gradient(colors: [colors[0].opacity(0), colors[0].opacity(0.22)]),
                    center: c, angle: .degrees(-50)))
            }
            .allowsHitTesting(false)
        }
        .accessibilityHidden(true)
    }

    // MARK: Center orb

    private var orbAndEmpty: some View {
        VStack(spacing: 8) {
            Button { model.showQR = true } label: {
                VStack(spacing: 6) {
                    BreathingOrb(mood: mood, breathe: !model.isBusy && animate)
                    Text(model.deviceName).font(.headline).lineLimit(1)
                    Text(model.lanIPs.first ?? "Not on a network")
                        .font(.caption).monospacedDigit().foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Show my QR code")
            .accessibilityLabel("This Mac, \(model.deviceName)")
            .accessibilityHint("Shows your QR code")

            if model.devices.isEmpty {
                VStack(spacing: 6) {
                    Text("Looking for nearby devices…").font(.subheadline).foregroundStyle(.secondary)
                    Text("Open FastDrop on your phone or TV on the same Wi‑Fi.")
                        .font(.footnote).foregroundStyle(.tertiary).multilineTextAlignment(.center)
                    HStack {
                        Button("Scan Network") { model.scanNetwork() }.disabled(model.scanning)
                        Button("Add by IP") { model.showAddDevice = true }
                    }
                    .buttonStyle(.bordered).controlSize(.small)
                }
                .padding(.top, 4)
                .transition(reduceMotion ? .opacity : .scale(scale: 0.9).combined(with: .opacity))
            }
        }
        .animation(Motion.pop(reduceMotion), value: model.devices.isEmpty)
    }
}

/// Breathing is computed from the clock, not a `repeatForever` animation: a repeating animation
/// started in onAppear also captures the view's first layout move and swings the orb between the
/// window corner and its real position forever.
private struct BreathingOrb: View {
    let mood: Mood
    let breathe: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 15.0, paused: !breathe)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            let s = breathe ? 1 + 0.015 * (1 + sin(t * 2 * .pi / 4.8)) : 1
            ZStack {
                Circle()
                    .fill(LinearGradient(colors: mood.colors, startPoint: .topLeading, endPoint: .bottomTrailing))
                Image(systemName: "laptopcomputer")
                    .font(.system(size: 36, weight: .medium))
                    .foregroundStyle(.white)
            }
            .frame(width: 92, height: 92)
            .shadow(color: mood.colors[0].opacity(0.45), radius: 18, y: 6)
            .scaleEffect(s)
        }
        .frame(width: 92 * 1.03, height: 92 * 1.03)
    }
}

// MARK: - Device bubble

private struct DeviceBubble: View {
    let peer: Peer
    let scale: CGFloat
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hovering = false
    @State private var targeted = false

    private var isLive: Bool { model.live?.peerID == peer.id && model.live?.state == .running }
    private var isPending: Bool { model.pending?.peer.id == peer.id }

    var body: some View {
        Button { model.tap(peer) } label: {
            VStack(spacing: 6) {
                avatar
                Text(targeted ? "Drop to send" : peer.name)
                    .font(.callout.weight(.semibold)).lineLimit(1).truncationMode(.tail)
                    .frame(maxWidth: 120)
                Text(peer.ip).font(.caption2.monospaced()).foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .scaleEffect((targeted ? 1.18 : (hovering ? 1.07 : 1)) * scale)
        .animation(Motion.snappy(reduceMotion), value: hovering)
        .animation(Motion.bouncy(reduceMotion), value: targeted)
        .onHover { hovering = $0 }
        .dropDestination(for: URL.self) { urls, _ in
            return model.send(to: peer, urls: urls)
        } isTargeted: { targeted = $0 }
        .contextMenu {
            Button("Send Files…") { model.chooseFiles(thenSendTo: peer) }
            Button("Copy IP Address") { model.copyIP(peer) }
            if peer.manual { Button("Forget Device") { model.forget(peer) } }
        }
        .help("Send to \(peer.name)")
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Send to \(peer.name)")
        .accessibilityValue("\(DeviceGlyph.label(forType: peer.type)), \(peer.ip)")
        .accessibilityHint("Drop files here or click to choose files")
        .accessibilityAddTraits(.isButton)
    }

    private var avatar: some View {
        ZStack {
            Circle().fill(Palette.gradient(forDeviceType: peer.type))
            Image(systemName: DeviceGlyph.symbol(forType: peer.type))
                .font(.system(size: 26, weight: .medium)).foregroundStyle(.white)
            if targeted {
                Circle().strokeBorder(.white, lineWidth: 3)
            }
            if isLive, let live = model.live {
                Circle().trim(from: 0, to: live.fraction)
                    .stroke(.white, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .rotationEffect(.degrees(-90)).padding(-6)
                    .animation(Motion.gentle(reduceMotion), value: live.fraction)
            } else if isPending {
                PendingRing(reduceMotion: reduceMotion)
            }
        }
        .frame(width: 64, height: 64)
        .shadow(color: .black.opacity(hovering || targeted ? 0.35 : 0.18), radius: hovering || targeted ? 14 : 6, y: 4)
    }
}

private struct PendingRing: View {
    let reduceMotion: Bool

    var body: some View {
        if reduceMotion {
            Circle().stroke(.white.opacity(0.9), style: StrokeStyle(lineWidth: 3, dash: [4, 5])).padding(-6)
        } else {
            TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { ctx in
                let t = ctx.date.timeIntervalSinceReferenceDate
                Circle().trim(from: 0, to: 0.3)
                    .stroke(.white, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .rotationEffect(.degrees(t.truncatingRemainder(dividingBy: 1) * 360))
                    .padding(-6)
            }
        }
    }
}
