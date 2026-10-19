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

    /// Six tiles at most, the file being read first and then the newest read; never a blank slot or a file twice.
    func testTheSampleMosaicShowsTheReadingFileThenTheNewestRead() {
        let recent = (1...24).reversed().map(file)
        func tiles(_ preview: ReadingPreview, columns: Int = 2) -> [SourceFile] { SampleMosaic.tiles(preview, columns: columns) }
        XCTAssertEqual(tiles(ReadingPreview(current: file(25), recent: recent)).map(\.uri), ["demo:25"] + (20...24).reversed().map { "demo:\($0)" })
        XCTAssertEqual(tiles(ReadingPreview(current: file(24), recent: recent)).map(\.uri), (19...24).reversed().map { "demo:\($0)" })
        XCTAssertEqual(tiles(ReadingPreview(current: nil, recent: Array(recent.prefix(2)))).count, 2, "paused: what was read stays")
        XCTAssertEqual(tiles(ReadingPreview(current: nil, recent: Array(recent.prefix(5)))).count, 4, "whole rows: no empty half-row")
        XCTAssertEqual(tiles(ReadingPreview(current: file(25), recent: Array(recent.prefix(2)))).count, 2)
        XCTAssertEqual(tiles(ReadingPreview(current: nil, recent: Array(recent.prefix(5))), columns: 1).count, 5, "one column fills every row")
        XCTAssertEqual(tiles(ReadingPreview(current: file(1))).count, 1, "the first file alone, no reserved slots")
        XCTAssertEqual(tiles(ReadingPreview()), [])
        XCTAssertEqual(SampleMosaic.columns(.large), 2)
        XCTAssertEqual(SampleMosaic.columns(.accessibility1), 1, "one column of rows at the largest text sizes")
    }

    func testTheSampleIsSpokenByKindNotShownAsSlashCounts() {
        let taste = TasteSnap(counts: [.photo: .init(read: 12, planned: 40), .pdf: .init(read: 3, planned: 5)], readyToTry: true, pending: 30)
        XCTAssertEqual(taste.read, 15)
        XCTAssertEqual(taste.planned, 45)
        XCTAssertTrue(taste.spoken.contains("12 of 40 photos read"), taste.spoken)
        XCTAssertTrue(taste.spoken.contains("3 of 5 PDFs read"), taste.spoken)
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

    func testExistingCellsCompareEqualAfterAnInsertion() {
        let before = (1...4).map(file)
        let after = [file(5)] + (1...4).map(file)
        for old in before {
            let retained = after.first { $0.uri == old.uri }!
            XCTAssertEqual(RecentFileCell(file: old), RecentFileCell(file: retained))
        }
        XCTAssertNotEqual(RecentFileCell(file: before[0]), RecentFileCell(file: after[0]))
    }
    /// Two tiles share a row on a phone; a third waits for a fourth, so the next row starts full.
    func testTwoSampleTilesShareOneRowAtSeveralWidths() {
        for width: CGFloat in [320, 375, 430] {
            func height(_ count: Int) -> CGFloat {
                let preview = ReadingPreview(current: file(count), recent: (1..<count).map(file))
                let host = UIHostingController(rootView: SampleMosaic(preview: preview).environment(\.dynamicTypeSize, .large))
                return host.sizeThatFits(in: CGSize(width: width, height: 4000)).height
            }
            XCTAssertEqual(height(3), height(2), accuracy: 1)
            XCTAssertGreaterThan(height(4), height(2) + 40)
        }
    }
}
