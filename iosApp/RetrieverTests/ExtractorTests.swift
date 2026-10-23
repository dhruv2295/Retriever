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

        let scan = try XCTUnwrap(extractor().read(turned, labels: false, jpegSide: 512))

        XCTAssertTrue(scan.text.contains("48213"), scan.text)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(scan.jpeg as CFData, nil))
        let pixels = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(pixels.width, 512)
        XCTAssertEqual(pixels.height, 110)
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        XCTAssertEqual(properties?[kCGImagePropertyOrientation] as? Int ?? 1, 1)
    }
}
