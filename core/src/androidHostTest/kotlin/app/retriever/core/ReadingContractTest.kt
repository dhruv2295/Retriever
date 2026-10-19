package app.retriever.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ReadingContractTest {
    @Test fun keywordReadingCountsAsReadAndWaitingForMeaningIsSeparate() {
        val store = newStore()
        val files = listOf(testFile("a.txt"), testFile("b.jpg", Kind.PHOTO), testFile("c.jpg", Kind.PHOTO), testFile("d.txt"))
        store.upsertFiles(files)
        val (a, b, c, d) = files.map { store.fileByUri(it.uri)!!.id }
        val vector = Vector.of(FloatArray(DIMS) { 1f }, SIG)
        store.replaceChunks(a, listOf(Store.NewChunk(1, "", "words", null)))
        store.replaceChunks(b, listOf(Store.NewChunk(1, "img", null, null, labels = "beach", visual = true)))
        store.replaceChunks(c, listOf(Store.NewChunk(1, "img", null, null, visual = true)))
        store.replaceChunks(d, listOf(Store.NewChunk(1, "", "head", vector), Store.NewChunk(2, "", "tail", null)))
        val kinds = setOf(Kind.DOC, Kind.PHOTO)

        val keyword = store.countsNow(includeTails = false, kinds = kinds).progress
        assertEquals(ReadingProgress(read = 3, nameOnly = 1), keyword, "text and labels are read without a model")
        val model = store.countsNow(kinds = kinds).progress
        assertEquals(ReadingProgress(read = 2, partlyRead = 1, nameOnly = 1, laterPasses = 1, meaningWaiting = 3), model)
        assertEquals(keyword.contentRead, model.contentRead, "the meaning upgrade never moves files out of C = F + P")

        store.setTailAttempts(store.tailChunksIn(listOf(a)).single().id, 3)
        val exhausted = store.countsNow(kinds = kinds).progress
        assertEquals(ReadingProgress(read = 1, partlyRead = 2, nameOnly = 1, laterPasses = 1, meaningWaiting = 2), exhausted)
    }

    @Test fun realOutcomesPartitionMetadataFailuresTailsAndDisabledKinds() {
        val store = newStore()
        val files = (1..9).map { testFile("$it.txt", if (it == 9) Kind.AUDIO else Kind.DOC) }
        store.upsertFiles(files)
        val ids = files.map { store.fileByUri(it.uri)!!.id }
        val vector = Vector.of(FloatArray(DIMS) { 1f }, SIG)
        store.replaceChunks(ids[0], listOf(Store.NewChunk(1, "", "words", vector)))
        // A long file whose tail still waits for a vector beside its embedded head.
        store.replaceChunks(ids[1], listOf(Store.NewChunk(1, "", "head", vector), Store.NewChunk(2, "", "words", null)))
        store.replaceChunks(ids[2], listOf(Store.NewChunk(1, "", "words", vector)), partial = true)
        store.replaceChunks(ids[3], listOf(Store.NewChunk(0, "", null, vector)))
        store.replaceChunks(ids[4], emptyList())
        store.markFailed(ids[5], 3)
        val c = store.countsNow(ids.take(7), kinds = setOf(Kind.DOC)).progress
        assertEquals(ReadingProgress(1, 2, 1, 1, 3, 1, 1, true), c)
        assertEquals(9, c.libraryTotal)
        assertEquals(3, c.contentRead)
        store.setTailAttempts(store.tailChunks(10).single().id, 3)
        assertEquals(ReadExtent.MEANING_LIMITED, store.readExtents(listOf(ids[1]))[ids[1]])
        val exhausted = store.countsNow().progress
        assertEquals(2, exhausted.partlyRead)
        assertEquals(0, exhausted.laterPasses)
        store.upsertFiles(listOf(files[0].copy(modifiedAt = 2)))
        assertEquals(2, store.countsNow().progress.contentRead, "an old revision's content is gone immediately")
        store.removeFiles(listOf(files[2].uri))
        assertEquals(8, store.countsNow().progress.libraryTotal)
    }

    @Test fun defaultsMigrateWithoutChangingExistingChoicesOrRetuningThem() {
        val store = newStore()
        assertEquals(ReadingSchedule.CHARGING, store.prefs().automaticReading)
        store.setMeta("prefs", "{}")
        assertEquals(ReadingSchedule.BATTERY, store.prefs().automaticReading)
        store.setMeta("prefs", "{\"onlyWhileCharging\":true}")
        assertEquals(ReadingSchedule.CHARGING, store.prefs().automaticReading)
        store.updatePrefs { it.withSchedule(ReadingSchedule.MANUAL) }
        assertEquals(ReadingSchedule.MANUAL, store.prefs().retuned(PROFILE, PROFILE.copy(tier = Tier.LIGHT)).automaticReading)
    }

    @Test fun expiryPauseAndNotNowPersistAcrossFacadeHandoffs() = runBlocking {
        val file = testFile("words.txt")
        val store = newStore(Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens)).also { it.setProfile(PROFILE) }
        val monitor = FakeMonitor(DeviceState(8.0, false, 80, Thermal.NONE))
        fun facade() = testRetriever(store, listOf(file), FakeExtractor(mapOf(file.name to listOf(textPiece("words")))), monitor = monitor).apply {
            uiVisible =
                true
        }
        val first = facade()
        first.readNow()
        val until = store.prefs().readNowUntil
        val second = facade()
        second.refreshSetUp()
        assertEquals(until, second.prefs.value.readNowUntil)
        second.updatePrefs { it.copy(readNowUntil = 1) }
        assertEquals(NextRun.WHEN_CHARGING, second.indexBatch(1.minutes))
        assertEquals(0, store.prefs().readNowUntil)
        second.readNow()
        second.pause()
        assertEquals(0, store.prefs().readNowUntil)
        val third = facade()
        third.refreshSetUp()
        assertTrue(third.prefs.value.paused)
        third.resume()
        assertEquals(NextRun.WHEN_CHARGING, third.indexBatch(1.minutes))
        third.planTaste()
        third.decideLater()
        assertEquals(NextRun.DONE, third.taste())
        assertEquals(StopReason.PAUSED, third.state.value.lastStop)
        assertTrue(third.searchNames("words").hits.isNotEmpty(), "Pause keeps name updates alive")
    }

    @Test fun savingPowerEndsTheExplicitSessionEvenWhileVisible() = runBlocking {
        for (hot in listOf(false, true)) {
            val store = newStore().also { it.setProfile(PROFILE) }
            val monitor = FakeMonitor(DeviceState(8.0, true, 80, if (hot) Thermal.SEVERE else Thermal.NONE, powerSave = !hot))
            val r = testRetriever(store, listOf(testFile("a.txt")), FakeExtractor(), monitor = monitor).apply { uiVisible = true }
            r.readNow()
            r.indexBatch(1.minutes)
            assertEquals(0, store.prefs().readNowUntil)
            assertEquals(if (hot) StopReason.HOT else StopReason.POWER_SAVE, r.state.value.lastStop)
        }
    }

    @Test fun failedSampleNeverUnlocksOrSendsReadyAndNoticeIsClaimedOnce() = runBlocking {
        val store = newStore()
        val file = testFile("a.txt")
        store.upsertFiles(listOf(file))
        val id = store.fileByUri(file.uri)!!.id
        store.setTaste(listOf(id))
        val r = testRetriever(store, listOf(file), FakeExtractor())
        store.markFailed(id, 3)
        assertFalse(r.tasteProgress().first().readyToTry)
        assertFalse(r.claimSampleNotification())
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", "read content", null)))
        assertTrue(r.claimSampleNotification())
        assertFalse(testRetriever(store, listOf(file), FakeExtractor()).claimSampleNotification())
    }

    @Test fun noAccessDropsTheCountButRetainsPreferences() = runBlocking {
        val store = newStore()
        val file = testFile("private.txt")
        store.upsertFiles(listOf(file))
        store.replaceChunks(store.fileByUri(file.uri)!!.id, listOf(Store.NewChunk(1, "", "content", null)))
        val r = testRetriever(store, listOf(file), FakeExtractor())
        r.pause()
        r.clearUnavailableLibrary()
        assertEquals(0, r.counts().first().progress.libraryTotal)
        assertTrue(r.prefs.value.paused)
        assertTrue(r.searchNames("private").hits.isEmpty())
    }

    @Test fun aCapAndQueuedMeaningWorkRemainSeparateFacts() {
        val store = newStore()
        val file = testFile("long.txt")
        store.upsertFiles(listOf(file))
        val id = store.fileByUri(file.uri)!!.id
        val head = Store.NewChunk(1, "", "head", Vector.of(FloatArray(DIMS) { 1f }, SIG))
        store.replaceChunks(id, listOf(head, Store.NewChunk(2, "", "content", null)), partial = true)
        assertEquals(ReadExtent.FIRST_PART_DEEPENING, store.readExtents(listOf(id))[id])
        assertEquals(1, store.countsNow().progress.partlyRead)
        assertEquals(1, store.countsNow().progress.laterPasses)
        // Read without the model: the cap stays Partly read and the vectors wait for the meaning upgrade.
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", "content", null)), partial = true)
        assertEquals(ReadingProgress(partlyRead = 1, meaningWaiting = 1), store.countsNow().progress)
    }

    @Test fun aFailedRereadWithUsableContentStaysPartial() {
        val store = newStore()
        val file = testFile("again.txt")
        store.upsertFiles(listOf(file))
        val id = store.fileByUri(file.uri)!!.id
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", "content", Vector.of(FloatArray(DIMS) { 1f }, SIG))))
        store.markFailed(id, 1)
        assertTrue(store.countsNow().progress.unfinished, "a queued reread must prevent completion even with usable content")
        assertEquals(1, store.countsNow().progress.rereads)
        store.markFailed(id, 3)
        val progress = store.countsNow().progress
        assertEquals(1, progress.partlyRead)
        assertEquals(0, progress.read)
        assertEquals(0, progress.couldntRead)
        assertTrue(store.skipped(50).isEmpty(), "usable content is not in the Couldn’t read disclosure")
        assertEquals(1, progress.contentRead)
    }

    @Test fun pendingFilesCannotStarveQueuedMeaningWork() = runBlocking {
        val store = newStore()
        val files = (0..10).map { testFile("$it.txt") }
        store.upsertFiles(files)
        val first = store.fileByUri(files[0].uri)!!.id
        store.replaceChunks(first, listOf(Store.NewChunk(1, "", "earlier words", null)))
        var heads = 0
        var served = false
        val engine = IndexEngine(
            store,
            FakeExtractor(files.associate { it.name to listOf(textPiece("new words")) }),
            FakeEmbedder(),
            FakeMonitor(),
            settingsOf(),
            onFile = { if (++heads == 9) served = store.tailChunkCount() == 0L },
        )
        engine.runBatch(1.minutes, requireCharging = false, deepen = true)
        assertTrue(served, "a tail opportunity is reserved before all first passes finish")
    }

    @Test fun aLargeSampleCountsEachRevisionOnceAcrossSqlParameterBatches() {
        val store = newStore()
        store.upsertFiles((1..1501).map { testFile("$it.txt") })
        val ids = store.pending(1001).map { it.id }
        val counts = store.countsNow(ids)
        assertEquals(1501, counts.progress.libraryTotal)
        assertEquals(1001, counts.progress.scheduled)
        assertEquals(500, counts.progress.notScheduled)
        assertEquals(1001, counts.sampleProgress?.libraryTotal)
    }

    @Test fun theSessionDeadlineCancelsSuspendedExtractionAtASafeBoundary() = runBlocking {
        val store = newStore(Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens)).also { it.setProfile(PROFILE) }
        val file = testFile("slow.pdf", Kind.PDF)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val r = testRetriever(store, listOf(file), extractor, monitor = FakeMonitor(DeviceState(8.0, false, 80, Thermal.NONE)))
        r.readNow()
        r.updatePrefs { it.copy(readNowUntil = System.currentTimeMillis() + 500) }
        val next = kotlinx.coroutines.withTimeout(3_000) { r.indexBatch(1.minutes) }
        assertTrue(entered.isCompleted)
        assertEquals(NextRun.WHEN_CHARGING, next)
        assertEquals(0, store.prefs().readNowUntil)
        assertEquals(0, store.fileByUri(file.uri)?.attempts)
        assertEquals(FileState.PENDING, store.fileByUri(file.uri)?.state)
    }

    @Test fun pauseLetsAnInFlightNameScanFinish() = runBlocking {
        val store = newStore().also { it.setProfile(PROFILE) }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val proceed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val file = testFile("names.txt")
        val source = object : FileSource {
            override suspend fun changes(since: String?): Changes {
                entered.complete(Unit)
                proceed.await()
                return Changes(listOf(file), emptyList(), "1", isFullScan = true)
            }
        }
        val hasher = object : ContentHasher {
            override suspend fun quickHash(uri: String, size: Long): String? = null
            override suspend fun fullHash(uri: String): String? = null
        }
        val r =
            Retriever(store, source, FakeExtractor(), FakeFactory(), FakeMonitor(), hasher, PIXEL, {
                true
            }, { true }, { _, _ -> }, System::currentTimeMillis, { 0L })
        val batch = async { r.indexBatch(1.minutes) }
        entered.await()
        r.pause()
        proceed.complete(Unit)
        assertEquals(NextRun.DONE, batch.await())
        assertEquals(1, store.fileCount())
        assertTrue(store.prefs().paused)
    }

    @Test fun sessionCountsSurviveHandoffsAndCountLaterPassesWithoutLibraryDeltas() = runBlocking {
        val store = newStore()
        val files = (1..4).map { testFile("$it.txt") }
        store.upsertFiles(files)
        val ids = files.map { store.fileByUri(it.uri)!!.id }
        val vector = Vector.of(FloatArray(DIMS) { 1f }, SIG)
        store.replaceChunks(ids[0], listOf(Store.NewChunk(1, "", "old content", vector)))
        store.replaceChunks(ids[1], listOf(Store.NewChunk(1, "", "a text tail", null)))
        val r = testRetriever(store, files, FakeExtractor())
        r.readNow()
        store.replaceChunks(ids[2], listOf(Store.NewChunk(1, "", "new content", vector)))
        store.replaceChunks(ids[3], listOf(Store.NewChunk(0, "", null, vector)))
        store.removeFiles(listOf(files[0].uri))
        assertEquals(1, r.counts().first().sessionRead)
        store.setChunkVector(store.tailChunks(1).single().id, vector)
        assertEquals(2, testRetriever(store, files, FakeExtractor()).counts().first().sessionRead)
        r.pause()
        store.replaceChunks(ids[3], listOf(Store.NewChunk(1, "", "later automatic content", vector)))
        assertEquals(2, r.counts().first().sessionRead, "a finished session is frozen")
        r.readNow()
        assertEquals(0, r.counts().first().sessionRead)
    }
}
