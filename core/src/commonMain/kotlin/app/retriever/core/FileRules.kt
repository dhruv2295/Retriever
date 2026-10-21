package app.retriever.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Duration

/** Which files count and what kind they are; shared by the Android and iOS sources. */
object FileRules {
    // Android/media holds WhatsApp and other apps' shared media (most photos on a typical phone);
    // only Android/data and Android/obb are private to their apps.
    fun shouldSkip(relativePath: String, name: String): Boolean = relativePath.startsWith("Android/data/", ignoreCase = true) ||
        relativePath.startsWith("Android/obb/", ignoreCase = true) ||
        relativePath.contains("/.") ||
        relativePath.startsWith(".") ||
        name.startsWith(".")

    val DOC_TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "xml", "html", "htm", "kt", "java", "py", "js", "ts", "swift",
        "go", "rs", "c", "cpp", "h", "yaml", "yml", "log", "docx", "pptx", "xlsx",
        "odt", "ods", "odp", "epub", "rtf",
    )

    private val MIME_BY_EXTENSION = mapOf(
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "text/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "epub" to "application/epub+zip",
        "rtf" to "text/rtf",
        "pdf" to "application/pdf",
    )

    /** For files whose MIME type MediaStore left empty; null when the extension is unknown. */
    fun mimeForExtension(ext: String): String? {
        val e = ext.lowercase()
        return MIME_BY_EXTENSION[e] ?: if (e in DOC_TEXT_EXTENSIONS) "text/plain" else null
    }

    fun classify(mime: String, relativePath: String, name: String): Kind = when {
        mime.startsWith("image/") ->
            if (relativePath.contains("screenshot", ignoreCase = true) ||
                name.contains("screenshot", ignoreCase = true)
            ) {
                Kind.SCREENSHOT
            } else {
                Kind.PHOTO
            }

        mime.startsWith("video/") -> Kind.VIDEO

        mime.startsWith("audio/") -> Kind.AUDIO

        mime == "application/pdf" -> Kind.PDF

        isDoc(mime, name) -> Kind.DOC

        else -> Kind.OTHER
    }

    private fun isDoc(mime: String, name: String): Boolean = mime.startsWith("text/") ||
        mime == "application/json" ||
        mime.contains("officedocument") ||
        mime.contains("opendocument") ||
        mime.contains("msword") ||
        mime == "application/epub+zip" ||
        name.substringAfterLast('.', "").lowercase() in DOC_TEXT_EXTENSIONS

    private val TASTE_SHARES = mapOf(
        Kind.PHOTO to 0.35,
        Kind.SCREENSHOT to 0.25,
        Kind.PDF to 0.125,
        Kind.DOC to 0.125,
        Kind.VIDEO to 0.15,
    )
    private val TASTE_ORDER = listOf(Kind.SCREENSHOT, Kind.DOC, Kind.PDF, Kind.PHOTO, Kind.VIDEO)
    private const val BURST_MS = 3_600_000L

    private fun at(f: SourceFile) = f.takenAt ?: f.modifiedAt

    /** A varied first read of at most [DEMO_FILES] within [budget], or the single cheapest eligible file when none fits. */
    fun planTaste(files: List<SourceFile>, costMs: (SourceFile) -> Double, budget: Duration): TastePlan {
        val byKind = files.filter { it.kind in TASTE_SHARES }.groupBy { it.kind }
        val budgetMs = budget.inWholeMilliseconds.toDouble().coerceAtLeast(0.0)
        val shareSum = byKind.keys.sumOf { TASTE_SHARES.getValue(it) }
        val videos = byKind[Kind.VIDEO].orEmpty().sortedWith(compareBy<SourceFile> { it.size }.thenBy { it.uri })
        // At most a few videos even if a fast tune or tiny byte sizes promise hundreds.
        val videoBudget = if (videos.isEmpty()) {
            0.0
        } else {
            maxOf(budgetMs * TASTE_SHARES.getValue(Kind.VIDEO) / shareSum, costMs(videos.first())).coerceAtMost(budgetMs)
        }
        var videoLeft = videoBudget
        // At least one candidate: a video measured faster than the seed must not lose the slot a seeded one gets.
        val pickedVideos = videos.take(maxOf(1, (videoBudget / VIDEO_SEED_MS).toInt())).takeWhile {
            val cost = costMs(it)
            (cost <= videoLeft).also { fits -> if (fits) videoLeft -= cost }
        }
        val remaining = budgetMs - pickedVideos.sumOf(costMs)
        val others = byKind - Kind.VIDEO
        val otherShares = others.keys.sumOf { TASTE_SHARES.getValue(it) }
        val allOthersFit = others.values.flatten().sumOf(costMs) <= remaining
        val picks = others.mapValues { (kind, group) ->
            val allowance = if (allOthersFit) remaining else remaining * TASTE_SHARES.getValue(kind) / otherShares
            val n = if (allOthersFit) group.size else (allowance / costMs(group.first())).toInt().coerceIn(0, 40)
            pickAcrossTime(group, n).let { candidates ->
                var left = allowance
                candidates.takeWhile { f -> (costMs(f) <= left).also { if (it) left -= costMs(f) } }
            }
        } + (Kind.VIDEO to pickedVideos)
        val ordered = TASTE_ORDER.mapNotNull { picks[it]?.takeIf(List<SourceFile>::isNotEmpty) }.ifEmpty {
            byKind.values.flatten().minWithOrNull(compareBy<SourceFile>(costMs).thenBy { it.uri })
                ?.let { listOf(listOf(it)) }.orEmpty()
        }
        // Two of each kind first, then the rest, cut at the demo's size.
        val all = (ordered.flatMap { it.take(2) } + ordered.flatMap { it.drop(2) }).take(DEMO_FILES)
        return TastePlan(
            ids = all.map { it.uri },
            byKind = all.groupingBy { it.kind }.eachCount(),
            oldest = all.minOfOrNull(::at),
            newest = all.maxOfOrNull(::at),
            seconds = ceil(all.sumOf(costMs) / 1000).toInt(),
            everything = all.size == byKind.values.sumOf { it.size },
        )
    }

    /** Newest 2, then one per equal-count time bin nearest the middle, under burst and folder guards. */
    private fun pickAcrossTime(group: List<SourceFile>, n: Int): List<SourceFile> {
        val s = group.sortedBy(::at)
        val cap = ceil(0.4 * n)
        val picked = ArrayList<SourceFile>(n)
        fun calm(f: SourceFile) = picked.none { abs(at(f) - at(it)) < BURST_MS }
        fun ok(f: SourceFile) = calm(f) && picked.count { it.folder == f.folder } < cap
        for (f in s.asReversed()) if (picked.size < minOf(2, n) && ok(f)) picked += f
        val bins = n - 2
        for (i in 0 until bins) {
            val lo = i * s.size / bins
            val hi = (i + 1) * s.size / bins
            val order = (lo until hi).sortedBy { abs(2 * it - (lo + hi - 1)) }
            // A one-folder library can't satisfy the folder guard, so fall back to the burst guard alone.
            (order.firstOrNull { ok(s[it]) } ?: order.firstOrNull { calm(s[it]) })?.let { picked += s[it] }
        }
        for (f in s.asReversed()) if (picked.size < n && f !in picked) picked += f
        return picked
    }
}

/** [ids] are file uris in run order; [seconds] is the seeded cost of the whole plan. */
data class TastePlan(val ids: List<String>, val byKind: Map<Kind, Int>, val oldest: Long?, val newest: Long?, val seconds: Int, val everything: Boolean)
