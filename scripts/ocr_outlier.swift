// ocr_outlier.swift — 列表项图标离群检测
// 思路: 提取所有列表项左侧图标 → 灰度化 48x48 → 像素欧氏距离
//       → 找「离群最小」(到多数项都近) 的图标作为标准型
//       → 输出距离标准型最远的 N 项（疑似异常/高风险），并生成对比拼图
import Foundation
import Vision
import AppKit
import CoreText

let args = Array(CommandLine.arguments.dropFirst())
guard !args.isEmpty else { exit(1) }
let outDir = "/tmp/hunter_grid"
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

let N = 48
func grayVec(_ cg: CGImage) -> [Double]? {
    let ctx = CGContext(data: nil, width: N, height: N, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpaceCreateDeviceGray(),
                        bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    ctx.setFillColor(CGColor(gray: 1, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: N, height: N))
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: N, height: N))
    guard let out = ctx.makeImage(), let data = out.dataProvider?.data,
          let ptr = CFDataGetBytePtr(data) else { return nil }
    let n = CFDataGetLength(data)
    var v = [Double](repeating: 0, count: N * N)
    let bpr = N * 2
    for y in 0..<N { for x in 0..<N {
        let i = y * bpr + x * 2
        v[y * N + x] = i < n ? Double(ptr[i]) : 255
    }}
    return v
}
func dist(_ a: [Double], _ b: [Double]) -> Double {
    var s = 0.0
    for i in 0..<a.count { let d = a[i] - b[i]; s += d * d }
    return sqrt(s)
}

struct It { var idx: Int; var text: String; var vec: [Double]; var cg: CGImage }
var items: [It] = []
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
        var dark = 0, total = 0
        let bw = raw.width, bh = raw.height
        if let data = raw.dataProvider?.data, let ptr = CFDataGetBytePtr(data) {
            let n = CFDataGetLength(data); let bpr = bw * 4
            for y in 0..<bh { for x in 0..<bw {
                let i = y * bpr + x * 4
                if i + 2 < n { let v = min(Int(ptr[i]), Int(ptr[i+1]), Int(ptr[i+2])); total += 1; if v < 140 { dark += 1 } }
            }}
        }
        guard total > 0, dark > total / 40 else { continue }
        guard let v = grayVec(raw) else { continue }
        items.append(It(idx: idx, text: s, vec: v, cg: raw))
        idx += 1
    }
}

print("图标数: \(items.count)")
guard items.count > 3 else { exit(0) }

// 找标准型：到所有其他项距离之和最小者
var scores = [Double](repeating: 0, count: items.count)
for i in 0..<items.count {
    var s = 0.0
    for j in 0..<items.count where j != i { s += dist(items[i].vec, items[j].vec) }
    scores[i] = s
}
let stdIdx = scores.enumerated().min { $0.element < $1.element }!.offset
print("标准型 IDX=\(items[stdIdx].idx) 文本='\(items[stdIdx].text)' 文本='\(items[stdIdx].text)'")

// 离群排序
var ranked: [(i: Int, d: Double)] = items.indices.map { (i: $0, d: dist(items[$0].vec, items[stdIdx].vec)) }
ranked.sort { $0.d > $1.d }
print("\n===== 离标准型最远的项（疑似异常/高风险） =====")
for r in ranked.prefix(22) {
    print(String(format: "d=%7.1f | %@", r.d, items[r.i].text))
}

// 拼图：标准型 + 离群前 20
var sel: [(String, CGImage)] = [("STD \(items[stdIdx].idx)", items[stdIdx].cg)]
for r in ranked.prefix(20) { sel.append((items[r.i].text, items[r.i].cg)) }
let cell = 150, labelH = 34, cols = 5
let rows = Int(ceil(Double(sel.count) / Double(cols)))
let W = cols * cell, H = rows * (cell + labelH)
let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
let font = CTFontCreateWithName("Helvetica-Bold" as CFString, 12, nil)
for (k, s) in sel.enumerated() {
    let cx = (k % cols) * cell, cy = H - (k / cols) * (cell + labelH) - cell
    ctx.setStrokeColor(CGColor(red: 0.8, green: 0.8, blue: 0.8, alpha: 1))
    ctx.stroke(CGRect(x: cx + 1, y: cy + 1, width: cell - 2, height: cell + labelH - 2))
    ctx.draw(s.1, in: CGRect(x: cx + 30, y: cy + 34, width: 90, height: 90))
    let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.black]
    let disp = String(s.0.prefix(18))
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: disp, attributes: attrs))
    ctx.textPosition = CGPoint(x: cx + 6, y: cy + 4)
    CTLineDraw(line, ctx)
}
if let out = ctx.makeImage() {
    let ni = NSImage(cgImage: out, size: NSSize(width: W, height: H))
    if let tiff = ni.tiffRepresentation, let bmp = NSBitmapImageRep(data: tiff),
       let png = bmp.representation(using: .png, properties: [:]) {
        try? png.write(to: URL(fileURLWithPath: "\(outDir)/outliers.png"))
        print("outliers: \(outDir)/outliers.png")
    }
}
