// Copyright 2026 The MediaPipe Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Adapted from google-ai-edge/mediapipe-samples examples/universal_embedder/ios/UniversalEmbedder/UniversalEmbedderHelper.swift (Apache-2.0). Modified for Retriever: implements RetrieverCore's NativeEmbedderOpener/NativeEmbedder, takes the model path, token window and cache dir from Kotlin, embeds mixed text+image content, returns Float32 bytes and reports errors as codes instead of throwing.

import Foundation
import MediaPipeTasksRetrieval
import RetrieverCore
import UIKit

final class MediaPipeOpener: NSObject, NativeEmbedderOpener {
    private(set) var lastMessage: String?

    func open(modelPath: String, gpu: Bool, visionTokens: Int32, maxTokens: Int32, cacheDir: String) -> NativeEmbedder? {
        let options = UniversalEmbedderOptions()
        options.baseOptions.modelAssetPath = modelPath
        // Delegates are per-modality: the text tower encodes strings, the vision tower images.
        let delegate: Delegate = gpu ? .GPU : .CPU
        options.textDelegate = delegate
        options.visionDelegate = delegate
        options.maxInputLength = Int(maxTokens)
        options.visionTokensPerImage = Int(visionTokens)
        try? FileManager.default.createDirectory(atPath: cacheDir, withIntermediateDirectories: true)
        options.cacheDirectory = cacheDir
        // Embeddings are unit length, so a dot product is the cosine similarity.
        options.l2Normalize = true
        do {
            lastMessage = nil
            return MediaPipeNativeEmbedder(try UniversalEmbedder(options: options))
        } catch {
            lastMessage = error.localizedDescription
            return nil
        }
    }
}

/// Not thread safe: the Kotlin wrapper serializes calls.
final class MediaPipeNativeEmbedder: NSObject, NativeEmbedder {
    private var embedder: UniversalEmbedder?
    private(set) var lastError: Int32 = 0
    private(set) var lastMessage: String?

    init(_ embedder: UniversalEmbedder) {
        self.embedder = embedder
    }

    func embed(parts: [Any]) -> Data? {
        lastError = 0
        lastMessage = nil
        guard let embedder else { return fail(2, "the model is closed") }
        do {
            let result: EmbeddingResult
            if parts.count == 1, let text = parts[0] as? String {
                result = try embedder.embed(text: text)
            } else if parts.count == 1, let jpeg = parts[0] as? Data {
                result = try embedder.embed(imageBytes: jpeg)
            } else {
                let content: [Any] = try parts.map { part in
                    if let jpeg = part as? Data {
                        guard let image = UIImage(data: jpeg) else { throw EmbedError.badImage }
                        return try MPImage(uiImage: image)
                    }
                    return part
                }
                result = try embedder.embed(content: content)
            }
            guard let floats = result.embeddings.first?.floatEmbedding, !floats.isEmpty else {
                return fail(2, "the model returned no embedding")
            }
            return floats.map { $0.floatValue }.withUnsafeBufferPointer { Data(buffer: $0) }
        } catch {
            let message = error.localizedDescription
            // The same LiteRT-LM message Android matches in MediaPipeEmbedder.isInputTooLong.
            let tooLong = message.range(of: "exceeds maximum supported", options: .caseInsensitive) != nil
            return fail(tooLong ? 1 : 2, message)
        }
    }

    func close() {
        embedder = nil
    }

    private func fail(_ code: Int32, _ message: String) -> Data? {
        lastError = code
        lastMessage = message
        return nil
    }

    private enum EmbedError: LocalizedError {
        case badImage
        var errorDescription: String? { "the image could not be decoded" }
    }
}
