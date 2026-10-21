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
        // A generic one-page document from the evaluation corpus, under a generic name.
        let corpus = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../eval/corpus/docs/Doc_A.docx")
        try FileManager.default.copyItem(at: corpus, to: folder.appendingPathComponent("Letter.docx"))
        let shown = try await representation("Letter.docx")
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
