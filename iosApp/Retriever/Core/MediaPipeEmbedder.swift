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
import os

final class MediaPipeOpener: NSObject, RefusesForNow {
    private(set) var lastMessage: String?
    /// The last open was refused for too little memory: it may open later, so the GPU is not to blame.
    private(set) var refusedForNow = false

    /// The model maps about 390 MB (M440); below this the system would sooner kill the app than let it load.
    private static let minAvailableBytes = 390 * 1024 * 1024
    private static let log = Logger(subsystem: "app.retriever", category: "model")

    func open(modelPath: String, gpu: Bool, visionGpu: Bool, visionTokens: Int32, maxTokens: Int32, cacheDir: String) -> NativeEmbedder? {
        let available = os_proc_available_memory()
        // 0 on the simulator and macOS, where there is no per-process limit to check.
        refusedForNow = available > 0 && available < Self.minAvailableBytes
        if refusedForNow {
            lastMessage = "not enough memory to open the model"
            Self.log.notice("model open refused: \(available / 1_048_576) MB available, \(Self.minAvailableBytes / 1_048_576) MB needed")
            return nil
        }
        let options = UniversalEmbedderOptions()
        options.baseOptions.modelAssetPath = modelPath
        // Delegates are per-modality: the text tower encodes strings, the vision tower images.
        let delegate: Delegate = gpu ? .GPU : .CPU
        options.textDelegate = delegate
        options.visionDelegate = visionGpu ? .GPU : .CPU
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

    /// One pool per call: the result holds an NSNumber per dimension, and Kotlin calls this in a loop.
    func embed(parts: [Any]) -> Data? {
        autoreleasepool {
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
                    // The JPEG's own bytes, as embed(imageBytes:) takes them: an MPImage would be decoded and re-encoded.
                    result = try embedder.embed(content: parts.map { part in (part as? Data).map { ImagePart(data: $0) } ?? part })
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
    }

    func close() {
        embedder = nil
    }

    private func fail(_ code: Int32, _ message: String) -> Data? {
        lastError = code
        lastMessage = message
        return nil
    }
}
