import RetrieverCore
import SwiftUI
import XCTest

@testable import Retriever

@MainActor
final class ReadingFilesTests: XCTestCase {
    private func file(_ i: Int) -> SourceFile {
        SourceFile(
            uri: "demo:\(i)", name: "\(i).jpg", folder: "Photos", mime: "image/jpeg", kind: .photo,
            size: 100, modifiedAt: Int64(i), takenAt: nil)
    }

    func testAFailureReplacesTheWaitSentenceAndNeverJoinsIt() {
        let wait = "Plug in, or choose Read now for a short session."
        XCTAssertEqual(StatusContent.reason(detail: wait, lastError: nil, message: nil)?.text, wait)
        XCTAssertEqual(StatusContent.reason(detail: wait, lastError: nil, message: nil)?.failed, false)
        XCTAssertEqual(StatusContent.reason(detail: wait, lastError: "Couldn’t open the model", message: nil)?.text, "Couldn’t open the model")
        XCTAssertEqual(
            StatusContent.reason(detail: wait, lastError: "old", message: "Couldn’t save this change. Try again.")?.text,
            "Couldn’t save this change. Try again.")
        XCTAssertEqual(StatusContent.reason(detail: wait, lastError: "x", message: nil)?.failed, true)
        XCTAssertNil(StatusContent.reason(detail: nil, lastError: nil, message: nil))
    }

    /// Recently read shifted every tile one place per file read; now what is on screen stays and new files join the end.
    func testRecentlyReadKeepsItsTilesInPlaceAsFilesAreRead() {
        let first = RecentlyRead.held([], [file(3), file(2), file(1)])
        XCTAssertEqual(first.map(\.uri), ["demo:3", "demo:2", "demo:1"], "newest first when it appears")
        let next = RecentlyRead.held(first, [file(5), file(4), file(3), file(2)])
        XCTAssertEqual(next.map(\.uri), ["demo:3", "demo:2", "demo:1", "demo:4", "demo:5"], "the shown tiles never move")
    }

    /// Reading status's dog walked ten seconds then held still for fifty, Home's cadence; there it walks while reading runs.
    func testReadingStatusKeepsTheDogWalkingWhileReadingRuns() {
        let walk = MascotDirector.shared.next(mood: .walking, last: nil, onScreen: 0, settles: StatusContent.dogSettles)
        let later = MascotDirector.shared.next(mood: .walking, last: walk, onScreen: 3_600, settles: StatusContent.dogSettles)
        XCTAssertNotEqual(later.kind, .hold, "an hour in, still walking")
        XCTAssertEqual(later.clip, .walk)
    }

    private func sample(_ count: Int, read: Int) -> [SampleFile] {
        (0..<count).map { SampleFile(index: Int32($0), file: file($0 + 1), read: $0 < read, note: nil) }
    }

    /// The pile holds the last three processed files, in plan order, each at its own index's slot.
    func testThePileShowsTheLastThreeReadInPlanOrder() {
        let shown = SamplePile.shared.shown(files: sample(30, read: 20))
        XCTAssertEqual(shown.map(\.index), Array(17..<20).map(Int32.init))
        XCTAssertTrue(SamplePile.shared.shown(files: sample(5, read: 0)).isEmpty)
    }

    /// The empty pile shows the plan's first three kinds, or photos, PDFs and documents before there is a plan.
    func testTheEmptyPileShowsThePlansFirstKinds() {
        XCTAssertEqual(SamplePileView.kinds([]), [.photo, .pdf, .doc])
        let mixed = [file(1), file(2)].enumerated().map { SampleFile(index: Int32($0.offset), file: $0.element, read: false, note: nil) }
        XCTAssertEqual(SamplePileView.kinds(mixed), [.photo])
    }

    /// The pile is one fixed square whether it holds nothing, one card, twelve or more: the screen never moves.
    func testThePileKeepsOneSizeAtAnyCount() {
        for width: CGFloat in [320, 390, 430] {
            func size(_ count: Int) -> CGSize {
                let cards = SamplePile.shared.shown(files: sample(count, read: count))
                let host = UIHostingController(rootView: SamplePileView(cards: cards, kinds: [.photo], label: "", hint: "") {})
                return host.sizeThatFits(in: CGSize(width: width, height: 4000))
            }
            for count in [1, 12, 20] { XCTAssertEqual(size(count), size(0), "\(count) cards at \(width) pt") }
            XCTAssertEqual(size(0).height, min(CGFloat(SamplePile.shared.FOOTPRINT_DP), width), accuracy: 1)
        }
    }

    func testTheSampleIsSpokenByKindNotShownAsSlashCounts() {
        let taste = TasteSnap(counts: [.photo: .init(read: 12, planned: 40), .pdf: .init(read: 3, planned: 5)], pending: 28)
        XCTAssertEqual(taste.planned, 45)
        XCTAssertEqual(taste.done, 17, "a name-only or unopenable file is done without being read")
        XCTAssertTrue(taste.spoken.contains("12 of 40 photos processed"), taste.spoken)
        XCTAssertTrue(taste.spoken.contains("3 of 5 PDFs processed"), taste.spoken)
        XCTAssertFalse(taste.spoken.contains("/"))
    }

    func testFileProgressDoesNotInvalidateTheSettledSnapshot() throws {
        let model = AppModel(fixture: .reading(done: 30, total: 40))
        var snap = try XCTUnwrap(model.live)
        let writes = model.snapWrites
        snap.reading = ReadingPreview(current: file(25), recent: (1...24).reversed().map(file))
        model.apply(snap, starred: model.starred, modelMissing: model.modelMissing, suggestions: model.suggestions)
        XCTAssertEqual(model.snapWrites, writes)
        XCTAssertEqual(model.live?.reading, snap.reading)
        XCTAssertEqual(model.snap?.reading, ReadingPreview())
        snap.reading.current = nil
        snap.reading.pauseReason = "Paused while you use other apps. It carries on when you come back."
        model.apply(snap, starred: model.starred, modelMissing: model.modelMissing, suggestions: model.suggestions)
        XCTAssertNil(model.live?.reading.current)
        XCTAssertEqual(model.live?.reading.pauseReason, snap.reading.pauseReason)
        XCTAssertEqual(model.live?.reading.recent.count, 24)
    }

    func testExistingTilesCompareEqualAfterAnInsertion() {
        let before = (1...4).map(file)
        let after = [file(5)] + (1...4).map(file)
        for old in before {
            let retained = after.first { $0.uri == old.uri }!
            XCTAssertEqual(FileTile(file: old), FileTile(file: retained))
        }
        XCTAssertNotEqual(FileTile(file: before[0]), FileTile(file: after[0]))
    }
}
