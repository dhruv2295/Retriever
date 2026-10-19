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

package app.retriever.core.android

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import app.retriever.core.AUDIO_SAMPLE_RATE
import app.retriever.core.Backend
import app.retriever.core.DeviceProfile
import app.retriever.core.Embedder
import app.retriever.core.EmbedderClosedException
import app.retriever.core.EmbedderFactory
import app.retriever.core.InputTooLongException
import app.retriever.core.Modality
import app.retriever.core.ModelBuild
import app.retriever.core.ModelVariant
import app.retriever.core.Part
import app.retriever.core.Prefix
import app.retriever.core.Signature
import app.retriever.core.hasNonFinite
import app.retriever.core.lowerNewThreads
import app.retriever.core.probeKindFor
import app.retriever.core.tracedAsync
import com.google.ai.edge.litertlm.ModelInfo
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.components.containers.EmbeddingResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.BaseOptions.DelegateOptions.NpuOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File

// LiteRT-LM reports it as INVALID_ARGUMENT "Input sequence length (N) exceeds maximum supported signature length".
private fun Throwable.isInputTooLong(): Boolean =
    generateSequence(this) { it.cause }.any { it.message?.contains("exceeds maximum supported signature length") == true }

class MediaPipeEmbedderFactory(private val context: Context, private val modelDir: File, private val visionOverride: () -> Backend? = { null }) :
    EmbedderFactory {
    // NPU only for SoC-specific builds; the Pixel 8 Pro (Tensor G3) has none.
    override fun backends(build: ModelBuild) = if (build.isNpu) listOf(Backend.NPU) else listOf(Backend.GPU, Backend.CPU)

    override fun profileNow(profile: DeviceProfile, visible: Boolean): DeviceProfile {
        val forced = visionOverride()
        return if (forced != null && profile.backend != Backend.NPU) {
            profile.copy(backend = forced, visionBackend = forced)
        } else {
            super.profileNow(profile, visible)
        }
    }

    override fun open(profile: DeviceProfile, build: ModelBuild): Embedder {
        require(build.variant == profile.model) { "build ${build.variant} for profile ${profile.model}" }
        val file = File(modelDir, build.fileName)
        check(file.exists()) { "Model file missing: ${file.absolutePath}" }
        return lowerNewThreads {
            MediaPipeEmbedder(context, file, build, profile.backend, profile.visionTokens, profile.maxTokens, profile.visionBackend ?: profile.backend)
        }
    }

    // Keeps every configuration's cache for models still on disk: a re-tune (vt70 to vt140 on the Pixel) used to
    // wipe the other dir, so switching back rebuilt ~95 MB of XNNPack weights and the GPU program cache.
    override fun keepOnly(inUse: Embedder) {
        if (inUse !is MediaPipeEmbedder) return
        val root = File(context.cacheDir, "embed")
        val dirs = root.listFiles()?.associate { d -> d.name to (d.list()?.toList() ?: emptyList()) } ?: return
        val models = modelDir.listFiles()?.filter { it.isFile }?.associate { it.name to cacheKey(it) } ?: return
        staleEmbedCaches(dirs, models).forEach { File(root, it).deleteRecursively() }
    }
}

// The runtime names its cache files "<model file>_<mtime s>_<size>.<part>…", so a re-copied model orphans the old set.
private fun cacheKey(model: File) = "${model.lastModified() / 1000}_${model.length()}"

/**
 * Relative paths under the embed cache root to delete. [dirs] maps each config dir to its entries; [models] maps
 * each model file on disk to its [cacheKey]. A dir is kept while its model exists; inside it, files keyed by an
 * older copy of the model are stale.
 */
// ponytail: keeps every config of a present model (at most a few); prune by last use if configs multiply.
fun staleEmbedCaches(dirs: Map<String, List<String>>, models: Map<String, String>): List<String> = dirs.flatMap { (dir, entries) ->
    val model = models.keys.firstOrNull { dir.startsWith(it.substringBeforeLast('.') + "-") }
        ?: return@flatMap listOf(dir)
    val current = "${model}_${models.getValue(model)}"
    entries.filter { it.startsWith("${model}_") && !it.startsWith("$current.") }.map { "$dir/$it" }
}

// Recovery, model-limit reading, NPU options, per-configuration cache and close adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/OnDeviceEmbedder.kt (Apache-2.0); split vision delegate from GemmaEmbeddingModelStore.kt in the same directory. Modified for Retriever: coroutine Mutex instead of synchronized, throws instead of returning null, close waits for an in-flight embed, cache dir passed through setCacheDir, no in-place CPU rebuild (a probe input tells a bad input from a broken backend, and the core records the fallback).
private class MediaPipeEmbedder(
    private val context: Context,
    private val modelFile: File,
    private val modelBuild: ModelBuild,
    override val backend: Backend,
    requestedVisionTokens: Int,
    requestedMaxTokens: Int,
    requestedVisionBackend: Backend,
) : Embedder {
    private val model: ModelVariant = modelBuild.variant
    private val delegate = when (backend) {
        Backend.GPU -> Delegate.GPU
        Backend.CPU -> Delegate.CPU
        Backend.NPU -> Delegate.NPU
    }

    // The model ships fixed-length signatures and pads every input to the chosen one, so keep it short.
    private val maxTokens: Int
    private val visionTokens: Int
    private val modelHasAudio: Boolean

    init {
        val (maxContext, maxVision, audio) = ModelInfo.from(modelFile.absolutePath).use { info ->
            // Read from the bundle: audio-stripped builds of an audio-capable model exist.
            Triple(info.maxContextTokens(), info.maxVisionTokenBudget(), info.inputModalities().audio)
        }
        maxTokens = maxContext.takeIf { it > 0 }?.coerceAtMost(requestedMaxTokens) ?: requestedMaxTokens
        visionTokens = if (maxVision > 0) minOf(requestedVisionTokens, maxVision) else requestedVisionTokens
        modelHasAudio = audio
        if (model.audio && !audio) Log.w(TAG, "${modelBuild.fileName} reports no audio input; audio parts will fail")
    }

    override val signature = Signature.of(modelBuild, visionTokens, maxTokens)

    // Own cache dir per configuration, or different token configs collide on the compiled program cache.
    val cache = File(
        context.cacheDir,
        "embed/${modelBuild.fileName.substringBeforeLast('.')}-${delegate.name}-vision${requestedVisionBackend.name}-vt$visionTokens-t$maxTokens",
    )

    /** Set when part of the model had to run somewhere other than [backend]. */
    override var detail: String? = null
        private set

    private val mutex = Mutex()
    private var closed = false
    private var warm = false

    override var visionBackend: Backend = requestedVisionBackend
        private set

    // Remembers a GPU vision compile failure in this configuration's cache dir, so later opens skip the failing compile.
    private val visionCpu = File(cache, "vision-cpu")

    private val embedder: UniversalEmbedder = try {
        val cpuVision = (requestedVisionBackend == Backend.CPU) || (delegate == Delegate.GPU && model.vision && visionCpu.exists())
        detail = when {
            delegate == Delegate.GPU && requestedVisionBackend == Backend.CPU -> "vision on CPU (rendering policy)"
            delegate == Delegate.GPU && cpuVision -> "vision encoder on CPU (GPU failed before)"
            else -> null
        }
        visionBackend = if (cpuVision) Backend.CPU else backend
        build(delegate, if (cpuVision) Delegate.CPU else delegate)
    } catch (e: Exception) {
        // A measured compile failure keeps text on the GPU and retires only this vision configuration.
        if (delegate != Delegate.GPU || !model.vision || detail != null) throw e
        cache.mkdirs()
        visionCpu.createNewFile()
        detail = "vision encoder on CPU (GPU failed: ${e.message?.lineSequence()?.last()?.trim()})"
        visionBackend = Backend.CPU
        build(delegate, Delegate.CPU)
    }

    private fun build(textDelegate: Delegate, visionDelegate: Delegate): UniversalEmbedder {
        val base = BaseOptions.builder()
            .setModelAssetPath(modelFile.absolutePath)
            .setDelegate(textDelegate)
        if (textDelegate == Delegate.NPU) {
            val libs = context.applicationInfo.nativeLibraryDir
            base.setDelegateOptions(
                NpuOptions.builder().setDispatchLibraryDirectory(libs).setCompilerPluginLibraryDirectory(libs).build(),
            )
        }
        cache.mkdirs()
        val builder = UniversalEmbedderOptions.builder()
            .setBaseOptions(base.build())
            .setCacheDir(cache.absolutePath)
            .setL2Normalize(true)
            .setMaxInputLength(maxTokens)
            .setTextDelegate(textDelegate)
        // A text-only model has no vision or audio encoder; configuring one makes loading fail.
        if (model.vision) builder.setVisionTokensPerImage(visionTokens).setVisionDelegate(visionDelegate)
        // Gallery forces audio onto CPU for accuracy. Leaving it unset when audio is off was tried: MediaPipe still
        // builds the audio encoder's 91 MB cache and native heap didn't drop (emulator, M740), so it isn't worth a reopen.
        if (model.audio && modelHasAudio) builder.setAudioDelegate(Delegate.CPU)
        return UniversalEmbedder.createFromOptions(context, builder.build())
    }

    override suspend fun embedRaw(parts: List<Part>): FloatArray = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (closed) throw EmbedderClosedException()
        try {
            // The runtime starts more pools on its first inference, so those threads are lowered too.
            vector(tracedAsync("embed.run") { if (warm) infer(parts) else lowerNewThreads { infer(parts) }.also { warm = true } })
        } catch (e: Exception) {
            // An over-long input fails the same way on every backend: report it so the caller splits the text.
            if (e.isInputTooLong()) throw InputTooLongException("input exceeds the model's token window", e)
            // One odd input (an undecodable image or audio buffer) fails only itself: the file fails, the
            // model stays. Only when a known-good input fails too is the backend broken; the caller then
            // falls back (NPU to the generic build, GPU to CPU) and records it, never silently.
            // A NaN output lands here too. The probe matches the failing modality: a broken vision or audio
            // encoder still passes a text probe.
            if (runCatching { vector(probe(probeKindFor(parts))) }.isSuccess) throw IllegalArgumentException("model rejected this input", e)
            closed = true
            runCatching { embedder.close() }.onFailure { Log.w(TAG, "closing failed embedder", it) }
            throw EmbedderClosedException("${backend.name} inference failed", e)
        }
    }.also { currentCoroutineContext().ensureActive() }

    private fun vector(result: EmbeddingResult): FloatArray {
        val v = result.embeddings().firstOrNull()?.floatEmbedding() ?: throw IllegalStateException("MediaPipe returned no embedding")
        check(!hasNonFinite(v)) { "non-finite embedding" }
        return v
    }

    private fun probe(kind: Modality): EmbeddingResult = when {
        kind == Modality.IMAGE -> embedder.embedContent(listOf(PROBE_JPEG))
        kind == Modality.AUDIO && modelHasAudio -> embedder.embedContent(listOf(content(Part.Audio(ShortArray(AUDIO_SAMPLE_RATE)))))
        else -> embedder.embedText(PROBE)
    }

    private fun infer(parts: List<Part>): EmbeddingResult = if (parts.size == 1 && parts[0] is Part.Text) {
        embedder.embedText((parts[0] as Part.Text).text)
    } else {
        embedder.embedContent(parts.map(::content))
    }

    private fun content(part: Part): Any = when (part) {
        is Part.Text -> part.text

        is Part.Image -> part.jpeg

        is Part.Audio -> {
            val format = AudioData.AudioDataFormat.builder()
                .setNumOfChannels(1)
                .setSampleRate(AUDIO_SAMPLE_RATE.toFloat())
                .build()
            AudioData.create(format, part.pcm.size).apply { load(part.pcm) }
        }
    }

    // Blocks until an in-flight embed finishes; call off the main thread.
    override fun close() {
        runBlocking {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    runCatching { embedder.close() }.onFailure { Log.w(TAG, "closing embedder", it) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Retriever"
        val PROBE = Prefix.query("warm up")
        val PROBE_JPEG: ByteArray by lazy {
            val bitmap = createBitmap(32, 32)
            ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
                bitmap.recycle()
            }.toByteArray()
        }
    }
}
