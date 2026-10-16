package app.retriever.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.measureTimedValue

data class Samples(
    val images: List<ByteArray>,
    val texts: List<String>,
    val audio: List<ShortArray>,
    /** Short search queries: their latency is what a person feels while typing. */
    val queries: List<String> = listOf(
        "electricity bill", "beach sunset", "rent agreement deposit", "error dialog screenshot",
        "flight tickets", "doctor prescription", "meeting notes", "invoice", "passport photo", "recipe",
    ),
)

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
)

data class CalibrationResult(val backends: List<BackendResult>, val chosen: Backend, val profile: DeviceProfile)

class Calibration(private val factory: EmbedderFactory) {
    private class Measured(val result: BackendResult, val raws: List<FloatArray>?)

    suspend fun run(
        samples: Samples,
        build: ModelBuild,
        ramGb: Double,
        visionTokens: Int = 70,
        maxTokens: Int = 512,
    ): CalibrationResult {
        val variant = build.variant
        // Order matters: images, then texts, then audio. Cosines compare outputs one-to-one by index.
        val images = samples.images.map { listOf<Part>(Part.Image(it)) }
        val texts = samples.texts.map { listOf<Part>(Part.Text(Prefix.document("none", it))) }
        val audio = if (variant.audio) samples.audio.map { listOf<Part>(Part.Audio(it)) } else emptyList()
        val queries = samples.queries.map { listOf<Part>(Part.Text(Prefix.query(it))) }

        fun profile(backend: Backend) =
            DeviceProfile(Tier.BALANCED, variant, backend, visionTokens, 0, variant.audio, false, maxTokens)

        // NPU builds can't run on CPU, so without CPU there is no reference: ok just means no NaN.
        val backends = factory.backends(build)
        val results = mutableListOf<BackendResult>()
        var cpuRaws: List<FloatArray>? = null
        if (Backend.CPU in backends) {
            val cpu = measure(profile(Backend.CPU), build, images, texts, audio, queries, null, false)
            results += cpu.result
            cpuRaws = cpu.raws
        }
        for (backend in backends.filter { it != Backend.CPU }) {
            results += measure(
                profile(backend), build, images, texts, audio, queries, cpuRaws, Backend.CPU in backends,
            ).result
        }

        val pick = results.filter { it.ok }.minByOrNull { it.imageMs ?: it.textMs ?: Double.MAX_VALUE }
            ?: throw IllegalStateException(
                "no backend passed: " + results.joinToString("; ") { "${it.backend}: ${it.error}" },
            )
        val chosen = pick.backend
        return CalibrationResult(results, chosen, Tiers.profileFor(pick.imageMs ?: 9999.0, ramGb, chosen, variant, maxTokens))
    }

    private suspend fun measure(
        profile: DeviceProfile,
        build: ModelBuild,
        images: List<List<Part>>,
        texts: List<List<Part>>,
        audio: List<List<Part>>,
        queries: List<List<Part>>,
        cpuRaws: List<FloatArray>?,
        needsAgreement: Boolean,
    ): Measured {
        val backend = profile.backend
        var loadMs = 0.0
        try {
            val opened = measureTimedValue { factory.open(profile, build) }
            loadMs = opened.duration.inWholeNanoseconds / 1e6
            val embedder = opened.value
            try {
                val all = images + texts + audio + queries
                all.firstOrNull()?.let { embedder.embedRaw(it) } // warm-up
                val raws = mutableListOf<FloatArray>()
                val times = all.mapIndexed { i, parts ->
                    val timed = try {
                        measureTimedValue { embedder.embedRaw(parts) }
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
                if (needsAgreement && cpuRaws != null && cpuRaws.size == raws.size && raws.isNotEmpty()) {
                    val cos = raws.indices.map { cosine(raws[it], cpuRaws[it]) }
                    min = cos.min()
                    mean = cos.average().toFloat()
                }
                val agrees = !needsAgreement || (mean != null && mean >= 0.98f)
                val ok = noNaN && agrees

                val ni = images.size
                val nt = texts.size
                val result = BackendResult(
                    backend = backend,
                    ok = ok,
                    error = if (ok) null else if (!noNaN) "NaN in output" else "mean cosine vs CPU below 0.98",
                    loadMs = loadMs,
                    imageMs = median(times.subList(0, ni)),
                    textMs = median(times.subList(ni, ni + nt)),
                    audioMs = median(times.subList(ni + nt, ni + nt + audio.size)),
                    minCosineVsCpu = min,
                    meanCosineVsCpu = mean,
                    detail = embedder.detail,
                    queryMs = median(times.subList(ni + nt + audio.size, times.size)),
                )
                return Measured(result, raws)
            } finally {
                embedder.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val result = BackendResult(backend, false, e.message ?: e::class.simpleName, loadMs, null, null, null, null, null)
            return Measured(result, null)
        }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
