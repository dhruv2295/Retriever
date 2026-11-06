import RetrieverCore
import UIKit
import XCTest

@testable import Retriever

/// Person.jpg is a generated picture of no real person (README.md). Vision's face landmarks don't run on the
/// simulator, so the tests that detect run on a device; the alignment and SFace run everywhere.
final class FaceFinderTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: folder)
    }

    private func finder() -> FaceFinder {
        FaceFinder(
            extractor: FileExtractor(
                folders: FolderSource(documents: folder, defaults: UserDefaults(suiteName: UUID().uuidString)!), allowMobileData: { false }))
    }

    private func person() throws -> UIImage {
        try XCTUnwrap(UIImage(contentsOfFile: URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Person.jpg").path))
    }

    private func save(_ image: UIImage, _ name: String) throws -> String {
        try XCTUnwrap(image.jpegData(compressionQuality: 0.9)).write(to: folder.appendingPathComponent(name))
        return "folder://documents/\(name)"
    }

    private func floats(_ data: Data) -> [Float] {
        data.withUnsafeBytes { Array($0.bindMemory(to: Float32.self)) }
    }

    private func skipOnSimulator() throws {
        #if targetEnvironment(simulator)
            throw XCTSkip("Vision's face landmarks need a device")
        #endif
    }

    /// Core's alignment and SFace give a fingerprint that knows the face mirrored, from the landmarks placed by hand
    /// on Person.jpg (512 × 768): eyes, nose base, mouth corners.
    func testAlignedFaceIsKnownAgainMirrored() throws {
        let photo = try XCTUnwrap(try person().cgImage)
        let points: [Float] = [200, 309, 306, 309, 255, 380, 220, 422, 292, 422]
        let flipped = UIGraphicsImageRenderer(
            size: CGSize(width: 512, height: 768),
            format: {
                let f = UIGraphicsImageRendererFormat()
                f.scale = 1
                return f
            }()
        )
        .image { ctx in
            ctx.cgContext.translateBy(x: 512, y: 0)
            ctx.cgContext.scaleBy(x: -1, y: 1)
            UIImage(cgImage: photo).draw(at: .zero)
        }
        let mirrorPoints: [Float] = [206, 309, 312, 309, 257, 380, 220, 422, 292, 422]

        let a = floats(try XCTUnwrap(finder().fingerprint(photo, points)))
        let b = floats(try XCTUnwrap(finder().fingerprint(try XCTUnwrap(flipped.cgImage), mirrorPoints)))

        // Raw: core makes each fingerprint unit length when it stores it.
        func length(_ v: [Float]) -> Float { sqrt(v.map { $0 * $0 }.reduce(0, +)) }
        XCTAssertEqual(a.count, Int(FacesKt.FACE_DIMS))
        XCTAssertGreaterThanOrEqual(zip(a, b).map(*).reduce(0, +) / (length(a) * length(b)), FacesKt.SAME_PERSON)
    }

    /// Vision finds the one face, and its box is a fraction of the photo, y down, around the middle of this portrait.
    func testAFaceIsFound() throws {
        try skipOnSimulator()
        let faces = try XCTUnwrap(finder().faces(uri: try save(try person(), "Person.jpg")))

        XCTAssertEqual(faces.count, 1)
        let face = try XCTUnwrap(faces.first)
        XCTAssertEqual(face.vector.count, Int(FacesKt.FACE_DIMS) * 4)
        XCTAssertLessThan(face.left, 0.5)
        XCTAssertGreaterThan(face.right, 0.5)
        XCTAssertLessThan(face.top, face.bottom)
    }

    /// A photo that can't be opened is nil, to be tried again; looked at and found none is an empty list.
    func testNoPhotoIsNilAndNoFaceIsEmpty() throws {
        XCTAssertNil(finder().faces(uri: "folder://documents/Missing.jpg"))

        try skipOnSimulator()
        let blank = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 300)).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 400, height: 300))
        }
        XCTAssertEqual(finder().faces(uri: try save(blank, "Blank.jpg"))?.count, 0)
    }
}
