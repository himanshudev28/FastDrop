import FastDropCore
import SwiftUI

struct OfferCard: View {
    let offer: IncomingOffer
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorScheme) private var scheme
    @State private var appeared = false
    @State private var totalWindow: Double = 1

    private var shown: [OfferFile] { Array(offer.files.prefix(4)) }
    private var extra: Int { max(0, offer.files.count - 4) }

    var body: some View {
        VStack(spacing: 16) {
            avatar
            VStack(spacing: 4) {
                Text("\(offer.from) wants to send you").font(.title3.weight(.semibold)).multilineTextAlignment(.center)
                Text("\(offer.files.count) file\(offer.files.count == 1 ? "" : "s") · \(Fmt.bytes(offer.total))")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            fileList
            countdown
            HStack(spacing: 12) {
                Button { model.respond(false) } label: { Text("Decline").frame(maxWidth: .infinity) }
                    .keyboardShortcut(.cancelAction)
                Button { model.respond(true) } label: { Text("Accept").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).tint(Color(hex: 0x0B8F6A))   // dark enough for white text
                    .keyboardShortcut(.defaultAction)
            }
            .controlSize(.large)
        }
        .padding(24)
        .frame(width: 420)
        .background {
            let shape = RoundedRectangle(cornerRadius: 24, style: .continuous)
            if reduceTransparency { shape.fill(Color(nsColor: .windowBackgroundColor)) } else { shape.fill(.thickMaterial) }
        }
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous)
            .strokeBorder(scheme == .dark ? Color.white.opacity(0.08) : Color.black.opacity(0.06), lineWidth: 1))
        .shadow(color: .black.opacity(0.25), radius: 30, y: 14)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(offer.from) wants to send you \(offer.files.count) files, \(Fmt.bytes(offer.total))")
        .onAppear {
            totalWindow = max(1, offer.deadline.timeIntervalSinceNow)
            appeared = true
        }
    }

    private var avatar: some View {
        ZStack {
            if !reduceMotion {
                PhaseAnimator([false, true]) { grown in
                    Circle().stroke(Palette.colors(forDeviceType: offer.fromType)[0].opacity(grown ? 0 : 0.5), lineWidth: 3)
                        .frame(width: 72, height: 72)
                        .scaleEffect(grown ? 1.7 : 1)
                } animation: { grown in grown ? .easeOut(duration: 1.6) : nil }   // grow out, snap back
            }
            Circle().fill(Palette.gradient(forDeviceType: offer.fromType)).frame(width: 72, height: 72)
                .shadow(color: Palette.colors(forDeviceType: offer.fromType)[0].opacity(0.4), radius: 12, y: 4)
            Image(systemName: DeviceGlyph.symbol(forType: offer.fromType))
                .font(.system(size: 30, weight: .medium)).foregroundStyle(.white)
        }
        .frame(height: 84)
        .accessibilityHidden(true)
    }

    private var fileList: some View {
        VStack(spacing: 0) {
            ForEach(Array(shown.enumerated()), id: \.offset) { i, f in
                HStack(spacing: 10) {
                    Image(nsImage: FileIcon.image(forName: f.name)).resizable().frame(width: 24, height: 24)
                    Text(f.appLabel ?? f.name).font(.callout).lineLimit(1).truncationMode(.middle)
                    Spacer(minLength: 8)
                    Text(Fmt.bytes(f.size)).font(.caption).monospacedDigit().foregroundStyle(.secondary)
                }
                .padding(.horizontal, 12).padding(.vertical, 7)
                .opacity(appeared || reduceMotion ? 1 : 0)
                .offset(y: appeared || reduceMotion ? 0 : 8)
                .animation(reduceMotion ? nil : .spring(response: 0.45, dampingFraction: 0.8).delay(0.05 * Double(i)), value: appeared)
            }
            if extra > 0 {
                Text("+ \(extra) more").font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12).padding(.vertical, 6)
            }
        }
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.primary.opacity(0.06)))
    }

    private var countdown: some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { ctx in
            let left = max(0, offer.deadline.timeIntervalSince(ctx.date))
            HStack(spacing: 10) {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.primary.opacity(0.1))
                        Capsule().fill(Palette.receive)
                            .frame(width: g.size.width * min(1, left / totalWindow))
                    }
                }
                .frame(height: 4)
                Text("\(Int(left.rounded(.up)))s").font(.caption).monospacedDigit().foregroundStyle(.secondary)
                    .frame(minWidth: 28, alignment: .trailing)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Expires in \(Int(left.rounded(.up))) seconds")
        }
    }
}
