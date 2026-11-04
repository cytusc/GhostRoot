// ocr_color.swift — OCR + 文本颜色判定（识别高风险/告警项）
// 思路: OCR 出每个文本框 → 采样框内像素，取「最暗且偏红」像素作为字色
//       若字色 R 显著大于 G/B → 判定为高风险（红色告警）
// 用法: ./ocr_color <img...>
import Foundation
import Vision
import AppKit

let args = Array(CommandLine.arguments.dropFirst())
guard !args.isEmpty else { exit(1) }

struct Hit { var text: String; var r: Int; var g: Int; var b: Int; var screen: String }
var hits: [Hit] = []

for (si, path) in args.enumerated() {
    guard let img = NSImage(contentsOfFile: path),
          let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else { continue }
    let W = cg.width, H = cg.height
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.recognitionLanguages = ["zh-Hans", "en-US"]
    req.usesLanguageCorrection = false
    if #available(macOS 13.0, *) { req.minimumTextHeight = 0.005 }
    try? VNImageRequestHandler(cgImage: cg, options: [:]).perform([req])
    guard let data = cg.dataProvider?.data, let ptr = CFDataGetBytePtr(data) else { continue }
    let n = CFDataGetLength(data)
    let bpr = W * 4
    for o in req.results ?? [] {
        guard let t = o.topCandidates(1).first else { continue }
        let bb = o.boundingBox
        let x0 = Int(bb.minX * Double(W)), x1 = Int(bb.maxX * Double(W))
        let yTop = Int((1 - bb.maxY) * Double(H)), yBot = Int((1 - bb.minY) * Double(H))
        let sx0 = x0 + (x1 - x0) / 6, sx1 = x1 - (x1 - x0) / 6
        let sy0 = yTop + (yBot - yTop) / 4, sy1 = yBot - (yBot - yTop) / 4
        guard sx1 > sx0, sy1 > sy0 else { continue }
        var bestLum = 1000, br = 0, bg = 0, bb2 = 0
        for y in stride(from: sy0, to: sy1, by: 1) {
            for x in stride(from: sx0, to: sx1, by: 1) {
                let i = y * bpr + x * 4
                if i + 2 >= n { continue }
                let r = Int(ptr[i]), g = Int(ptr[i+1]), b = Int(ptr[i+2])
                let lum = (r + g + b) / 3
                if lum < bestLum { bestLum = lum; br = r; bg = g; bb2 = b }
            }
        }
        guard bestLum < 110 else { continue }
        hits.append(Hit(text: t.string, r: br, g: bg, b: bb2, screen: URL(fileURLWithPath: path).lastPathComponent))
    }
}

// 去重
var seen = Set<String>()
var uniq: [Hit] = []
for h in hits {
    let key = h.text + "|" + "\(h.r/40),\(h.g/40),\(h.b/40)"
    if seen.insert(key).inserted { uniq.append(h) }
}

print("=== 红色/彩色字（疑似高风险标记）===")
var reds = 0
for h in uniq {
    if h.r > h.g + 45 && h.r > h.b + 45 {
        reds += 1
        print("[\(h.screen)] RGB(\(h.r),\(h.g),\(h.b))  \(h.text)")
    }
}
print("红色项数: \(reds) / 采样 \(uniq.count)")
print("\n=== 所有文本（去重）===")
for h in uniq { print("[\(h.r),\(h.g),\(h.b)] \(h.text)") }
