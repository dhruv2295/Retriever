import RetrieverCore
import XCTest

@testable import Retriever

@MainActor
final class ReadingFilesTests: XCTestCase {
    private func file(_ i: Int) -> SourceFile {
        SourceFile(
            uri: "demo:\(i)", name: "\(i).jpg", folder: "Photos", mime: "image/jpeg", kind: .photo,
            size: 100, modifiedAt: Int64(i), takenAt: nil)
    }

    func testVisibleCellsKeepNewestFirstAndCountTheRest() {
        let files = (1...24).reversed().map(file)
        let reading = ReadingPreview(current: file(25), recent: files)
        XCTAssertEqual(reading.recent.map(\.uri), files.map(\.uri))
        XCTAssertEqual(reading.current?.uri, "demo:25")
        XCTAssertEqual(reading.more(read: 71), "+47 more")
        XCTAssertNil(reading.more(read: 24))
        XCTAssertNil(reading.more(read: 23), "a lagging count never shows a negative remainder")
        XCTAssertNil(ReadingPreview().more(read: 0))
        XCTAssertEqual(ReadingPreview().more(read: 5), "+5 more")
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
}
