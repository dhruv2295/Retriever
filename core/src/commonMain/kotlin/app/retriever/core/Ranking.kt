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
        else -> DeviceProfile(Tier.LIGHT, variant, backend, 70, 0, false, true, maxTokens)
    }
}
