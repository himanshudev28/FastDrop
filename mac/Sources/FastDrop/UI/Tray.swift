import FastDropCore
import SwiftUI

struct BottomTray: View {
    let dropTargeted: Bool
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            if model.picked.isEmpty {
                hint.transition(.scale(scale: 0.96).combined(with: .opacity))
            } else {
                tray.transition(.scale(scale: 0.96).combined(with: .opacity))
            }
        }
        .animation(Motion.pop(reduceMotion), value: model.picked.isEmpty)
    }

    private var stroke: Color { scheme == .dark ? .white.opacity(0.08) : .black.opacity(0.06) }

    @ViewBuilder private var cardBackground: some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        if reduceTransparency { shape.fill(Color(nsColor: .windowBackgroundColor)) } else { shape.fill(.regularMaterial) }
    }

    // MARK: hint

    private var hint: some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        return HStack(spacing: 16) {
            Image(systemName: dropTargeted ? "square.and.arrow.down.on.square" : "arrow.down.doc")
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(dropTargeted ? Palette.violet : .secondary)
                .symbolEffect(.bounce, value: dropTargeted)
            VStack(alignment: .leading, spacing: 2) {
                Text("Drop files or folders here").font(.headline)
                Text("then click a device to send").font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            Button("Choose Files…") { model.chooseFiles() }
                .buttonStyle(.bordered).controlSize(.large)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, minHeight: 110, maxHeight: 110)
        .background(shape.fill(dropTargeted ? Palette.violet.opacity(0.12) : Color.primary.opacity(0.03)))
        .overlay(shape.strokeBorder(dropTargeted ? Palette.violet : Color.secondary.opacity(0.45),
                                    style: dropTargeted ? StrokeStyle(lineWidth: 2) : StrokeStyle(lineWidth: 1.5, dash: [6, 5])))
        .scaleEffect(dropTargeted ? 1.02 : 1)
        .animation(Motion.bouncy(reduceMotion), value: dropTargeted)
        .accessibilityElement(children: .contain)
    }

    // MARK: tray

    private var tray: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(model.picked.count) item\(model.picked.count == 1 ? "" : "s")").font(.headline)
                Text(Fmt.bytes(model.pickedTotal)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }
            .frame(minWidth: 60, alignment: .leading)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(model.picked) { f in
                        Chip(file: f).transition(.scale(scale: 0.5).combined(with: .opacity))
                    }
                }
                .padding(.vertical, 6)
            }

            VStack(alignment: .trailing, spacing: 8) {
                HStack(spacing: 8) {
                    Button { model.chooseFiles() } label: { Label("Add", systemImage: "plus") }
                    Button("Clear") { model.clearPicked() }
                }
                .buttonStyle(.bordered).controlSize(.small)
                Label(model.devices.isEmpty ? "Waiting for a device…" : "Click a device to send",
                      systemImage: model.devices.isEmpty ? "dot.radiowaves.left.and.right" : "arrow.up")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 18).padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .background(cardBackground)
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(stroke, lineWidth: 1))
        .shadow(color: .black.opacity(0.14), radius: 14, y: 6)
    }

    private struct Chip: View {
        let file: PickedFile
        @Environment(AppModel.self) private var model
        @Environment(\.accessibilityReduceMotion) private var reduceMotion
        @State private var hovering = false

        var body: some View {
            HStack(spacing: 8) {
                Image(nsImage: FileIcon.image(for: file.url)).resizable().frame(width: 36, height: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(file.name).font(.caption).lineLimit(2).truncationMode(.middle)
                    Text(file.isFolder ? "Folder · \(Fmt.bytes(file.size))" : Fmt.bytes(file.size))
                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(width: 90, alignment: .leading)
            }
            .padding(8)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.primary.opacity(0.06)))
            .overlay(alignment: .topTrailing) {
                if hovering {
                    Button { model.remove(file) } label: {
                        Image(systemName: "xmark.circle.fill").font(.body)
                            .symbolRenderingMode(.palette).foregroundStyle(.white, .gray)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Remove \(file.name)")
                    .offset(x: 5, y: -5)
                    .transition(.scale.combined(with: .opacity))
                }
            }
            .padding(.top, 4).padding(.trailing, 4)
            .onHover { h in withAnimation(Motion.snappy(reduceMotion)) { hovering = h } }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(file.name)
        }
    }
}

// MARK: - Toast

struct ToastView: View {
    let toast: Toast
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    private var symbol: (String, Color) {
        switch toast.kind {
        case .info: return ("info.circle.fill", .secondary)
        case .success: return ("checkmark.circle.fill", Palette.success)
        case .error: return ("exclamationmark.triangle.fill", Palette.danger)
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol.0).foregroundStyle(symbol.1).font(.body)
            Text(toast.text).font(.callout).lineLimit(2)
            Button { model.dismissToast() } label: {
                Image(systemName: "xmark").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss")
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .frame(maxWidth: 520)
        .background {
            if reduceTransparency { Capsule().fill(Color(nsColor: .windowBackgroundColor)) }
            else { Capsule().fill(.thickMaterial) }
        }
        .overlay(Capsule().strokeBorder(Color.primary.opacity(0.08), lineWidth: 1))
        .shadow(color: .black.opacity(0.2), radius: 12, y: 5)
        .accessibilityElement(children: .combine)
    }
}
