import CoreML
import RetrieverCore
import Vision
import os

/// People on iOS, for core's IosFaceReader (README.md): Vision finds each face and its landmarks, core's
/// FaceAlign turns and scales it onto SFace's template, and SFace (Faces/SFace.mlpackage) gives its fingerprint. The
/// model loads on the first photo, so nothing loads while People is off. The same limits as Android's reader, so both
/// group the same faces.
final class FaceFinder: NSObject, NativeFaceReader, @unchecked Sendable {
    private static let log = Logger(subsystem: "app.retriever", category: "faces")
    private let extractor: FileExtractor
    /// Guards `model`, which predicts one face at a time.
    private let lock = NSLock()
    private var model: MLModel?

    init(extractor: FileExtractor) {
        self.extractor = extractor
    }

    func faces(uri: String) -> [NativeFace]? {
        autoreleasepool {
            guard let photo = extractor.uprightImage(uri: uri, maxSide: Self.maxSide) else { return nil }
            // Fails on the simulator ("Could not create inference context"); pinned to its CPU it gives collapsed landmarks.
            let request = VNDetectFaceLandmarksRequest()
            do {
                try VNImageRequestHandler(cgImage: photo).perform([request])
            } catch {
                Self.log.error("Face detection failed: \(error, privacy: .public)")
                return nil
            }
            let size = CGSize(width: photo.width, height: photo.height)
            return (request.results ?? []).compactMap { face in
                guard let points = Self.points(face, in: size), let vector = fingerprint(photo, points) else { return nil }
                // Vision's box is a fraction of the photo with y up; core's has y down.
                let b = face.boundingBox
                func f(_ v: CGFloat) -> Float { Float(min(max(v, 0), 1)) }
                return NativeFace(left: f(b.minX), top: f(1 - b.maxY), right: f(b.maxX), bottom: f(1 - b.minY), vector: vector)
            }
        }
    }

    /// The five points in FaceAlign.POINTS' order, in photo pixels with y down. Slots go by place in the image, not
    /// Vision's left and right (the person's own); the nose is its region's lowest point, the base, as ML Kit gives it.
    /// Nil for a face turned too far, or too small to know again.
    static func points(_ face: VNFaceObservation, in size: CGSize) -> [Float]? {
        if let yaw = face.yaw?.doubleValue, abs(yaw) > maxTurn { return nil }
        guard let marks = face.landmarks, let leftEye = marks.leftPupil ?? marks.leftEye, let rightEye = marks.rightPupil ?? marks.rightEye,
            let nose = marks.nose, let lips = marks.outerLips
        else { return nil }
        func pixels(_ region: VNFaceLandmarkRegion2D) -> [CGPoint] {
            region.pointsInImage(imageSize: size).map { CGPoint(x: $0.x, y: size.height - $0.y) }
        }
        func mean(_ p: [CGPoint]) -> CGPoint? {
            p.isEmpty ? nil : CGPoint(x: p.map(\.x).reduce(0, +) / CGFloat(p.count), y: p.map(\.y).reduce(0, +) / CGFloat(p.count))
        }
        let mouth = pixels(lips)
        guard let a = mean(pixels(leftEye)), let b = mean(pixels(rightEye)), let base = pixels(nose).max(by: { $0.y < $1.y }),
            let mouthLeft = mouth.min(by: { $0.x < $1.x }), let mouthRight = mouth.max(by: { $0.x < $1.x })
        else { return nil }
        let eyes = [a, b].sorted { $0.x < $1.x }
        guard hypot(eyes[1].x - eyes[0].x, eyes[1].y - eyes[0].y) >= minEyes else { return nil }
        return [eyes[0], eyes[1], base, mouthLeft, mouthRight].flatMap { [Float($0.x), Float($0.y)] }
    }

    /// SFace's fingerprint, as Float32 bytes, of the face at `points` in `photo`.
    func fingerprint(_ photo: CGImage, _ points: [Float]) -> Data? {
        let input = KotlinFloatArray(size: Int32(points.count))
        for (i, v) in points.enumerated() { input.set(index: Int32(i), value: v) }
        guard let t = FaceAlign.shared.transform(points: input), let pixels = Self.aligned(photo, t) else { return nil }
        let n = Int(FaceAlign.shared.SIZE)
        guard let data = try? MLMultiArray(shape: [1, NSNumber(value: n), NSNumber(value: n), 3], dataType: .float32) else { return nil }
        // RGB as 0..255 floats, row by row (NHWC); the model scales them itself.
        let out = data.dataPointer.bindMemory(to: Float32.self, capacity: n * n * 3)
        for p in 0..<(n * n) {
            for c in 0..<3 { out[p * 3 + c] = Float32(pixels[p * 4 + c]) }
        }
        let result: MLFeatureProvider? = lock.withLock {
            guard let model = loadedModel(), let features = try? MLDictionaryFeatureProvider(dictionary: ["data": data]) else { return nil }
            do {
                return try model.prediction(from: features)
            } catch {
                Self.log.error("SFace failed: \(error, privacy: .public)")
                return nil
            }
        }
        guard let vector = result?.featureValue(for: "fc1")?.multiArrayValue, vector.count == Int(FacesKt.FACE_DIMS) else { return nil }
        var floats = (0..<vector.count).map { Float32(truncating: vector[$0]) }
        return Data(bytes: &floats, count: floats.count * MemoryLayout<Float32>.size)
    }

    /// The 112 px RGBA crop core's transform (x' = a·x − b·y + tx, y' = b·x + a·y + ty, both y down) makes of `photo`.
    private static func aligned(_ photo: CGImage, _ t: KotlinFloatArray) -> [UInt8]? {
        let n = Int(FaceAlign.shared.SIZE)
        let (a, b, tx, ty) = (CGFloat(t.get(index: 0)), CGFloat(t.get(index: 1)), CGFloat(t.get(index: 2)), CGFloat(t.get(index: 3)))
        let h = CGFloat(photo.height)
        var pixels = [UInt8](repeating: 0, count: n * n * 4)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard
                let ctx = CGContext(
                    data: buffer.baseAddress, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4,
                    space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            // Core Graphics draws with y up; this is core's transform with both sides flipped.
            ctx.concatenate(CGAffineTransform(a: a, b: -b, c: b, d: a, tx: tx - b * h, ty: CGFloat(n) - a * h - ty))
            ctx.interpolationQuality = .high
            ctx.draw(photo, in: CGRect(x: 0, y: 0, width: CGFloat(photo.width), height: h))
            return true
        }
        return drawn ? pixels : nil
    }

    private func loadedModel() -> MLModel? {
        guard model == nil else { return model }
        let config = MLModelConfiguration()
        #if targetEnvironment(simulator)
            config.computeUnits = .cpuOnly
        #endif
        do {
            guard let url = Bundle.main.url(forResource: "SFace", withExtension: "mlmodelc") else { throw CocoaError(.fileNoSuchFile) }
            model = try MLModel(contentsOf: url, configuration: config)
        } catch {
            Self.log.error("SFace didn't load: \(error, privacy: .public)")
        }
        return model
    }

    /// The photo's longer side as decoded: a face 5% of it wide is still ~60 px.
    private static let maxSide: Int32 = 1280
    /// Eyes closer than this in the decoded photo give SFace too little to go on.
    private static let minEyes: CGFloat = 20
    /// Radians: SFace knows faces seen from the front, not in profile.
    private static let maxTurn = Double.pi / 4
}
