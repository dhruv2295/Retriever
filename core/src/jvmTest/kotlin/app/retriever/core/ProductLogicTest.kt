package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val HOUR_MS = 3_600_000L

private fun newLibraryStore(): Store = newStore()

private fun uriOf(id: String) = "content://t/$id"

private fun sf(
    id: String,
    size: Long = 100L,
    modifiedAt: Long = 1L,
    name: String = id,
    folder: String = "",
    kind: Kind = Kind.DOC,
) = SourceFile(uriOf(id), name, folder, "text/plain", kind, size, modifiedAt)

private fun vecOf(sig: Signature) = Vector.of(FloatArray(DIMS) { if (it == 0) 1f else 0f }, sig)

private fun names(files: List<Store.StoredFile>) = files.map { it.file.name }

private class FakeHasher(
    private val quick: Map<String, String?> = emptyMap(),
    private val full: Map<String, String?> = emptyMap(),
) : ContentHasher {
    val quickCalls = mutableListOf<String>()
    val fullCalls = mutableListOf<String>()

    override suspend fun quickHash(uri: String, size: Long): String? {
        quickCalls += uri
        return quick[uri]
    }

    override suspend fun fullHash(uri: String): String? {
        fullCalls += uri
        return full[uri]
    }
}

private fun countingExtractor() = object : Extractor {
    val names = mutableListOf<String>()

    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
        names += file.name
        return listOf(Piece("", listOf(Part.Text(file.name)), file.name))
    }
}

private suspend fun IndexEngine.batch() = runBatch(9.minutes, requireCharging = false)

class LibrarySyncTest {
    @Test
    fun firstSyncIsAFullScanThatAddsEveryFileAndStoresTheToken() = runTest {
        val store = newLibraryStore()
        val source = FakeSource().apply {
            next = Changes(listOf(sf("a"), sf("b")), emptyList(), "tok1", isFullScan = true)
        }
        val result = LibrarySync(store, source, now = { 0L }).sync()
        assertEquals(SyncResult(added = 2, changed = 0, removed = 0, fullScan = true), result)
        assertEquals(2L, store.fileCount())
        assertEquals("tok1", store.meta("source_token"))
        assertEquals(listOf<String?>(null), source.calls)
    }

    @Test
    fun secondSyncWithinADayIsIncrementalAndCountsChangedFiles() = runTest {
        val store = newLibraryStore()
        val source = FakeSource().apply {
            next = Changes(listOf(sf("a"), sf("b")), emptyList(), "tok1", isFullScan = true)
        }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        sync.sync()

        clock = HOUR_MS
        source.next = Changes(listOf(sf("a", modifiedAt = 5)), emptyList(), "tok2", isFullScan = false)
        val result = sync.sync()

        assertEquals(SyncResult(added = 0, changed = 1, removed = 0, fullScan = false), result)
        assertEquals(listOf<String?>(null, "tok1"), source.calls)
        assertEquals("tok2", store.meta("source_token"))
    }

    @Test
    fun sampleModeKeepsTheNewestPerKindAndTurningItOffBringsTheRestBack() = runTest {
        val store = newLibraryStore()
        val all = (1..5).map { sf("p$it", modifiedAt = it.toLong(), kind = Kind.PHOTO) } +
            (1..3).map { sf("d$it", modifiedAt = it.toLong(), kind = Kind.DOC) }
        val source = FakeSource().apply { next = Changes(all, emptyList(), "tok1", isFullScan = true) }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        sync.sync()
        assertEquals(8L, store.fileCount())

        store.updatePrefs { it.copy(sampleSize = 2) }
        clock = HOUR_MS
        val trimmed = sync.sync()
        assertEquals(null, source.calls.last(), "a new sample size forces a full scan")
        assertEquals(4, trimmed.removed)
        assertEquals(setOf("p5", "p4", "d3", "d2"), store.files(limit = 100).map { it.file.name }.toSet())

        store.updatePrefs { it.copy(sampleSize = null) }
        clock = 2 * HOUR_MS
        val restored = sync.sync()
        assertEquals(4, restored.added)
        assertEquals(8L, store.fileCount())
    }

    @Test
    fun changedScanRulesForceAFullScanEvenWithinADay() = runTest {
        val store = newLibraryStore()
        val source = FakeSource().apply {
            next = Changes(listOf(sf("a")), emptyList(), "tok1", isFullScan = true)
        }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        sync.sync()

        clock = HOUR_MS
        source.rules = 2
        source.next = Changes(listOf(sf("a"), sf("wa")), emptyList(), "tok2", isFullScan = true)
        val result = sync.sync()

        assertEquals(listOf<String?>(null, null), source.calls)
        assertEquals(1, result.added)
        assertTrue(result.fullScan)

        clock = 2 * HOUR_MS
        source.next = Changes(emptyList(), emptyList(), "tok3", isFullScan = false)
        sync.sync()
        assertEquals("tok2", source.calls.last(), "same rules again: back to incremental")
    }

    @Test
    fun forcedFullScanRemovesKnownFilesMissingFromTheScan() = runTest {
        val store = newLibraryStore()
        val source = FakeSource().apply {
            next = Changes(listOf(sf("a"), sf("b")), emptyList(), "tok1", isFullScan = true)
        }
        val sync = LibrarySync(store, source, now = { 0L })
        sync.sync()

        source.next = Changes(listOf(sf("a")), emptyList(), "tok2", isFullScan = true)
        val result = sync.sync(forceFull = true)

        assertEquals(SyncResult(added = 0, changed = 0, removed = 1, fullScan = true), result)
        assertNull(store.fileByUri(uriOf("b")))
        assertEquals(1L, store.fileCount())
        assertEquals(listOf<String?>(null, null), source.calls)
    }

    @Test
    fun excludedFoldersAreNeverAdded() = runTest {
        val store = newLibraryStore()
        store.updatePrefs { Prefs(excludedFolders = setOf("Download/Telegram")) }
        val source = FakeSource().apply {
            next = Changes(
                listOf(
                    sf("tg", folder = "Download/Telegram/x"),
                    sf("pic", folder = "Pictures"),
                    sf("tx", folder = "Download/TelegramX"),
                ),
                emptyList(),
                "tok1",
                isFullScan = true,
            )
        }
        val result = LibrarySync(store, source, now = { 0L }).sync()

        assertEquals(2, result.added)
        assertNull(store.fileByUri(uriOf("tg")))
        assertTrue(store.fileByUri(uriOf("pic")) != null)
        assertTrue(store.fileByUri(uriOf("tx")) != null, "Download/TelegramX is not inside Download/Telegram")
    }

    @Test
    fun changingExcludedFoldersForcesAFullScanAndRemovesKnownExcludedFiles() = runTest {
        val store = newLibraryStore()
        val source = FakeSource().apply {
            next = Changes(listOf(sf("tg", folder = "Download/Telegram/x"), sf("pic", folder = "Pictures"), sf("tx", folder = "Download/TelegramX")), emptyList(), "tok1", isFullScan = true)
        }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        assertEquals(3, sync.sync().added)

        store.updatePrefs { Prefs(excludedFolders = setOf("Download/Telegram")) }
        clock = HOUR_MS
        source.next = Changes(listOf(sf("tg", folder = "Download/Telegram/x"), sf("pic", folder = "Pictures"), sf("tx", folder = "Download/TelegramX")), emptyList(), "tok2", isFullScan = true)
        val result = sync.sync()

        assertNull(source.calls.last(), "a changed exclusion list must rescan from scratch")
        assertEquals(1, result.removed)
        assertNull(store.fileByUri(uriOf("tg")))
        assertEquals(2L, store.fileCount())
    }

    @Test
    fun staleFullScanStampForcesAFullScan() = runTest {
        val store = newLibraryStore()
        val source = FakeSource().apply {
            next = Changes(listOf(sf("a")), emptyList(), "tok1", isFullScan = true)
        }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        sync.sync()

        clock = HOUR_MS
        source.next = Changes(emptyList(), emptyList(), "tok2", isFullScan = false)
        sync.sync()
        assertEquals("tok1", source.calls.last())

        clock = 24L * HOUR_MS + 1
        source.next = Changes(emptyList(), emptyList(), "tok3", isFullScan = true)
        sync.sync()
        assertNull(source.calls.last(), "last full scan is more than 24 h old")
    }

    @Test
    fun fullScanFlagDecidesRemovalNotTheTokenPassed() = runTest {
        val store = newLibraryStore()
        val source = FakeSource().apply {
            next = Changes(listOf(sf("a"), sf("b")), emptyList(), "tok1", isFullScan = true)
        }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        sync.sync()

        clock = HOUR_MS
        source.next = Changes(listOf(sf("a")), emptyList(), "tok2", isFullScan = true)
        val result = sync.sync()

        assertEquals("tok1", source.calls.last())
        assertEquals(1, result.removed)
        assertNull(store.fileByUri(uriOf("b")))
    }

    @Test
    fun incrementalSyncRemovesOnlyTheReportedDeletions() = runTest {
        val store = newLibraryStore()
        val source = FakeSource().apply {
            next = Changes(listOf(sf("a"), sf("b"), sf("c")), emptyList(), "tok1", isFullScan = true)
        }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        sync.sync()

        clock = HOUR_MS
        source.next = Changes(emptyList(), listOf(uriOf("b")), "tok2", isFullScan = false)
        val result = sync.sync()

        assertEquals(1, result.removed)
        assertNull(store.fileByUri(uriOf("b")))
        assertTrue(store.fileByUri(uriOf("a")) != null)
        assertTrue(store.fileByUri(uriOf("c")) != null, "a file missing from an incremental list is not a deletion")
    }

    @Test
    fun renameOrMoveWithSameSizeAndTimeCountsAsChangedAndIsReindexed() = runTest {
        val store = newLibraryStore()
        val source = FakeSource().apply {
            next = Changes(listOf(sf("a", name = "a.txt")), emptyList(), "tok1", isFullScan = true)
        }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        sync.sync()
        IndexEngine(store, FakeExtractor(), FakeEmbedder(), FakeMonitor(), settingsOf()).batch()
        assertEquals(FileState.INDEXED, store.fileByUri(uriOf("a"))?.state)

        clock = HOUR_MS
        source.next = Changes(listOf(sf("a", name = "b.txt", folder = "Moved")), emptyList(), "tok2", isFullScan = false)
        val result = sync.sync()

        assertEquals(1, result.changed)
        assertEquals(FileState.PENDING, store.fileByUri(uriOf("a"))?.state)
    }
}

class DuplicateFinderTest {
    @Test
    fun groupsIdenticalFilesAndFullHashesOnlyFilesThatShareAQuickHash() = runTest {
        val store = newLibraryStore()
        store.upsertFiles(
            listOf(
                sf("a", size = 100_000, modifiedAt = 5),
                sf("b", size = 100_000, modifiedAt = 2),
                sf("c", size = 100_000, modifiedAt = 3),
                sf("d", size = 100_000, modifiedAt = 4),
            ),
        )
        val hasher = FakeHasher(
            quick = mapOf(uriOf("a") to "q", uriOf("b") to "q", uriOf("c") to "q", uriOf("d") to "other"),
            full = mapOf(uriOf("a") to "f1", uriOf("b") to "f1", uriOf("c") to "f2", uriOf("d") to "f9"),
        )
        val groups = DuplicateFinder(store, hasher).find()

        assertEquals(listOf(listOf(uriOf("b"), uriOf("a"))), groups.map { g -> g.files.map { it.uri } }, "oldest first")
        assertEquals(100_000L, groups.single().wastedBytes)
        assertEquals(3, hasher.fullCalls.size)
        assertEquals(setOf(uriOf("a"), uriOf("b"), uriOf("c")), hasher.fullCalls.toSet())
    }

    @Test
    fun aFileWithoutAQuickHashIsSkipped() = runTest {
        val store = newLibraryStore()
        store.upsertFiles(listOf(sf("a", size = 100_000, modifiedAt = 1), sf("b", size = 100_000, modifiedAt = 2)))
        val hasher = FakeHasher(
            quick = mapOf(uriOf("a") to null, uriOf("b") to "q"),
            full = mapOf(uriOf("a") to "f", uriOf("b") to "f"),
        )
        assertTrue(DuplicateFinder(store, hasher).find().isEmpty())
        assertTrue(hasher.fullCalls.isEmpty())
    }

    @Test
    fun groupsAreSortedByWastedBytesNotByFileSize() = runTest {
        val store = newLibraryStore()
        store.upsertFiles(
            listOf(
                sf("x1", size = 100_000), sf("x2", size = 100_000), sf("x3", size = 100_000),
                sf("y1", size = 150_000), sf("y2", size = 150_000),
            ),
        )
        val hasher = FakeHasher(
            quick = listOf("x1", "x2", "x3", "y1", "y2").associate { uriOf(it) to "q" },
            full = mapOf(
                uriOf("x1") to "s", uriOf("x2") to "s", uriOf("x3") to "s",
                uriOf("y1") to "b", uriOf("y2") to "b",
            ),
        )
        val groups = DuplicateFinder(store, hasher).find()

        assertEquals(listOf(100_000L, 150_000L), groups.map { it.size })
        assertEquals(listOf(200_000L, 150_000L), groups.map { it.wastedBytes })
    }
}

class PrefsTest {
    @Test
    fun lightTierLeavesAudioOutByDefault() {
        val light = PROFILE.copy(tier = Tier.LIGHT, bulkOnlyWhenCharging = true)
        val prefs = Prefs.defaultFor(light)
        assertEquals(Kind.entries.toSet() - Kind.AUDIO, prefs.kinds)
        assertTrue(prefs.onlyWhileCharging)
    }

    @Test
    fun fullTierIndexesEveryKindByDefault() {
        assertEquals(Kind.entries.toSet(), Prefs.defaultFor(PROFILE).kinds)
    }

    @Test
    fun effectiveTurnsAudioAndVideoFramesOnFromKinds() {
        val base = PROFILE.copy(audio = false, videoFrameEverySec = 0, bulkOnlyWhenCharging = false)

        val photos = Prefs(kinds = setOf(Kind.PHOTO), onlyWhileCharging = true).effective(base)
        assertFalse(photos.audio)
        assertEquals(0, photos.videoFrameEverySec)
        assertTrue(photos.bulkOnlyWhenCharging)

        val audio = Prefs(kinds = setOf(Kind.AUDIO)).effective(base)
        assertTrue(audio.audio)
        assertEquals(0, audio.videoFrameEverySec)

        val video = Prefs(kinds = setOf(Kind.VIDEO)).effective(base)
        assertTrue(video.audio)
        assertEquals(5, video.videoFrameEverySec)
    }

    @Test
    fun effectiveNeverAsksAModelWithoutAudioForAudio() {
        val m440 = PROFILE.copy(model = ModelVariant.M440)
        assertFalse(Prefs(kinds = setOf(Kind.AUDIO, Kind.VIDEO)).effective(m440).audio)
    }

    @Test
    fun corruptPrefsAreLogged() {
        val logged = mutableListOf<String>()
        val store = newStore(log = { m, _ -> logged += m })
        store.setMeta("prefs", "not json")
        assertEquals(Prefs(), store.prefs())
        assertEquals(1, logged.size)
    }

    @Test
    fun excludesMatchesTheFolderAndItsSubfoldersOnly() {
        val prefs = Prefs(excludedFolders = setOf("Download/Telegram"))
        assertTrue(prefs.excludes("Download/Telegram"))
        assertTrue(prefs.excludes("Download/Telegram/x"))
        assertFalse(prefs.excludes("Download/TelegramX"))
        assertFalse(prefs.excludes("Pictures"))
    }

    @Test
    fun prefsDefaultWhenMissingOrCorrupt() {
        val store = newLibraryStore()
        assertFalse(store.hasPrefs())
        assertEquals(Prefs(), store.prefs())
        store.setMeta("prefs", "not json")
        assertEquals(Prefs(), store.prefs())
    }

    @Test
    fun prefsRoundTripThroughTheStore() {
        val store = newLibraryStore()
        val prefs = Prefs(kinds = setOf(Kind.PHOTO), onlyWhileCharging = true, excludedFolders = setOf("Download/Telegram"))
        store.updatePrefs { prefs }
        assertTrue(store.hasPrefs())
        assertEquals(prefs, store.prefs())
    }
}

class ProductStoreTest {
    @Test
    fun filesSortsByNewestLargestAndNameAndPages() {
        val store = newLibraryStore()
        store.upsertFiles(
            listOf(
                sf("a", size = 50, modifiedAt = 1, name = "b.txt"),
                sf("b", size = 30, modifiedAt = 3, name = "A.txt"),
                sf("c", size = 20, modifiedAt = 2, name = "c.txt"),
            ),
        )
        assertEquals(listOf("A.txt", "c.txt", "b.txt"), names(store.files(sort = Store.Sort.NEWEST)))
        assertEquals(listOf("b.txt", "A.txt", "c.txt"), names(store.files(sort = Store.Sort.LARGEST)))
        assertEquals(listOf("A.txt", "b.txt", "c.txt"), names(store.files(sort = Store.Sort.NAME)))
        assertEquals(listOf("A.txt", "c.txt"), names(store.files(sort = Store.Sort.NEWEST, limit = 2, offset = 0)))
        assertEquals(listOf("b.txt"), names(store.files(sort = Store.Sort.NEWEST, limit = 2, offset = 2)))
    }

    @Test
    fun filesFiltersByKindAndFolderAndFoldersCountsFiles() {
        val store = newLibraryStore()
        store.upsertFiles(
            listOf(
                sf("p1", kind = Kind.PHOTO, folder = "Pictures", modifiedAt = 1),
                sf("d1", kind = Kind.PDF, folder = "Docs", modifiedAt = 2),
                sf("p2", kind = Kind.PHOTO, folder = "Pictures", modifiedAt = 3),
            ),
        )
        assertEquals(listOf("p2", "p1"), names(store.files(kind = Kind.PHOTO)))
        assertEquals(listOf("d1"), names(store.files(folder = "Docs")))
        assertEquals(listOf("Pictures" to 2L, "Docs" to 1L), store.folders())
    }

    @Test
    fun sizeCollisionsGroupsEqualSizesAboveTheMinimum() {
        val store = newLibraryStore()
        store.upsertFiles(
            listOf(
                sf("x1", size = 200), sf("x2", size = 200), sf("x3", size = 200),
                sf("y1", size = 100), sf("y2", size = 100),
                sf("z1", size = 50), sf("z2", size = 50),
                sf("u", size = 300),
            ),
        )
        val groups = store.sizeCollisions(minSize = 60)
        assertEquals(listOf(200L, 100L), groups.map { it.first().file.size })
        assertEquals(listOf(3, 2), groups.map { it.size })
    }

    @Test
    fun requeueMakesOnlyTheChosenKindsPendingAgain() {
        val store = newLibraryStore()
        store.upsertFiles(listOf(sf("doc", kind = Kind.DOC), sf("pdf", kind = Kind.PDF)))
        for (f in store.pending(10)) {
            store.replaceChunks(f.id, listOf(Store.NewChunk(0, "", "text", vecOf(SIG))))
        }
        assertEquals(2L, store.countsByState()[FileState.INDEXED])

        store.requeue(setOf(Kind.DOC))

        assertEquals(listOf("doc"), names(store.pending(10)))
        assertEquals(1L, store.countsByState()[FileState.PENDING])
    }

    @Test
    fun kindsOffSkipsExtractionButStillStoresTheName() = runTest {
        val store = newLibraryStore()
        store.upsertFiles(listOf(sf("doc", kind = Kind.DOC)))
        val extractor = countingExtractor()
        IndexEngine(store, extractor, FakeEmbedder(), FakeMonitor(), settingsOf(setOf(Kind.PDF))).batch()

        assertTrue(extractor.names.isEmpty(), "a DOC is not extracted when only PDF is on")
        assertEquals(1L, store.chunkCount())
        assertEquals(1L, store.countsByState()[FileState.INDEXED])
    }

    @Test
    fun kindsAreReadPerFileSoASwitchTurnedOnMidBatchApplies() = runTest {
        val store = newLibraryStore()
        store.upsertFiles(listOf(sf("old", modifiedAt = 1), sf("new", modifiedAt = 2)))
        val extractor = countingExtractor()
        var kinds = setOf(Kind.PDF)
        IndexEngine(
            store, extractor, FakeEmbedder(), FakeMonitor(), { IndexSettings(PROFILE, kinds) },
            onFile = { kinds = Kind.entries.toSet() },
        ).batch()

        assertEquals(listOf("old"), extractor.names, "the file after the switch is read inside")
    }

    @Test
    fun aClosedEmbedderStopsTheBatchWithoutChargingTheFile() = runTest {
        val store = newLibraryStore()
        store.upsertFiles(listOf(sf("a"), sf("b")))
        val closed = object : Embedder {
            override val signature = SIG
            override val backend = Backend.CPU
            override suspend fun embedRaw(parts: List<Part>): FloatArray = throw EmbedderClosedException()
            override fun close() {}
        }
        assertFailsWith<EmbedderClosedException> {
            IndexEngine(store, FakeExtractor(), closed, FakeMonitor(), settingsOf()).batch()
        }
        assertEquals(2L, store.countsByState()[FileState.PENDING])
        assertEquals(listOf(0, 0), store.pending(10).map { it.attempts })
    }

    @Test
    fun replaceChunksForARemovedFileWritesNothing() {
        val store = newLibraryStore()
        store.upsertFiles(listOf(sf("gone")))
        val id = store.pending(1).single().id
        store.removeFiles(listOf(uriOf("gone")))

        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "text", vecOf(SIG))))

        assertEquals(0L, store.chunkCount())
        assertTrue(store.keywordSearch("text", 10).isEmpty())
    }

    @Test
    fun keywordSearchUsesAPrefixOnlyFromThreeLetters() {
        val store = newLibraryStore()
        store.upsertFiles(listOf(sf("f")))
        store.replaceChunks(store.pending(1).single().id, listOf(Store.NewChunk(0, "", "apple pie", vecOf(SIG))))

        assertTrue(store.keywordSearch("a", 10).isEmpty())
        assertTrue(store.keywordSearch("ap", 10).isEmpty())
        assertEquals(1, store.keywordSearch("app", 10).size)
        assertEquals(1, store.keywordSearch("pie", 10).size)
    }

    @Test
    fun pausedRoundTripsThroughPrefs() {
        val store = newLibraryStore()
        assertFalse(store.prefs().paused)
        store.updatePrefs { it.copy(paused = true) }
        assertTrue(store.prefs().paused)
    }

    @Test
    fun profileRoundTripsAndIsNullWhenMissingOrCorrupt() {
        val store = newLibraryStore()
        assertNull(store.profile())
        store.setProfile(PROFILE)
        assertEquals(PROFILE, store.profile())
        store.setMeta("profile", "junk")
        assertNull(store.profile())
    }
}
