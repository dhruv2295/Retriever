import CryptoKit
@preconcurrency import RetrieverCore
import XCTest

@testable import Retriever

/// The download's own checks, with no network: a whole file left by a relaunch is checked under a background task and
/// marked ready, and a download with no room for it never starts.
@MainActor
final class ModelDownloadTests: XCTestCase {
    private func temporaryDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return dir
    }

    func testAWholeFileIsCheckedUnderABackgroundTaskAndMarkedReady() async throws {
        let dir = temporaryDir()
        let download = ModelDownload(dir: dir)
        let bytes = Data("the model".utf8)
        try bytes.write(to: dir.appendingPathComponent("model.bin"))
        let sha = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let build = ModelBuild(variant: .m440, fileName: "model.bin", sizeBytes: Int64(bytes.count), sha256: sha, soc: nil)
        XCTAssertFalse(RetrieverIosKt.modelReady(modelDir: dir.path, build: build), "no marker yet")
        download.start(build, allowCellular: false)
        let ready = await eventually { download.state == .ready }
        XCTAssertTrue(ready)
        XCTAssertTrue(RetrieverIosKt.modelReady(modelDir: dir.path, build: build), "the check wrote its marker")
        XCTAssertEqual(download.checkTask, .invalid, "the background task ends with the check")
    }

    func testNoRoomForTheDownloadNeverStartsIt() async {
        let download = ModelDownload(dir: temporaryDir())
        let build = ModelBuild(variant: .m440, fileName: "huge.bin", sizeBytes: 1 << 60, sha256: "", soc: nil)
        download.start(build, allowCellular: false)
        let refused = await eventually { if case .noSpace = download.state { true } else { false } }
        XCTAssertTrue(refused)
        XCTAssertEqual(AppModel.phase(download.state), .noSpace)
    }
}
