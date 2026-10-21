package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The setup machine on the real facade: what it persists, what planOrCommit does, and Try's search scope. */
class SetupFlowTest {
    private val files = (1..30).map { testFile("doc$it.txt", Kind.DOC, modifiedAt = it * 3_600_000L) }

    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    private suspend fun Retriever.inputs(): SetupInputs {
        refreshSetUp()
        return setup.value!!.withAccess(true)
    }

    @Test
    fun everyAnswerSurvivesTheProcessAndThePlanIsMadeOnce() = test {
        val store = newStore()
        fun process() = testRetriever(store, files, FakeExtractor(), modelReady = { false })
        assertNull(process().setup.value, "nothing until loaded")
        assertEquals(SetupInputs(accessGiven = true), process().inputs(), "a new install starts at the welcome, with a plan still to make")

        process().setSetupMark(SetupMark.ACCESS_CHOSEN)
        assertEquals(SetupStep.PLAN, process().inputs().step)

        process().planOrCommit()
        val planned = process().inputs()
        assertTrue(planned.planned > 0, planned.toString())
        assertEquals(SetupStage.SAMPLE to SetupStep.READ_SAMPLE, planned.stage to planned.step)
        val plan = store.tasteIds()
        process().planOrCommit()
        assertEquals(plan, store.tasteIds(), "a plan already made is kept, never redrawn")

        process().setSetupMark(SetupMark.CHOOSING)
        assertEquals(SetupStage.CHOOSE, process().inputs().stage)
        process().decideLater()
        assertEquals(SetupStage.DONE to SetupStep.SCHEDULE, process().inputs().let { it.stage to it.step })
        process().planOrCommit()
        assertEquals(plan, store.tasteIds(), "a choice taken is never replanned")
    }

    /** A mark this version dropped (STARTED, before the welcome held the access ask) reads as NONE, never as an old install. */
    @Test
    fun aDroppedMarkReadsAsTheWelcome() = test {
        val store = newStore().also { s -> s.updatePrefs { it.copy(setupStarted = true) } }
        store.setMeta(SETUP_MARK_KEY, "STARTED")
        val inputs = testRetriever(store, files, FakeExtractor()).inputs()
        assertEquals(SetupInputs(accessGiven = true), inputs)
        assertEquals(SetupStage.WELCOME, inputs.stage)
    }

    /** The Try screen is gone: a person who left the app on it comes back to the demo, not the welcome. */
    @Test
    fun aSavedTryingResumesTheDemo() = test {
        val store = newStore()
        val r = testRetriever(store, files, FakeExtractor())
        r.setSetupMark(SetupMark.ACCESS_CHOSEN)
        r.planOrCommit()
        store.setMeta(SETUP_MARK_KEY, "TRYING")
        val back = testRetriever(store, files, FakeExtractor()).inputs()
        assertEquals(SetupStage.SAMPLE to SetupStep.READ_SAMPLE, back.stage to back.step)
    }

    @Test
    fun skipTheDemoLeadsToTheChoiceAndBackToTheWelcome() = test {
        val r = testRetriever(newStore(), files, FakeExtractor(), modelReady = { false })
        r.setSetupMark(SetupMark.ACCESS_CHOSEN)
        r.planOrCommit()
        r.skipFirstRun()
        val left = r.inputs()
        assertTrue(left.skipped)
        assertTrue(left.showSetup, "setup shows until a choice is made")
        assertEquals(SetupStage.CHOOSE, left.stage)
        assertFalse(r.hasPendingTaste(), "a skipped demo doesn't run in front")

        r.setSetupMark(SetupPolicy.back(left.stage, left.skipped)!!)
        val welcome = r.inputs()
        assertEquals(SetupStage.WELCOME to false, welcome.stage to welcome.skipped)
        r.setSetupMark(SetupMark.ACCESS_CHOSEN)
        assertEquals(SetupStage.SAMPLE, r.inputs().stage, "Start the demo after Back runs the demo")
    }

    @Test
    fun nothingToReadReadsEverythingAsFilesArrive() = test {
        val r = testRetriever(newStore(), emptyList(), FakeExtractor(), modelReady = { false })
        r.setSetupMark(SetupMark.ACCESS_CHOSEN)
        r.planOrCommit()
        assertEquals(SetupStage.DONE to SetupStep.SCHEDULE, r.inputs().let { it.stage to it.step })
        assertNull(r.scopeIds.value)
    }

    @Test
    fun anInstallSetUpBeforeThisFlowIsDone() = test {
        val started = newStore().also { s -> s.updatePrefs { it.copy(setupStarted = true) } }
        val tuned = newStore().also { it.setProfile(PROFILE) }
        // Too little memory to tune, from before setupStarted: only its saved prefs say it was set up.
        val textOnly = newStore().also { s -> s.updatePrefs { it.copy(allowMobileData = true) } }
        val small = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 3.0, false)
        for ((store, probe) in listOf(started to PIXEL, tuned to PIXEL, textOnly to small)) {
            val inputs = testRetriever(store, files, FakeExtractor(), probe = probe).inputs()
            assertEquals(SetupStage.DONE to SetupStep.SCHEDULE, inputs.stage to inputs.step)
            assertFalse(inputs.showSetup)
        }
        val fresh = testRetriever(newStore(), files, FakeExtractor(), probe = small).inputs()
        assertEquals(SetupStage.WELCOME, fresh.stage, "a new text-only install still starts at the welcome")
    }

    @Test
    fun aDownloadedModelAddsNoMeaningBeforeTheTune() = test {
        val store = newStore()
        val r = testRetriever(store, files, FakeExtractor(files.associate { it.name to listOf(textPiece("words of ${it.name}")) }))
        r.refreshSetUp()
        assertEquals(false, r.setUp.value)
        r.commitScope()
        r.indexBatch(1.minutes)
        assertEquals(0L, store.countsByState()[FileState.PENDING] ?: 0L, "the words are read without the model")
        assertEquals(0, store.vectorCount(), "an untuned model would pick its own vision tokens")
    }

    @Test
    fun tryFindsEveryNameButOnlyTheSamplesWords() = test {
        val sample = testFile("trip.txt")
        val named = testFile("lighthouse.txt")
        val outside = testFile("notes.txt")
        val store = newStore(sig = null)
        val r = testRetriever(store, listOf(sample, named, outside), FakeExtractor(), modelReady = { false })
        store.upsertFiles(listOf(sample, named, outside))
        for (f in listOf(sample, outside)) {
            store.replaceChunks(store.fileByUri(f.uri)!!.id, listOf(Store.NewChunk(1, "", "the old lighthouse at dusk", null)))
        }
        val scope = listOf(store.fileByUri(sample.uri)!!.id)

        assertEquals(setOf("trip.txt", "lighthouse.txt"), r.search("lighthouse", scope = scope).hits.map { it.file.name }.toSet())
        assertEquals(listOf("lighthouse.txt"), r.searchNames("lighthouse", scope = scope).hits.map { it.file.name })
        assertEquals(setOf("trip.txt", "lighthouse.txt", "notes.txt"), r.search("lighthouse").hits.map { it.file.name }.toSet())
        assertEquals(listOf("lighthouse.txt"), r.search("lighthouse", scope = emptyList()).hits.map { it.file.name }, "an empty sample still finds names")
    }

    @Test
    fun searchingOnceOpensTheUpgradeOfferUntilDismissed() = test {
        val store = newStore()
        store.updatePrefs { it.copy(rememberSearches = false) }
        val r = testRetriever(store, files, FakeExtractor()).apply { refreshSetUp() }
        assertFalse(r.prefs.value.searchTried)
        r.recordQuery("receipts")
        assertTrue(r.prefs.value.searchTried, "searching counts even when searches aren't remembered")
        assertEquals(emptyList(), r.recentQueries())
        r.dismissUpgradeOffer()
        assertTrue(store.prefs().upgradeOfferDismissed)
    }

    @Test
    fun totalsGroupInIndexOrderWithoutSorting() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { IndexDb.Schema.create(it) }
        fun plan(sql: String): List<String> = driver.executeQuery(null, "EXPLAIN QUERY PLAN $sql", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(3)!!) })
        }, 0).value
        val sums = "COUNT(*), SUM(CASE WHEN size > 0 THEN size END), SUM(size <= 0)"
        for (column in listOf("kind", "folder")) {
            val steps = plan("SELECT $column, $sums FROM files GROUP BY $column")
            assertTrue(steps.any { "USING INDEX" in it } && steps.none { "TEMP B-TREE FOR GROUP BY" in it }, steps.toString())
        }
    }
}
