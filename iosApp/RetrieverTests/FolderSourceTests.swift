import RetrieverCore
import XCTest

@testable import Retriever

/// A folder's files keep the URIs they were indexed under: a changed URI would read every file again.
final class FolderSourceTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: root)
    }

    private func write(_ path: String, in dir: URL, _ text: String = "Meet at the station at nine.") throws {
        let url = dir.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(text.utf8).write(to: url)
    }

    /// Reached through a symlinked parent, as a device's /var is /private/var; links inside it and hidden or empty
    /// files are left out.
    func testFilesAreNamedByTheirPathInsideTheFolder() throws {
        let fm = FileManager.default
        let real = root.appendingPathComponent("real")
        let docs = root.appendingPathComponent("link/Documents")
        try fm.createDirectory(at: real.appendingPathComponent("Documents"), withIntermediateDirectories: true)
        try fm.createSymbolicLink(at: root.appendingPathComponent("link"), withDestinationURL: real)
        let outside = root.appendingPathComponent("outside")
        try write("elsewhere.txt", in: outside)
        try write("Notes.txt", in: docs)
        try write("Trips/2024/Résumé café.txt", in: docs)
        try write(".secret.txt", in: docs)
        try write("Empty.txt", in: docs, "")
        try fm.createSymbolicLink(at: docs.appendingPathComponent("Linked.txt"), withDestinationURL: outside.appendingPathComponent("elsewhere.txt"))
        try fm.createSymbolicLink(at: docs.appendingPathComponent("Linked folder"), withDestinationURL: outside)

        let source = FolderSource(documents: docs, defaults: try XCTUnwrap(UserDefaults(suiteName: UUID().uuidString)))
        let files = source.changes(since: nil, known: NoneKnown()).upserted.sorted { $0.uri < $1.uri }

        XCTAssertEqual(files.map(\.uri), ["folder://documents/Notes.txt", "folder://documents/Trips/2024/Résumé café.txt"])
        XCTAssertEqual(files.map(\.name), ["Notes.txt", "Résumé café.txt"])
        XCTAssertEqual(files.map(\.folder), ["Retriever", "Retriever/Trips/2024"])
        XCTAssertNotNil(source.resolve(files[1].uri).flatMap { try? Data(contentsOf: $0) })
    }
}

private final class NoneKnown: NSObject, KnownFiles {
    func file(uri: String) -> SourceFile? { nil }
}
