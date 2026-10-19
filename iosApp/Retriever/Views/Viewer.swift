// Ported from Retriever's Android PdfPageScreen.kt and PlayerScreen.kt (same project): open a file where it
// matched. Modified for iOS: PDFKit's PDFView at the page with the matched words highlighted, AVPlayerViewController
// at the moment, a zoomable photo, and Quick Look for everything else.

import AVFoundation
import AVKit
import PDFKit
import Photos
import QuickLook
import RetrieverCore
import SwiftUI
import os

/// A file shown in the app at the place it matched.
enum Viewing: Hashable {
    case pdf(SourceFile, page: Int, words: [String])
    case video(SourceFile, ms: Int64)
    case photos([SourceFile], index: Int)
    case document(SourceFile, [SourceFile])
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
func viewing(_ file: SourceFile, locator: String, words: [String], among: [SourceFile] = []) -> Viewing {
    if file.kind == .pdf, !file.uri.hasPrefix("ph://") {
        return .pdf(file, page: Locator.shared.pageOf(l: locator)?.intValue ?? 1, words: words)
    }
    if file.kind == .video { return .video(file, ms: Locator.shared.msOf(l: locator)?.int64Value ?? 0) }
    guard isPhoto(file) else { return .document(file, quickLookFiles(file, among: among)) }
    let photos = among.filter(isPhoto)
    return .photos(photos.contains { $0.uri == file.uri } ? photos : [file], index: photos.firstIndex { $0.uri == file.uri } ?? 0)
}

/// The files Quick Look pages through when `file` is opened from `among`: its folder neighbours with no in-app viewer.
func quickLookFiles(_ file: SourceFile, among: [SourceFile]) -> [SourceFile] {
    (among.contains { $0.uri == file.uri } ? among : [file]).filter { $0.kind != .pdf && $0.kind != .video && !isPhoto($0) }
}

/// The words to highlight inside the file: those a keyword match marked, else the query's own words.
func highlightWords(snippet: String?, query: String?) -> [String] {
    let marked = Format.markedWords(snippet)
    if !marked.isEmpty { return marked }
    return query.map { HighlightKt.highlightTerms(query: $0) } ?? []
}

@MainActor @ViewBuilder func viewer(_ v: Viewing) -> some View {
    switch v {
    case .pdf(let file, let page, let words): PDFViewer(file: file, page: page, words: words)
    case .video(let file, let ms): VideoViewer(file: file, ms: ms)
    case .photos(let files, let index): PhotoViewer(files: files, index: index)
    case .document(let file, let files): DocumentViewer(file: file, files: files)
    }
}

// MARK: PDF

struct PDFViewer: View {
    let file: SourceFile
    let page: Int
    let words: [String]
    @State private var document: PDFDocument?
    @State private var failed = false
    @Environment(AppModel.self) private var model: AppModel?

    var body: some View {
        Group {
            if let document {
                PDFPageView(document: document, page: page, words: words)
                    .accessibilityIdentifier("viewer.pdf")
                    .ignoresSafeArea(edges: .bottom)
                    .accessibilityLabel(file.name)
            } else if failed {
                StateView(mood: .trouble, title: "Couldn't open this file", message: "It may have moved, or it's locked.")
            } else {
                ProgressView()
            }
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                StarButton(file: file)
                FileOverflow(file: file)
            }
        }
        .navigationTitle(file.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard document == nil else { return }
            guard let url = model?.services?.folders.resolve(file.uri) else {
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
        guard let destination = pending, !bounds.isEmpty, scaleFactor > 0 else { return }
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
    @Environment(AppModel.self) private var model: AppModel?

    var body: some View {
        Group {
            if let player {
                PlayerController(player: player).ignoresSafeArea().accessibilityIdentifier("viewer.video")
            } else if failed {
                StateView(mood: .trouble, title: "Couldn't play this video", message: "It may have moved, or it's only in iCloud and there's no connection.")
            } else {
                ProgressView()
            }
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                StarButton(file: file)
                FileOverflow(file: file)
            }
        }
        .navigationTitle(file.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task {
            guard player == nil else { return }
            let item: AVPlayerItem?
            if file.uri.hasPrefix("ph://") {
                item = await Self.libraryItem(String(file.uri.dropFirst(5)))
            } else {
                item = model?.services?.folders.resolve(file.uri).map { AVPlayerItem(url: $0) }
            }
            guard !Task.isCancelled else { return }
            guard let item else {
                failed = true
                return
            }
            let player = AVPlayer(playerItem: item)
            #if DEBUG
                player.isMuted = CommandLine.arguments.contains("-mutedMedia")
            #endif
            await player.seek(to: CMTime(value: ms, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
            guard !Task.isCancelled else { return }
            self.player = player
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            player.play()
        }
        .onDisappear { player?.pause() }
    }

    /// Off the main actor: Photos calls back on its own queue, and a closure formed here would trap there.
    nonisolated static func libraryItem(_ id: String) async -> sending AVPlayerItem? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return nil }
        let manager = PHImageManager.default()
        return await awaitPhotoRequest(
            cancel: { manager.cancelImageRequest($0) },
            start: { done in
                let options = PHVideoRequestOptions()
                options.isNetworkAccessAllowed = true
                options.deliveryMode = .automatic
                return manager.requestPlayerItem(forVideo: asset, options: options) { @Sendable item, _ in done(item) }
            })
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
            ToolbarItemGroup(placement: .topBarTrailing) {
                StarButton(file: files[selection])
                FileOverflow(file: files[selection])
            }
        }
        .navigationTitle(files[selection].name)
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
                    .accessibilityIdentifier("viewer.photo")
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
        .environment(\.colorScheme, .dark)
        .task {
            guard image == nil else { return }
            image = await Self.load(String(file.uri.dropFirst(5))) { p in Task { @MainActor in progress = p } }
            failed = image == nil && !Task.isCancelled
        }
    }

    /// Network is allowed only here: the person asked to see this one photo. Off the main actor, because Photos
    /// calls both handlers on its own queue. Swiping away cancels the request.
    nonisolated private static func load(_ id: String, onProgress: @escaping @Sendable (Double) -> Void) async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return nil }
        let manager = PHImageManager.default()
        return await awaitPhotoRequest(
            cancel: { manager.cancelImageRequest($0) },
            start: { done in
                let options = PHImageRequestOptions()
                options.isNetworkAccessAllowed = true
                options.deliveryMode = .highQualityFormat
                options.progressHandler = { @Sendable p, _, _, _ in onProgress(p) }
                return manager.requestImage(for: asset, targetSize: CGSize(width: 3000, height: 3000), contentMode: .aspectFit, options: options) {
                    @Sendable image, info in
                    guard info?[PHImageResultIsDegradedKey] as? Bool != true else { return }
                    done(image)
                }
            })
    }
}

/// Photos need not call back after cancellation, and may call back before returning the request ID.
@concurrent func awaitPhotoRequest<Value>(
    cancel: @escaping @Sendable (PHImageRequestID) -> Void,
    start: @Sendable (@escaping @Sendable (sending Value?) -> Void) -> PHImageRequestID
) async -> sending Value? {
    let request = PhotoRequest<Value>()
    return await withTaskCancellationHandler(
        operation: { @Sendable () async -> sending Value? in
            await withCheckedContinuation { done in
                guard request.install(done) else {
                    done.resume(returning: nil)
                    return
                }
                let id = start { request.finish($0) }
                if request.register(id) { cancel(id) }
            }
        },
        onCancel: {
            if let id = request.cancel() { cancel(id) }
        })
}

private final class PhotoRequest<Value>: Sendable {
    private struct State {
        var done: CheckedContinuation<Value?, Never>?
        var id: PHImageRequestID?
        var cancelled = false
    }
    private let state = OSAllocatedUnfairLock(initialState: State())

    func install(_ done: CheckedContinuation<Value?, Never>) -> Bool {
        state.withLock {
            guard !$0.cancelled else { return false }
            $0.done = done
            return true
        }
    }

    func register(_ id: PHImageRequestID) -> Bool {
        state.withLock {
            $0.id = id
            return $0.cancelled
        }
    }

    func finish(_ value: sending Value?) {
        let done = state.withLock { s in
            defer { s.done = nil }
            return s.done
        }
        done?.resume(returning: value)
    }

    func cancel() -> PHImageRequestID? {
        let (done, id) = state.withLock { s in
            s.cancelled = true
            defer {
                s.done = nil
                s.id = nil
            }
            return (s.done, s.id)
        }
        done?.resume(returning: nil)
        return id
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


/// Quick Look keeps its native rendering and paging; the app toolbar supplies the same file actions as other viewers.
private struct DocumentViewer: View {
    let file: SourceFile
    let files: [SourceFile]
    @Environment(\.services) private var services
    @State private var current: SourceFile?

    var body: some View {
        let items = files.compactMap { f in services?.folders.resolve(f.uri).map { (f, $0) } }
        Group {
            if items.isEmpty { StateView(mood: .trouble, title: "Couldn't open this file", message: "Its folder can't be reached right now.") }
            else { DocumentPreview(items: items, initial: file.uri, current: $current).ignoresSafeArea(edges: .bottom) }
        }
        .navigationTitle((current ?? file).name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItemGroup(placement: .topBarTrailing) { StarButton(file: current ?? file); FileOverflow(file: current ?? file) } }
    }
}

private struct DocumentPreview: UIViewControllerRepresentable {
    let items: [(SourceFile, URL)]
    let initial: String
    @Binding var current: SourceFile?

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        controller.currentPreviewItemIndex = items.firstIndex { $0.0.uri == initial } ?? 0
        context.coordinator.observation = controller.observe(\.currentPreviewItemIndex, options: [.new]) { _, change in
            let index = change.newValue ?? 0
            Task { @MainActor in
                if items.indices.contains(index) { current = items[index].0 }
            }
        }
        return controller
    }
    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let parent: DocumentPreview
        var observation: NSKeyValueObservation?
        init(_ parent: DocumentPreview) { self.parent = parent }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { parent.items.count }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { parent.items[index].1 as NSURL }
    }
}
