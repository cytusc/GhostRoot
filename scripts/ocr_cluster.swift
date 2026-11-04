// ocr_cluster.swift — OCR + 列表项图标聚类
// 用途: 检测类 App（如 Hunter）用左侧表情图标标记状态。此工具:
//   1) OCR 识别每屏文本及位置
//   2) 对每个文本行左侧的图标区域做特征打印
//   3) 贪心聚类相同图标，输出「簇ID → 出现次数 / 代表项名 / 代表图标PNG」
//   4) 人眼只需看代表图标即可判断哪个簇是高风险
//
// 用法: ./ocr_cluster <img1> [img2] ...   输出目录 /tmp/ocr_icons
import Foundation
import Vision
import AppKit

let args = Array(CommandLine.arguments.dropFirst())
guard !args.isEmpty else { exit(1) }

let outDir = "/tmp/ocr_icons"
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

struct Item { var text: String; var icon: CGImage; var key: String }

// ---- 特征打印 ----
func featurePrint(_ cg: CGImage) -> VNFeaturePrintObservation? {
    let req = VNGenerateImageFeaturePrintRequest()
    let h = VNImageRequestHandler(cgImage: cg, options: [:])
    try? h.perform([req])
    return req.results?.first as? VNFeaturePrintObservation
}
func distance(_ a: VNFeaturePrintObservation, _ b: VNFeaturePrintObservation) -> Float {
    var d: Float = 0
    try? a.computeDistance(&d, to: b)
    return d
}

var clusters: [(fp: VNFeaturePrintObservation, items: [String], rep: CGImage)] = []
var allItems: [Item] = []

for (fi, path) in args.enumerated() {
    guard let img = NSImage(contentsOfFile: path),
          let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else { continue }
    let W = Double(cg.width), H = Double(cg.height)

    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.recognitionLanguages = ["zh-Hans", "en-US"]
    req.usesLanguageCorrection = false
    if #available(macOS 13.0, *) { req.minimumTextHeight = 0.006 }
    let handler = VNImageRequestHandler(cgImage: cg, options: [:])
    try? handler.perform([req])
    let obs = (req.results ?? []).sorted { a, b in
        let ay = a.boundingBox.midY, by = b.boundingBox.midY
        if abs(ay - by) > 0.008 { return ay > by }
        return a.boundingBox.minX < b.boundingBox.minX
    }

    for o in obs {
        guard let t = o.topCandidates(1).first else { continue }
        let bb = o.boundingBox
        // 图标位于文字左侧：x 约 bb.minX-0.075 ~ bb.minX-0.015（归一化，按 1080 宽≈0.069）
        let iconX0 = bb.minX - 0.075, iconX1 = bb.minX - 0.012
        guard iconX0 > 0 else { continue }
        let y0 = bb.midY - 0.022, y1 = bb.midY + 0.022
        guard y0 > 0, y1 < 1 else { continue }
        let px = CGRect(x: iconX0 * W, y: (1 - y1) * H, width: (iconX1 - iconX0) * W, height: (y1 - y0) * H)
        guard let iconCG = cg.cropping(to: px) else { continue }
        // 排除几乎全白（无图标）
        var minV = 255
        if let data = iconCG.dataProvider?.data, let ptr = CFDataGetBytePtr(data) {
            let n = CFDataGetLength(data)
            for i in stride(from: 0, to: n - 3, by: 4) {
                let v = min(Int(ptr[i]), Int(ptr[i+1]), Int(ptr[i+2]))
                if v < minV { minV = v }
            }
        }
        guard minV < 160 else { continue }   // 图标是深色线条
        guard let fp = featurePrint(iconCG) else { continue }
        allItems.append(Item(text: t.string, icon: iconCG, key: path))
        // 聚类
        var matched = false
        for i in 0..<clusters.count {
            if distance(clusters[i].fp, fp) < 0.28 {
                clusters[i].items.append(t.string)
                matched = true; break
            }
        }
        if !matched {
            clusters.append((fp: fp, items: [t.string], rep: iconCG))
        }
    }
}

// 输出
print("簇数: \(clusters.count)  总项数: \(allItems.count)")
for (i, c) in clusters.enumerated() {
    let uniq = Array(NSOrderedSet(array: c.items)) as! [String]
    print(String(format: "--- 簇 %d : %d 项 ---", i, uniq.count))
    for s in uniq.prefix(14) { print("    \(s)") }
    // 存代表图标
    let rep = NSImage(cgImage: c.rep, size: NSSize(width: c.rep.width, height: c.rep.height))
    if let tiff = rep.tiffRepresentation, let bmp = NSBitmapImageRep(data: tiff),
       let png = bmp.representation(using: .png, properties: [:]) {
        try? png.write(to: URL(fileURLWithPath: "\(outDir)/cluster\(i).png"))
    }
}
