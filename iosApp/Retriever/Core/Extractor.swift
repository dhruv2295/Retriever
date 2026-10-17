@preconcurrency import AVFoundation
import ImageIO
import Network
import os
import PDFKit
import Photos
import RetrieverCore
import UIKit
import Vision
import ZIPFoundation

/// Reads files for the Kotlin IosExtractor and content hasher. Kotlin calls from background threads, so the
/// blocking waits here are fine. Every failure is nil (or -1 / empty), never a throw. Unchecked Sendable: each
/// mutable field sits behind its own lock.
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
        autoreleasepool { image(uri, maxSide: CGFloat(maxSide))?.jpegData(compressionQuality: 0.85) }
    }

    func ocr(uri: String, page: Int32) -> String? {
        autoreleasepool {
            let source = page >= 0 ? pdfPage(uri, Int(page)).flatMap { render($0, maxSide: Self.ocrSide) } : image(uri, maxSide: Self.ocrSide)
            guard let cg = source?.cgImage else { return nil }
            _ = Self.logLanguages
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.automaticallyDetectsLanguage = true
            guard (try? VNImageRequestHandler(cgImage: cg).perform([request])) != nil else { return nil }
            return (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
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

    /// An iCloud-only photo fetches its small derivative on Wi-Fi, or on any network when the person allows mobile
    /// data; otherwise it keeps only its name. Async so a stalled download can be cancelled; the handler may come
    /// back on the main queue, so this must never wait there.
    private func image(_ uri: String, maxSide: CGFloat) -> UIImage? {
        if let asset = asset(uri) {
            assert(!Thread.isMainThread)
            let path = pathMonitor.currentPath
            let wifi = path.status == .satisfied && !path.isExpensive && !path.isConstrained
            let options = PHImageRequestOptions()
            options.deliveryMode = .highQualityFormat
            options.resizeMode = .exact
            options.isNetworkAccessAllowed = wifi || allowMobileData()
            let done = DispatchSemaphore(value: 0)
            let box = Box<UIImage>()
            let id = PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: maxSide, height: maxSide), contentMode: .aspectFit, options: options) { [self] image, info in
                box.value = image
                if image == nil, info?[PHImageResultIsInCloudKey] as? Bool == true {
                    let n = cloudLock.withLock { cloudOnly += 1; return cloudOnly }
                    Self.log.info("iCloud-only photos left without an image: \(n)")
                }
                done.signal()
            }
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
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
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

    func videoDurationMs(uri: String) -> Int64 {
        guard let asset = avAsset(uri), let duration = wait({ try await asset.load(.duration) }), duration.isNumeric else { return 0 }
        return Int64(duration.seconds * 1000)
    }

    func videoFrameJpeg(uri: String, ms: Int64, maxSide: Int32) -> Data? {
        autoreleasepool { videoFrame(uri, ms: ms, maxSide: maxSide) }
    }

    private func videoFrame(_ uri: String, ms: Int64, maxSide: Int32) -> Data? {
        guard let asset = avAsset(uri) else { return nil }
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: Int(maxSide), height: Int(maxSide))
        let tolerance = CMTime(seconds: 1, preferredTimescale: 600)
        generator.requestedTimeToleranceBefore = tolerance
        generator.requestedTimeToleranceAfter = tolerance
        let time = CMTime(value: ms, timescale: 1000)
        return wait({ try await generator.image(at: time).image }).flatMap { UIImage(cgImage: $0).jpegData(compressionQuality: 0.85) }
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

    func textHead(uri: String, maxBytes: Int32) -> String? {
        readBytes(uri: uri, offset: 0, length: maxBytes).map { String(decoding: $0, as: UTF8.self) }
    }

    func readBytes(uri: String, offset: Int64, length: Int32) -> Data? {
        autoreleasepool {
            guard let url = uri.hasPrefix("ph://") ? exported(uri) : folders.resolve(uri), let handle = try? FileHandle(forReadingFrom: url) else { return nil }
            defer { try? handle.close() }
            guard (try? handle.seek(toOffset: UInt64(max(offset, 0)))) != nil else { return nil }
            do {
                return try handle.read(upToCount: Int(length)) ?? Data() // nil at the end of the file
            } catch {
                return nil
            }
        }
    }

    /// A photo or video as a temp file, for the content hash. ponytail: a one-entry export cache (the hasher reads
    /// one file at a time) and nothing over 1 GB; a streaming hash if big videos matter. PhotoKit has no public byte
    /// size, so the cap is counted while copying and the copy is cancelled past it.
    private let exportLock = NSLock()
    private var export: (uri: String, url: URL)?

    private func exported(_ uri: String) -> URL? {
        exportLock.withLock {
            if let export, export.uri == uri { return export.url }
            if let old = export?.url { try? FileManager.default.removeItem(at: old) }
            export = nil
            guard let asset = asset(uri), let current = PhotosSource.renditions(asset).current else { return nil }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("export-\(UUID().uuidString)")
            guard FileManager.default.createFile(atPath: url.path, contents: nil), let handle = try? FileHandle(forWritingTo: url) else { return nil }
            let options = PHAssetResourceRequestOptions()
            options.isNetworkAccessAllowed = false
            let copy = OSAllocatedUnfairLock(initialState: (bytes: Int64(0), failed: false, id: PHAssetResourceDataRequestID?.none))
            let done = DispatchSemaphore(value: 0)
            let id = PHAssetResourceManager.default().requestData(for: current, options: options, dataReceivedHandler: { chunk in
                let cancel: PHAssetResourceDataRequestID? = copy.withLock { s in
                    if !s.failed {
                        s.bytes += Int64(chunk.count)
                        s.failed = s.bytes > 1 << 30 || (try? handle.write(contentsOf: chunk)) == nil
                    }
                    return s.failed ? s.id : nil // nil id: the request hasn't returned yet; a later chunk cancels
                }
                if let cancel { PHAssetResourceManager.default().cancelDataRequest(cancel) }
            }, completionHandler: { error in
                if error != nil { copy.withLock { $0.failed = true } }
                done.signal()
            })
            copy.withLock { $0.id = id }
            done.wait()
            try? handle.close()
            guard !copy.withLock({ $0.failed }) else {
                try? FileManager.default.removeItem(at: url)
                return nil
            }
            export = (uri, url)
            return url
        }
    }

    func zipEntryNames(uri: String) -> [String] {
        guard let archive = archive(uri) else { return [] }
        return archive.map(\.path)
    }

    func zipEntryText(uri: String, name: String, maxBytes: Int32) -> String? {
        guard let archive = archive(uri), let entry = archive[name] else { return nil }
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

    private func archive(_ uri: String) -> Archive? {
        folders.resolve(uri).flatMap { try? Archive(url: $0, accessMode: .read, pathEncoding: nil) }
    }

    // MARK: Helpers

    private func asset(_ uri: String) -> PHAsset? {
        guard uri.hasPrefix("ph://") else { return nil }
        return PHAsset.fetchAssets(withLocalIdentifiers: [String(uri.dropFirst("ph://".count))], options: nil).firstObject
    }

    /// Runs async AVFoundation loading from the synchronous bridge; callers are on Kotlin's IO threads.
    private func wait<T>(_ op: @escaping @Sendable () async throws -> T) -> T? {
        let done = DispatchSemaphore(value: 0)
        let box = Box<T>()
        Task.detached {
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
