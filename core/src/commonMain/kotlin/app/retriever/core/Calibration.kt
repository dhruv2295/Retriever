package app.retriever.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlin.time.TimedValue
import kotlin.time.measureTimedValue

data class Samples(
    val images: List<ByteArray>,
    /** The person's own document text, if any; [Calibration] fills up to [TEXTS] from [FALLBACK_TEXTS]. */
    val texts: List<String>,
    val audio: List<ShortArray>,
    /** Short search queries: their latency is what a person feels while typing. */
    val queries: List<String> = listOf(
        "electricity bill", "beach sunset", "rent agreement deposit", "error dialog screenshot",
        "flight tickets", "doctor prescription", "meeting notes", "invoice", "passport photo", "recipe",
    ),
) {
    companion object {
        const val TEXTS = 10

        /** Stand-in documents, so both platforms tune on the same text whatever is on the phone. */
        val FALLBACK_TEXTS = listOf(
            "Lease agreement with a two month security deposit",
            "Flight booking confirmation for Bengaluru to Delhi",
            "Notes from the team meeting about the launch date",
            "Recipe for masala chai with ginger and cardamom",
            "Electricity bill for September",
            "Screenshot of an error dialog in the banking app",
            "Doctor's prescription for antibiotics",
            "Workout plan for the week",
            "Invoice 48213 for office chairs",
            "Photos from the beach trip at sunset",
        )
    }
}

data class BackendResult(
    val backend: Backend,
    val ok: Boolean,
    val error: String?,
    val loadMs: Double,
    val imageMs: Double?,
    val textMs: Double?,
    val audioMs: Double?,
    val minCosineVsCpu: Float?,
    val meanCosineVsCpu: Float?,
    val detail: String? = null,
    val queryMs: Double? = null,
    val maxFrameGapMs: Double = 0.0,
    val renderingStalls: Boolean = false,
)

data class CalibrationResult(val backends: List<BackendResult>, val chosen: Backend, val profile: DeviceProfile)

class Calibration(
    private val factory: EmbedderFactory,
    private val clock: TimeSource = TimeSource.Monotonic,
    private val frames: FrameSource? = null,
    private val visible: () -> Boolean = { true },
) {
    private class Measured(val result: BackendResult, val raws: List<FloatArray>?, val totalMs: Double)

    private class TooSlow : Exception("much slower than CPU")

    suspend fun run(samples: Samples, build: ModelBuild, ramGb: Double, maxTokens: Int = 512): CalibrationResult {
        val variant = build.variant
        // Order matters: images, then texts, then audio. Cosines compare outputs one-to-one by index.
        val images = samples.images.map { listOf<Part>(Part.Image(it)) }
        val texts = (samples.texts + Samples.FALLBACK_TEXTS).take(Samples.TEXTS)
            .map { listOf<Part>(Part.Text(Prefix.document("none", it))) }
        val audio = if (variant.audio) samples.audio.map { listOf<Part>(Part.Audio(it)) } else emptyList()
        val queries = samples.queries.map { listOf<Part>(Part.Text(Prefix.query(it))) }

        // Always the reference 70 vision tokens: the tier thresholds assume them, whatever a phone indexes at.
        fun profile(backend: Backend) = DeviceProfile(Tier.BALANCED, variant, backend, REFERENCE_VISION_TOKENS, 0, variant.audio, false, maxTokens)

        val backends = factory.backends(build)
        val results = mutableListOf<BackendResult>()
        var cpuRaws: List<FloatArray>? = null
        var budgetMs: Double? = null
        if (Backend.CPU in backends) {
            val cpu = measure(profile(Backend.CPU), build, images, texts, audio, queries, null, null)
            results += cpu.result
            cpuRaws = cpu.raws.takeIf { cpu.result.ok }
            // A backend that can't beat CPU is never picked, so stop measuring it once it is far behind
            // (an emulator's GPU falls back to WebGPU and takes ten minutes).
            if (cpu.result.ok) budgetMs = maxOf(SLOW_FACTOR * cpu.totalMs, MIN_BUDGET_MS)
        }
        for (backend in backends.filter { it != Backend.CPU }) {
            // Without a CPU result (an NPU build, or CPU failed) there is no reference: ok just means no NaN.
            results += measure(profile(backend), build, images, texts, audio, queries, cpuRaws, budgetMs).result
        }

        // Photo time sets the tier, but query time is what a person feels while typing, so both count.
        val pick = results.filter { it.ok }.minByOrNull { (it.imageMs ?: 0.0) + (it.queryMs ?: 0.0) }
            ?: throw IllegalStateException(
                "no backend passed: " + results.joinToString("; ") { "${it.backend}: ${it.error}" },
            )
        val chosen = pick.backend
        val gpu = results.firstOrNull { it.backend == Backend.GPU }
        val profile = Tiers.profileFor(pick.imageMs ?: 9999.0, ramGb, chosen, variant, maxTokens).copy(
            gpuStallsRendering = gpu?.renderingStalls == true,
            cpuImageMs = results.firstOrNull { it.backend == Backend.CPU && it.ok }?.imageMs,
            gpuImageMs = gpu?.takeIf { it.ok }?.imageMs,
        )
        return CalibrationResult(results, chosen, profile)
    }

    private suspend fun measure(
        profile: DeviceProfile,
        build: ModelBuild,
        images: List<List<Part>>,
        texts: List<List<Part>>,
        audio: List<List<Part>>,
        queries: List<List<Part>>,
        cpuRaws: List<FloatArray>?,
        budgetMs: Double?,
    ): Measured {
        val backend = profile.backend
        // From after the open: a GPU's first open compiles its kernels, which says nothing about its speed after.
        var start = clock.markNow()
        fun elapsedMs() = start.elapsedNow().inWholeNanoseconds / 1e6
        fun checkBudget() {
            if (budgetMs != null && elapsedMs() > budgetMs) throw TooSlow()
        }
        var loadMs = 0.0
        val rendering = frames?.takeIf { backend == Backend.GPU }?.let { RenderingStalls(it, visible) }
        try {
            val opened = clock.measureTimedValue { factory.open(profile, build) }
            loadMs = opened.duration.inWholeNanoseconds / 1e6
            val embedder = opened.value
            start = clock.markNow()
            try {
                suspend fun timedEmbed(parts: List<Part>): TimedValue<FloatArray> {
                    val usesGpu = parts.any {
                        (it is Part.Image && embedder.visionBackend == Backend.GPU) ||
                            (it is Part.Text && embedder.backend == Backend.GPU)
                    }
                    return if (usesGpu && rendering != null) {
                        rendering.measure { clock.measureTimedValue { embedder.embedRaw(parts) } }
                    } else {
                        clock.measureTimedValue { embedder.embedRaw(parts) }
                    }
                }
                // A cold model pages in weights and builds caches on its first calls of each kind; time none of them.
                for (kind in listOf(images, texts, audio, queries)) {
                    kind.firstOrNull()?.let { parts ->
                        repeat(WARM_UPS) {
                            timedEmbed(parts)
                            checkBudget()
                        }
                    }
                }
                val all = images + texts + audio + queries
                val raws = mutableListOf<FloatArray>()
                val times = all.mapIndexed { i, parts ->
                    checkBudget()
                    val timed = try {
                        timedEmbed(parts)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val which = when {
                            i < images.size -> "image #${i + 1}"
                            i < images.size + texts.size -> "text #${i - images.size + 1}"
                            i < images.size + texts.size + audio.size -> "audio #${i - images.size - texts.size + 1}"
                            else -> "query #${i - images.size - texts.size - audio.size + 1}"
                        }
                        throw IllegalStateException("$which: ${e.message}", e)
                    }
                    raws += timed.value
                    timed.duration.inWholeNanoseconds / 1e6
                }
                val noNaN = raws.none { r -> r.any { it.isNaN() } }

                var min: Float? = null
                var mean: Float? = null
                if (cpuRaws != null && cpuRaws.size == raws.size && raws.isNotEmpty()) {
                    val cos = raws.indices.map { cosine(raws[it], cpuRaws[it]) }
                    min = cos.min()
                    mean = cos.average().toFloat()
                }
                val agrees = cpuRaws == null || (mean != null && mean >= 0.98f)
                val ok = noNaN && agrees

                val ni = images.size
                val nt = texts.size
                val result = BackendResult(
                    backend = backend,
                    ok = ok,
                    error = if (ok) {
                        null
                    } else if (!noNaN) {
                        "NaN in output"
                    } else {
                        "mean cosine vs CPU below 0.98"
                    },
                    loadMs = loadMs,
                    imageMs = median(times.subList(0, ni)),
                    textMs = median(times.subList(ni, ni + nt)),
                    audioMs = median(times.subList(ni + nt, ni + nt + audio.size)),
                    minCosineVsCpu = min,
                    meanCosineVsCpu = mean,
                    detail = embedder.detail,
                    queryMs = median(times.subList(ni + nt + audio.size, times.size)),
                    maxFrameGapMs = rendering?.maxGapMs ?: 0.0,
                    renderingStalls = rendering?.stalled == true,
                )
                return Measured(result, raws, elapsedMs())
            } finally {
                embedder.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val result = BackendResult(
                backend, false, e.message ?: e::class.simpleName, loadMs, null, null, null, null, null,
                maxFrameGapMs = rendering?.maxGapMs ?: 0.0,
                renderingStalls = rendering?.stalled == true,
            )
            return Measured(result, null, elapsedMs())
        }
    }

    private companion object {
        const val WARM_UPS = 2
        const val REFERENCE_VISION_TOKENS = 70
        const val SLOW_FACTOR = 3.0

        /** A GPU compiling its kernels on first open may take this long however fast the CPU was. */
        const val MIN_BUDGET_MS = 30_000.0
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}

object Tiers {
    /**
     * INFERRED, not measured: below this a phone gets names and text only, no model. Meant for 3 GB iPhones
     * (XR, SE 2) and 2-3 GB Android phones; the M440 is 388 MB on disk plus its runtime working set beside the
     * app and the OS. Re-measure on a device before moving it.
     */
    const val MIN_MODEL_RAM_GB = 4.0

    fun textOnly(ramGb: Double): Boolean = ramGb < MIN_MODEL_RAM_GB

    fun profileFor(imageMs: Double, ramGb: Double, backend: Backend, variant: ModelVariant, maxTokens: Int = 512): DeviceProfile = when {
        imageMs <= 150 && ramGb >= 8 ->
            DeviceProfile(Tier.FULL, variant, backend, 140, 2, variant.audio, false, maxTokens)

        imageMs <= 1200 && ramGb >= 6 ->
            DeviceProfile(Tier.BALANCED, variant, backend, 70, 5, variant.audio, false, maxTokens)

        // One keyframe per 30 s (still at most MAX_VIDEO_MOMENTS), so a slow phone reads videos without stalling.
        else -> DeviceProfile(Tier.LIGHT, variant, backend, 70, 30, false, true, maxTokens)
    }
}
