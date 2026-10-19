// Host-only Vision timing at production photo sizes; pass an absolute corpus/photos path.
import Foundation
import ImageIO
import Vision

func thumbnail(_ url: URL, long: Int, short: Int) -> CGImage {
    let src = CGImageSourceCreateWithURL(url as CFURL, nil)!
    let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil)! as NSDictionary
    let w = (props[kCGImagePropertyPixelWidth] as! NSNumber).doubleValue
    let h = (props[kCGImagePropertyPixelHeight] as! NSNumber).doubleValue
    let scale = min(1, min(Double(long) / max(w, h), Double(short) / min(w, h)))
    let opts: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: Int((max(w, h) * scale).rounded()),
    ]
    return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)!
}
func ms(_ f: () throws -> Void) rethrows -> Double {
    let t = DispatchTime.now().uptimeNanoseconds
    try f()
    return Double(DispatchTime.now().uptimeNanoseconds - t) / 1e6
}
precondition(CommandLine.arguments.count == 2, "Pass an absolute directory of JPG fixtures")
let folder = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
let files = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).filter { $0.pathExtension == "jpg" }.sorted {
    $0.path < $1.path
}
precondition(!files.isEmpty, "No JPG fixtures found")
var ocrTimes: [Double] = []
var labelTimes: [Double] = []
var emptyTimes: [Double] = []
var decodeTimes: [Double] = []
for round in 0..<4 {
    for file in files {
        try autoreleasepool {
            var large: CGImage!
            var small: CGImage!
            let decode = ms {
                large = thumbnail(file, long: 1600, short: 1080)
                small = thumbnail(file, long: 512, short: 512)
            }
            let text = VNRecognizeTextRequest()
            text.recognitionLevel = .accurate
            text.usesLanguageCorrection = true
            text.automaticallyDetectsLanguage = true
            let ocr = try ms { try VNImageRequestHandler(cgImage: large).perform([text]) }
            let labels = VNClassifyImageRequest()
            let label = try ms { try VNImageRequestHandler(cgImage: small).perform([labels]) }
            if round == 0 {
                print(
                    "cold/first", file.lastPathComponent, String(format: "decode=%.1f ocr=%.1f label=%.1f", decode, ocr, label), "blocks=",
                    text.results?.count ?? 0, "labels>=0.7=", labels.results?.filter { $0.confidence >= 0.7 }.count ?? 0)
            } else {
                ocrTimes.append(ocr)
                labelTimes.append(label)
                decodeTimes.append(decode)
                if text.results?.isEmpty != false { emptyTimes.append(ocr) }
            }
        }
    }
}
func report(_ name: String, _ times: [Double]) {
    let s = times.sorted()
    guard !s.isEmpty else { return }
    print(name, "n=", s.count, String(format: "p50=%.1f p95=%.1f max=%.1f ms", s[s.count / 2], s[min(s.count - 1, Int(Double(s.count) * 0.95))], s.last!))
}
report("decode", decodeTimes)
report("ocr", ocrTimes)
report("label", labelTimes)
report("ocr_empty", emptyTimes)
