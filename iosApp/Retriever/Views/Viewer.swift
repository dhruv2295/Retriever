// Ported from Retriever's Android PdfPageScreen.kt and PlayerScreen.kt (same project): open a file where it
// matched. Modified for iOS: PDFKit's PDFView at the page with the matched words highlighted, AVPlayerViewController
// at the moment, a zoomable photo, and Quick Look for everything else.

import AVFoundation
import AVKit
import PDFKit
import Photos
import RetrieverCore
import SwiftUI
import os

/// A file shown in the app at the place it matched.
enum Viewing: Hashable {
    case pdf(SourceFile, page: Int, words: [String])
    case video(SourceFile, ms: Int64)
    case photos([SourceFile], index: Int)
}

/// Opens a file at its match: in the app for PDFs, videos and library photos, in Quick Look otherwise.
struct OpenFile {
    var action: (SourceFile, String, [String], [SourceFile]) -> Void = { _, _, _, _ in }

    /// `among` is the list the file was tapped in, so photos swipe and Quick Look pages through its neighbours.
    func callAsFunction(_ file: SourceFile, locator: String = "", words: [String] = [], among: [SourceFile] = []) {
        action(file, locator, words, among)
    }
}

extension EnvironmentValues {
    @Entry var openFile = OpenFile()
}

/// Library photos open in the app (not iCloud-only PDFs or videos); folder PDFs and videos too.
private func isPhoto(_ file: SourceFile) -> Bool {
    file.uri.hasPrefix("ph://") && file.kind != .video && file.kind != .pdf
}

/// What opening a file means: a page to push, or nil for Quick Look.
func viewing(_ file: SourceFile, locator: String, words: [String], among: [SourceFile] = []) -> Viewing? {
    if file.kind == .pdf, !file.uri.hasPrefix("ph://") {
        return .pdf(file, page: Locator.shared.pageOf(l: locator)?.intValue ?? 1, words: words)
    }
    if file.kind == .video { return .video(file, ms: Locator.shared.msOf(l: locator)?.int64Value ?? 0) }
    guard isPhoto(file) else { return nil }
    let photos = among.filter(isPhoto)
    return .photos(photos.contains { $0.uri == file.uri } ? photos : [file], index: photos.firstIndex { $0.uri == file.uri } ?? 0)
}

/// The files Quick Look pages through when `file` is opened from `among`: its folder neighbours with no in-app viewer.
func quickLookFiles(_ file: SourceFile, among: [SourceFile]) -> [SourceFile] {
    (among.contains { $0.uri == file.uri } ? among : [file]).filter { viewing($0, locator: "", words: []) == nil }
}

/// The words to highlight inside the file: those a keyword match marked, else the query's own words.
func highlightWords(snippet: String?, query: String?) -> [String] {
    let marked = Format.markedWords(snippet)
    if !marked.isEmpty { return marked }
    return query.map { Store.Companion.shared.ftsTokens(s: $0) } ?? []
}

@MainActor @ViewBuilder func viewer(_ v: Viewing) -> some View {
    switch v {
    case .pdf(let file, let page, let words): PDFViewer(file: file, page: page, words: words)
    case .video(let file, let ms): VideoViewer(file: file, ms: ms)
    case .photos(let files, let index): PhotoViewer(files: files, index: index)
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
                    .accessibilityLabel(file.name)
            } else if failed {
                StateView(mood: .trouble, title: "Couldn't open this file", message: "It may have moved, or it's locked.")
            } else {
                ProgressView()
            }
        }
        .toolbar {
            // No page number: it would stay at the opened page while the person scrolls.
            ToolbarItem(placement: .principal) { Title(name: file.name, detail: nil) }
            ToolbarItemGroup(placement: .topBarTrailing) {
                StarButton(file: file)
                ShareButton(file: file)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard document == nil, let url = Thumbnails.services?.folders.resolve(file.uri) else {
                failed = true
                return
            }
            let doc = await Self.open(url)
            if let doc, !doc.isLocked { document = doc } else { failed = true }
        }
    }

    /// Parses off the main actor; the new document is handed over whole.
    @concurrent private static func open(_ url: URL) async -> sending PDFDocument? {
        PDFDocument(url: url)
    }
}

/// go(to:) lands wrong until autoScales has settled the layout, so the jump waits for the first real bounds.
final class JumpPDFView: PDFView {
    var pending: PDFDestination?

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let destination = pending, !bounds.isEmpty else { return }
        pending = nil
        go(to: destination)
    }
}

private struct PDFPageView: UIViewRepresentable {
    let document: PDFDocument
    let page: Int
    let words: [String]

    func makeUIView(context: Context) -> JumpPDFView {
        let view = JumpPDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.document = document
        guard let target = document.page(at: page - 1) else { return view }
        let found = Self.matches(on: target, words: words)
        view.highlightedSelections = found
        // The match sits a little below the top; no match means the top of the page.
        let top = target.bounds(for: view.displayBox).maxY
        view.pending = PDFDestination(page: target, at: CGPoint(x: 0, y: found.first.map { min($0.bounds(for: target).maxY + 120, top) } ?? top))
        return view
    }

    func updateUIView(_ view: JumpPDFView, context: Context) {}

    /// Whole-word matches on one page only, so a long PDF never blocks the main thread.
    static func matches(on page: PDFPage, words: [String]) -> [PDFSelection] {
        guard let text = page.string else { return [] }
        let range = NSRange(text.startIndex..., in: text)
        return words.filter { $0.count >= 3 }.flatMap { word -> [PDFSelection] in
            guard let regex = try? NSRegularExpression(pattern: "\\b\(NSRegularExpression.escapedPattern(for: word))\\b", options: .caseInsensitive) else {
                return []
            }
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
                StateView(mood: .trouble, title: "Couldn't play this video", message: "It may have moved, or it's only in iCloud and there's no connection.")
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
            guard let item = await Self.item(file.uri) else {
                failed = true
                return
            }
            let player = AVPlayer(playerItem: item)
            await player.seek(to: CMTime(value: ms, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
            self.player = player
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            player.play()
        }
        .onDisappear { player?.pause() }
    }

    /// Off the main actor: Photos calls back on its own queue, and a closure formed here would trap there.
    @concurrent private static func item(_ uri: String) async -> sending AVPlayerItem? {
        guard uri.hasPrefix("ph://") else {
            return await MainActor.run { Thumbnails.services?.folders.resolve(uri) }.map { AVPlayerItem(url: $0) }
        }
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [String(uri.dropFirst(5))], options: nil).firstObject else { return nil }
        nonisolated(unsafe) let options = PHVideoRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .automatic
        let manager = PHImageManager.default()
        let request = OSAllocatedUnfairLock(initialState: PHInvalidImageRequestID)
        return await withTaskCancellationHandler {
            await withCheckedContinuation { done in
                request.withLock { $0 = manager.requestPlayerItem(forVideo: asset, options: options) { item, _ in done.resume(returning: item) } }
                if Task.isCancelled { manager.cancelImageRequest(request.withLock { $0 }) }
            }
        } onCancel: {
            manager.cancelImageRequest(request.withLock { $0 })
        }
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
    let files: [SourceFile]
    @State private var selection: Int

    init(files: [SourceFile], index: Int) {
        self.files = files
        _selection = State(initialValue: index)
    }

    var body: some View {
        TabView(selection: $selection) {
            ForEach(files.indices, id: \.self) { PhotoPage(file: files[$0]).tag($0) }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .background(Color.black.ignoresSafeArea())
        .toolbar {
            ToolbarItem(placement: .principal) { Title(name: files[selection].name, detail: Format.date(files[selection].modifiedAt)) }
            ToolbarItemGroup(placement: .topBarTrailing) {
                StarButton(file: files[selection])
                ShareButton(file: files[selection])
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }
}

private struct PhotoPage: View {
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
                StateView(mood: .trouble, title: "Couldn't show this photo", message: "It may be only in iCloud, and there's no connection right now.")
            } else if let progress {
                ProgressView("Downloading from iCloud", value: progress).tint(.white).foregroundStyle(.white).padding(40)
            } else {
                ProgressView().tint(.white)
            }
        }
        .task {
            guard image == nil else { return }
            image = await Self.load(String(file.uri.dropFirst(5))) { p in Task { @MainActor in progress = p } }
            failed = image == nil && !Task.isCancelled
        }
    }

    /// Network is allowed only here: the person asked to see this one photo. Off the main actor, because Photos
    /// calls both handlers on its own queue. Swiping away cancels the request.
    @concurrent private static func load(_ id: String, onProgress: @escaping @Sendable (Double) -> Void) async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return nil }
        nonisolated(unsafe) let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat
        options.progressHandler = { p, _, _, _ in onProgress(p) }
        let manager = PHImageManager.default()
        let request = OSAllocatedUnfairLock(initialState: PHInvalidImageRequestID)
        return await withTaskCancellationHandler {
            await withCheckedContinuation { done in
                request.withLock {
                    $0 = manager.requestImage(for: asset, targetSize: CGSize(width: 3000, height: 3000), contentMode: .aspectFit, options: options) {
                        image, _ in
                        done.resume(returning: image)
                    }
                }
                if Task.isCancelled { manager.cancelImageRequest(request.withLock { $0 }) }
            }
        } onCancel: {
            manager.cancelImageRequest(request.withLock { $0 })
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
