package app.retriever.core

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
