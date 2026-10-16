package app.rummage.core.android

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import app.rummage.core.AUDIO_SAMPLE_RATE
import app.rummage.core.Backend
import app.rummage.core.Embedder
import app.rummage.core.MAX_INPUT_TOKENS
import app.rummage.core.EmbedderClosedException
import app.rummage.core.EmbedderFactory
import app.rummage.core.ModelVariant
import app.rummage.core.Part
import app.rummage.core.Signature
import com.google.ai.edge.litertlm.ModelInfo
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import com.google.mediapipe.tasks.components.containers.EmbeddingResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MediaPipeEmbedderFactory(
    private val context: Context,
    private val modelDir: File,
) : EmbedderFactory {
    // No NPU: the model has no Tensor G3 build.
    override fun backends() = listOf(Backend.GPU, Backend.CPU)

    override fun open(model: ModelVariant, backend: Backend, visionTokens: Int): Embedder {
        val file = File(modelDir, model.fileName)
        check(file.exists()) { "Model file missing: ${file.absolutePath}" }
        return MediaPipeEmbedder(context, file, model, backend, visionTokens)
    }
}

// Recovery, model-limit reading and close adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/OnDeviceEmbedder.kt (Apache-2.0). Modified for Rummage: coroutine Mutex instead of synchronized, throws instead of returning null, close waits for an in-flight embed.
private class MediaPipeEmbedder(
    context: Context,
    private val modelFile: File,
    private val model: ModelVariant,
    override val backend: Backend,
    requestedVisionTokens: Int,
) : Embedder {
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
        maxTokens = maxContext.takeIf { it > 0 }?.coerceAtMost(MAX_INPUT_TOKENS) ?: MAX_INPUT_TOKENS
        visionTokens = if (maxVision > 0) minOf(requestedVisionTokens, maxVision) else requestedVisionTokens
        modelHasAudio = audio
        if (model.audio && !audio) Log.w(TAG, "${model.fileName} reports no audio input; audio parts will fail")
    }

    override val signature = Signature.of(model, visionTokens)

    /** Set when part of the model had to run somewhere other than [backend]. */
    override var detail: String? = null
        private set

    // Own cache dir per configuration, or different vision-token configs collide on the GPU program cache.
    private val isolated = object : ContextWrapper(context) {
        override fun getCacheDir(): File =
            File(super.getCacheDir(), "cache_${model.name}_${backend.name}_vt$visionTokens").apply { mkdirs() }
    }

    private val mutex = Mutex()
    private var cpuFallback = delegate == Delegate.CPU
    private var closed = false

    private var embedder: UniversalEmbedder = try {
        build(delegate, delegate)
    } catch (e: Exception) {
        // Some GPUs (Tensor G3's Mali) can't compile the vision encoder; keep text on the GPU.
        if (delegate != Delegate.GPU || !model.vision) throw e
        detail = "vision encoder on CPU (GPU failed: ${e.message?.lineSequence()?.last()?.trim()})"
        build(delegate, Delegate.CPU)
    }

    private fun build(textDelegate: Delegate, visionDelegate: Delegate): UniversalEmbedder {
        val base = BaseOptions.builder()
            .setModelAssetPath(modelFile.absolutePath)
            .setDelegate(textDelegate)
            .build()
        val builder = UniversalEmbedderOptions.builder()
            .setBaseOptions(base)
            .setL2Normalize(true)
            .setMaxInputLength(maxTokens)
            .setTextDelegate(textDelegate)
        // A text-only model has no vision or audio encoder; configuring one makes loading fail.
        if (model.vision) builder.setVisionTokensPerImage(visionTokens).setVisionDelegate(visionDelegate)
        if (model.audio && modelHasAudio) builder.setAudioDelegate(Delegate.CPU) // Gallery forces audio onto CPU for accuracy
        return UniversalEmbedder.createFromOptions(isolated, builder.build())
    }

    override suspend fun embedRaw(parts: List<Part>): FloatArray = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) throw EmbedderClosedException()
            val result = try {
                infer(parts)
            } catch (e: Exception) {
                if (cpuFallback) throw IllegalStateException("Embedding failed on CPU", e)
                Log.w(TAG, "embedding failed on ${backend.name}; rebuilding on CPU", e)
                runCatching { embedder.close() }.onFailure { Log.w(TAG, "closing failed embedder", it) }
                cpuFallback = true
                embedder = try {
                    build(Delegate.CPU, Delegate.CPU)
                } catch (rebuild: Exception) {
                    // The old embedder is already closed; nothing usable is left.
                    closed = true
                    throw EmbedderClosedException("CPU rebuild failed after GPU inference failed", rebuild)
                }
                detail = "fell back to CPU after GPU inference failed"
                infer(parts)
            }
            result.embeddings().firstOrNull()?.floatEmbedding()
                ?: throw IllegalStateException("MediaPipe returned no embedding")
        }
    }

    private fun infer(parts: List<Part>): EmbeddingResult =
        if (parts.size == 1 && parts[0] is Part.Text) {
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
        const val TAG = "Rummage"
    }
}
