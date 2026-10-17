// Ported from Retriever's Android PdfPageScreen.kt and PlayerScreen.kt (same project): open a file where it
// matched. Modified for iOS: PDFKit's PDFView at the page with the matched words highlighted, AVPlayerViewController
// at the moment, a zoomable photo, and Quick Look for everything else.

import AVKit
import PDFKit
import Photos
import RetrieverCore
import SwiftUI

/// A file shown in the app at the place it matched.
enum Viewing: Hashable {
    case pdf(SourceFile, page: Int, words: [String])
    case video(SourceFile, ms: Int64)
    case photo(SourceFile)
}

/// Opens a file at its match: in the app for PDFs, videos and library photos, in Quick Look otherwise.
struct OpenFile {
    var action: (SourceFile, String, [String]) -> Void = { _, _, _ in }

    func callAsFunction(_ file: SourceFile, locator: String = "", words: [String] = []) {
        action(file, locator, words)
    }
}

extension EnvironmentValues {
    @Entry var openFile = OpenFile()
}

/// What opening a file means: a page to push, or a file for Quick Look.
func viewing(_ file: SourceFile, locator: String, words: [String]) -> Viewing? {
    if file.kind == .pdf, !file.uri.hasPrefix("ph://") { return .pdf(file, page: Format.page(locator) ?? 1, words: words) }
    if file.kind == .video { return .video(file, ms: Format.ms(locator) ?? 0) }
    if file.uri.hasPrefix("ph://") { return .photo(file) }
    return nil
}

/// The words to highlight inside the file: those a keyword match marked, else the query's own words.
func highlightWords(snippet: String?, query: String?) -> [String] {
    let marked = Format.markedWords(snippet)
    if !marked.isEmpty { return marked }
    return query.map { Store.Companion.shared.ftsTokens(s: $0) } ?? []
}

@MainActor @ViewBuilder func viewer(_ v: Viewing) -> some View {
    switch v {
    case let .pdf(file, page, words): PDFViewer(file: file, page: page, words: words)
    case let .video(file, ms): VideoViewer(file: file, ms: ms)
    case let .photo(file): PhotoViewer(file: file)
    }
}

// MARK: PDF

struct PDFViewer: View {
    let file: SourceFile
    let page: Int
    let words: [String]
    @State private var document: PDFDocument?
    @State private var failed = false

    var body: some View {
        Group {
            if let document {
                PDFPageView(document: document, page: page, words: words)
                    .ignoresSafeArea(edges: .bottom)
                    .accessibilityLabel("\(file.name), page \(page)")
            } else if failed {
                StateView(art: "IllusEmpty", title: "Couldn't open this file", message: "It may have moved, or it's locked.")
            } else {
                ProgressView()
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) { Title(name: file.name, detail: "Page \(page)") }
            ToolbarItemGroup(placement: .topBarTrailing) {
                StarButton(file: file)
                ShareButton(file: file)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard document == nil, let url = Thumbnails.services?.folders.resolve(file.uri) else { return failed = true }
            let doc = await Self.open(url)
            if let doc, !doc.isLocked { document = doc } else { failed = true }
        }
    }

    /// Parses off the main actor; the new document is handed over whole.
    @concurrent private static func open(_ url: URL) async -> sending PDFDocument? {
        PDFDocument(url: url)
    }
}

private struct PDFPageView: UIViewRepresentable {
    let document: PDFDocument
    let page: Int
    let words: [String]

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.document = document
        guard let target = document.page(at: page - 1) else { return view }
        let found = Self.matches(on: target, words: words)
        view.highlightedSelections = found
        // go(to:) lands wrong until autoScales has settled the layout. The match sits a little below the top.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            guard let first = found.first else { return view.go(to: target) }
            let box = first.bounds(for: target)
            view.go(to: PDFDestination(page: target, at: CGPoint(x: 0, y: min(box.maxY + 120, target.bounds(for: view.displayBox).maxY))))
        }
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {}

    /// Whole-word matches on one page only, so a long PDF never blocks the main thread.
    static func matches(on page: PDFPage, words: [String]) -> [PDFSelection] {
        guard let text = page.string else { return [] }
        let range = NSRange(text.startIndex..., in: text)
        return words.filter { $0.count >= 3 }.flatMap { word -> [PDFSelection] in
            guard let regex = try? NSRegularExpression(pattern: "\\b\(NSRegularExpression.escapedPattern(for: word))\\b", options: .caseInsensitive) else { return [] }
            return regex.matches(in: text, range: range).compactMap { m in
                let s = page.selection(for: m.range)
                s?.color = .systemYellow
                return s
            }
        }
    }
}

// MARK: Video

struct VideoViewer: View {
    let file: SourceFile
    let ms: Int64
    @State private var player: AVPlayer?
    @State private var failed = false

    var body: some View {
        Group {
            if let player {
                PlayerController(player: player).ignoresSafeArea()
            } else if failed {
                StateView(art: "IllusEmpty", title: "Couldn't play this video", message: "It may have moved, or it's only in iCloud and there's no connection.")
            } else {
                ProgressView()
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) { Title(name: file.name, detail: ms > 0 ? "From \(Self.clock(ms))" : nil) }
            ToolbarItemGroup(placement: .topBarTrailing) {
                StarButton(file: file)
                ShareButton(file: file)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task {
            guard player == nil else { return }
            guard let item = await item() else { return failed = true }
            let player = AVPlayer(playerItem: item)
            await player.seek(to: CMTime(value: ms, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
            self.player = player
            player.play()
        }
        .onDisappear { player?.pause() }
    }

    private func item() async -> AVPlayerItem? {
        if file.uri.hasPrefix("ph://") {
            guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [String(file.uri.dropFirst(5))], options: nil).firstObject else { return nil }
            let options = PHVideoRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .automatic
            return await withCheckedContinuation { done in
                PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { item, _ in done.resume(returning: item) }
            }
        }
        return Thumbnails.services?.folders.resolve(file.uri).map { AVPlayerItem(url: $0) }
    }

    static func clock(_ ms: Int64) -> String {
        let s = ms / 1000
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}

private struct PlayerController: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {}
}

// MARK: Photo

struct PhotoViewer: View {
    let file: SourceFile
    @State private var image: UIImage?
    @State private var progress: Double?
    @State private var failed = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image {
                ZoomableImage(image: image)
                    .ignoresSafeArea()
                    .accessibilityLabel("\(Format.kind(file.kind)), \(file.name)")
                    .accessibilityAddTraits(.isImage)
            } else if failed, [.denied, .restricted].contains(PHPhotoLibrary.authorizationStatus(for: .readWrite)) {
                StateView(art: "IllusWelcome", title: "Photos access is off", message: "Turn it on in Settings to see your photos here.")
            } else if failed {
                StateView(art: "IllusEmpty", title: "Couldn't show this photo", message: "It may be only in iCloud, and there's no connection right now.")
            } else if let progress {
                ProgressView("Downloading from iCloud", value: progress).tint(.white).foregroundStyle(.white).padding(40)
            } else {
                ProgressView().tint(.white)
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) { Title(name: file.name, detail: Format.date(file.modifiedAt)) }
            ToolbarItemGroup(placement: .topBarTrailing) {
                StarButton(file: file)
                ShareButton(file: file)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task {
            guard image == nil else { return }
            image = await load()
            failed = image == nil
        }
    }

    /// Network is allowed only here: the person asked to see this one photo.
    private func load() async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [String(file.uri.dropFirst(5))], options: nil).firstObject else { return nil }
        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat
        options.progressHandler = { p, _, _, _ in
            DispatchQueue.main.async { progress = p }
        }
        return await withCheckedContinuation { done in
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 3000, height: 3000), contentMode: .aspectFit, options: options) { image, _ in
                done.resume(returning: image)
            }
        }
    }
}

/// Pinch or double-tap to zoom.
private struct ZoomableImage: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.delegate = context.coordinator
        scroll.maximumZoomScale = 5
        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false
        let view = UIImageView(image: image)
        view.contentMode = .scaleAspectFit
        view.frame = scroll.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroll.addSubview(view)
        context.coordinator.imageView = view
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:)))
        tap.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(tap)
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        context.coordinator.imageView?.image = image
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

        @objc func doubleTap(_ tap: UITapGestureRecognizer) {
            guard let scroll = tap.view as? UIScrollView else { return }
            scroll.setZoomScale(scroll.zoomScale > 1 ? 1 : 2.5, animated: true)
        }
    }
}

/// A two-line inline title: the file's name and where in it.
private struct Title: View {
    let name: String
    let detail: String?

    var body: some View {
        VStack(spacing: 0) {
            Text(name).font(.headline).lineLimit(1).truncationMode(.middle)
            if let detail { Text(detail).font(.caption).foregroundStyle(.secondary).monospacedDigit() }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}
