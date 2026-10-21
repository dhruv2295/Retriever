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

/// A file's place in the list it was opened from.
struct Place: Hashable {
    var files: [SourceFile]
    var index: Int

    var file: SourceFile { files[index] }
    var label: String? { Self.label(index: index, count: files.count) }

    /// "2 of 5"; nothing for a lone file, so a restored route never shows a swipe cue it cannot honor.
    static func label(index: Int, count: Int) -> String? { count > 1 ? "\(index + 1) of \(count)" : nil }
}

/// The words a viewer shows over a file.
enum ViewerCopy {
    static func match(page: Int?) -> String? { page.map { "Match on page \($0)" } }
    static func page(_ page: Int, of total: Int) -> String? { total > 1 ? "Page \(page) of \(total)" : nil }
    static func match(ms: Int64?) -> String? { ms.map { "Match at \(clock(seconds: Int($0 / 1000)))" } }

    static func clock(seconds: Int) -> String {
        let (h, m, s) = (seconds / 3600, seconds / 60 % 60, seconds % 60)
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

/// A file shown in the app at the place it matched.
enum Viewing: Hashable {
    case pdf(Place, page: Int?, words: [String])
    case media(Place, ms: Int64?)
    case photos([SourceFile], index: Int)
    case document(Place, locator: String, words: [String])
}

/// Opens a file at its match: in the app for PDFs, media and library photos, in Quick Look otherwise.
struct OpenFile {
    var action: (SourceFile, String, [String], [SourceFile]) -> Void = { _, _, _, _ in }

    /// `among` is the list the file was tapped in, so photos swipe and the other viewers step through its neighbours.
    func callAsFunction(_ file: SourceFile, locator: String = "", words: [String] = [], among: [SourceFile] = []) {
        action(file, locator, words, among)
    }
}

extension EnvironmentValues {
    @Entry var openFile = OpenFile()
}

/// Library photos open in the app (not iCloud-only PDFs or videos).
private func isPhoto(_ file: SourceFile) -> Bool {
    file.uri.hasPrefix("ph://") && file.kind != .video && file.kind != .pdf
}

private func isFolderPDF(_ file: SourceFile) -> Bool { file.kind == .pdf && !file.uri.hasPrefix("ph://") }
private func isMedia(_ file: SourceFile) -> Bool { file.kind == .video || file.kind == .audio }
private func isQuickLook(_ file: SourceFile) -> Bool { file.kind != .pdf && !isMedia(file) && !isPhoto(file) }

/// `file` among the neighbours its viewer can step through; alone when the list does not hold it.
private func place(_ file: SourceFile, among: [SourceFile], where keep: (SourceFile) -> Bool) -> Place {
    let list = among.filter(keep)
    guard let index = list.firstIndex(where: { $0.uri == file.uri }) else { return Place(files: [file], index: 0) }
    return Place(files: list, index: index)
}

/// What opening a file means. A page or a moment is a match only when the locator named one.
func viewing(_ file: SourceFile, locator: String, words: [String], among: [SourceFile] = []) -> Viewing {
    if isFolderPDF(file) {
        return .pdf(place(file, among: among, where: isFolderPDF), page: Locator.shared.pageOf(l: locator)?.intValue, words: words)
    }
    if isMedia(file) { return .media(place(file, among: among, where: isMedia), ms: Locator.shared.msOf(l: locator)?.int64Value) }
    guard isPhoto(file) else { return .document(place(file, among: among, where: isQuickLook), locator: locator, words: words) }
    let photos = among.filter(isPhoto)
    return .photos(photos.contains { $0.uri == file.uri } ? photos : [file], index: photos.firstIndex { $0.uri == file.uri } ?? 0)
}

/// The words to highlight inside the file: those a keyword match marked, else the query's own words.
func highlightWords(snippet: String?, query: String?) -> [String] {
    let marked = Format.markedWords(snippet)
    if !marked.isEmpty { return marked }
    return query.map { HighlightKt.highlightTerms(query: $0) } ?? []
}

@MainActor @ViewBuilder func viewer(_ v: Viewing) -> some View {
    switch v {
    case .pdf(let place, let page, let words): PDFViewer(place: place, page: page, words: words)
    case .media(let place, let ms): MediaViewer(place: place, ms: ms)
    case .photos(let files, let index): PhotoViewer(files: files, index: index)
    case .document(let place, let locator, let words): DocumentViewer(place: place, locator: locator, words: words)
    }
}

// MARK: Shared chrome

/// Back, the name, Share, Star and one overflow. On a narrow screen Star lives in the overflow, which always has it.
private struct ViewerBar<Extra: View>: ViewModifier {
    let file: SourceFile
    var detail: String?
    @ViewBuilder let extra: Extra
    @Environment(\.horizontalSizeClass) private var sizeClass

    func body(content: Content) -> some View {
        content
            .navigationTitle(file.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if let detail {
                    ToolbarItem(placement: .principal) {
                        VStack(spacing: 0) {
                            Text(file.name).font(.headline).lineLimit(1)
                            Text(detail).font(.caption).foregroundStyle(.secondary)
                        }.accessibilityElement(children: .combine)
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    ShareButton(file: file)
                    extra
                    if sizeClass == .regular { StarButton(file: file) }
                    FileOverflow(file: file)
                }
            }
    }
}

extension View {
    fileprivate func viewerBar(_ file: SourceFile, detail: String? = nil) -> some View {
        modifier(ViewerBar(file: file, detail: detail) { EmptyView() })
    }

    fileprivate func viewerBar(_ file: SourceFile, @ViewBuilder extra: () -> some View) -> some View {
        modifier(ViewerBar(file: file, extra: extra))
    }
}

/// Previous and next file as buttons, so a pan or a seek is never taken for a page turn.
private struct StepBar: ToolbarContent {
    let count: Int
    @Binding var index: Int

    var body: some ToolbarContent {
        ToolbarItemGroup(placement: .bottomBar) {
            Button {
                index -= 1
            } label: {
                Label("Previous file", systemImage: "chevron.left").labelStyle(.iconOnly)
            }
            .disabled(index == 0)
            Spacer()
            Text(Place.label(index: index, count: count) ?? "").font(.footnote).monospacedDigit()
            Spacer()
            Button {
                index += 1
            } label: {
                Label("Next file", systemImage: "chevron.right").labelStyle(.iconOnly)
            }
            .disabled(index == count - 1)
        }
    }
}

/// Small labels over the content: where the match is, which page is showing.
private struct Badge: View {
    let lines: [String]

    var body: some View {
        if !lines.isEmpty {
            VStack(spacing: 2) { ForEach(lines, id: \.self) { Text($0) } }
                .font(.caption.weight(.semibold))
                .monospacedDigit()
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(.regularMaterial, in: Capsule())
                .padding(8)
                .accessibilityElement(children: .combine)
        }
    }
}

/// Why a file cannot be shown, each with its own way on.
enum ViewerProblem: Error, Equatable {
    case noAccess, missing, locked
    case cantOpen(title: String, message: String)
}

/// The folder behind a file, or the reason it is not there to open.
func reach(_ file: SourceFile, _ folders: FolderSource?) -> Result<URL, ViewerProblem> {
    guard let url = folders?.resolve(file.uri) else { return .failure(.noAccess) }
    return FileManager.default.fileExists(atPath: url.path) ? .success(url) : .failure(.missing)
}

private struct ProblemView: View {
    let file: SourceFile
    let problem: ViewerProblem
    @Environment(FileActions.self) private var actions: FileActions?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        switch problem {
        case .missing:
            StateView(mood: .trouble, title: "This file is no longer available", message: "It may have been moved or deleted.") {
                Button("Go back") { dismiss() }
            }
        case .noAccess:
            StateView(mood: .trouble, title: "Access to this folder has changed", message: "Choose the folder again to open this file.") {
                NavigationLink(Words.shared.WHAT_IT_CAN_SEE, value: Route.folders)
            }
        case .locked:
            StateView(mood: .trouble, title: "This PDF needs a password", message: "Another app can ask for it.") { openElsewhere }
        case .cantOpen(let title, let message):
            StateView(mood: .trouble, title: title, message: message) { openElsewhere }
        }
    }

    private var openElsewhere: some View {
        Button("Open in another app") { actions?.select(.open, file: file) }
    }
}

// MARK: PDF

struct PDFViewer: View {
    let place: Place
    let page: Int?
    let words: [String]
    @State private var index: Int

    init(place: Place, page: Int?, words: [String]) {
        (self.place, self.page, self.words) = (place, page, words)
        _index = State(initialValue: place.index)
    }

    var body: some View {
        let first = index == place.index
        PDFPane(file: place.files[index], page: first ? page : nil, words: first ? words : [])
            .id(place.files[index].uri)
            .toolbar { if place.files.count > 1 { StepBar(count: place.files.count, index: $index) } }
    }
}

private struct PDFPane: View {
    let file: SourceFile
    /// The matched page; nil when the PDF was opened without a match.
    let page: Int?
    let words: [String]
    @State private var document: PDFDocument?
    @State private var problem: ViewerProblem?
    @State private var current: Int
    @Environment(AppModel.self) private var model: AppModel?

    init(file: SourceFile, page: Int?, words: [String]) {
        (self.file, self.page, self.words) = (file, page, words)
        _current = State(initialValue: page ?? 1)
    }

    var body: some View {
        Group {
            if let document {
                PDFPageView(document: document, page: page ?? 1, words: words)
                    .accessibilityIdentifier("viewer.pdf")
                    .ignoresSafeArea(edges: .bottom)
                    .accessibilityLabel(file.name)
            } else if let problem {
                ProblemView(file: file, problem: problem)
            } else {
                ProgressView()
            }
        }
        .overlay(alignment: .bottom) {
            if let document { Badge(lines: [ViewerCopy.match(page: page), ViewerCopy.page(current, of: document.pageCount)].compactMap { $0 }) }
        }
        .onReceive(NotificationCenter.default.publisher(for: .PDFViewPageChanged)) { note in
            if let view = note.object as? PDFView, let shown = view.currentPage, let document = view.document {
                current = document.index(for: shown) + 1
            }
        }
        .viewerBar(file)
        .task {
            guard document == nil, problem == nil else { return }
            switch reach(file, model?.services?.folders) {
            case .failure(let reason): problem = reason
            case .success(let url):
                let doc = await Self.open(url)
                if let doc, !doc.isLocked {
                    document = doc
                } else if doc != nil {
                    problem = .locked
                } else {
                    problem = .cantOpen(title: "Couldn't open this file", message: "It may be damaged, or in a form Retriever can't show.")
                }
            }
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
        return WordMatcher(words: words).ranges(in: text).compactMap { range in
            let selection = page.selection(for: range)
            selection?.color = .systemYellow
            return selection
        }
    }
}

// MARK: Media

struct MediaViewer: View {
    let place: Place
    let ms: Int64?
    @State private var index: Int

    init(place: Place, ms: Int64?) {
        (self.place, self.ms) = (place, ms)
        _index = State(initialValue: place.index)
    }

    var body: some View {
        MediaPane(file: place.files[index], ms: index == place.index ? ms : nil)
            .id(place.files[index].uri)
            .toolbar { if place.files.count > 1 { StepBar(count: place.files.count, index: $index) } }
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

private struct MediaPane: View {
    let file: SourceFile
    /// The matched moment; nil when there is no real locator.
    let ms: Int64?
    @State private var player: AVPlayer?
    @State private var problem: ViewerProblem?
    @Environment(AppModel.self) private var model: AppModel?

    var body: some View {
        Group {
            if let player {
                PlayerController(player: player).ignoresSafeArea(edges: .horizontal).accessibilityIdentifier("viewer.video")
            } else if let problem {
                ProblemView(file: file, problem: problem)
            } else {
                ProgressView()
            }
        }
        .overlay(alignment: .top) {
            if player != nil, let label = ViewerCopy.match(ms: ms) { Badge(lines: [label]) }
        }
        .viewerBar(file)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task {
            guard player == nil, problem == nil else { return }
            let item: AVPlayerItem?
            if file.uri.hasPrefix("ph://") {
                item = await MediaViewer.libraryItem(String(file.uri.dropFirst(5)))
            } else {
                switch reach(file, model?.services?.folders) {
                case .failure(let reason):
                    problem = reason
                    return
                case .success(let url): item = AVPlayerItem(url: url)
                }
            }
            guard !Task.isCancelled else { return }
            guard let item else {
                problem = .cantOpen(
                    title: file.kind == .audio ? "Couldn't play this audio" : "Couldn't play this video",
                    message: "It may be only in iCloud and there's no connection, or in a form Retriever can't play.")
                return
            }
            let player = AVPlayer(playerItem: item)
            #if DEBUG
                player.isMuted = CommandLine.arguments.contains("-mutedMedia")
            #endif
            // Opens at the moment, paused: nothing plays until the person presses play.
            await player.seek(to: CMTime(value: ms ?? 0, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
            guard !Task.isCancelled else { return }
            self.player = player
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        }
        .onDisappear { player?.pause() }
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
        .viewerBar(files[selection], detail: Place.label(index: selection, count: files.count))
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }
}

private struct PhotoPage: View {
    let file: SourceFile
    @State private var image: UIImage?
    @State private var progress: Double?
    @State private var failed = false
    @State private var attempt = 0
    @Environment(\.openURL) private var openURL

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image {
                ZoomableImage(image: image)
                    .accessibilityIdentifier("viewer.photo")
                    .ignoresSafeArea()
                    .accessibilityLabel("\(Words.shared.kindLabel(kind: file.kind)), \(file.name)")
                    .accessibilityAddTraits(.isImage)
            } else if failed, [.denied, .restricted].contains(PHPhotoLibrary.authorizationStatus(for: .readWrite)) {
                StateView(art: "IllusWelcome", title: "Photos access is off", message: "Turn it on in Settings to see your photos here.") {
                    Button("Open Settings") { if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) } }
                }
            } else if failed {
                StateView(mood: .trouble, title: "Couldn't show this photo", message: "It may be only in iCloud, and there's no connection right now.") {
                    Button("Try again") { attempt += 1 }
                }
            } else if let progress {
                ProgressView("Downloading from iCloud", value: progress).tint(.white).foregroundStyle(.white).padding(40)
            } else {
                ProgressView().tint(.white)
            }
        }
        .environment(\.colorScheme, .dark)
        .task(id: attempt) {
            guard image == nil else { return }
            failed = false
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

// MARK: Documents

/// Quick Look keeps its native rendering and paging; the app bar supplies the same file actions as other viewers.
private struct DocumentViewer: View {
    let place: Place
    let locator: String
    let words: [String]
    @Environment(\.services) private var services

    var body: some View {
        let items = place.files.compactMap { f in services?.folders.resolve(f.uri).map { (f, $0) } }
        if !items.contains(where: { $0.0.uri == place.file.uri }) {
            ProblemView(file: place.file, problem: .noAccess).viewerBar(place.file)
        } else {
            DocumentPane(items: items, initial: place.file.uri, locator: locator, words: words)
        }
    }
}

private struct DocumentPane: View {
    let items: [(SourceFile, URL)]
    let locator: String
    let words: [String]
    @State private var index: Int
    @State private var reading = false

    init(items: [(SourceFile, URL)], initial: String, locator: String, words: [String]) {
        (self.items, self.locator, self.words) = (items, locator, words)
        _index = State(initialValue: items.firstIndex { $0.0.uri == initial } ?? 0)
    }

    var body: some View {
        let file = items[index].0
        DocumentPreview(items: items, index: $index)
            .ignoresSafeArea(edges: .bottom)
            .viewerBar(file) {
                if file.kind == .doc {
                    Button {
                        reading = true
                    } label: {
                        Label("Text from this file", systemImage: "text.alignleft").labelStyle(.iconOnly)
                    }
                }
            }
            .toolbar { if items.count > 1 { StepBar(count: items.count, index: $index) } }
            .sheet(isPresented: $reading) { TextReader(file: file, locator: locator, words: words) }
    }
}

private struct DocumentPreview: UIViewControllerRepresentable {
    let items: [(SourceFile, URL)]
    @Binding var index: Int

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        controller.currentPreviewItemIndex = index
        context.coordinator.observation = controller.observe(\.currentPreviewItemIndex, options: [.new]) { _, change in
            let shown = change.newValue ?? 0
            Task { @MainActor in
                if items.indices.contains(shown), shown != index { index = shown }
            }
        }
        return controller
    }
    func updateUIViewController(_ controller: QLPreviewController, context: Context) {
        if controller.currentPreviewItemIndex != index { controller.currentPreviewItemIndex = index }
    }
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let parent: DocumentPreview
        var observation: NSKeyValueObservation?
        init(_ parent: DocumentPreview) { self.parent = parent }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { parent.items.count }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { parent.items[index].1 as NSURL }
    }
}
