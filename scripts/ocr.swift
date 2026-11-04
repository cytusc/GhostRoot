// ocr.swift — 批量 OCR 截图文本（macOS Vision framework）
// 用法: ./ocr <img1> <img2> ...   或   ./ocr --lang zh-Hans,en-US <imgs...>
import Foundation
import Vision
import AppKit

var langs = ["zh-Hans", "en-US"]
var paths: [String] = []
var args = Array(CommandLine.arguments.dropFirst())
if let i = args.firstIndex(of: "--lang"), i + 1 < args.count {
    langs = args[i + 1].split(separator: ",").map(String.init)
    args.removeSubrange(i...(i + 1))
}
paths = args

for path in paths {
    guard let img = NSImage(contentsOfFile: path),
          let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        FileHandle.standardError.write("skip: \(path)\n".data(using: .utf8)!)
        continue
    }
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.recognitionLanguages = langs
    req.usesLanguageCorrection = false
    // 优先读小字号/密集文本
    if #available(macOS 13.0, *) { req.minimumTextHeight = 0.008 }
    let handler = VNImageRequestHandler(cgImage: cg, options: [:])
    do { try handler.perform([req]) } catch {
        FileHandle.standardError.write("ocr fail: \(path) \(error)\n".data(using: .utf8)!)
        continue
    }
    print("===== \(URL(fileURLWithPath: path).lastPathComponent) =====")
    let obs = req.results ?? []
    // 按 y 坐标从上到下排序
    let sorted = obs.sorted { a, b in
        let ay = a.boundingBox.midY, by = b.boundingBox.midY
        if abs(ay - by) > 0.01 { return ay > by }
        return a.boundingBox.minX < b.boundingBox.minX
    }
    for o in sorted {
        if let t = o.topCandidates(1).first {
            let b = o.boundingBox
            print(String(format: "y=%.3f x=%.3f | %@", b.midY, b.minX, t.string))
        }
    }
}
