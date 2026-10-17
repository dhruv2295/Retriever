package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The retrieval defaults RetrievalEval measured on the M440 model (iOS simulator, 30 files, 31 queries, Oct 2026),
 * all kept: the default scored recall@1 29, recall@5 31/31, MRR 0.96, so no alternative could clear the rule of
 * +2 queries at recall@5. Changing one changes search on both platforms, and DIMS, vision tokens or a prefix also
 * re-index every file through the signature: re-run the eval (-eval on iOS, corpus eval on Android) first.
 */
class DefaultsTest {
    /** 512 and 768 float ranked exactly as 256 (29 · 31 · 0.96) at 2-3x the memory and scan time; int8 lost nothing. */
    @Test
    fun vectorsKeep256Dims() = assertEquals(256, DIMS)

    /** k 10/30/100 and keyword weight 0.5/2 changed no rank; weight 0 lost the name query "IMG_1007" (rank 1 to 12). */
    @Test
    fun fusionIsPlainRrf() = assertEquals(SearchTuning(rrfK = 60, keywordWeight = 1.0), SearchTuning())

    /** 140 tokens: +1 query at recall@1, none at recall@5, 1.7-4x the image embed time; 280 is refused (the model's max is 140). */
    @Test
    fun visionTokensPerTier() {
        fun vt(imageMs: Double, ramGb: Double) = Tiers.profileFor(imageMs, ramGb, Backend.GPU, ModelVariant.M440).let { it.tier to it.visionTokens }
        assertEquals(Tier.FULL to 140, vt(100.0, 8.0))
        assertEquals(Tier.BALANCED to 70, vt(500.0, 6.0))
        assertEquals(Tier.LIGHT to 70, vt(5000.0, 4.0))
    }

    /** Without them: recall@5 31 to 29 fused, 30 to 27 on the vector lane alone. */
    @Test
    fun modelCardPrefixes() {
        assertEquals("task: search result | query: rent receipt", Prefix.query("rent receipt"))
        assertEquals("title: lease.pdf | text: deposit", Prefix.document("lease.pdf", "deposit"))
    }
}
