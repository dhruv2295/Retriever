package app.retriever.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Inverted regressions from the adversarial review, plus both sync removal paths. */
class TrialReviewRegressionTest {
    private val sig = Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens)

    @Test fun hugeVideoLibraryPlansTheCheapestFile() {
        val files = (1..10).map { testFile("v$it.mp4", Kind.VIDEO, size = 500_000_000) }
        val plan = FileRules.planTaste(files, SpeedBook()::costMs, 90.seconds)
        assertEquals(listOf(files.first().uri), plan.ids)
        assertFalse(plan.everything)
    }

    @Test fun trimKeepsTheLastUnreadCandidate() = runBlocking {
        val file = testFile("old-plan-video.mp4", Kind.VIDEO, size = 500_000_000)
        val store = newStore(sig).also { it.setProfile(PROFILE) }
        store.upsertFiles(listOf(file))
        store.setTaste(listOf(store.fileByUri(file.uri)!!.id))
        val r = testRetriever(store, listOf(file), FakeExtractor()).apply { uiVisible = true }
        r.refreshSetUp()
        assertEquals(NextRun.DONE, r.taste())
        assertEquals(listOf(store.fileByUri(file.uri)!!.id), r.scopeIds.value)
        assertFalse(r.tasteDecided())
        assertEquals(0 to 1, r.tasteProgress().first().let { it.read to it.done }, "found by name only: processed, not read")
        val counts = r.counts().first()
        assertEquals(0L, counts.byState[FileState.PENDING])
        assertEquals(0L, counts.waiting)
        assertEquals(1L, store.countsByState()[FileState.INDEXED])
    }

    @Test fun excludedFilesLeaveRecentlyRead() = runBlocking {
        val file = testFile("secret.txt", folder = "/private-folder")
        val store = newStore(sig).also { it.setProfile(PROFILE) }
        val r = testRetriever(store, listOf(file), FakeExtractor(mapOf(file.name to listOf(textPiece("content"))))).apply { uiVisible = true }
        r.planTaste()
        r.taste()
        assertEquals(listOf(file), r.state.value.recentFiles)
        r.updatePrefs { it.copy(excludedFolders = setOf(file.folder)) }
        assertEquals(null, store.fileByUri(file.uri))
        assertTrue(r.state.value.recentFiles.isEmpty())
        r.release()
    }

    @Test fun fullScanRemovalDropsOnlyRemovedRecentFiles() = removedFilesLeaveRecentlyRead(fullScan = true)

    @Test fun incrementalDeletionDropsOnlyRemovedRecentFiles() = removedFilesLeaveRecentlyRead(fullScan = false)

    private fun removedFilesLeaveRecentlyRead(fullScan: Boolean) = runBlocking {
        val gone = testFile("gone.txt")
        val kept = testFile("kept.txt")
        val store = newStore(sig).also { it.setProfile(PROFILE) }
        val source = FakeSource().apply { next = Changes(listOf(gone, kept), emptyList(), "1", isFullScan = true) }
        val hasher = object : ContentHasher {
            override suspend fun quickHash(uri: String, size: Long): String? = null
            override suspend fun fullHash(uri: String): String? = null
        }
        val r = Retriever(
            store, source, FakeExtractor(listOf(gone, kept).associate { it.name to listOf(textPiece("content")) }), FakeFactory(), FakeMonitor(), hasher,
            PIXEL, { true }, { true }, { _, _ -> }, System::currentTimeMillis, { 0L },
        ).apply { uiVisible = true }
        r.planTaste()
        r.taste()
        assertEquals(setOf(gone, kept), r.state.value.recentFiles.toSet())
        source.next = Changes(listOf(kept), listOf(gone.uri), "2", isFullScan = fullScan)
        // Planning also syncs; the regular batch must reconcile deletions too.
        if (fullScan) r.planTaste() else r.indexBatch(120.seconds)
        assertEquals(null, store.fileByUri(gone.uri))
        assertEquals(listOf(kept), r.state.value.recentFiles)
        r.release()
    }

    @Test fun batterySaverStopsContentButKeepsNamesCurrent() = runBlocking {
        val store = newStore(sig).also { it.setProfile(PROFILE) }
        val source = FakeSource()
        val hasher = object : ContentHasher {
            override suspend fun quickHash(uri: String, size: Long): String? = null
            override suspend fun fullHash(uri: String): String? = null
        }
        val monitor = FakeMonitor(DeviceState(8.0, false, 80, Thermal.NONE, powerSave = true))
        val r = Retriever(
            store, source, FakeExtractor(), FakeFactory(),
            monitor,
            hasher, PIXEL, { true }, { true }, { _, _ -> }, System::currentTimeMillis, { 0L },
        )
        repeat(3) { assertEquals(NextRun.DONE, r.indexBatch(120.seconds)) }
        assertEquals(3, source.calls.size)
        monitor.state = monitor.state.copy(powerSave = false, charging = true, pluggedIn = true)
        assertEquals(NextRun.DONE, r.indexBatch(120.seconds))
        assertEquals(4, source.calls.size)
    }
}
