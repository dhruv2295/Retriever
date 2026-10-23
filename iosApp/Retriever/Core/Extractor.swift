@preconcurrency import AVFoundation
import ImageIO
import Network
import PDFKit
import Photos
import RetrieverCore
import UIKit
import Vision
import ZIPFoundation
import os

/// Reads files for the Kotlin IosExtractor and content hasher. Kotlin calls from its own threads, so the blocking
/// waits here are fine there; Swift code calls them through `blocking`, or uses the async `videoFrame`. Every failure
/// is nil (or -1 / empty), never a throw. Unchecked Sendable: each mutable field sits behind its own lock.
final class FileExtractor: NSObject, NativeExtractor, @unchecked Sendable {
    private let folders: FolderSource
    private static let ocrSide: CGFloat = 1600
    private static let log = Logger(subsystem: "app.retriever", category: "extractor")
    private let pathMonitor = NWPathMonitor()
    private let cloudLock = NSLock()
    private var cloudOnly = 0

    private let allowMobileData: @Sendable () -> Bool

    init(folders: FolderSource, allowMobileData: @escaping @Sendable () -> Bool) {
        self.folders = folders
        self.allowMobileData = allowMobileData
        super.init()
        pathMonitor.start(queue: DispatchQueue(label: "app.retriever.extractor.network"))
    }

    // MARK: Images

    // Every entry point drains its own pool: Kotlin calls them in loops inside one task, which would otherwise
    // keep each page's renders and PDFKit objects alive until the whole file is done.

    func imageJpeg(uri: String, maxSide: Int32) -> Data? {
        autoreleasepool {
            let side = CGFloat(maxSide)
            return image(uri, asset: asset(uri), size: CGSize(width: side, height: side)).flatMap { Self.jpeg($0, maxSide: side) }
        }
    }

    /// One decode (or one page render) serves the OCR, the labels and the model's JPEG.
    func scan(uri: String, page: Int32, jpegSide: Int32) -> NativeScan? {
        autoreleasepool {
            if page >= 0 {
                return pdfPage(uri, Int(page)).flatMap { render($0, maxSide: Self.ocrSide) }.flatMap { read($0, labels: false, jpegSide: CGFloat(jpegSide)) }
            }
            let asset = asset(uri)
            return image(uri, asset: asset, size: ocrSize(uri, asset)).flatMap { read($0, labels: true, jpegSide: CGFloat(jpegSide)) }
        }
    }

    /// Text and (for a photo) labels in one Vision pass over the picture as shown, and its JPEG.
    func read(_ image: UIImage, labels: Bool, jpegSide: CGFloat) -> NativeScan? {
        guard let cg = image.cgImage, let jpeg = Self.jpeg(image, maxSide: jpegSide) else { return nil }
        _ = Self.logLanguages
        let text = VNRecognizeTextRequest()
        text.recognitionLevel = .accurate
        text.usesLanguageCorrection = true
        text.automaticallyDetectsLanguage = true
        let classify = VNClassifyImageRequest()
        let requests = labels ? [text, classify] : [text]
        let id = track {
            for request in requests { request.cancel() }
        }
        defer { untrack(id) }
        // One handler keeps the decoded image for both. One perform each: a request that fails (the simulator can't
        // classify) stops the others in the same perform, and the text must not go with it.
        let handler = VNImageRequestHandler(cgImage: cg, orientation: CGImagePropertyOrientation(image.imageOrientation))
        for request in requests { try? handler.perform([request]) }
        let found = (text.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
        let tags = labels ? classify.results.map(Self.labels) : nil
        return NativeScan(jpeg: jpeg, text: found, labels: tags)
    }

    private static func labels(_ results: [VNClassificationObservation]) -> String {
        results.filter { $0.confidence >= 0.7 }.map { $0.identifier.lowercased().replacingOccurrences(of: "_", with: " ") }.joined(separator: "\n")
    }

    /// The picture as shown, at most `maxSide` px: redrawn upright when it is larger or turned, so the model never
    /// depends on a JPEG's orientation tag.
    static func jpeg(_ image: UIImage, maxSide: CGFloat) -> Data? {
        let (w, h) = (image.size.width * image.scale, image.size.height * image.scale)
        let fit = min(1, maxSide / max(w, h, 1))
        guard fit < 1 || image.imageOrientation != .up else { return image.jpegData(compressionQuality: 0.85) }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let size = CGSize(width: max(1, (w * fit).rounded()), height: max(1, (h * fit).rounded()))
        return UIGraphicsImageRenderer(size: size, format: format).jpegData(withCompressionQuality: 0.85) { ctx in
            ctx.cgContext.interpolationQuality = .high
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    /// What Vision can read on this OS, logged once per OS version (no user data), so a missing script shows up.
    private static let logLanguages: Void = {
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        guard UserDefaults.standard.string(forKey: "ocrLanguagesLoggedOS") != os else { return }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        let languages = (try? request.supportedRecognitionLanguages()) ?? []
        log.info("OCR languages on \(os, privacy: .public): \(languages.joined(separator: ","), privacy: .public)")
        UserDefaults.standard.set(os, forKey: "ocrLanguagesLoggedOS")
    }()

    /// Core's OCR size for the image's own pixel size; a square 1600 when the size is unknown.
    private func ocrSize(_ uri: String, _ asset: PHAsset?) -> CGSize {
        var w = 0
        var h = 0
        if let asset {
            (w, h) = (asset.pixelWidth, asset.pixelHeight)
        } else if let url = folders.resolve(uri), let src = CGImageSourceCreateWithURL(url as CFURL, nil),
            let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        {
            (w, h) = (p[kCGImagePropertyPixelWidth] as? Int ?? 0, p[kCGImagePropertyPixelHeight] as? Int ?? 0)
        }
        guard w > 0, h > 0 else { return CGSize(width: Self.ocrSide, height: Self.ocrSide) }
        let screenshot = asset?.mediaSubtypes.contains(.photoScreenshot) == true
        let fit =
            screenshot
            ? ExtractionKt.ocrTargetSize(w: Int32(w), h: Int32(h))
            : ExtractionKt.fitSize(w: Int32(w), h: Int32(h), maxShort: 1080, maxLong: 1600)
        return CGSize(width: fit.first!.intValue, height: fit.second!.intValue)
    }

    /// An iCloud-only photo fetches its small derivative on Wi-Fi, or on any network when the person allows mobile
    /// data; otherwise it keeps only its name. Async so a stalled download can be cancelled; the handler may come
    /// back on the main queue, so this must never wait there.
    private func image(_ uri: String, asset: PHAsset?, size: CGSize) -> UIImage? {
        if let asset {
            assert(!Thread.isMainThread)
            let path = pathMonitor.currentPath
            let wifi = path.status == .satisfied && !path.isExpensive && !path.isConstrained
            let options = PHImageRequestOptions()
            options.deliveryMode = .highQualityFormat
            options.resizeMode = .exact
            options.isNetworkAccessAllowed = wifi || allowMobileData()
            let done = DispatchSemaphore(value: 0)
            let box = Box<UIImage>()
            let id = PHImageManager.default().requestImage(for: asset, targetSize: size, contentMode: .aspectFit, options: options) { [self] image, info in
                box.value = image
                if image == nil, info?[PHImageResultIsInCloudKey] as? Bool == true {
                    let n = cloudLock.withLock {
                        cloudOnly += 1
                        return cloudOnly
                    }
                    Self.log.info("iCloud-only photos left without an image: \(n)")
                }
                done.signal()
            }
            let tracked = track {
                PHImageManager.default().cancelImageRequest(id)
                done.signal()
            }
            defer { untrack(tracked) }
            guard done.wait(timeout: .now() + 20) == .success else {
                PHImageManager.default().cancelImageRequest(id)
                return nil
            }
            return box.value
        }
        guard let url = folders.resolve(uri), let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(size.width, size.height),
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary).map { UIImage(cgImage: $0) }
    }

    // MARK: PDF

    /// The first call for each file: it opens the PDF afresh, so an edited file is never read from the cache.
    func pdfPageCount(uri: String) -> Int32 {
        autoreleasepool {
            pdfLock.withLock { lastPdf = nil }
            guard let doc = pdf(uri), !doc.isLocked else { return -1 }
            return Int32(doc.pageCount)
        }
    }

    func pdfPageText(uri: String, page: Int32) -> String? {
        autoreleasepool { pdfPage(uri, Int(page))?.string }
    }

    func pdfPageJpeg(uri: String, page: Int32, maxSide: Int32) -> Data? {
        autoreleasepool { pdfPage(uri, Int(page)).flatMap { render($0, maxSide: CGFloat(maxSide)) }?.jpegData(compressionQuality: 0.85) }
    }

    /// The last PDF opened, so a file's page calls parse it once rather than three or four times per page.
    private let pdfLock = NSLock()
    private var lastPdf: (uri: String, doc: PDFDocument)?

    private func pdf(_ uri: String) -> PDFDocument? {
        pdfLock.withLock {
            if let last = lastPdf, last.uri == uri { return last.doc }
            let doc = folders.resolve(uri).flatMap { PDFDocument(url: $0) }
            lastPdf = doc.map { (uri, $0) }
            return doc
        }
    }

    private func pdfPage(_ uri: String, _ index: Int) -> PDFPage? {
        guard let doc = pdf(uri), !doc.isLocked, index >= 0, index < doc.pageCount else { return nil }
        return doc.page(at: index)
    }

    /// The page on white: PDFs with no background would otherwise come out black in a JPEG.
    private func render(_ page: PDFPage, maxSide: CGFloat) -> UIImage? {
        let thumb = page.thumbnail(of: CGSize(width: maxSide, height: maxSide), for: .mediaBox)
        guard thumb.size.width > 0, thumb.size.height > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: thumb.size, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: thumb.size))
            thumb.draw(at: .zero)
        }
    }

    // MARK: Video

    /// The first call for each video: it loads the asset afresh, and the moments after it reuse it (as lastPdf does).
    func videoDurationMs(uri: String) -> Int64 {
        let asset = avAsset(uri)
        videoLock.withLock { lastVideo = asset.map { (uri, $0) } }
        guard let asset, let duration = wait({ try await asset.load(.duration) }), duration.isNumeric else { return 0 }
        return Int64(duration.seconds * 1000)
    }

    func videoFrameJpeg(uri: String, ms: Int64, maxSide: Int32) -> Data? {
        autoreleasepool {
            guard let asset = videoLock.withLock({ lastVideo.flatMap { $0.uri == uri ? $0.asset : nil } }) ?? avAsset(uri) else { return nil }
            return wait({ try await Self.frame(asset, ms: ms, maxSide: CGFloat(maxSide)) }).flatMap { UIImage(cgImage: $0).jpegData(compressionQuality: 0.85) }
        }
    }

    /// For Swift's own callers (thumbnails): async all the way, so a grid of video cells never blocks Swift's threads.
    @concurrent func videoFrame(uri: String, ms: Int64, maxSide: CGFloat) async -> UIImage? {
        let video: AVAsset?
        if let asset = asset(uri) {
            let manager = PHImageManager.default()
            let found: Box<AVAsset>? = await awaitPhotoRequest(
                cancel: { manager.cancelImageRequest($0) },
                start: { done in
                    let options = PHVideoRequestOptions()
                    options.isNetworkAccessAllowed = false
                    options.deliveryMode = .highQualityFormat
                    return manager.requestAVAsset(forVideo: asset, options: options) { @Sendable av, _, _ in
                        let box = Box<AVAsset>()
                        box.value = av
                        done(box)
                    }
                })
            video = found?.value
        } else {
            video = folders.resolve(uri).map { AVURLAsset(url: $0) }
        }
        guard let video, let frame = try? await Self.frame(video, ms: ms, maxSide: maxSide) else { return nil }
        return UIImage(cgImage: frame)
    }

    /// The last video read, so its moments load the asset once rather than once each.
    private let videoLock = NSLock()
    private var lastVideo: (uri: String, asset: AVAsset)?

    private static func frame(_ asset: AVAsset, ms: Int64, maxSide: CGFloat) async throws -> CGImage {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxSide, height: maxSide)
        let tolerance = CMTime(seconds: 1, preferredTimescale: 600)
        generator.requestedTimeToleranceBefore = tolerance
        generator.requestedTimeToleranceAfter = tolerance
        return try await generator.image(at: CMTime(value: ms, timescale: 1000)).image
    }

    private func avAsset(_ uri: String) -> AVAsset? {
        if let asset = asset(uri) {
            let options = PHVideoRequestOptions()
            options.isNetworkAccessAllowed = false
            options.deliveryMode = .highQualityFormat
            let done = DispatchSemaphore(value: 0)
            let box = Box<AVAsset>()
            PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { av, _, _ in
                box.value = av
                done.signal()
            }
            done.wait()
            return box.value
        }
        return folders.resolve(uri).map { AVURLAsset(url: $0) }
    }

    // MARK: Text and archives

    func readBytes(uri: String, offset: Int64, length: Int32) -> Data? {
        autoreleasepool {
            guard let url = folders.resolve(uri), let handle = try? FileHandle(forReadingFrom: url) else { return nil }
            defer { try? handle.close() }
            guard (try? handle.seek(toOffset: UInt64(max(offset, 0)))) != nil else { return nil }
            do {
                return try handle.read(upToCount: Int(length)) ?? Data()  // nil at the end of the file
            } catch {
                return nil
            }
        }
    }

    /// The last archive listed, so an N-entry deck opens its zip once rather than N+1 times (as lastPdf does). The
    /// listing reopens it afresh, so an edited file is never read stale; a single-entry read (docx) is not cached.
    private let archiveLock = NSLock()
    private var lastArchive: (uri: String, archive: Archive)?

    private func openArchive(_ uri: String) -> Archive? {
        folders.resolve(uri).flatMap { try? Archive(url: $0, accessMode: .read, pathEncoding: nil) }
    }

    func zipEntryNames(uri: String) -> [String] {
        guard let archive = openArchive(uri) else { return [] }
        archiveLock.withLock { lastArchive = (uri, archive) }
        return archive.map(\.path)
    }

    func zipEntryText(uri: String, name: String, maxBytes: Int32) -> String? {
        guard let archive = archiveLock.withLock({ lastArchive.flatMap { $0.uri == uri ? $0.archive : nil } }) ?? openArchive(uri),
            let entry = archive[name]
        else { return nil }
        let cap = Int(maxBytes)
        var out = Data()
        do {
            _ = try archive.extract(entry, skipCRC32: true) { chunk in
                out.append(chunk.prefix(cap - out.count))
                if out.count >= cap { throw Capped() }
            }
        } catch is Capped {
        } catch {
            return nil
        }
        return String(decoding: out, as: UTF8.self)
    }

    private struct Capped: Error {}

    // MARK: Helpers

    /// Runs one of the blocking calls above for Swift code, on a GCD thread: a wait on one of Swift's cooperative
    /// threads (one per core) would hold up every other task in the app.
    static func blocking<T: Sendable>(_ work: @escaping @Sendable () -> T) async -> T {
        await withCheckedContinuation { done in DispatchQueue.global(qos: .userInitiated).async { done.resume(returning: work()) } }
    }

    /// Background time is up: the PhotoKit waits and Vision passes running now end at once, with nothing, so the
    /// cancelled batch gets to its end without them.
    func cancelInFlight() {
        let cancels = inFlightLock.withLock {
            defer { inFlight = [:] }
            return Array(inFlight.values)
        }
        for cancel in cancels { cancel() }
    }

    private let inFlightLock = NSLock()
    private var inFlight: [Int: () -> Void] = [:]
    private var nextInFlight = 0

    private func track(_ cancel: @escaping () -> Void) -> Int {
        inFlightLock.withLock {
            nextInFlight += 1
            inFlight[nextInFlight] = cancel
            return nextInFlight
        }
    }

    private func untrack(_ id: Int) {
        _ = inFlightLock.withLock { inFlight.removeValue(forKey: id) }
    }

    private func asset(_ uri: String) -> PHAsset? {
        guard uri.hasPrefix("ph://") else { return nil }
        return PHAsset.fetchAssets(withLocalIdentifiers: [String(uri.dropFirst("ph://".count))], options: nil).firstObject
    }

    /// Runs async AVFoundation loading from the synchronous bridge; callers are on Kotlin's worker threads.
    private func wait<T>(_ op: @escaping @Sendable () async throws -> T) -> T? {
        let done = DispatchSemaphore(value: 0)
        let box = Box<T>()
        Task.detached(priority: .utility) {
            box.value = try? await op()
            done.signal()
        }
        done.wait()
        return box.value
    }
}

private final class Box<T>: @unchecked Sendable {
    var value: T?
}

extension CGImagePropertyOrientation {
    /// Vision takes Core Graphics' (EXIF) numbering, which differs from UIKit's (Apple's sample "Classifying Images
    /// with Vision and Core ML").
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
