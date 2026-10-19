package app.retriever.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The first run's short read: only the plan is read, everything stays findable by name, and commit reads the rest. */
class TasteScopeTest {
    private val hour = 3_600_000L
    private val docs = (1..20).map { testFile("doc$it.txt", Kind.DOC, modifiedAt = it * 2 * hour) }
    private val pdfs = (1..20).map { testFile("pdf$it.pdf", Kind.PDF, modifiedAt = it * 2 * hour) }
    private val shots = (1..20).map { testFile("shot$it.png", Kind.SCREENSHOT, modifiedAt = it * 2 * hour) }
    private val files = docs + pdfs + shots

    // Docs run past the head chunks, so planned ones leave tails for the deepen pass.
    private val pieces = docs.associate { d -> d.name to (1..IndexEngine.HEAD_TEXT_CHUNKS + 2).map { textPiece("page $it of ${d.name}") } } +
        (pdfs + shots).associate { it.name to listOf(textPiece("words of ${it.name}")) }

    private val sig = Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens)

    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    @Test
    fun aShortReadReadsOnlyThePlanThenCommitReadsTheRestOnce() = test {
        val store = newStore(sig) // no profile yet: the tune comes after the plan
        val extractor = FakeExtractor(pieces)
        val r = testRetriever(store, files, extractor).apply { uiVisible = true }

        val plan = r.planTaste(5.seconds)
        assertFalse(plan.everything)
        assertTrue(plan.ids.size in 3 until files.size)
        assertFalse(store.hasPrefs(), "the plan lives in meta, not Prefs")
        store.setProfile(PROFILE) // the tune
        r.refreshSetUp()

        assertEquals(NextRun.DONE, r.taste(), "an empty plan stops, it doesn't ask for more")
        val planned = plan.ids.map { store.fileByUri(it)!! }
        assertTrue(planned.all { it.state == FileState.INDEXED })
        assertEquals(plan.ids.toSet(), extractor.names.map { n -> files.first { it.name == n }.uri }.toSet(), "only planned files are read")
        val waiting = (files.size - plan.ids.size).toLong()
        assertEquals(waiting, store.countsByState()[FileState.PENDING])

        val ids = planned.map { it.id }
        assertTrue(planned.any { it.file.kind == Kind.DOC })
        assertEquals(emptyList(), store.tailChunksIn(ids), "planned tails deepen after the heads")

        val other = files.first { it.uri !in plan.ids }
        assertTrue(other.name in r.searchNames(other.name).hits.map { it.file.name }, "unplanned files are found by name")

        val c = r.counts().first()
        assertEquals(waiting, c.waiting)
        assertEquals(0L, c.byState[FileState.PENDING], "caught up on what was asked")
        assertFalse(r.tasteDecided())

        assertEquals(NextRun.DONE, r.taste())
        assertEquals(plan.ids.size, extractor.names.size, "a finished plan reads nothing more")

        r.commit(whileCharging = true)
        assertTrue(store.prefs().onlyWhileCharging)
        assertTrue(r.tasteDecided())
        r.indexBatch(1.minutes)
        assertEquals(0L, store.countsByState()[FileState.PENDING] ?: 0L)
        assertEquals(files.size, extractor.names.size)
        assertEquals(files.size, extractor.names.toSet().size, "no planned file is read twice")
        assertEquals(0L, r.counts().first().waiting)
    }

    @Test
    fun foregroundTrialResumesWithOnlyTailsAfterABatchBoundary() = test {
        val store = newStore(sig).also { it.setProfile(PROFILE) }
        val planned = docs.first()
        val outside = docs.last()
        store.upsertFiles(listOf(planned, outside))
        val id = store.fileByUri(planned.uri)!!.id
        store.setTaste(listOf(id))
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", "Later content", null)))
        val factory = FakeFactory()
        val r = testRetriever(
            store,
            listOf(planned, outside),
            FakeExtractor(),
            factory = factory,
            monitor = FakeMonitor(DeviceState(8.0, false, 80, Thermal.NONE, screenOn = true)),
        ).apply { uiVisible = true }
        r.refreshSetUp()
        assertTrue(r.hasPendingTaste(), "a searchable head does not finish the trial's work")
        assertEquals(NextRun.SOON, r.indexBatch(0.seconds))
        assertTrue(r.hasPendingTaste(), "the next foreground batch must resume tails")
        var sawReadingFile = false
        factory.beforeEmbed = { if (r.state.value.currentFile == planned) sawReadingFile = true }
        // The continued-reading caller's heads-only preference must not strand an undecided foreground trial.
        assertEquals(NextRun.DONE, r.indexBatch(120.seconds, deepen = false))
        assertTrue(sawReadingFile, "deepening reports its own file to Reading now")
        assertFalse(r.hasPendingTaste())
        assertTrue(store.tailChunksIn(listOf(id)).isEmpty())
        assertEquals(FileState.PENDING, store.fileByUri(outside.uri)!!.state)
        assertEquals(1L, r.counts().first().progress.contentRead)
        r.release()
    }

    @Test
    fun pendingCountsPerKindIncludeOnlyTheRemainingPlan() = test {
        val store = newStore(sig)
        val r = testRetriever(store, files, FakeExtractor())
        r.planTaste(5.seconds)
        val ids = store.tasteIds()
        store.replaceChunks(ids.first(), emptyList())
        val expected = store.pendingIn(ids).groupingBy { it.file.kind }.eachCount()
        val counts = r.counts().first()
        for (kind in Kind.entries) {
            assertEquals(expected[kind]?.toLong() ?: 0L, counts.byKindState[kind to FileState.PENDING])
        }
        assertEquals(expected.values.sum().toLong(), counts.byState[FileState.PENDING])
        assertEquals(1L, counts.byKindState[store.file(ids.first())!!.kind to FileState.INDEXED])
        assertEquals(files.size.toLong(), counts.byKind.values.sum())
    }

    @Test
    fun noScopeKeyReadsAsAll() = test {
        val store = newStore(sig).also { it.setProfile(PROFILE) }
        val extractor = FakeExtractor(pieces)
        val r = testRetriever(store, files, extractor).apply { uiVisible = true }
        r.refreshSetUp()
        assertEquals(Scope.ALL, store.tasteScope())
        assertTrue(r.tasteDecided(), "a returning user never sees the first run")
        r.indexBatch(1.minutes)
        assertEquals(files.size, extractor.names.size)
        assertEquals(0L, r.counts().first().waiting)
    }

    @Test
    fun notYetKeepsTheScope() = test {
        val store = newStore(sig)
        val r = testRetriever(store, files, FakeExtractor(pieces))
        r.planTaste(5.seconds)
        r.decideLater()
        assertTrue(r.tasteDecided())
        assertEquals(Scope.SAMPLE, store.tasteScope())
    }

    /** The regression the meta key exists to prevent: a plan made before the tune must not cost a LIGHT phone its defaults. */
    @Test
    fun aPlanBeforeTheTuneKeepsTheLightDefaults() = test {
        val store = newStore()
        val r = testRetriever(store, files, FakeExtractor(), probe = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 4.0, false))
        r.planTaste()
        assertFalse(store.hasPrefs())
        assertEquals(Tier.LIGHT, r.calibrate(Samples(listOf(ByteArray(10)), listOf("hello world"), emptyList()), tuneAgain = false).profile.tier)
        assertTrue(store.prefs().onlyWhileCharging)
        assertFalse(Kind.AUDIO in store.prefs().kinds)
    }

    @Test
    fun celebratedOnlyOnceMarked() = test {
        val r = testRetriever(newStore(), emptyList(), FakeExtractor())
        assertFalse(r.celebrated())
        r.markCelebrated()
        assertTrue(r.celebrated())
    }
}
