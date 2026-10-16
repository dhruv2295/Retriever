package app.rummage.core

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
    fun profileFor(imageMs: Double, ramGb: Double, backend: Backend): DeviceProfile = when {
        imageMs <= 120 && ramGb >= 8 -> DeviceProfile(
            tier = Tier.FULL,
            model = ModelVariant.M740,
            backend = backend,
            visionTokens = 140,
            videoFrameEverySec = 2,
            audio = true,
            bulkOnlyWhenCharging = false,
        )
        imageMs <= 400 && ramGb >= 6 -> DeviceProfile(
            tier = Tier.BALANCED,
            model = ModelVariant.M740,
            backend = backend,
            visionTokens = 70,
            videoFrameEverySec = 5,
            audio = true,
            bulkOnlyWhenCharging = true,
        )
        else -> DeviceProfile(
            tier = Tier.LIGHT,
            // The text-only 270M file won't load in UniversalEmbedder ("tf_lite_vision_encoder not found").
            model = ModelVariant.M740,
            backend = backend,
            visionTokens = 70,
            videoFrameEverySec = 0,
            audio = false,
            bulkOnlyWhenCharging = true,
        )
    }
}
