package app.rummage

import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.rummage.core.Backend
import app.rummage.core.Calibration
import app.rummage.core.DIMS
import app.rummage.core.DeviceProfile
import app.rummage.core.Embedder
import app.rummage.core.FileMetric
import app.rummage.core.IndexEngine
import app.rummage.core.Kind
import app.rummage.core.ModelVariant
import app.rummage.core.Part
import app.rummage.core.SearchEngine
import app.rummage.core.Samples
import app.rummage.core.Store
import app.rummage.core.Tier
import app.rummage.core.Vector
import app.rummage.core.VectorTable
import app.rummage.core.android.AndroidDeviceMonitor
import app.rummage.core.android.AndroidExtractor
import app.rummage.core.android.ImageBytes
import app.rummage.core.android.MediaDecoding
import app.rummage.core.android.MediaPipeEmbedderFactory
import app.rummage.core.android.MediaStoreSampler
import app.rummage.core.db.IndexDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

@Serializable
data class EvalQuery(val q: String, val target: String)

class SpikeRunner(private val app: Context, private val scope: CoroutineScope) {
    val outDir: File = app.getExternalFilesDir("spike")!!
    val modelDir: File = app.getExternalFilesDir("models")!!

    val log = mutableStateListOf<String>()
    var busy by mutableStateOf<String?>(null)
    var progress by mutableStateOf("")
    var hits by mutableStateOf<List<SearchEngine.Hit>>(emptyList())
    var lastQueryMs by mutableStateOf<Double?>(null)
    var profile by mutableStateOf<DeviceProfile?>(null)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val store by lazy { Store(AndroidSqliteDriver(IndexDb.Schema, app, "index.db")) }
    private val monitor by lazy { AndroidDeviceMonitor(app) }
    private val extractor by lazy { AndroidExtractor(app) }
    private val factory by lazy { MediaPipeEmbedderFactory(app, modelDir) }
    private val sampler by lazy { MediaStoreSampler(app) }

    private var job: Job? = null
    private var searchProfile: DeviceProfile? = null
    private var searchEmbedder: Embedder? = null
    private var searchEngine: SearchEngine? = null

    init {
        profile = runCatching {
            store.meta("profile")?.let { json.decodeFromString(DeviceProfile.serializer(), it) }
        }.getOrNull()
    }

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun modelsPresent(): Map<ModelVariant, Boolean> =
        listOf(ModelVariant.M740, ModelVariant.M270).associateWith { File(modelDir, it.fileName).exists() }

    fun cancel() {
        job?.cancel()
    }

    private fun say(line: String) {
        val stamped = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + line
        runCatching { File(outDir, "log.txt").appendText(stamped + "\n") }
        scope.launch(Dispatchers.Main) { log.add(stamped) }
    }

    private fun action(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        progress = ""
        job = scope.launch(Dispatchers.Default) {
            try {
                block()
            } catch (e: CancellationException) {
                say("cancelled")
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Rummage", "$label failed", e)
                say("error: ${e.message ?: e}")
            } finally {
                busy = null
            }
        }
    }

    @Synchronized
    private fun engine(): SearchEngine {
        val p = profile ?: error("calibrate first")
        if (searchEngine == null || searchProfile != p) {
            searchEmbedder?.close()
            val e = factory.open(p.model, p.backend, p.visionTokens)
            searchEmbedder = e
            searchEngine = SearchEngine(store, e)
            searchProfile = p
        }
        return searchEngine!!
    }

    /** Unloads the search model so index and calibrate runs measure one model; search reopens it lazily. */
    @Synchronized
    fun releaseSearch() {
        searchEmbedder?.close()
        searchEmbedder = null
        searchEngine = null
    }

    fun calibrate() = action("calibrate") {
        releaseSearch()
        val lines = mutableListOf<String>()
        fun report(s: String) {
            lines += s
            say(s)
        }

        val sample = sampler.sample(mapOf(Kind.PHOTO to 10, Kind.PDF to 5, Kind.DOC to 5, Kind.AUDIO to 3))
        val images = sample.filter { it.kind == Kind.PHOTO }
            .mapNotNull { ImageBytes.jpeg(app, Uri.parse(it.uri), 512) }
        val tmp = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 0, true, false)
        val texts = mutableListOf<String>()
        for (f in sample.filter { it.kind == Kind.PDF || it.kind == Kind.DOC }) {
            if (texts.size >= 10) break
            texts += extractor.extract(f, tmp).flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
        }
        val sampleTexts = (texts.take(10) + FALLBACK_TEXTS).take(maxOf(10, texts.take(10).size))
        val audio = sample.filter { it.kind == Kind.AUDIO }.take(3).mapNotNull {
            MediaDecoding.windows(MediaDecoding.decodeAudio16kMono(app, Uri.parse(it.uri), 30))
                .firstOrNull()?.second?.takeIf { w -> w.isNotEmpty() }
        }
        report("samples: ${images.size} images, ${sampleTexts.size} texts (${texts.size} from files), ${audio.size} audio")

        val result = Calibration(factory).run(Samples(images, sampleTexts, audio), monitor.state().ramGb)
        for (b in result.backends) {
            report(
                "${b.backend} ok=${b.ok} err=${b.error ?: "-"} load=${b.loadMs.f1()}ms image=${b.imageMs.f1()}ms " +
                    "text=${b.textMs.f1()}ms audio=${b.audioMs.f1()}ms query=${b.queryMs.f1()}ms " +
                    "cos min=${b.minCosineVsCpu.f4()} mean=${b.meanCosineVsCpu.f4()}" +
                    (b.detail?.let { " note: $it" } ?: ""),
            )
        }
        report("chosen: ${result.chosen}")
        report("profile: ${result.profile}")

        val text = json.encodeToString(DeviceProfile.serializer(), result.profile)
        store.setMeta("profile", text)
        File(outDir, "profile.json").writeText(text)
        profile = result.profile

        if (modelsPresent().values.all { it }) {
            runCatching { Calibration(factory).compareModels(sampleTexts, ModelVariant.M270, ModelVariant.M740, result.chosen) }
                .onSuccess { report("270M vs 740M text cosine: ${it.f4()}") }
                .onFailure { report("270M vs 740M check skipped: ${it.message?.lineSequence()?.last()?.trim()}") }
        }
        File(outDir, "calibration.txt").writeText(lines.joinToString("\n") + "\n")
    }

    fun buildTestSet() = action("test set") {
        val quota = mapOf(
            Kind.PHOTO to 300, Kind.SCREENSHOT to 250, Kind.PDF to 150, Kind.DOC to 100,
            Kind.AUDIO to 100, Kind.VIDEO to 50, Kind.OTHER to 50,
        )
        val files = sampler.sample(quota)
        store.upsertFiles(files)
        val csv = buildString {
            append("kind,name,folder,size,uri\n")
            for (f in files) append("${f.kind},${csv(f.name)},${csv(f.folder)},${f.size},${csv(f.uri)}\n")
        }
        File(outDir, "testset.csv").writeText(csv)
        say("test set: ${files.size} files")
        say("by kind: ${store.countsByKind()}")
        say("by state: ${store.countsByState()}")
    }

    private class Rec(val tMs: Long, val kind: Kind, val pieces: Int, val extractMs: Double, val embedMs: Double, val ok: Boolean)

    fun index() = action("index") {
        // Spike-only escape hatch: a hand-written profile.override.json wins over the calibrated tier.
        File(outDir, "profile.override.json").takeIf { it.exists() }?.let {
            val forced = json.decodeFromString(DeviceProfile.serializer(), it.readText())
            store.setMeta("profile", json.encodeToString(DeviceProfile.serializer(), forced))
            profile = forced
            say("using override profile: $forced")
        }
        val p = profile
        if (p == null) {
            say("calibrate first")
            return@action
        }
        releaseSearch()
        val total = store.countsByState()["pending"] ?: 0L
        val metricsFile = File(outDir, "metrics.csv")
        if (!metricsFile.exists()) {
            metricsFile.writeText("t_ms,kind,name,pieces,chunks,extract_ms,embed_ms,ok,error,thermal,battery,charging,pss_mb\n")
        }
        val startMark = TimeSource.Monotonic.markNow()
        val startBattery = monitor.state().batteryPct
        val recs = mutableListOf<Rec>()
        var peakPss = 0.0

        fun record(m: FileMetric) {
            val t = startMark.elapsedNow().inWholeMilliseconds
            val pss = Debug.getPss() / 1024.0
            if (pss > peakPss) peakPss = pss
            recs += Rec(t, m.file.kind, m.pieces, m.extractMs, m.embedMs, m.ok)
            metricsFile.appendText(
                "$t,${m.file.kind},${csv(m.file.name)},${m.pieces},${m.chunks},${m.extractMs.f1()},${m.embedMs.f1()}," +
                    "${m.ok},${csv(m.error ?: "")},${m.state.thermal},${m.state.batteryPct},${m.state.charging},${pss.f1()}\n",
            )
            progress = "${recs.size}/$total · ${m.file.kind} ${m.file.name} · ${m.state.thermal} · ${m.state.batteryPct}%"
        }

        val embedder = factory.open(p.model, p.backend, p.visionTokens)
        var stopReason = "INTERRUPTED"
        try {
            stopReason = IndexEngine(store, extractor, embedder, monitor, p, onFile = ::record)
                .runBatch(budget = 3.hours, requireCharging = false, pauseWhenHot = false).stopReason.name
        } finally {
            embedder.close()
            writeSummary(startMark, startBattery, recs, peakPss, stopReason)
        }
    }

    private fun writeSummary(startMark: TimeSource.Monotonic.ValueTimeMark, startBattery: Int, recs: List<Rec>, peakPss: Double, stopReason: String) {
        val wallMs = startMark.elapsedNow().inWholeMilliseconds
        val wallMin = wallMs / 60_000.0
        val endBattery = monitor.state().batteryPct
        val okCount = recs.count { it.ok }
        val indexedFiles = store.countsByState()["indexed"] ?: 0L
        val windowMin = minOf(5.0, wallMin).coerceAtLeast(1e-3)
        val first = recs.filter { it.tMs <= windowMin * 60_000 }.sumOf { it.pieces } / windowMin
        val last = recs.filter { it.tMs > wallMs - windowMin * 60_000 }.sumOf { it.pieces } / windowMin
        val dbMb = listOf("index.db", "index.db-wal").sumOf { app.getDatabasePath(it).length() } / 1_048_576.0
        val summary = buildString {
            appendLine("wall: ${wallMin.f1()} min")
            appendLine("files ok: $okCount, failed: ${recs.size - okCount}")
            appendLine("stop reason: $stopReason")
            appendLine("battery: $startBattery% -> $endBattery% (${endBattery - startBattery})")
            appendLine("peak PSS: ${peakPss.f1()} MB")
            for ((kind, rs) in recs.groupBy { it.kind }) {
                appendLine("$kind: n=${rs.size} median extract=${median(rs.map { it.extractMs }).f1()}ms embed=${median(rs.map { it.embedMs }).f1()}ms")
            }
            appendLine("pieces/min first ${windowMin.f1()} min: ${first.f1()}, last: ${last.f1()}, ratio last/first: ${(if (first > 0) last / first else 0.0).f2()}")
            append("index.db: ${dbMb.f2()} MB total, ${(if (indexedFiles > 0) dbMb / (indexedFiles / 1000.0) else 0.0).f2()} MB per 1000 indexed files ($indexedFiles)")
        }
        File(outDir, "summary.txt").writeText(summary + "\n")
        say("index summary:\n$summary")
    }

    fun search(query: String, mode: SearchEngine.Mode) = action("search") {
        val eng = engine()
        val t = TimeSource.Monotonic.markNow()
        val result = eng.search(query, mode)
        val ms = t.elapsedNow().inWholeNanoseconds / 1e6
        hits = result
        lastQueryMs = ms
        say("$query ($mode): ${result.size} hits in ${ms.f1()} ms")
    }

    fun eval() = action("eval") {
        val file = File(outDir, "queries.json")
        if (!file.exists()) {
            val template = listOf(EvalQuery("lease agreement", "lease"), EvalQuery("beach sunset", "IMG_"))
            file.writeText(json.encodeToString(ListSerializer(EvalQuery.serializer()), template))
            say("wrote template ${file.absolutePath}; edit it and run eval again")
            return@action
        }
        val queries = json.decodeFromString(ListSerializer(EvalQuery.serializer()), file.readText())
        val eng = engine()
        val rows = StringBuilder("q,target,vector_hit,keyword_hit,merged_hit,merged_top5\n")
        val counts = SearchEngine.Mode.entries.associateWith { 0 }.toMutableMap()
        for (eq in queries) {
            val flags = mutableMapOf<SearchEngine.Mode, Boolean>()
            var mergedNames = ""
            for (mode in SearchEngine.Mode.entries) {
                val res = eng.search(eq.q, mode, limit = 5)
                val hit = res.any { it.file.name.contains(eq.target, ignoreCase = true) }
                flags[mode] = hit
                if (hit) counts[mode] = counts.getValue(mode) + 1
                if (mode == SearchEngine.Mode.MERGED) mergedNames = res.joinToString(" | ") { it.file.name }
            }
            rows.append(
                "${csv(eq.q)},${csv(eq.target)},${flags[SearchEngine.Mode.VECTOR]},${flags[SearchEngine.Mode.KEYWORD]}," +
                    "${flags[SearchEngine.Mode.MERGED]},${csv(mergedNames)}\n",
            )
        }
        File(outDir, "eval.csv").writeText(rows.toString())
        for (mode in SearchEngine.Mode.entries) say("$mode ${counts[mode]}/${queries.size}")
    }

    fun latencyTest() = action("latency") {
        val eng = engine()
        val sig = searchEmbedder!!.signature
        val table = store.loadVectors()
        if (table.size == 0) {
            say("no vectors indexed")
            return@action
        }
        val reps = (50_000 + table.size - 1) / table.size
        val big = VectorTable(
            LongArray(table.size * reps) { table.chunkIds[it % table.size] },
            LongArray(table.size * reps) { table.fileIds[it % table.size] },
            ByteArray(table.bytes.size * reps) { table.bytes[it % table.bytes.size] },
            FloatArray(table.size * reps) { table.scales[it % table.size] },
        )
        val scan = List(30) {
            val i = Random.nextInt(table.size)
            val q = Vector(table.bytes.copyOfRange(i * DIMS, (i + 1) * DIMS), table.scales[i], sig)
            val t = TimeSource.Monotonic.markNow()
            eng.scanTop(q, 100, big)
            t.elapsedNow().inWholeNanoseconds / 1e6
        }
        say("scan ${big.size} rows x30: p50 ${pct(scan, 0.5).f1()} ms, p95 ${pct(scan, 0.95).f1()} ms")

        eng.reload()
        val queries = listOf("invoice", "beach sunset", "meeting notes about launch", "error dialog", "prescription")
        val e2e = List(20) {
            val t = TimeSource.Monotonic.markNow()
            eng.search(queries[it % queries.size], SearchEngine.Mode.MERGED)
            t.elapsedNow().inWholeNanoseconds / 1e6
        }
        say("end-to-end merged x20: p50 ${pct(e2e, 0.5).f1()} ms, p95 ${pct(e2e, 0.95).f1()} ms")
    }

    private fun pct(xs: List<Double>, p: Double): Double =
        xs.sorted().let { it[(it.size * p).toInt().coerceAtMost(it.size - 1)] }

    private fun median(xs: List<Double>): Double =
        if (xs.isEmpty()) 0.0 else xs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun Double?.f1() = this?.let { "%.1f".format(Locale.US, it) } ?: "-"
    private fun Double.f2() = "%.2f".format(Locale.US, this)
    private fun Float?.f4() = this?.let { "%.4f".format(Locale.US, it) } ?: "-"

    private companion object {
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
