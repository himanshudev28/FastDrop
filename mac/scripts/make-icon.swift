// Renders the FastDrop app icon into an .iconset folder.
// Usage: swift scripts/make-icon.swift <out.iconset>
import AppKit

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.iconset")
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

func render(_ px: Int) -> Data {
    let s = CGFloat(px)
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    let inset = s * 0.098
    let body = CGRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2)
    let path = NSBezierPath(roundedRect: body, xRadius: body.width * 0.225, yRadius: body.width * 0.225)
    NSGradient(colors: [NSColor(red: 0x4F / 255, green: 0xA3 / 255, blue: 1, alpha: 1),
                        NSColor(red: 0x7C / 255, green: 0x6C / 255, blue: 1, alpha: 1)])!
        .draw(in: path, angle: 45)   // violet top-left to blue bottom-right

    NSGraphicsContext.saveGraphicsState()
    path.addClip()
    // Subtle top highlight.
    NSGradient(colors: [NSColor.white.withAlphaComponent(0.22), NSColor.white.withAlphaComponent(0)])!
        .draw(in: CGRect(x: body.minX, y: body.midY, width: body.width, height: body.height / 2), angle: 270)

    // Radar waves behind the bolt.
    let c = CGPoint(x: body.midX, y: body.midY)
    for (r, a) in [(0.40, 0.22), (0.30, 0.35), (0.20, 0.0)] where a > 0 {
        let rad = body.width * r
        let arc = NSBezierPath()
        arc.appendArc(withCenter: c, radius: rad, startAngle: 0, endAngle: 360)
        arc.lineWidth = body.width * 0.028
        NSColor.white.withAlphaComponent(a).setStroke()
        arc.stroke()
    }
    NSGraphicsContext.restoreGraphicsState()

    let config = NSImage.SymbolConfiguration(pointSize: body.width * 0.46, weight: .bold)
        .applying(.init(paletteColors: [.white]))
    if let sym = NSImage(systemSymbolName: "bolt.fill", accessibilityDescription: nil)?.withSymbolConfiguration(config) {
        let r = CGRect(x: body.midX - sym.size.width / 2, y: body.midY - sym.size.height / 2,
                       width: sym.size.width, height: sym.size.height)
        NSGraphicsContext.saveGraphicsState()
        let sh = NSShadow()
        sh.shadowColor = NSColor.black.withAlphaComponent(0.3)
        sh.shadowBlurRadius = s * 0.03
        sh.shadowOffset = NSSize(width: 0, height: -s * 0.015)
        sh.set()
        sym.draw(in: r)
        NSGraphicsContext.restoreGraphicsState()
    }
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

for base in [16, 32, 128, 256, 512] {
    try render(base).write(to: out.appendingPathComponent("icon_\(base)x\(base).png"))
    try render(base * 2).write(to: out.appendingPathComponent("icon_\(base)x\(base)@2x.png"))
}
print("Wrote \(out.path)")
