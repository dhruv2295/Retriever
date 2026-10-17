// Adapted from mazzzystar/Queryable Queryable/Queryable/PhotoHelper/CachedImageManager.swift
// (Apple Sample Code License; Queryable took it from Apple sample code).
// Modified for Retriever: one async load per cell through an NSCache, the PHCachingImageManager setup (no network,
// cache off for high quality), the matched PDF page or video moment instead of the first frame, and
// QLThumbnailGenerator for files in folders.

import Photos
import PDFKit
import QuickLookThumbnailing
import RetrieverCore
import SwiftUI

/// Small pictures of files: photos from the library without touching iCloud, folder files through Quick Look, and
/// the matched page of a PDF or moment of a video.
@MainActor
enum Thumbnails {
    /// Set when the app runs on a real index; fixtures leave it nil and cells show the kind's icon.
    static var services: AppServices?

    private static let cache = NSCache<NSString, UIImage>()
    private static let photos: PHCachingImageManager = {
        let m = PHCachingImageManager()
        m.allowsCachingHighQualityImages = false
        return m
    }()

    static func load(_ file: SourceFile, locator: String, pixels: CGFloat) async -> UIImage? {
        let key = "\(file.uri)|\(file.modifiedAt)|\(file.size)|\(locator)|\(Int(pixels))" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        guard let services else { return nil }
        let uri = file.uri
        let image: UIImage?
        if file.kind == .video, Format.ms(locator) != nil || !uri.hasPrefix("ph://") {
            // A folder video has no poster frame, so it shows the first second.
            let ms = Format.ms(locator) ?? 1000
            image = await Task.detached { services.extractor.videoFrameJpeg(uri: uri, ms: ms, maxSide: Int32(pixels)).flatMap(UIImage.init(data:)) }.value
        } else if uri.hasPrefix("ph://") {
            image = await photo(String(uri.dropFirst(5)), pixels: pixels)
        } else if let url = services.folders.resolve(uri) {
            if file.kind == .pdf, let page = Format.page(locator) {
                image = await Task.detached { pdfPage(url, index: page - 1, pixels: pixels) }.value
            } else {
                image = await quickLook(url, pixels: pixels)
            }
        } else {
            image = nil
        }
        if let image { cache.setObject(image, forKey: key) }
        return image
    }

    private static func photo(_ id: String, pixels: CGFloat) async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return nil }
        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = false
        options.deliveryMode = .highQualityFormat // one callback
        options.resizeMode = .fast
        return await withCheckedContinuation { done in
            photos.requestImage(for: asset, targetSize: CGSize(width: pixels, height: pixels), contentMode: .aspectFill, options: options) { image, _ in
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
            guard let ctx = CGContext(data: buf.baseAddress, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.setFillColor(UIColor.white.cgColor)
            ctx.fill(CGRect(x: 0, y: 0, width: n, height: n))
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: n, height: n))
            return true
        }
        guard drew else { return false }
        return stride(from: 0, to: px.count, by: 4).allSatisfy { min(px[$0], px[$0 + 1], px[$0 + 2]) > 237 }
    }
}

/// The file's picture filling its frame, or its kind's icon until (or unless) there is one.
struct Thumb: View {
    let file: SourceFile
    var locator = ""
    /// The longest side wanted, in points.
    let points: CGFloat
    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?

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
            .task(id: "\(file.uri)|\(locator)") {
                image = await Thumbnails.load(file, locator: locator, pixels: points * scale)
            }
            .accessibilityHidden(true)
    }
}
