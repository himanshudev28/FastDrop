import FastDropCore
import SwiftUI

/// One continuous card that morphs between connecting, running, done and failed.
struct TransferCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorScheme) private var scheme

    private enum Phase: Equatable { case pending, running, done, failed, cancelled }

    private var phase: Phase {
        if model.pending != nil && model.live == nil { return .pending }
        switch model.live?.state {
        case .done: return .done
        case .failed: return .failed
        case .cancelled: return .cancelled
        default: return model.live == nil ? .pending : .running
        }
    }

    @State private var showFiles = false

    /// Pause/resume/skip was agreed with the other device (protocol section 7); otherwise those controls stay hidden.
    private var hasCtl: Bool { model.live?.transfer.ctlOn ?? false }
    private var paused: Bool { phase == .running && (model.live?.paused ?? false) }

    private var outgoing: Bool { model.live?.transfer.outgoing ?? true }
    private var peerName: String { model.live?.transfer.peerName ?? model.pending?.peer.name ?? "device" }
    private var peerType: String { model.live?.transfer.peerType ?? model.pending?.peer.type ?? "phone" }
    private var fraction: Double { phase == .done ? 1 : (model.live?.fraction ?? 0) }
    private var colors: [Color] { outgoing ? [Palette.sendA, Palette.sendB] : [Palette.recvA, Palette.recvB] }

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 16) {
                ring
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(title).font(.headline).lineLimit(1).truncationMode(.middle)
                        Image(systemName: DeviceGlyph.symbol(forType: peerType))
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
                        .lineLimit(2).monospacedDigit()
                        .contentTransition(.numericText())
                    if phase == .pending, let code = model.pending?.code {
                        Text("Code \(code)").font(.system(.callout, design: .rounded, weight: .semibold)).monospacedDigit()
                            .padding(.horizontal, 8).padding(.vertical, 2)
                            .background(Capsule().fill(Color.primary.opacity(0.08)))
                            .help("The other device shows the same code")
                            .accessibilityLabel("Confirmation code \(code)")
                    }
                    if let status = model.batchStatus, phase == .pending || phase == .running {
                        Text(status).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 8)
                buttons
            }
            if phase == .running {
                VStack(spacing: 8) {
                    bar
                    HStack {
                        Text(footer).font(.caption).monospacedDigit().foregroundStyle(.secondary)
                            .contentTransition(.numericText())
                        Spacer()
                        if hasCtl, let l = model.live, l.transfer.fileCount > 1, !l.transfer.isText {
                            Button {
                                withAnimation(Motion.pop(reduceMotion)) { showFiles.toggle() }
                            } label: {
                                Label(showFiles ? "Hide files" : "Files", systemImage: showFiles ? "chevron.up" : "chevron.down")
                                    .labelStyle(.titleAndIcon).font(.caption)
                            }
                            .buttonStyle(.plain).foregroundStyle(.secondary)
                            .accessibilityLabel(showFiles ? "Hide file list" : "Show file list")
                        }
                    }
                    if showFiles, hasCtl, let l = model.live { fileList(l) }
                }
                .transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .top)))
            }
        }
        .padding(18)
        .frame(maxWidth: 620)
        .background(surface)
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous)
            .strokeBorder(scheme == .dark ? Color.white.opacity(0.08) : Color.black.opacity(0.06), lineWidth: 1))
        .shadow(color: .black.opacity(0.18), radius: 18, y: 8)
        .animation(Motion.pop(reduceMotion), value: phase)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(title). \(subtitle)")
    }

    /// One row per file (first 50): finished files get a check, skipped ones read "Skipped", the rest a skip button.
    private func fileList(_ l: LiveTransfer) -> some View {
        ScrollView {
            VStack(spacing: 2) {
                ForEach(Array(l.transfer.names.enumerated()), id: \.offset) { i, name in
                    let skipped = l.skipped.contains(i)
                    let finished = !skipped && l.transfer.isFileFinished(i)
                    HStack(spacing: 8) {
                        Text(name).font(.caption).lineLimit(1).truncationMode(.middle)
                            .strikethrough(skipped)
                            .foregroundStyle(skipped ? .tertiary : .primary)
                        Spacer(minLength: 8)
                        if skipped {
                            Text("Skipped").font(.caption2).foregroundStyle(.secondary)
                        } else if finished {
                            Image(systemName: "checkmark").font(.caption2.weight(.bold)).foregroundStyle(Palette.success)
                                .accessibilityLabel("Finished")
                        } else {
                            Button { model.skipFile(i) } label: {
                                Image(systemName: "forward.end.fill").font(.caption2)
                            }
                            .buttonStyle(.borderless).foregroundStyle(.secondary)
                            .help("Skip this file")
                            .accessibilityLabel("Skip \(name)")
                        }
                    }
                    .padding(.horizontal, 4).frame(minHeight: 22)
                }
            }
        }
        .frame(maxHeight: 132)
    }

    @ViewBuilder private var surface: some View {
        let shape = RoundedRectangle(cornerRadius: 24, style: .continuous)
        if reduceTransparency { shape.fill(Color(nsColor: .windowBackgroundColor)) }
        else { shape.fill(.regularMaterial) }
    }

    // MARK: ring

    private var ring: some View {
        ZStack {
            Circle().stroke(Color.primary.opacity(0.1), lineWidth: 6)
            if phase == .pending {
                TimelineView(.animation(paused: reduceMotion)) { ctx in
                    let t = ctx.date.timeIntervalSinceReferenceDate
                    Circle().trim(from: 0, to: 0.3)
                        .stroke(AngularGradient(colors: colors + [colors[0].opacity(0)], center: .center),
                                style: StrokeStyle(lineWidth: 6, lineCap: .round))
                        .rotationEffect(.degrees(reduceMotion ? 0 : (t * 300).truncatingRemainder(dividingBy: 360)))
                }
            } else {
                Circle().trim(from: 0, to: phase == .failed || phase == .cancelled ? 0 : fraction)
                    .stroke(phase == .done ? AnyShapeStyle(Palette.success)
                            : AnyShapeStyle(AngularGradient(colors: colors, center: .center)),
                            style: StrokeStyle(lineWidth: 6, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.smooth, value: fraction)
            }
            center
        }
        .frame(width: 56, height: 56)
    }

    @ViewBuilder private var center: some View {
        switch phase {
        case .running:
            if paused {
                Image(systemName: "pause.fill").font(.callout).foregroundStyle(.secondary)
            } else {
                Text("\(Int(fraction * 100))%")
                    .font(.system(.caption, design: .rounded, weight: .semibold)).monospacedDigit()
                    .contentTransition(.numericText(value: fraction * 100))
            }
        case .done:
            Image(systemName: "checkmark").font(.title3.weight(.bold)).foregroundStyle(Palette.success)
                .symbolEffect(.bounce, value: phase == .done)
        case .failed:
            Image(systemName: "xmark").font(.title3.weight(.bold)).foregroundStyle(Palette.danger)
        case .cancelled:
            Image(systemName: "xmark").font(.title3.weight(.bold)).foregroundStyle(.secondary)
        case .pending:
            Image(systemName: outgoing ? "arrow.up" : "arrow.down").font(.callout.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }

    private var bar: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.1))
                Capsule().fill(LinearGradient(colors: colors, startPoint: .leading, endPoint: .trailing))
                    .frame(width: max(4, g.size.width * fraction))
                    .animation(.smooth, value: fraction)
            }
        }
        .opacity(paused ? 0.45 : 1)
        .frame(height: 5)
        .accessibilityHidden(true)
    }

    // MARK: text

    private var title: String {
        switch phase {
        case .pending: return "Sending to \(peerName)"
        case .running: return outgoing ? "Sending to \(peerName)" : "Receiving from \(peerName)"
        case .done: return outgoing ? "Sent to \(peerName)" : "Received from \(peerName)"
        case .failed: return outgoing ? "Couldn’t send to \(peerName)" : "Couldn’t receive from \(peerName)"
        case .cancelled: return "Cancelled"
        }
    }

    private var subtitle: String {
        guard let l = model.live, phase != .pending else { return model.pending?.status ?? "Connecting…" }
        let n = l.transfer.fileCount - l.skipped.count
        let files = l.transfer.isText ? "Text" : "\(n) file\(n == 1 ? "" : "s")"
        let skippedNote = l.skipped.isEmpty ? "" : " · \(l.skipped.count) skipped"
        switch phase {
        case .running:
            let resumed = l.transfer.baseline > 0 ? "Resumed at \(Int(Double(l.transfer.baseline) * 100 / Double(max(1, l.transfer.total)))) % · " : ""
            return "\(paused ? "Paused · " : "")\(resumed)\(files)\(skippedNote) · \(Fmt.bytes(l.done)) of \(Fmt.bytes(l.transfer.total))"
        case .done:
            if l.transfer.isText { return "Text sent" }
            let sp = l.speed > 1 ? " · \(Fmt.speed(l.speed)) avg" : ""
            return "\(files)\(skippedNote) · \(Fmt.bytes(l.transfer.total))\(sp)"
        default: return l.message.isEmpty ? "Transfer stopped" : l.message
        }
    }

    private var footer: String {
        guard let l = model.live else { return "" }
        if paused { return "Paused" }
        return "\(Fmt.speed(l.speed)) · \(Fmt.duration(l.eta)) left"
    }

    // MARK: buttons

    @ViewBuilder private var buttons: some View {
        HStack(spacing: 8) {
            switch phase {
            case .pending, .running:
                if phase == .running, hasCtl, !(model.live?.transfer.isText ?? true) {
                    Button { model.togglePause() } label: {
                        Label(paused ? "Resume" : "Pause", systemImage: paused ? "play.fill" : "pause.fill")
                    }
                    .help(paused ? "Resume the transfer" : "Pause the transfer")
                    .accessibilityLabel(paused ? "Resume transfer" : "Pause transfer")
                }
                Button("Cancel") { model.cancelTransfer() }   // no Esc: too easy to stop a transfer by accident
            case .done:
                if !outgoing {
                    Button("Open Folder") { model.openSaveFolder() }
                }
                Button("Dismiss") { model.dismissTransfer() }
                    .keyboardShortcut(model.offer == nil ? .cancelAction : nil)   // Esc belongs to the offer's Decline
            case .failed, .cancelled:
                if outgoing { Button("Retry") { model.retry() }.buttonStyle(.borderedProminent).tint(Palette.sendA) }
                Button("Dismiss") { model.dismissTransfer() }
                    .keyboardShortcut(model.offer == nil ? .cancelAction : nil)
            }
        }
        .controlSize(.regular)
    }
}

// MARK: - Confetti

struct ConfettiBurst: View {
    let trigger: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private struct Particle {
        let vx: Double, vy: Double, spin: Double, phase: Double
        let size: CGFloat, color: Color, round: Bool
    }

    private static let duration = 2.0
    private static let palette: [Color] = [
        Palette.sendA, Palette.sendB, Palette.recvA, Palette.recvB, Palette.webA, Palette.webB, Palette.violet, Palette.blue,
    ]

    @State private var particles: [Particle] = []
    @State private var start = Date.distantPast
    @State private var active = false

    var body: some View {
        TimelineView(.animation(paused: !active)) { ctx in
            Canvas { gc, size in
                let t = ctx.date.timeIntervalSince(start)
                guard active, t >= 0, t < Self.duration else { return }
                let origin = CGPoint(x: size.width / 2, y: size.height - 60)
                let fade = min(1, (Self.duration - t) / 0.6)
                for p in particles {
                    let x = origin.x + p.vx * t
                    let y = origin.y + p.vy * t + 0.5 * 900 * t * t
                    var c = gc
                    c.opacity = fade
                    c.translateBy(x: x, y: y)
                    c.rotate(by: .radians(p.phase + p.spin * t))
                    let r = CGRect(x: -p.size / 2, y: -p.size / 3, width: p.size, height: p.size * 0.66)
                    let path = p.round ? Path(ellipseIn: CGRect(x: -p.size / 3, y: -p.size / 3, width: p.size * 0.66, height: p.size * 0.66))
                                       : Path(roundedRect: r, cornerRadius: 2)
                    c.fill(path, with: .color(p.color))
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onChange(of: trigger) { _, _ in burst() }
    }

    private func burst() {
        guard !reduceMotion else { return }
        particles = (0..<90).map { _ in
            let angle = Double.random(in: -.pi * 0.85 ... -.pi * 0.15)
            let speed = Double.random(in: 380...920)
            return Particle(vx: cos(angle) * speed, vy: sin(angle) * speed, spin: Double.random(in: -10...10),
                            phase: Double.random(in: 0...(2 * .pi)), size: CGFloat.random(in: 6...12),
                            color: Self.palette.randomElement() ?? Palette.violet, round: Bool.random())
        }
        start = Date()
        active = true
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.duration + 0.1))
            if Date().timeIntervalSince(start) >= Self.duration { active = false; particles = [] }
        }
    }
}
