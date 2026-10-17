package app.retriever.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class RecentAndLimitTest {
    private object NoHasher : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    }

    private fun retriever(store: Store, files: List<SourceFile> = emptyList()) = Retriever(
        store,
        FakeSource().apply { next = Changes(files, emptyList(), "tok", isFullScan = true) },
        FakeExtractor(),
        FakeFactory(),
        FakeMonitor(),
        NoHasher,
        DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 8.0, false),
        { false },
        { true },
        { _, _ -> },
        System::currentTimeMillis,
    )

    @Test
    fun removeRecentQueryRemovesOnlyThatPhrase() = runBlocking {
        val r = retriever(newStore())
        r.recordQuery("wifi password")
        r.recordQuery("electricity bill")
        r.removeRecentQuery(" wifi password ")
        assertEquals(listOf("electricity bill"), r.recentQueries())
    }

    @Test
    fun searchHonoursLimit() = runBlocking {
        val r = retriever(newStore(), (1..5).map { testFile("bill $it.txt", modifiedAt = it.toLong()) })
        r.indexBatch(1.minutes) // no model: syncs the names only
        assertEquals(5, r.search("bill", limit = 60).hits.size)
        assertEquals(3, r.search("bill", limit = 3).hits.size)
    }
}
