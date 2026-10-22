import QuickLookThumbnailing
import XCTest

@testable import Retriever

/// Documents get Quick Look's picture, as Files draws it, never a text stand-in: a page for a Word file, a page for
/// one line of text, and the system's icon for a type nothing can draw.
final class ThumbnailsTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: folder)
    }

    private func representation(_ name: String) async throws -> QLThumbnailRepresentation {
        let made = await Thumbnails.quickLook(folder.appendingPathComponent(name), pixels: 256)
        let shown = try XCTUnwrap(made, name)
        XCTAssertNotNil(shown.uiImage.cgImage, name)
        return shown
    }

    func testAWordFileShowsItsPage() async throws {
        // A generic one-page document (eval/make-corpus.sh's Doc_A.docx) kept beside this file: eval/corpus is generated
        // and ignored, so a checkout without it failed here on every simulator.
        let letter = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Letter.docx")
        try FileManager.default.copyItem(at: letter, to: folder.appendingPathComponent("Letter.docx"))
        let shown = try await representation("Letter.docx")
        // Quick Look promises a page or, failing that, the icon, with no time limit: on three just-booted simulators
        // at once its extension gave up after about a minute in 5 of 9 runs. Thumbnails doesn't keep that icon.
        if shown.type == .icon {
            throw XCTSkip("Quick Look fell back to the icon: its thumbnail extension didn't finish (fresh or busy simulator)")
        }
        XCTAssertEqual(shown.type, .thumbnail)
    }

    func testOneLineOfTextIsAPictureNotNothing() async throws {
        try Data("Meet at the station at nine.\n".utf8).write(to: folder.appendingPathComponent("Notes.txt"))
        _ = try await representation("Notes.txt")
    }

    func testAnUnknownTypeShowsTheSystemIcon() async throws {
        try Data("zzzz".utf8).write(to: folder.appendingPathComponent("Archive.unknownkind"))
        let shown = try await representation("Archive.unknownkind")
        XCTAssertEqual(shown.type, .icon)
    }
}
