package app.retriever.core

import kotlin.math.ln
import kotlin.math.sqrt

/** How search fuses its lanes. RetrievalEval measures alternatives; DefaultsTest pins these. */
data class SearchTuning(
    val rrfK: Int = 60,
    /** Weight of each keyword lane (chunk text, file name) against the vector lane's 1.0. */
    val keywordWeight: Double = 1.0,
)

/**
 * Reciprocal rank fusion: a lane adds weight / (k + rank + 1), weights default to 1. Ties keep first-appearance
 * order: LinkedHashMap preserves insertion order and sortedByDescending is stable.
 */
fun rrfMerge(lists: List<List<Long>>, k: Int = 60, weights: List<Double>? = null): List<Pair<Long, Double>> {
    val scores = LinkedHashMap<Long, Double>()
    lists.forEachIndexed { lane, list ->
        val w = weights?.get(lane) ?: 1.0
        list.forEachIndexed { i, id ->
            scores[id] = (scores[id] ?: 0.0) + w / (k + i + 1)
        }
    }
    return scores.entries.map { it.key to it.value }.sortedByDescending { it.second }
}

/**
 * The one switch for the relevance rule. Off: every hit found by meaning counts as strong, on every model. Fitted on
 * public data on 2026-10-17 and kept off, for every group: no margin passed (eval/REPORT.md, "Relevance rule fit").
 */
const val RELEVANCE_RULE_ENABLED = false

/**
 * m, the rule's margin in standard deviations above the expected noise maximum. 0 is a placeholder, not a measurement.
 * The conformal fit (alpha 0.10, M440/92dcbea1/vt70, 2,478 public calibration queries) gave m = -1.74, which calls 78% of
 * text and 54% of photo no-match queries strong on the test split; no single m reaches strong recall 0.80 at
 * false-strong 0.12 in any modality group, and leave-one-dataset-out fits range from -2.14 to -0.64. So -1.74 is not shipped.
 * At 0, about 9% of queries that match nothing in a 5,000-row group still show one strong hit (RelevanceRuleTest).
 */
const val RELEVANCE_MARGIN = 0.0

/**
 * When a file found only by meaning counts as a strong match, judged against the query's own cosines (relevance-method.md
 * section 3.1). The scan fills one histogram of cosines per [Modality] (text-to-image cosines sit in another band from
 * text-to-text ones); a hit is strong when (cos - median) / (IQR / 1.349) - sqrt(2 ln n) >= [margin], n being the rows of
 * its group: how far it sits above the largest cosine n draws of pure noise would reach. A group under [minRows] rows is
 * too thin to estimate, so every hit in it is strong. No cosine constant: the scale comes from each query.
 */
class RelevanceRule(val margin: Double = RELEVANCE_MARGIN, val minRows: Int = MIN_ROWS) {
    /** A fresh histogram for one scan: [BINS] counts per modality. */
    fun histogram() = IntArray(Modality.entries.size * BINS)

    /** One group's noise in one query's histogram: [n] rows, robust centre and spread. */
    class Noise(val n: Int, val median: Double, val sigma: Double) {
        /** sqrt(2 ln n): where the largest of n Gaussian noise draws lands, in standard deviations. */
        val expectedMax: Double get() = sqrt(2 * ln(n.toDouble()))

        /** How many standard deviations [cos] sits above that expected maximum; strong when >= the margin. */
        fun excess(cos: Float): Double = (cos - median) / sigma - expectedMax
    }

    /** Per modality ordinal, that group's [Noise]; null for a group under [minRows]. */
    fun noise(histogram: IntArray): Array<Noise?> = Array(Modality.entries.size) { g ->
        val off = g * BINS
        var n = 0
        for (i in off until off + BINS) n += histogram[i]
        if (n < minRows) return@Array null
        val q1 = quantile(histogram, off, n, 0.25)
        val median = quantile(histogram, off, n, 0.5)
        Noise(n, median, maxOf(quantile(histogram, off, n, 0.75) - q1, BIN_WIDTH) / 1.349)
    }

    /** Per modality ordinal, the lowest cosine that is strong; -Infinity for a group too thin to judge. */
    fun minStrong(histogram: IntArray): FloatArray {
        val noise = noise(histogram)
        return FloatArray(noise.size) { g -> noise[g]?.let { (it.median + it.sigma * (it.expectedMax + margin)).toFloat() } ?: Float.NEGATIVE_INFINITY }
    }

    /** The [p] quantile of one group's counts, interpolated inside its bin. */
    private fun quantile(h: IntArray, off: Int, n: Int, p: Double): Double {
        val target = p * n
        var cum = 0
        for (b in 0 until BINS) {
            val c = h[off + b]
            if (c > 0 && cum + c >= target) return -1.0 + (b + (target - cum) / c) * BIN_WIDTH
            cum += c
        }
        return 1.0
    }

    companion object {
        const val BINS = 1024
        const val MIN_ROWS = 200
        private const val BIN_WIDTH = 2.0 / BINS

        /** What search runs with: null while [RELEVANCE_RULE_ENABLED] is false, so no histogram work at all. */
        val shipped: RelevanceRule? = if (RELEVANCE_RULE_ENABLED) RelevanceRule() else null

        /** int8 dot products can stray just past ±1. */
        fun bin(cos: Float): Int = ((cos + 1f) * (BINS / 2)).toInt().coerceIn(0, BINS - 1)
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

    fun profileFor(
        imageMs: Double,
        ramGb: Double,
        backend: Backend,
        variant: ModelVariant,
        maxTokens: Int = 512,
    ): DeviceProfile = when {
        imageMs <= 150 && ramGb >= 8 ->
            DeviceProfile(Tier.FULL, variant, backend, 140, 2, variant.audio, false, maxTokens)
        imageMs <= 1200 && ramGb >= 6 ->
            DeviceProfile(Tier.BALANCED, variant, backend, 70, 5, variant.audio, false, maxTokens)
        // One keyframe per 30 s (still at most MAX_VIDEO_MOMENTS), so a slow phone reads videos without stalling.
        else -> DeviceProfile(Tier.LIGHT, variant, backend, 70, 30, false, true, maxTokens)
    }
}
