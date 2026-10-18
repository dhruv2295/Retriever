import RetrieverCore
import XCTest
@testable import Retriever

@MainActor final class FileActionsTests: XCTestCase {
    func testPhotoActionsHideRename() {
        XCTAssertEqual(FileAction.available("ph://asset"), [.info, .share, .open, .delete])
    }

    func testFolderActionsIncludeRename() {
        XCTAssertEqual(FileAction.available("folder://documents/test.txt"), FileAction.allCases)
    }

    func testUnknownSourcesOnlyShowInfo() {
        XCTAssertEqual(FileAction.available("unknown://file"), [.info])
    }

    func testNamesCannotEscapeTheFolder() {
        for name in ["", " ", ".", "..", "../secret", "a/b", "a\\b", "a\u{0}b"] { XCTAssertFalse(FileAction.validName(name)) }
        XCTAssertTrue(FileAction.validName("Trip 2026.pdf"))
    }

    func testHeroUsesWholeLibraryDuringTrial() {
        var snap = AppModel.Snap(setUp: true, byKind: [.photo: 100, .pdf: 10], indexed: 4, pending: 6, trial: true, waiting: 100)
        snap.readByKind = [.pdf: 4]
        XCTAssertEqual(snap.libraryTotal, 110)
        XCTAssertEqual(snap.readByKind[.pdf], 4)
        XCTAssertEqual(snap.pending, 6)
    }

    func testDismissalChangesHomeState() {
        let model = AppModel(fixture: .caughtUp)
        XCTAssertFalse(model.snap!.caughtUpDismissed)
        model.dismissCaughtUp()
        XCTAssertTrue(model.snap!.caughtUpDismissed)
    }
}
