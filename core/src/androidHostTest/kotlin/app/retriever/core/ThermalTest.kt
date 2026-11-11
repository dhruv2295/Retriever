package app.retriever.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ThermalTest {
    private fun state(t: Thermal, headroom: Float? = null, pluggedIn: Boolean = true, screenOn: Boolean = true) =
        DeviceState(8.0, charging = pluggedIn, 90, t, screenOn = screenOn, headroom = headroom)

    /** Android's MODERATE leaves UX "not largely impacted": without a headroom, reading slows there and pauses only at SEVERE. */
    @Test
    fun withoutAHeadroomModerateSlowsAndSevereRests() {
        assertFalse(state(Thermal.MODERATE).hot)
        assertTrue(state(Thermal.MODERATE).warm)
        assertFalse(state(Thermal.LIGHT).warm)
        assertTrue(listOf(Thermal.SEVERE, Thermal.CRITICAL).all { state(it).hot && !state(it).warm })
    }

    /** The headroom forecasts throttling, so reading goes on through MODERATE until the forecast nears SEVERE (1.0). */
    @Test
    fun theHeadroomDecidesTheRestWhereThePhoneHasOne() {
        assertFalse(state(Thermal.MODERATE, headroom = 0.9f).hot)
        assertTrue(state(Thermal.NONE, headroom = ThermalRest.AHEAD_STOPS_AT).warm)
        assertTrue(state(Thermal.NONE, headroom = ThermalRest.PAUSE_AT).hot)
        assertTrue(state(Thermal.SEVERE, headroom = 0.5f).hot, "SEVERE rests whatever the forecast")
    }

    @Test
    fun screenOffOnAChargerReadsOnUntilTheForecastReachesSevere() {
        assertFalse(state(Thermal.NONE, headroom = 0.97f, screenOn = false).hot)
        assertTrue(state(Thermal.NONE, headroom = 1f, screenOn = false).hot)
        assertTrue(state(Thermal.NONE, headroom = ThermalRest.PAUSE_AT, pluggedIn = false, screenOn = false).hot, "on battery")
    }

    private class SlowEmbedder(private val inner: Embedder, private val ms: Long) : Embedder by inner {
        override suspend fun embedRaw(parts: List<Part>): FloatArray {
            Thread.sleep(ms)
            return inner.embedRaw(parts)
        }
    }

    /** Warm, each file and each later part is followed by a rest as long as it took (capped), never past the budget. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aWarmFileIsFollowedByARestAsLongAsItTook() = runTest {
        val files = listOf("a.txt", "b.txt").mapIndexed { i, n -> testFile(n, modifiedAt = 10L - i) }
        var aTakesMs = 300L
        val extractor = FakeExtractor(files.associate { it.name to listOf(textPiece(it.name)) }) { if (it.name == "a.txt") Thread.sleep(aTakesMs) }
        fun engine() = IndexEngine(newStore().apply { upsertFiles(files) }, extractor, FakeEmbedder(), FakeMonitor(state(Thermal.MODERATE)), settingsOf())

        assertEquals(BatchResult(2, 0, StopReason.DONE), engine().runBatch(1.minutes))
        val clock = testScheduler
        assertTrue(clock.currentTime in aTakesMs until ThermalRest.WARM_REST_MAX.inWholeMilliseconds, "rested ${clock.currentTime}ms")

        aTakesMs = 1_500
        val before = clock.currentTime
        assertEquals(StopReason.BUDGET, engine().runBatch(1.seconds).stopReason)
        assertEquals(before, clock.currentTime, "no rest past the budget")

        // A PDF's third page waits for the deepen pass: two embeds of 200 ms rest 400 ms, then its tail's 200 ms rests too.
        val pdf = testFile("long.pdf", Kind.PDF)
        val pages = FakeExtractor(mapOf(pdf.name to (1..3).map { textPiece("page $it") }))
        val start = clock.currentTime
        IndexEngine(newStore().apply { upsertFiles(listOf(pdf)) }, pages, SlowEmbedder(FakeEmbedder(), 200), FakeMonitor(state(Thermal.MODERATE)), settingsOf())
            .runBatch(1.minutes, deepen = true)
        assertTrue(clock.currentTime - start >= 600, "rested ${clock.currentTime - start}ms")
    }

    @Test
    fun readAheadNarrowsAsThePhoneWarms() {
        val table = listOf(
            state(Thermal.NONE, 0.5f) to 2,
            state(Thermal.NONE, 0.75f) to 1,
            state(Thermal.LIGHT, 0.5f) to 1,
            state(Thermal.NONE, 0.87f) to 0,
            state(Thermal.MODERATE, 0.6f) to 0,
            state(Thermal.NONE, 0.96f) to 0,
            state(Thermal.NONE) to 2,
            state(Thermal.LIGHT) to 1,
            state(Thermal.MODERATE) to 0,
        )
        // Screen off: with it on and Retriever hidden, another app is in use and nothing is read ahead.
        for ((s, depth) in table) assertEquals(depth, IndexEngine.readAheadDepth(s.copy(screenOn = false), visible = false), "$s")
    }

    @Test
    fun readAheadIsTwoOnlyHiddenOnACharger() {
        val off = state(Thermal.NONE, 0.2f, screenOn = false)
        assertEquals(2, IndexEngine.readAheadDepth(off, visible = false))
        assertEquals(1, IndexEngine.readAheadDepth(off.copy(charging = false, pluggedIn = false), visible = false))
        assertEquals(1, IndexEngine.readAheadDepth(off.copy(ramGb = 4.0), visible = false), "under 6 GB: one file's pages at a time")
        assertEquals(2, IndexEngine.readAheadDepth(off.copy(ramGb = 6.0), visible = false))
        assertEquals(0, IndexEngine.readAheadDepth(off, visible = false, lowMemory = true), "the system says memory is low")
    }

    @Test
    fun inFrontReadAheadIsOneOnlyWhileUntouched() {
        assertEquals(0, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f), visible = true), "in use: its frames come first")
        assertEquals(1, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f), visible = true, idle = true), "untouched on a charger")
        assertEquals(0, IndexEngine.readAheadDepth(state(Thermal.MODERATE), visible = true, idle = true), "too warm for any")
        assertEquals(0, IndexEngine.readAheadDepth(state(Thermal.NONE, 0.2f), visible = true, lowMemory = true, idle = true), "the system says memory is low")
    }

    @Test
    fun readAheadYieldsToAnotherAppEvenOnTheCharger() {
        for (plugged in listOf(false, true)) {
            val otherApp = state(Thermal.NONE, 0.2f, pluggedIn = plugged).copy(screenOn = true)
            assertEquals(0, IndexEngine.readAheadDepth(otherApp, visible = false))
            assertEquals(0, IndexEngine.readAheadDepth(otherApp, visible = false, idle = true))
        }
    }

    @Test
    fun aRestEndsOnlyBelowWhereReadAheadStops() {
        assertFalse(state(Thermal.NONE, 0.9f).cooled, "under the pause line, but the next file would land back on it")
        assertTrue(state(Thermal.NONE, 0.8f).cooled)
        assertTrue(state(Thermal.LIGHT).cooled, "iOS fair")
        assertFalse(state(Thermal.SEVERE).cooled, "iOS serious")
    }

    @Test
    fun theWorkManagerRecheckIsShortNotAFixedQuarterHour() {
        assertEquals(2.minutes, ThermalRest.RECHECK)
    }
}
