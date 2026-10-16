package app.retriever.core

/**
 * Reciprocal rank fusion. Ties keep first-appearance order: LinkedHashMap preserves insertion
 * order and sortedByDescending is stable.
 */
fun rrfMerge(lists: List<List<Long>>, k: Int = 60): List<Pair<Long, Double>> {
    val scores = LinkedHashMap<Long, Double>()
    for (list in lists) {
        list.forEachIndexed { i, id ->
            scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + i + 1)
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
