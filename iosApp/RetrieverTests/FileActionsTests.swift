import RetrieverCore
import XCTest

@testable import Retriever

@MainActor final class FileActionsTests: XCTestCase {
    func testPhotoActionsFollowTheOneOrderAndHideRename() {
        XCTAssertEqual(FileAction.available("ph://asset"), [.star, .share, .open, .info, .delete])
    }

    func testFolderActionsAreInTheSpecOrderWithDeleteLast() {
        XCTAssertEqual(FileAction.available("folder://documents/test.txt"), [.star, .share, .open, .info, .rename, .delete])
        XCTAssertEqual(
            FileAction.allCases.map { $0.title(starred: false) },
            ["Add to Starred", "Share", "Open in another app", "File info", "Rename", "Delete from \(Copy.device)"])
        XCTAssertEqual(FileAction.star.title(starred: true), "Remove from Starred")
        XCTAssertFalse(FileAction.allCases.contains { $0.icon(starred: false).isEmpty }, "every item has an icon")
    }

    func testUnknownSourcesOnlyStarAndShowInfo() {
        XCTAssertEqual(FileAction.available("unknown://file"), [.star, .info])
    }

    func testReadingComesFromTheSharedCore() {
        let file = SourceFile(uri: "folder://1/a.pdf", name: "a.pdf", folder: "", mime: "", kind: .pdf, size: 1, modifiedAt: 0, takenAt: nil)
        let partly = Store.StoredFile(
            id: 1, file: file, attempts: 0, state: .indexed, partial: true, extent: nil, contentRead: true, quickHash: nil, fullHash: nil)
        XCTAssertEqual(ReadingProgressKt.readingLabel(kind: .pdf, stored: partly, readInside: true), "Name and its beginning")
        XCTAssertEqual(ReadingProgressKt.readingLabel(kind: .pdf, stored: nil, readInside: true), "Its name. Not processed yet.")
    }

    func testThePlaceIsTheFoldersOwnName() {
        let inFolder = SourceFile(uri: "folder://1/a.pdf", name: "a.pdf", folder: "Documents/Taxes", mime: "", kind: .pdf, size: 1, modifiedAt: 0, takenAt: nil)
        XCTAssertEqual(FileActions.place(inFolder), "Taxes")
        XCTAssertEqual(
            FileActions.place(SourceFile(uri: "folder://1/b.pdf", name: "b.pdf", folder: "", mime: "", kind: .pdf, size: 1, modifiedAt: 0, takenAt: nil)),
            "Top level")
        XCTAssertEqual(
            FileActions.place(SourceFile(uri: "ph://x", name: "x", folder: "Recents", mime: "", kind: .photo, size: 0, modifiedAt: 0, takenAt: nil)), "Photos")
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
