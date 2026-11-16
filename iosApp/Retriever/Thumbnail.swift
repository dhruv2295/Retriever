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
    /// Nothing warms assets ahead of the grid, so the shared manager does; [cache] keeps what was drawn.
    nonisolated private static let photos = PHImageManager.default()

    static func load(_ file: SourceFile, locator: String, pixels: CGFloat, services: AppServices?) async -> UIImage? {
        let key = Thumb.requestID(file, locator: locator, pixels: pixels) as NSString
        if let hit = cache.object(forKey: key) { return hit }
        guard let services else { return nil }
        let uri = file.uri
        let image: UIImage?
        var keep = true
        if file.kind == .video, Locator.shared.msOf(l: locator) != nil || !uri.hasPrefix("ph://") {
            // A folder video has no poster frame, so it shows the first second.
            let ms = Locator.shared.msOf(l: locator)?.int64Value ?? 1000
            image = await services.extractor.videoFrame(uri: uri, ms: ms, maxSide: pixels)
        } else if uri.hasPrefix("ph://") {
            image = await photo(String(uri.dropFirst(5)), pixels: pixels)
        } else if let url = services.folders.resolve(uri) {
            if file.kind == .pdf, let page = Locator.shared.pageOf(l: locator)?.intValue {
                image = await Task.detached { pdfPage(url, index: page - 1, pixels: pixels) }.value
            } else {
                // The icon is also Quick Look's answer when a busy or just-booted system can't draw the page in time,
                // so it shows but isn't kept: the next appearance asks again.
                // ponytail: a type that only ever has an icon asks each time (under a second cold, milliseconds warm).
                let shown = await quickLook(url, pixels: pixels)
                image = shown?.uiImage
                keep = shown?.type != .icon
            }
        } else {
            image = nil
        }
        if let image, keep { cache.setObject(image, forKey: key, cost: image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0) }
        return image
    }

    /// Request sizes (px) shared by every surface, so a picture Home loaded is a cache hit in Browse.
    static func bucket(_ px: CGFloat) -> CGFloat {
        [256, 384, 512, 768].first { px <= $0 } ?? 768
    }

    /// Never changed after it is made, so the cells' requests can read it off the main actor.
    nonisolated(unsafe) private static let requestOptions: PHImageRequestOptions = {
        let o = PHImageRequestOptions()
        o.isNetworkAccessAllowed = false
        o.deliveryMode = .highQualityFormat  // one callback
        o.resizeMode = .fast
        return o
    }()

    /// Off the main actor: the asset fetch reads the library's database. A cell scrolled away cancels its request.
    @concurrent private static func photo(_ id: String, pixels: CGFloat) async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return nil }
        return await awaitPhotoRequest(
            cancel: { photos.cancelImageRequest($0) },
            start: { done in
                photos.requestImage(for: asset, targetSize: CGSize(width: pixels, height: pixels), contentMode: .aspectFill, options: requestOptions) {
                    @Sendable image, _ in done(image)
                }
            })
    }

    nonisolated private static func pdfPage(_ url: URL, index: Int, pixels: CGFloat) -> UIImage? {
        guard let page = PDFDocument(url: url)?.page(at: index) else { return nil }
        return page.thumbnail(of: CGSize(width: pixels, height: pixels), for: .cropBox)
    }

    /// A real page, sheet or slide where Quick Look can draw one, else the system's icon for the type.
    nonisolated static func quickLook(_ url: URL, pixels: CGFloat) async -> QLThumbnailRepresentation? {
        let request = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: pixels, height: pixels), scale: 1, representationTypes: .all)
        return try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: request)
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
                    Image(uiImage: image).resizable().scaledToFill().id(ObjectIdentifier(image)).transition(.opacity)
                } else {
                    Image(systemName: Format.icon(file.kind)).font(.title2).foregroundStyle(.secondary)
                }
            }
            .clipped()
            .task(id: Self.requestID(file, locator: locator, pixels: pixels)) {
                // The picture shown stays until the next is decoded (a new file in the same place, as Reading now
                // has), then gives way in a short fade; a first picture simply appears.
                let next = await Thumbnails.load(file, locator: locator, pixels: pixels, services: services)
                guard !Task.isCancelled else { return }
                withAnimation(image == nil || reduceMotion ? nil : .easeInOut(duration: 0.2)) { image = next }
            }
            .accessibilityHidden(true)
    }
}
