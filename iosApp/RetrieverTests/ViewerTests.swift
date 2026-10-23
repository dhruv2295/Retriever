import AVFoundation
import AVKit
import PDFKit
import Photos
import RetrieverCore
import SwiftUI
import XCTest
import os

@testable import Retriever

@MainActor
final class ViewerTests: XCTestCase {
    func testViewerHighlightTermsShareTheCoreStopwordRule() {
        XCTAssertEqual(highlightWords(snippet: nil, query: "a deposit at the bank"), ["deposit", "bank"])
        XCTAssertEqual(highlightWords(snippet: nil, query: "at"), ["at"])
    }

    func testPhotosCancellationCompletesWithoutACallbackAndIgnoresLateResults() async throws {
        let callbacks = OSAllocatedUnfairLock<(@Sendable (sending Int?) -> Void)?>(initialState: nil)
        let cancelled = OSAllocatedUnfairLock<[PHImageRequestID]>(initialState: [])
        let task = Task {
            await awaitPhotoRequest(cancel: { id in cancelled.withLock { $0.append(id) } }) { done in
                callbacks.withLock { $0 = done }
                return 42
            }
        }
        let started = await eventually { callbacks.withLock { $0 != nil } }
        XCTAssertTrue(started)
        task.cancel()
        let value = await task.value
        XCTAssertNil(value)
        XCTAssertEqual(cancelled.withLock { $0 }, [42])
        callbacks.withLock { $0 }?(1)
        callbacks.withLock { $0 }?(2)
    }

    func testPhotosCallbackCanArriveSynchronouslyOrOnABackgroundQueue() async {
        let synchronous: Int? = await awaitPhotoRequest(
            cancel: { _ in },
            start: { done in
                done(1)
                done(2)
                return 1
            })
        XCTAssertEqual(synchronous, 1)
        let background: Int? = await awaitPhotoRequest(
            cancel: { _ in },
            start: { done in
                DispatchQueue.global().async { done(3) }
                return 2
            })
        XCTAssertEqual(background, 3)
    }

    func testCancellationBeforeRegistrationNeverStartsPhotos() async {
        let starts = OSAllocatedUnfairLock(initialState: 0)
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return await awaitPhotoRequest(cancel: { _ in }) { (done: @escaping @Sendable (sending Int?) -> Void) in
                starts.withLock { $0 += 1 }
                done(1)
                return 1
            }
        }
        let value = await task.value
        XCTAssertNil(value)
        XCTAssertEqual(starts.withLock { $0 }, 0)
    }

    func testCancellationWhilePhotosIsReturningItsRequestID() async {
        let started = OSAllocatedUnfairLock(initialState: false)
        let cancelled = OSAllocatedUnfairLock<[PHImageRequestID]>(initialState: [])
        let release = DispatchSemaphore(value: 0)
        let task = Task {
            await awaitPhotoRequest(cancel: { id in cancelled.withLock { $0.append(id) } }) { (_: @escaping @Sendable (sending Int?) -> Void) in
                started.withLock { $0 = true }
                release.wait()
                return 7
            }
        }
        let began = await eventually { started.withLock { $0 } }
        XCTAssertTrue(began)
        task.cancel()
        release.signal()
        let value = await task.value
        XCTAssertNil(value)
        XCTAssertEqual(cancelled.withLock { $0 }, [7])
    }

    func testPhotoNeighborsAndQuickLookSelection() throws {
        let first = file("first", .photo, library: true)
        let second = file("second", .photo, library: true)
        let video = file("movie", .video, library: true)
        let doc = file("notes", .doc)
        let other = file("other", .doc)
        XCTAssertEqual(viewing(second, locator: "", words: [], among: [first, video, second]), .photos([first, second], index: 1))
        XCTAssertEqual(viewing(second, locator: "", words: [], among: [first]), .photos([second], index: 0))
        XCTAssertEqual(
            viewing(doc, locator: "", words: ["a"], among: [first, doc, video, other]),
            .document(Place(files: [doc, other], index: 0), locator: "", words: ["a"]))
        var opened: [SourceFile] = []
        let open = OpenFile { _, _, _, neighbors in opened = neighbors }
        open(second, among: [first, second])
        XCTAssertEqual(opened.map(\.uri), [first.uri, second.uri])
    }

    func testPDFsAndMediaStepThroughTheirOwnKindAndMatchOnlyWhenTheLocatorSaysSo() {
        let a = file("a", .pdf)
        let b = file("b", .pdf)
        let movie = file("movie", .video)
        let song = file("song", .audio)
        let doc = file("notes", .doc)
        XCTAssertEqual(viewing(b, locator: "", words: [], among: [a, doc, b]), .pdf(Place(files: [a, b], index: 1), page: nil, words: []))
        XCTAssertEqual(viewing(a, locator: "p. 3", words: ["x"], among: [a, b]), .pdf(Place(files: [a, b], index: 0), page: 3, words: ["x"]))
        XCTAssertEqual(viewing(song, locator: "1:05", words: [], among: [movie, doc, song]), .media(Place(files: [movie, song], index: 1), ms: 65000))
        XCTAssertEqual(viewing(movie, locator: "", words: [], among: []), .media(Place(files: [movie], index: 0), ms: nil))
    }

    func testPositionAndMatchLabels() {
        XCTAssertEqual(Place.label(index: 1, count: 5), "2 of 5")
        XCTAssertNil(Place.label(index: 0, count: 1), "a lone file has no position to show")
        XCTAssertEqual(ViewerCopy.match(page: 3), "Match on page 3")
        XCTAssertNil(ViewerCopy.match(page: nil))
        XCTAssertEqual(ViewerCopy.page(2, of: 12), "Page 2 of 12")
        XCTAssertNil(ViewerCopy.page(1, of: 1))
        XCTAssertEqual(ViewerCopy.match(ms: 65000), "Match at 1:05")
        XCTAssertEqual(ViewerCopy.match(ms: 3_725_000), "Match at 1:02:05")
        XCTAssertNil(ViewerCopy.match(ms: nil))
        XCTAssertEqual(ViewerCopy.clock(seconds: 5), "0:05")
        XCTAssertEqual(ViewerCopy.clock(seconds: 3_725, locale: Locale(identifier: "ar_EG")), "١:٠٢:٠٥", "in the person's digits")
    }

    func testReaderMatchesAreTheParagraphsWithTheWordsAndStepWrapRound() {
        let matches = ReaderMatches(paragraphs: ["The bank, then the Bank.", "Nothing here", "banking and BANK"], words: ["bank", "at"])
        XCTAssertEqual(matches.total, 2, "a paragraph counts once; whole words only, any case; two-letter words are not marked")
        XCTAssertEqual(matches.paragraph(0), 0)
        XCTAssertEqual(matches.paragraph(1), 2)
        XCTAssertNil(matches.paragraph(2))
        let locators = ["p. 1", "p. 2", "p. 3"]
        XCTAssertTrue(matches.opening(locators: locators, hit: "") == (0, 0), "no hit: the first matching paragraph")
        XCTAssertTrue(matches.opening(locators: locators, hit: "p. 3") == (2, 1), "the hit's own paragraph, second among the matches")
        XCTAssertTrue(matches.opening(locators: locators, hit: "p. 2") == (1, 0), "a hit page without the words opens there")
        XCTAssertEqual(ReaderMatches.step(1, by: 1, total: 2), 0)
        XCTAssertEqual(ReaderMatches.step(0, by: -1, total: 2), 1)
        XCTAssertEqual(ReaderMatches.step(0, by: 1, total: 0), 0)
    }

    func testPDFJumpWaitsForLayoutAndRunsOnlyOnce() throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 600, height: 800)).image { $0.fill(CGRect(x: 0, y: 0, width: 600, height: 800)) }
        let document = PDFDocument()
        document.insert(try XCTUnwrap(PDFPage(image: image)), at: 0)
        document.insert(try XCTUnwrap(PDFPage(image: image)), at: 1)
        let view = JumpPDFView()
        view.autoScales = true
        view.document = document
        view.pending = PDFDestination(page: try XCTUnwrap(document.page(at: 1)), at: CGPoint(x: 0, y: 800))
        view.layoutSubviews()
        XCTAssertNotNil(view.pending, "zero bounds cannot consume the jump")
        view.frame = CGRect(x: 0, y: 0, width: 390, height: 800)
        view.layoutIfNeeded()
        XCTAssertNil(view.pending)
        XCTAssertEqual(view.currentPage, document.page(at: 1))
        view.go(to: try XCTUnwrap(document.page(at: 0)))
        view.setNeedsLayout()
        view.layoutIfNeeded()
        XCTAssertEqual(view.currentPage, document.page(at: 0), "later layout must not repeat the initial jump")
    }

    /// The script seeds this disposable library before testing; sync its names for the subsequent live UI routes.
    func testSeededLibraryVideoOpensOffTheMainActor() async throws {
        let videos = PHAsset.fetchAssets(with: .video, options: nil)
        let video = try XCTUnwrap(videos.firstObject, "scripts/ios-unit-tests.sh must seed a library video")
        let item = await MediaViewer.libraryItem(video.localIdentifier)
        XCTAssertNotNil(item, "the real arbitrary-queue Photos callback must return without trapping")
        let retriever = AppServices.shared.retriever
        try await retriever.pause()
        _ = try await retriever.indexBatchSeconds(seconds: 30)
        let files = try await retriever.files(kind: nil, folder: nil, sort: .name, limit: 100, offset: 0)
        XCTAssertTrue(files.contains { $0.file.kind == .video && $0.file.uri.hasPrefix("ph://") })
        XCTAssertTrue(files.contains { $0.file.kind == .pdf })
    }

    func testFolderViewerUsesItsEnvironmentAndVideoUsesPlaybackAudio() async throws {
        let services = temporaryServices()
        let uri = "folder://documents/environment.pdf"
        let url = try XCTUnwrap(services.folders.resolve(uri))
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let pdf = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 400, height: 600)).pdfData { $0.beginPage() }
        try pdf.write(to: url)
        let file = SourceFile(
            uri: uri, name: "environment.pdf", folder: "test", mime: "application/pdf", kind: .pdf, size: Int64(pdf.count), modifiedAt: 0, takenAt: nil)
        let model = AppModel(services: services)
        let host = UIHostingController(
            rootView: AppStack(model: model, path: .constant([])) { PDFViewer(place: Place(files: [file], index: 0), page: 1, words: []) })
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let opened = await eventually { self.pdfView(in: host.view)?.document != nil }
        XCTAssertTrue(opened, "the PDF lives only under the injected services")

        let video = try XCTUnwrap(PHAsset.fetchAssets(with: .video, options: nil).firstObject)
        let movie = SourceFile(
            uri: "ph://\(video.localIdentifier)", name: "video", folder: "Photos", mime: "video/mp4", kind: .video, size: 1, modifiedAt: 0, takenAt: nil)
        try AVAudioSession.sharedInstance().setCategory(.soloAmbient)
        let player = UIHostingController(rootView: AppStack(model: model, path: .constant([])) { MediaViewer(place: Place(files: [movie], index: 0), ms: 0) })
        window.rootViewController = player
        let playing = await eventually {
            AVAudioSession.sharedInstance().category == .playback && self.playerController(in: player)?.player != nil
        }
        XCTAssertTrue(playing)
        XCTAssertEqual(AVAudioSession.sharedInstance().mode, .moviePlayback)
        let controller = try XCTUnwrap(playerController(in: player))
        let media = try XCTUnwrap(controller.player)
        XCTAssertTrue(CommandLine.arguments.contains("-mutedMedia"), "the test host must launch with muted media")
        XCTAssertTrue(media.isMuted, "the viewer must be muted before playback starts")
        media.pause()
    }

    private func playerController(in controller: UIViewController) -> AVPlayerViewController? {
        (controller as? AVPlayerViewController) ?? controller.children.lazy.compactMap { self.playerController(in: $0) }.first
    }

    private func pdfView(in view: UIView) -> PDFView? {
        (view as? PDFView) ?? view.subviews.lazy.compactMap { self.pdfView(in: $0) }.first
    }

    private func file(_ name: String, _ kind: Kind, library: Bool = false) -> SourceFile {
        SourceFile(uri: "\(library ? "ph" : "folder")://\(name)", name: name, folder: "test", mime: "", kind: kind, size: 1, modifiedAt: 0, takenAt: nil)
    }
}
