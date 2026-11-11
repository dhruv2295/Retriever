package app.retriever.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Free up space, then Download again: the same model and profile must not read the library again (critic #5). */
class RetuneTest {
    private val photos = (1..12).map { testFile("p$it.jpg", Kind.PHOTO, modifiedAt = it.toLong()) }
    private val docs = (1..4).map { testFile("d$it.txt", modifiedAt = it.toLong()) }
    private val videos = (1..2).map { testFile("v$it.mp4", Kind.VIDEO, modifiedAt = it.toLong()) }
    private val pieces = photos.associate { it.name to listOf(Piece("", listOf(Part.Image(ByteArray(it.name.length))), null)) } +
        (docs + videos).associate { it.name to listOf(textPiece("words of ${it.name}")) }

    private inner class Fx(profile: DeviceProfile) {
        val store = newStore(Signature.of(ModelVariant.M740.generic, profile.visionTokens, profile.maxTokens)).also { it.setProfile(profile) }

        @Volatile
        var ready = true

        @Volatile
        var cancelOn: String? = null
        val r: Retriever
        val extractor = FakeExtractor(pieces) { f -> if (f.name == cancelOn) r.cancelBatch() }

        init {
            r = testRetriever(store, photos + docs + videos, extractor, modelReady = { ready })
        }

        fun state(kind: Kind, s: FileState) = store.countsNow().byKindState[kind to s] ?: 0L

        /** What Services.freeUpSpace does mid-batch, a batch (sync) with the file gone, then Download again. */
        suspend fun freeUpAndRestore() {
            cancelOn = "v1.mp4"
            r.indexBatch(1.minutes)
            cancelOn = null
            r.release()
            ready = false
            r.refreshSetUp()
            r.indexBatch(1.minutes)
            ready = true
            r.refreshSetUp()
        }
    }

    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    @Test
    fun removingAndRestoringTheModelKeepsEveryReadFile() = test {
        val f = Fx(PROFILE)
        f.r.refreshSetUp()
        f.r.indexBatch(1.minutes)
        assertEquals(photos.size.toLong(), f.state(Kind.PHOTO, FileState.INDEXED))
        // P11's settings round trips before Free up space: videos off and on, sample mode on and off.
        f.r.updatePrefs { it.copy(kinds = it.kinds - Kind.VIDEO, sampleSize = 2) }
        f.r.updatePrefs { it.copy(kinds = it.kinds + Kind.VIDEO, sampleSize = null) }
        f.r.requeue(setOf(Kind.VIDEO))
        val profile = f.store.profile()
        val read = f.extractor.names.size

        f.freeUpAndRestore() // the videos' batch is running when Free up space cancels it
        assertEquals(photos.size.toLong(), f.state(Kind.PHOTO, FileState.INDEXED))
        f.r.indexBatch(1.minutes)

        assertEquals(profile, f.store.profile())
        assertEquals(emptyList(), f.extractor.names.drop(read).filterNot { it.startsWith("v") }, "no photo or doc read again")
        assertEquals(0L, f.store.countsByState()[FileState.PENDING] ?: 0L, "nothing left queued")
    }

    private val slow = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 4.0, false)
    private val samples = Samples(listOf(ByteArray(10)), listOf("hello world"), emptyList())

    @Test fun aTuneUsesTheSessionFloorAndPauseButNeverTheFileSchedule() = test {
        val monitor = FakeMonitor(DeviceState(8.0, false, 49, Thermal.NONE, screenOn = true))
        val r = testRetriever(newStore(), emptyList(), FakeExtractor(), monitor = monitor)
        r.setReadingSchedule(ReadingSchedule.MANUAL)
        assertEquals(StopReason.UNPLUGGED, assertFailsWith<CalibrationDeferred> { r.calibrate(samples, false) }.reason)
        monitor.state = monitor.state.copy(batteryPct = ReadingPolicy.RESUMED_BATTERY_FLOOR)
        assertTrue(r.calibrate(samples, false).backends.isNotEmpty(), "fifty, as a session, whatever the file schedule")
        assertFalse(r.prefs.value.started, "a tune starts no processing session")
        r.pause()
        assertEquals(StopReason.PAUSED, assertFailsWith<CalibrationDeferred> { r.calibrate(samples, true) }.reason)
    }

    @Test fun aDeferredOrCancelledGpuRetestKeepsItsRetryAvailable() = test {
        for (cancelled in listOf(false, true)) {
            val store = newStore().also { it.setProfile(PROFILE.copy(gpuError = "much slower than CPU")) }
            val r = testRetriever(store, emptyList(), FakeExtractor())
            assertFailsWith<CancellationException> {
                r.retestGpuIfDue {
                    if (cancelled) throw CancellationException("test cancellation") else throw CalibrationDeferred(StopReason.HOT)
                }
            }
            assertEquals("0", store.meta(Retriever.GPU_RETESTED))
            assertTrue(r.retestGpuIfDue { samples } != null, "a temporary stop does not consume the one retest")
        }
    }

    /** Mobile data (or the welcome tap) writes prefs before the first tune; the tier defaults must still land. */
    @Test
    fun anEarlierPrefsWriteStillGetsTheLightDefaults() = test {
        val store = newStore()
        store.updatePrefs { it.copy(allowMobileData = true) }
        val r = testRetriever(store, emptyList(), FakeExtractor(), probe = slow)
        assertEquals(Tier.LIGHT, r.calibrate(samples, tuneAgain = false).profile.tier)
        val p = store.prefs()
        assertTrue(p.onlyWhileCharging)
        assertEquals(Kind.entries.toSet() - Kind.AUDIO, p.kinds)
        assertTrue(p.allowMobileData)
    }

    /** A person-set value differs from the old tier's default, so a LIGHT tune leaves it. */
    @Test
    fun aPersonSetChargingChoiceSurvivesALightTune() = test {
        val store = newStore().also { it.setProfile(PROFILE.copy(tier = Tier.BALANCED, bulkOnlyWhenCharging = true)) }
        store.updatePrefs { it.copy(onlyWhileCharging = false) }
        val r = testRetriever(store, emptyList(), FakeExtractor(), probe = slow)
        assertEquals(Tier.LIGHT, r.calibrate(samples, tuneAgain = false).profile.tier)
        assertFalse(store.prefs().onlyWhileCharging)
    }

    /** Tune measures at the reference 70 vision tokens, so a FULL phone indexing at 140 is not demoted by Tune again (F030). */
    @Test
    fun tuneAgainOnAFullPhoneMeasuresAtTheReferenceTokens() = test {
        val store = newStore().also { it.setProfile(PROFILE.copy(visionTokens = 140)) }
        var vt = 0
        val factory = FakeFactory().apply {
            fail = { p, _ ->
                vt = p.visionTokens
                null
            }
            beforeEmbed = { if (vt == 140) delay(200) }
        }
        val r = testRetriever(store, emptyList(), FakeExtractor(), factory = factory)
        val profile = r.calibrate(samples, tuneAgain = true).profile
        assertEquals(Tier.FULL, profile.tier)
        assertEquals(140, profile.visionTokens, "Tune again keeps the stored tokens for indexing")
        assertTrue(factory.opened.all { it.first.visionTokens == 70 })
    }

    /** A tier up while photos read (retierFrom) requeues videos as designed, and never a photo. */
    @Test
    fun aTierUpRequeuesVideosNotPhotos() = test {
        val f = Fx(PROFILE.copy(tier = Tier.BALANCED, videoFrameEverySec = 5))
        f.r.refreshSetUp()
        f.r.indexBatch(1.minutes)
        assertEquals(Tier.FULL, f.store.profile()!!.tier, "five fast photos move the phone up")
        assertEquals(photos.size.toLong(), f.state(Kind.PHOTO, FileState.INDEXED))
        assertEquals(0L, f.state(Kind.PHOTO, FileState.PENDING))
        assertTrue(f.extractor.names.count { it.startsWith("v") } >= videos.size)
    }

    /** A Tune that turned the GPU down without a crash gets one more measure, hidden on a charger, and only once. */
    @Test
    fun aGpuTurnedDownIsMeasuredOnceMoreHiddenOnACharger() = test {
        val store = newStore().also { it.setProfile(PROFILE.copy(gpuError = "much slower than CPU")) }
        var opened = Backend.CPU
        val factory = FakeFactory().apply {
            fail = { p, _ ->
                opened = p.backend
                null
            }
            beforeEmbed = { if (opened == Backend.CPU) delay(5) }
        }
        val monitor = FakeMonitor(DeviceState(8.0, charging = false, batteryPct = 80, thermal = Thermal.NONE, pluggedIn = true))
        val r = testRetriever(store, emptyList(), FakeExtractor(), factory = factory, monitor = monitor)

        r.uiVisible = true
        assertNull(r.retestGpuIfDue { samples }, "not while the app is in front")
        r.uiVisible = false
        monitor.state = monitor.state.copy(pluggedIn = false)
        assertNull(r.retestGpuIfDue { samples }, "not off the charger")
        monitor.state = monitor.state.copy(pluggedIn = true)

        assertEquals(Backend.GPU, r.retestGpuIfDue { samples }?.chosen)
        assertEquals(Backend.GPU, store.profile()?.backend)
        assertNull(store.profile()?.gpuError)

        store.setProfile(PROFILE.copy(gpuError = "much slower than CPU"))
        assertNull(r.retestGpuIfDue { samples }, "once per phone")
    }

    /** A phone whose GPU was never turned down by a measure (no gpuError) is not retuned. */
    @Test
    fun aCpuPhoneWithoutAGpuErrorIsLeftAlone() = test {
        val store = newStore().also { it.setProfile(PROFILE) }
        val monitor = FakeMonitor(DeviceState(8.0, charging = true, batteryPct = 80, thermal = Thermal.NONE))
        val r = testRetriever(store, emptyList(), FakeExtractor(), monitor = monitor)
        assertNull(r.retestGpuIfDue { samples })
        assertEquals(PROFILE, store.profile())
    }
}
