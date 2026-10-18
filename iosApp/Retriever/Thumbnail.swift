// Adapted from mazzzystar/Queryable Queryable/Queryable/PhotoHelper/CachedImageManager.swift
// (Apple Sample Code License; Queryable took it from Apple sample code).
// Modified for Retriever: one async load per cell through an NSCache, the PHCachingImageManager setup (no network,
// cache off for high quality), the matched PDF page or video moment instead of the first frame, and
// QLThumbnailGenerator for files in folders.

import PDFKit
import Photos
import QuickLookThumbnailing
import RetrieverCore
import SwiftUI

/// Small pictures of files: photos from the library without touching iCloud, folder files through Quick Look, and
/// the matched page of a PDF or moment of a video.
@MainActor
enum Thumbnails {
    /// Bounded by decoded bytes, not count: a 768 px cell costs far more than a 52 pt row's.
    private static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.totalCostLimit = 32 << 20
        return c
    }()
    private static let photos: PHCachingImageManager = {
        let m = PHCachingImageManager()
        m.allowsCachingHighQualityImages = false
        return m
    }()

    static func load(_ file: SourceFile, locator: String, pixels: CGFloat, services: AppServices?) async -> UIImage? {
        let key = "\(file.uri)|\(file.modifiedAt)|\(file.size)|\(locator)|\(Int(pixels))" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        guard let services else { return nil }
        let uri = file.uri
        let image: UIImage?
        if file.kind == .video, Locator.shared.msOf(l: locator) != nil || !uri.hasPrefix("ph://") {
            // A folder video has no poster frame, so it shows the first second.
            let ms = Locator.shared.msOf(l: locator)?.int64Value ?? 1000
            image = await Task.detached { services.extractor.videoFrameJpeg(uri: uri, ms: ms, maxSide: Int32(pixels)).flatMap(UIImage.init(data:)) }.value
        } else if uri.hasPrefix("ph://") {
            image = await photo(String(uri.dropFirst(5)), pixels: pixels)
        } else if let url = services.folders.resolve(uri) {
            if file.kind == .pdf, let page = Locator.shared.pageOf(l: locator)?.intValue {
                image = await Task.detached { pdfPage(url, index: page - 1, pixels: pixels) }.value
            } else {
                image = await quickLook(url, pixels: pixels)
            }
        } else {
            image = nil
        }
        if let image { cache.setObject(image, forKey: key, cost: image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0) }
        return image
    }

    /// Request sizes (px) shared by every surface, so a picture Home loaded is a cache hit in Browse.
    static func bucket(_ px: CGFloat) -> CGFloat {
        [256, 384, 512, 768].first { px <= $0 } ?? 768
    }

    /// What PhotoKit is warming, so the next call can stop it: assets and the square size asked for them.
    private static var warmed: [(assets: [PHAsset], px: CGFloat)] = []

    /// Starts PhotoKit caching for Home's mosaic (the first is the big cell) and stops it for the set it replaces.
    /// The request matches `photo(_:pixels:)`, or the caching manager would not serve it.
    static func warm(_ files: [SourceFile], big: CGFloat, small: CGFloat) {
        stopWarming()
        let ids = files.map { $0.uri.hasPrefix("ph://") ? String($0.uri.dropFirst(5)) : "" }
        guard ids.contains(where: { !$0.isEmpty }) else { return }
        var byId: [String: PHAsset] = [:]
        PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil).enumerateObjects { a, _, _ in byId[a.localIdentifier] = a }
        let assets = ids.map { byId[$0] }
        warmed = [(assets.prefix(1).compactMap { $0 }, big), (assets.dropFirst().compactMap { $0 }, small)]
        for (assets, px) in warmed {
            photos.startCachingImages(for: assets, targetSize: CGSize(width: px, height: px), contentMode: .aspectFill, options: requestOptions)
        }
    }

    static func stopWarming() {
        for (assets, px) in warmed {
            photos.stopCachingImages(for: assets, targetSize: CGSize(width: px, height: px), contentMode: .aspectFill, options: requestOptions)
        }
        warmed = []
    }

    private static let requestOptions: PHImageRequestOptions = {
        let o = PHImageRequestOptions()
        o.isNetworkAccessAllowed = false
        o.deliveryMode = .highQualityFormat  // one callback
        o.resizeMode = .fast
        return o
    }()

    private static func photo(_ id: String, pixels: CGFloat) async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return nil }
        return await withCheckedContinuation { done in
            photos.requestImage(for: asset, targetSize: CGSize(width: pixels, height: pixels), contentMode: .aspectFill, options: requestOptions) { image, _ in
                done.resume(returning: image)
            }
        }
    }

    nonisolated private static func pdfPage(_ url: URL, index: Int, pixels: CGFloat) -> UIImage? {
        guard let page = PDFDocument(url: url)?.page(at: index) else { return nil }
        return page.thumbnail(of: CGSize(width: pixels, height: pixels), for: .cropBox)
    }

    private static func quickLook(_ url: URL, pixels: CGFloat) async -> UIImage? {
        let request = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: pixels, height: pixels), scale: 1, representationTypes: .thumbnail)
        guard let image = try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: request).uiImage else { return nil }
        // A mostly-white page (docx, short pdf) fills the tile with white; nil shows the kind's icon instead.
        return isBlank(image) ? nil : image
    }

    /// True when the picture is near-white everywhere once shrunk to 16x16, i.e. a page with no visible content.
    // ponytail: fixed 0.93 cut-off; a very pale genuine image would show the icon too.
    nonisolated private static func isBlank(_ image: UIImage) -> Bool {
        guard let cg = image.cgImage else { return false }
        let n = 16
        var px = [UInt8](repeating: 0, count: n * n * 4)
        let drew = px.withUnsafeMutableBytes { buf -> Bool in
            guard
                let ctx = CGContext(
                    data: buf.baseAddress, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            ctx.setFillColor(UIColor.white.cgColor)
            ctx.fill(CGRect(x: 0, y: 0, width: n, height: n))
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: n, height: n))
            return true
        }
        guard drew else { return false }
        return stride(from: 0, to: px.count, by: 4).allSatisfy { min(px[$0], px[$0 + 1], px[$0 + 2]) > 237 }
    }
}

extension EnvironmentValues {
    /// Set by RootView on a real index; fixtures leave it nil and cells show the kind's icon.
    @Entry var services: AppServices?
}

/// The file's picture filling its frame, or its kind's icon until (or unless) there is one.
struct Thumb: View {
    let file: SourceFile
    var locator = ""
    /// The longest side wanted, in points.
    let points: CGFloat
    /// Home's cells ask for one of the shared sizes (`Thumbnails.bucket`), so the same picture is not made twice.
    var bucketed = false
    @Environment(\.displayScale) private var scale
    @Environment(\.services) private var services
    @State private var image: UIImage?

    private var pixels: CGFloat { bucketed ? Thumbnails.bucket(points * scale) : points * scale }

    static func requestID(_ file: SourceFile, locator: String, pixels: CGFloat) -> String {
        "\(file.uri)|\(file.modifiedAt)|\(file.size)|\(locator)|\(Int(pixels))"
    }

    var body: some View {
        Rectangle()
            .fill(.fill.tertiary)
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Image(systemName: Format.icon(file.kind)).font(.title2).foregroundStyle(.secondary)
                }
            }
            .clipped()
            .task(id: Self.requestID(file, locator: locator, pixels: pixels)) {
                image = await Thumbnails.load(file, locator: locator, pixels: pixels, services: services)
            }
            .accessibilityHidden(true)
    }
}
