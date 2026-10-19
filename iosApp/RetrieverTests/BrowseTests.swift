import RetrieverCore
import XCTest

@testable import Retriever

@MainActor
final class BrowseTests: XCTestCase {
    func testFirstPageFailureIsNotAnEmptyAnswerAndCanRetry() async {
        var attempts = 0
        let pages = FilePages { _, _ in
            attempts += 1
            if attempts == 1 { throw CocoaError(.fileReadUnknown) }
            return []
        }
        await pages.load(.newest)
        XCTAssertTrue(pages.failed)
        XCTAssertFalse(pages.exhausted)
        XCTAssertFalse(pages.loading)
        await pages.loadPage()
        XCTAssertFalse(pages.failed)
        XCTAssertTrue(pages.exhausted)
        XCTAssertEqual(attempts, 2)
    }

    func testLaterPageFailureKeepsFilesAndRetriesTheSameOffset() async {
        var offsets: [Int32] = []
        let first = (0..<60).map(file)
        let pages = FilePages { _, offset in
            offsets.append(offset)
            if offsets.count == 2 { throw CocoaError(.fileReadUnknown) }
            return offset == 0 ? first : []
        }
        await pages.load(.newest)
        await pages.loadPage()
        XCTAssertTrue(pages.failed)
        XCTAssertEqual(pages.files.count, 60)
        XCTAssertFalse(pages.exhausted)
        await pages.loadPage()
        XCTAssertFalse(pages.failed)
        XCTAssertEqual(offsets, [0, 60, 60])
    }

    func testReturningKeepsPagesButChangingSortLoadsAgain() async {
        var requests: [FileQuery.Sort] = []
        let pages = FilePages { sort, _ in
            requests.append(sort)
            return []
        }
        await pages.load(.newest)
        await pages.load(.newest)
        XCTAssertEqual(requests, [.newest])
        await pages.load(.largest)
        XCTAssertEqual(requests, [.newest, .largest])
    }

    func testOverlappingPagesAdvanceByFetchedRowsAndDeduplicateWithinAPage() async {
        var offsets: [Int32] = []
        let pages = FilePages { _, offset in
            offsets.append(offset)
            switch offset {
            case 0: return (0..<60).map(self.file)
            case 60: return (0..<60).map(self.file)
            default: return [self.file(60), self.file(60), self.file(61)]
            }
        }
        await pages.load(.newest)
        await pages.loadPage()
        await pages.loadPage()
        XCTAssertEqual(offsets, [0, 60, 120])
        XCTAssertEqual(pages.files.map(\.id), (0..<62).map(Int64.init))
        XCTAssertTrue(pages.exhausted)
        await pages.load(.largest)
        XCTAssertEqual(offsets.last, 0)
        XCTAssertEqual(pages.files.count, 60, "a new sort clears the IDs from the previous pages")
    }

    private func file(_ id: Int) -> Store.StoredFile {
        let file = SourceFile(uri: "folder://test/\(id)", name: "\(id)", folder: "test", mime: "", kind: .doc, size: 1, modifiedAt: 0, takenAt: nil)
        return Store.StoredFile(id: Int64(id), file: file, attempts: 0, state: .indexed, partial: false, extent: nil)
    }
}
