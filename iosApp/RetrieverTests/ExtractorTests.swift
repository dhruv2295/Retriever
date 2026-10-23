import ImageIO
import UIKit
import XCTest

@testable import Retriever

final class ExtractorTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: folder)
    }

    private func extractor() -> FileExtractor {
        FileExtractor(folders: FolderSource(documents: folder, defaults: UserDefaults(suiteName: UUID().uuidString)!), allowMobileData: { false })
    }

    /// A photo PhotoKit hands over turned (its pixels stored sideways, with an orientation to show them by) is read as
    /// shown: Vision finds the words, and the model's JPEG is upright in its pixels, needing no orientation tag.
    func testATurnedPhotoIsReadAsShown() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: 1400, height: 300)
        let shown = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            ("Invoice 48213 total 1250" as NSString).draw(
                at: CGPoint(x: 60, y: 110), withAttributes: [.font: UIFont.systemFont(ofSize: 72), .foregroundColor: UIColor.black])
        }
        // Stored a quarter turn away, then tagged to be shown upright again.
        let stored = UIGraphicsImageRenderer(size: CGSize(width: 300, height: 1400), format: format).image { _ in
            UIImage(cgImage: shown.cgImage!, scale: 1, orientation: .left).draw(at: .zero)
        }
        let turned = UIImage(cgImage: try XCTUnwrap(stored.cgImage), scale: 1, orientation: .right)
        XCTAssertEqual(turned.size, size)

        // With labels, as a photo is read: the text survives a classifier that can't run (the simulator's can't).
        let scan = try XCTUnwrap(extractor().read(turned, labels: true, jpegSide: 512))

        XCTAssertTrue(scan.text.contains("48213"), scan.text)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(scan.jpeg as CFData, nil))
        let pixels = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(pixels.width, 512)
        XCTAssertEqual(pixels.height, 110)
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        XCTAssertEqual(properties?[kCGImagePropertyOrientation] as? Int ?? 1, 1)
    }

    /// Swift's callers of the blocking reads wait on GCD, not on Swift's cooperative threads (one per core): with
    /// three times as many reads stuck, other tasks still run.
    func testBlockingReadsLeaveSwiftsThreadsFree() async {
        let gate = DispatchSemaphore(value: 0)
        let stuck = 3 * ProcessInfo.processInfo.activeProcessorCount
        let reads = (0..<stuck).map { _ in
            Task.detached { await FileExtractor.blocking { gate.wait(timeout: .now() + 10) == .success } }
        }
        let clock = ContinuousClock()
        let start = clock.now
        let other = await Task.detached { 42 }.value
        XCTAssertEqual(other, 42)
        XCTAssertLessThan(clock.now - start, .seconds(2))
        for _ in 0..<stuck { gate.signal() }
        for read in reads {
            let finished = await read.value
            XCTAssertTrue(finished)
        }
    }
}
