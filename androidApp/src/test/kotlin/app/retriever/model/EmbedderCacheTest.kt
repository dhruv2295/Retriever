package app.retriever.model

import app.retriever.core.android.staleEmbedCaches
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbedderCacheTest {
    private val model = "embeddinggemma-2-740m.litertlm"
    private val key = "1792134909_484622336"
    private fun files(k: String) = listOf("${model}_$k.vision_adapter.xnnpack_cache", "${model}_$k.mldrift_program_cache.bin")

    @Test
    fun alternatingConfigsKeepEachOther() {
        val dirs = mapOf(
            "embeddinggemma-2-740m-GPU-vt70-t512" to files(key),
            "embeddinggemma-2-740m-GPU-vt140-t512" to files(key),
        )
        assertEquals(emptyList<String>(), staleEmbedCaches(dirs, mapOf(model to key)))
    }

    @Test
    fun staleMtimeDuplicateIsPruned() {
        val dirs = mapOf("embeddinggemma-2-740m-GPU-vt70-t512" to files(key) + files("1792134908_484622336"))
        assertEquals(
            files("1792134908_484622336").map { "embeddinggemma-2-740m-GPU-vt70-t512/$it" },
            staleEmbedCaches(dirs, mapOf(model to key)),
        )
    }

    @Test
    fun dirOfRemovedModelIsPruned() {
        val dirs = mapOf("embeddinggemma-2-300m-CPU-vt70-t512" to files(key))
        assertEquals(listOf("embeddinggemma-2-300m-CPU-vt70-t512"), staleEmbedCaches(dirs, mapOf(model to key)))
    }
}
