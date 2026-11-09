/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/OnDeviceEmbedder.kt (Apache-2.0), by way of Retriever's MediaPipeEmbedder.kt. Modified for Retriever: the model runs in Swift behind NativeEmbedder; Kotlin keeps the mutex, the closed flag, the per-configuration cache dir and the known-good probe that tells a bad input from a broken backend.

@file:OptIn(ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.UIKit.UIGraphicsImageRenderer

class IosEmbedderFactory(
    private val opener: NativeEmbedderOpener,
    private val modelDir: String,
    private val cacheDir: String,
    private val isSimulator: Boolean,
    /** iOS granted the person's continued read the GPU in the background (iOS 26+, on devices that support it). */
    private val backgroundGpu: () -> Boolean = { false },
    /** The GPU refused background work after all: drop the grant, so the next batch opens on the CPU. */
    private val backgroundGpuRefused: () -> Unit = {},
    /** The app is in the foreground: iOS refuses GPU work from a backgrounded app without the grant. */
    private val isActive: () -> Boolean,
) : EmbedderFactory {
    // No NPU dispatch on iOS; the simulator has no Metal delegate for the model.
    override fun backends(build: ModelBuild): List<Backend> = if (isSimulator) listOf(Backend.CPU) else listOf(Backend.GPU, Backend.CPU)

    // A background task gets CPU: iOS refuses GPU work from a backgrounded app without the grant. The key differs, so
    // the foreground reopens on the GPU; the signature has no backend in it, so nothing is read again.
    override fun backendNow(profile: DeviceProfile): Backend =
        if (profile.backend == Backend.GPU && !isActive() && !backgroundGpu()) Backend.CPU else profile.backend

    // EmbedderFactory.open's @Throws carries over (the header still has `error:`); repeating it here fails the shared
    // iosMain metadata compile, which reads the two annotations as different filters (Kotlin 2.4.21).
    override fun open(profile: DeviceProfile, build: ModelBuild): Embedder {
        require(build.variant == profile.model) { "build ${build.variant} for profile ${profile.model}" }
        // Own cache dir per configuration, or different token configs collide on the compiled program cache.
        val vision = profile.visionBackend ?: profile.backend
        val stem = build.fileName.substringBeforeLast('.')
        val cache = "$cacheDir/$stem-${profile.backend.name}-vision${vision.name}-vt${profile.visionTokens}-t${profile.maxTokens}"
        val files = NSFileManager.defaultManager
        files.createDirectoryAtPath(cache, true, null, null)
        val free = (files.attributesOfFileSystemForPath(cache, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L
        val gpu = profile.backend == Backend.GPU
        val native = opener.open(
            "$modelDir/${build.fileName}",
            gpu,
            vision == Backend.GPU,
            profile.visionTokens,
            profile.maxTokens,
            cacheDirFor(build, cache, free),
        )
        if (native == null) {
            val message = opener.lastMessage ?: "the model didn't open"
            // Too little memory now, or a GPU open in the background (which ends a background GPU grant): no reason to
            // give up the GPU.
            val forNow = (opener as? RefusesForNow)?.refusedForNow == true
            if (forNow || (gpu && !isActive())) {
                if (!forNow && gpu) backgroundGpuRefused()
                throw EmbedderClosedException(message, broken = false)
            }
            throw IllegalStateException(message)
        }
        return IosEmbedder(native, profile.backend, vision, Signature.of(build, profile.visionTokens, profile.maxTokens), isActive, backgroundGpuRefused)
    }
}

/** An opener that can refuse an open for now, e.g. for too little free memory, rather than failing it. */
// ponytail: a sub-interface so NativeEmbedderOpener and its test fakes stay as they are; fold it in when that file is next touched.
interface RefusesForNow : NativeEmbedderOpener {
    /** The last open returned null as a refusal for now, not a failure. */
    val refusedForNow: Boolean
}

/** Logic mirrors MediaPipeEmbedder.kt. */
private class IosEmbedder(
    private val native: NativeEmbedder,
    override val backend: Backend,
    override val visionBackend: Backend,
    override val signature: Signature,
    private val isActive: () -> Boolean,
    private val backgroundGpuRefused: () -> Unit,
) : Embedder {
    override val detail: String? get() = if (backend != visionBackend) "vision on CPU (rendering policy)" else null

    private val mutex = Mutex()
    private var closed = false

    override suspend fun embedRaw(parts: List<Part>): FloatArray {
        currentCoroutineContext().ensureActive()
        val input = parts.map {
            when (it) {
                is Part.Text -> it.text

                is Part.Image -> it.jpeg.toNSData()

                // Audio is unwired, not unsupported: SDK 1.1.1 exposes embed(audio:). Only image and text reach the model today.
                else -> throw IllegalArgumentException("no audio on iOS")
            }
        }
        return mutex.withLock {
            if (closed) throw EmbedderClosedException()
            // A NaN output is a failure like a null one.
            native.embed(input)?.toFloatArray()?.takeUnless(::hasNonFinite)?.let { return@withLock it }
            val message = native.lastMessage
            // An over-long input fails the same way on every backend: report it so the caller splits the text.
            if (native.lastError == INPUT_TOO_LONG) throw InputTooLongException(message ?: "input exceeds the model's token window")
            // In the background the probe would fail too, and the GPU would be demoted for good over a rule of iOS.
            if (backend == Backend.GPU && !isActive()) {
                backgroundGpuRefused()
                closed = true
                native.close()
                throw EmbedderClosedException("GPU unavailable in the background: $message", broken = false)
            }
            // One odd input fails only its file. Only when a known-good input fails too is the backend broken.
            // The probe matches the failing modality: a broken vision encoder still passes a text probe.
            val probe: Any = if (probeKindFor(parts) == Modality.IMAGE) PROBE_JPEG else PROBE
            if (native.embed(listOf(probe))?.toFloatArray()?.let(::hasNonFinite) ==
                false
            ) {
                throw IllegalArgumentException("model rejected this input", IllegalStateException(message))
            }
            closed = true
            native.close()
            throw EmbedderClosedException("${backend.name} inference failed: $message")
        }.also { currentCoroutineContext().ensureActive() }
    }

    // Blocks until an in-flight embed finishes; call off the main thread.
    override fun close() {
        runBlocking {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    native.close()
                }
            }
        }
    }

    private companion object {
        const val INPUT_TOO_LONG = 1
        val PROBE = Prefix.query("warm up")
        val PROBE_JPEG by lazy { UIGraphicsImageRenderer(CGSizeMake(32.0, 32.0)).JPEGDataWithCompressionQuality(0.8) {} }
    }
}
