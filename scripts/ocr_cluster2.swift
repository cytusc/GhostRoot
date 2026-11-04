// ocr_cluster2.swift — 列表项图标聚类（v2：过滤说明文字 + 归一化 + 拼接代表图）
// 用法: ./ocr_cluster2 <img...>
import Foundation
import Vision
import AppKit

let args = Array(CommandLine.arguments.dropFirst())
guard !args.isEmpty else { exit(1) }
let outDir = "/tmp/ocr_icons"
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

func featurePrint(_ cg: CGImage) -> VNFeaturePrintObservation? {
    let req = VNGenerateImageFeaturePrintRequest()
    let h = VNImageRequestHandler(cgImage: cg, options: [:])
    try? h.perform([req])
    return req.results?.first as? VNFeaturePrintObservation
}
func distance(_ a: VNFeaturePrintObservation, _ b: VNFeaturePrintObservation) -> Float {
    var d: Float = 0; try? a.computeDistance(&d, to: b); return d
}
func resize(_ cg: CGImage, _ s: Int) -> CGImage? {
    let ctx = CGContext(data: nil, width: s, height: s, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.interpolationQuality = .high
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: s, height: s))
    return ctx.makeImage()
}

struct Cluster { var fp: VNFeaturePrintObservation; var items: [String]; var rep: CGImage }
var clusters: [Cluster] = []

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
        // 只保留「列表项」：长度合适、非长句、无中文句号
        if s.count < 2 || s.count > 42 { continue }
        if s.contains("。") || s.contains("，") || s.contains("；") { continue }
        if s.contains(":") || s.contains("：") { continue }
        let bb = o.boundingBox
        let iconX0 = bb.minX - 0.085, iconX1 = bb.minX - 0.022
        guard iconX0 > 0 else { continue }
        let hh = max(bb.height * 1.15, 0.026)
        let y0 = bb.midY - hh/2, y1 = bb.midY + hh/2
        guard y0 > 0, y1 < 1 else { continue }
        let px = CGRect(x: iconX0 * W, y: (1 - y1) * H, width: (iconX1 - iconX0) * W, height: (y1 - y0) * H)
        guard let raw = cg.cropping(to: px), let norm = resize(raw, 48) else { continue }
        var minV = 255
        if let data = norm.dataProvider?.data, let ptr = CFDataGetBytePtr(data) {
            for i in stride(from: 0, to: CFDataGetLength(data) - 3, by: 4) {
                let v = min(Int(ptr[i]), Int(ptr[i+1]), Int(ptr[i+2]))
                if v < minV { minV = v }
            }
        }
        guard minV < 170 else { continue }
        guard let fp = featurePrint(norm) else { continue }
        var hit = false
        for i in 0..<clusters.count where distance(clusters[i].fp, fp) < 0.20 {
            clusters[i].items.append(s); hit = true; break
        }
        if !hit { clusters.append(Cluster(fp: fp, items: [s], rep: norm)) }
    }
}

clusters.sort { $0.items.count > $1.items.count }
print("簇数: \(clusters.count)")
for (i, c) in clusters.enumerated() {
    let uniq = Array(NSOrderedSet(array: c.items)) as! [String]
    print(String(format: "--- 簇 %d (%d 项) ---", i, uniq.count))
    for x in uniq.prefix(25) { print("    \(x)") }
}
// 完整映射（项名 -> 簇号），便于直接筛出非主簇的项
print("\n===== 完整映射 =====")
var name2cluster: [String: Int] = [:]
for (i, c) in clusters.enumerated() {
    for x in (Array(NSOrderedSet(array: c.items)) as! [String]) { name2cluster[x] = i }
}
for (n, ci) in name2cluster.sorted(by: { $0.key < $1.key }) {
    print("[\(ci)] \(n)")
}

// 拼接代表图
let cell = 64, cols = 6
let rows = Int(ceil(Double(clusters.count) / Double(cols)))
let W = cols * cell, H = rows * (cell + 18)
let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
for (i, c) in clusters.enumerated() {
    let cx = (i % cols) * cell, cy = H - (i / cols) * (cell + 18) - cell
    ctx.draw(c.rep, in: CGRect(x: cx + 8, y: cy + 8, width: cell - 16, height: cell - 16))
    // 标签用简笔横线表示序号（避免字体依赖），实际序号见终端输出
    ctx.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
    ctx.fill(CGRect(x: cx + 20, y: cy - 12, width: 24, height: 3))
}
if let out = ctx.makeImage() {
    let ni = NSImage(cgImage: out, size: NSSize(width: W, height: H))
    if let tiff = ni.tiffRepresentation, let bmp = NSBitmapImageRep(data: tiff),
       let png = bmp.representation(using: .png, properties: [:]) {
        try? png.write(to: URL(fileURLWithPath: "\(outDir)/reps.png"))
        print("代表图: \(outDir)/reps.png")
    }
}
