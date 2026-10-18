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
        XCTAssertEqual(quickLookFiles(doc, among: [first, doc, video, other]).map(\.uri), [doc.uri, other.uri])
        var opened: [SourceFile] = []
        let open = OpenFile { _, _, _, neighbors in opened = neighbors }
        open(second, among: [first, second])
        XCTAssertEqual(opened.map(\.uri), [first.uri, second.uri])
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
        let item = await VideoViewer.libraryItem(video.localIdentifier)
        XCTAssertNotNil(item, "the real arbitrary-queue Photos callback must return without trapping")
        let retriever = AppServices.shared.retriever
        try await retriever.pause()
        _ = try await retriever.indexBatchSeconds(seconds: 30)
        let files = try await retriever.files(kind: nil, folder: nil, sort: .name, limit: 100, offset: 0)
        XCTAssertTrue(files.contains { $0.file.kind == .video && $0.file.uri.hasPrefix("ph://") })
        XCTAssertTrue(files.contains { $0.file.kind == .pdf })
    }

    func testFolderViewerUsesItsEnvironmentAndVideoUsesPlaybackAudio() async throws {
        let services = AppServices.temporary()
        let uri = "folder://documents/environment.pdf"
        let url = try XCTUnwrap(services.folders.resolve(uri))
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let pdf = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 400, height: 600)).pdfData { $0.beginPage() }
        try pdf.write(to: url)
        let file = SourceFile(
            uri: uri, name: "environment.pdf", folder: "test", mime: "application/pdf", kind: .pdf, size: Int64(pdf.count), modifiedAt: 0, takenAt: nil)
        let model = AppModel(services: services)
        let host = UIHostingController(rootView: AppStack(model: model, path: .constant([])) { PDFViewer(file: file, page: 1, words: []) })
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
        let player = UIHostingController(rootView: AppStack(model: model, path: .constant([])) { VideoViewer(file: movie, ms: 0) })
        window.rootViewController = player
        let playing = await eventually { AVAudioSession.sharedInstance().category == .playback }
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
