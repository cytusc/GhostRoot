// ocr_icons.swift — 提取列表项左侧图标并编号，拼成带数字标签的网格图
// 输出: /tmp/ocr_icons/grid.png  + 终端 "idx | 文本" 映射
import Foundation
import Vision
import AppKit
import CoreText

let args = Array(CommandLine.arguments.dropFirst())
guard !args.isEmpty else { exit(1) }
let outDir = "/tmp/hunter_grid"
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

func grayscale(_ cg: CGImage, _ s: Int) -> CGImage? {
    let ctx = CGContext(data: nil, width: s, height: s, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpaceCreateDeviceGray(),
                        bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    ctx.setFillColor(CGColor(gray: 1, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: s, height: s))
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: s, height: s))
    return ctx.makeImage()
}

var icons: [(idx: Int, text: String, cg: CGImage)] = []
var idx = 0

for path in args {
    guard let img = NSImage(contentsOfFile: path),
          let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else { continue }
    let W = Double(cg.width), H = Double(cg.height)
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.recognitionLanguages = ["zh-Hans", "en-US"]
    req.usesLanguageCorrection = false
    if #available(macOS 13.0, *) { req.minimumTextHeight = 0.006 }
    try? VNImageRequestHandler(cgImage: cg, options: [:]).perform([req])
    let obs = (req.results ?? []).sorted { a, b in
        let ay = a.boundingBox.midY, by = b.boundingBox.midY
        if abs(ay - by) > 0.008 { return ay > by }
        return a.boundingBox.minX < b.boundingBox.minX
    }
    for o in obs {
        guard let t = o.topCandidates(1).first else { continue }
        let s = t.string
        if s.count < 3 || s.count > 42 { continue }
        if s.contains("。") || s.contains("，") || s.contains("；") { continue }
        let bb = o.boundingBox
        let iconX0 = bb.minX - 0.085, iconX1 = bb.minX - 0.022
        guard iconX0 > 0 else { continue }
        let hh = max(bb.height * 1.2, 0.028)
        let y0 = bb.midY - hh/2, y1 = bb.midY + hh/2
        guard y0 > 0, y1 < 1 else { continue }
        let px = CGRect(x: iconX0 * W, y: (1 - y1) * H, width: (iconX1 - iconX0) * W, height: (y1 - y0) * H)
        guard let raw = cg.cropping(to: px) else { continue }
        // 必须是深色图标：统计暗像素比例
        var dark = 0, total = 0
        let bw = raw.width, bh = raw.height
        if let data = raw.dataProvider?.data, let ptr = CFDataGetBytePtr(data) {
            let n = CFDataGetLength(data)
            let bpr = bw * 4
            for y in 0..<bh { for x in 0..<bw {
                let i = y * bpr + x * 4
                if i + 2 < n {
                    let v = min(Int(ptr[i]), Int(ptr[i+1]), Int(ptr[i+2]))
                    total += 1; if v < 140 { dark += 1 }
                }
            }}
        }
        guard total > 0, dark > total / 40 else { continue }   // 暗像素需 > 2.5%
        guard let g = grayscale(raw, 56) else { continue }
        icons.append((idx: idx, text: s, cg: g))
        idx += 1
    }
}

print("图标数: \(icons.count)")
for i in icons { print("IDX \(i.idx) | \(i.text)") }

// 网格拼图 + 数字标签
let cell = 78, labelH = 18, cols = 8
let rows = Int(ceil(Double(icons.count) / Double(cols)))
let W = cols * cell, H = rows * (cell + labelH)
let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))

let font = CTFontCreateWithName("Helvetica-Bold" as CFString, 13, nil)
let attrs: [NSAttributedString.Key: Any] = [
    .font: font,
    .foregroundColor: NSColor.black
]

for i in icons {
    let cx = (i.idx % cols) * cell
    let cy = H - (i.idx / cols) * (cell + labelH) - cell
    ctx.setStrokeColor(CGColor(red: 0.85, green: 0.85, blue: 0.85, alpha: 1))
    ctx.stroke(CGRect(x: cx + 1, y: cy + 1, width: cell - 2, height: cell + labelH - 2))
    ctx.draw(i.cg, in: CGRect(x: cx + 11, y: cy + 10, width: cell - 22, height: cell - 22))
    let s = NSAttributedString(string: "\(i.idx)", attributes: attrs)
    let line = CTLineCreateWithAttributedString(s)
    ctx.textPosition = CGPoint(x: cx + 6, y: cy - 2)
    CTLineDraw(line, ctx)
}

if let out = ctx.makeImage() {
    let ni = NSImage(cgImage: out, size: NSSize(width: W, height: H))
    if let tiff = ni.tiffRepresentation, let bmp = NSBitmapImageRep(data: tiff),
       let png = bmp.representation(using: .png, properties: [:]) {
        try? png.write(to: URL(fileURLWithPath: "\(outDir)/grid.png"))
        print("grid: \(outDir)/grid.png  (\(W)x\(H))")
    }
}
