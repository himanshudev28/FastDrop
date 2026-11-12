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

    // macOS-style rounded square with the standard margin, a soft drop shadow, flat signal orange.
    let inset = s * 0.098
    let body = CGRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2)
    let path = NSBezierPath(roundedRect: body, xRadius: body.width * 0.225, yRadius: body.width * 0.225)
    NSGraphicsContext.saveGraphicsState()
    let sh = NSShadow()
    sh.shadowColor = NSColor.black.withAlphaComponent(0.28)
    sh.shadowBlurRadius = s * 0.02
    sh.shadowOffset = NSSize(width: 0, height: -s * 0.008)
    sh.set()
    NSColor(red: 0xFF / 255, green: 0x5A / 255, blue: 0x1F / 255, alpha: 1).setFill()   // #FF5A1F
    path.fill()
    NSGraphicsContext.restoreGraphicsState()

    // The Android icon's 5x7 dot-matrix down arrow: lit #161513, unlit #DA4F1D.
    let lit: Set<Int> = [2, 7, 12, 17, 20, 22, 24, 26, 27, 28, 32]   // row * 5 + col, row 0 = top
    let pitch = body.height * 0.62 / 7
    let dot = pitch * 0.72
    let gridW = pitch * 4 + dot, gridH = pitch * 6 + dot
    let x0 = body.midX - gridW / 2, yTop = body.midY + gridH / 2
    let on = NSColor(red: 0x16 / 255, green: 0x15 / 255, blue: 0x13 / 255, alpha: 1)
    let off = NSColor(red: 0xDA / 255, green: 0x4F / 255, blue: 0x1D / 255, alpha: 1)
    for row in 0..<7 {
        for col in 0..<5 {
            (lit.contains(row * 5 + col) ? on : off).setFill()
            NSBezierPath(ovalIn: CGRect(x: x0 + CGFloat(col) * pitch, y: yTop - dot - CGFloat(row) * pitch,
                                        width: dot, height: dot)).fill()
        }
    }
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

for base in [16, 32, 128, 256, 512] {
    try render(base).write(to: out.appendingPathComponent("icon_\(base)x\(base).png"))
    try render(base * 2).write(to: out.appendingPathComponent("icon_\(base)x\(base)@2x.png"))
}
print("Wrote \(out.path)")
