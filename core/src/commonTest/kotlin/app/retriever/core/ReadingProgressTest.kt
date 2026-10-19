package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingProgressTest {
    private val device = DeviceState(8.0, false, 80, Thermal.NONE)
    private val pending = ReadingProgress(read = 20, partlyRead = 3, scheduled = 8, notScheduled = 9, nameOnly = 2, couldntRead = 1, laterPasses = 2)

    @Test fun countsPartitionRevisionsAndIncludePartialOnlyOnce() {
        assertEquals(23, pending.contentRead)
        assertEquals(17, pending.waiting)
        assertEquals(43, pending.libraryTotal)
        assertTrue(pending.unfinished)
        assertFalse(ReadingProgress(partlyRead = 3).unfinished, "permanent limits are not scheduled work")
    }

    @Test fun chargingAppliesInForegroundAndExplicitExpiryDoesNotRenew() {
        assertEquals(StopReason.UNPLUGGED, ReadingPolicy.blocker(Prefs(), device, true, false, 100))
        val session = Prefs(readNowUntil = 200)
        assertNull(ReadingPolicy.blocker(session, device, true, false, 199))
        assertEquals(StopReason.UNPLUGGED, ReadingPolicy.blocker(session, device, true, false, 200))
        assertEquals(StopReason.MANUAL, ReadingPolicy.blocker(Prefs().withSchedule(ReadingSchedule.MANUAL), device.copy(charging = true), true, false, 100))
    }

    @Test fun safeguardsBeatExplicitAndSampleSessions() {
        val prefs = Prefs(readNowUntil = 1_000)
        assertEquals(StopReason.POWER_SAVE, ReadingPolicy.blocker(prefs, device.copy(powerSave = true), true, true, 0))
        assertEquals(StopReason.HOT, ReadingPolicy.blocker(prefs, device.copy(thermal = Thermal.SEVERE), true, true, 0))
        assertEquals(StopReason.LOW_BATTERY, ReadingPolicy.blocker(prefs, device.copy(batteryPct = 20), true, true, 0))
        assertEquals(StopReason.BUSY, ReadingPolicy.blocker(prefs, device.copy(screenOn = true), false, true, 0))
        assertEquals(StopReason.PAUSED, ReadingPolicy.blocker(prefs.copy(paused = true), device.copy(powerSave = true), true, true, 0))
    }

    @Test fun oneStatePrecedenceKeepsBlockersAheadOfActiveSampleAndTails() {
        for (sample in listOf(true, false)) {
            val p = pending.copy(sample = sample)
            assertEquals(ReadingPhase.PAUSED, ReadingPolicy.decide(p, StopReason.PAUSED, true, true, false).phase)
            assertEquals("Paused for Low Power Mode", ReadingPolicy.decide(p, StopReason.POWER_SAVE, true, true, true).title)
            assertEquals(ReadingPhase.CHARGING, ReadingPolicy.decide(p, StopReason.UNPLUGGED, true, true, false).phase)
            assertEquals(ReadingPhase.WAITING, ReadingPolicy.decide(p, null, false, false, false).phase)
        }
        assertEquals(ReadingPhase.DEEPENING, ReadingPolicy.decide(pending.copy(scheduled = 0), null, true, true, false).phase)
        val upgrade = ReadingProgress(read = 5, meaningWaiting = 5)
        assertEquals(5, upgrade.contentRead, "waiting for meaning is a subset of Read, never another bucket")
        assertEquals(ReadingPhase.MEANING, ReadingPolicy.decide(upgrade, null, true, true, false).phase)
        assertEquals("Adding search by meaning to 5 files", ReadingPolicy.decide(upgrade, null, true, true, false).title)
        assertEquals(ReadingPhase.CHARGING, ReadingPolicy.decide(upgrade, StopReason.UNPLUGGED, false, false, false).phase)
        assertEquals(ReadingPhase.LIMITED, ReadingPolicy.decide(ReadingProgress(partlyRead = 2), null, false, false, false).phase)
        assertEquals(ReadingPhase.SAMPLE_READY, ReadingPolicy.decide(ReadingProgress(read = 2, sample = true), null, false, false, false).phase)
        assertEquals(ReadingPhase.CAUGHT_UP, ReadingPolicy.decide(ReadingProgress(read = 2), null, false, false, false).phase)
        assertEquals("Waiting for iOS", ReadingPolicy.decide(pending, null, false, false, true).title)
    }

    @Test fun aFilesReadingSaysHowFarItWasReadAndAlreadyReadContentStaysRead() {
        fun label(
            state: FileState?,
            content: Boolean = state == FileState.INDEXED,
            kind: Kind = Kind.PDF,
            partial: Boolean = false,
            extent: ReadExtent? = null,
            inside: Boolean = true,
        ) = readingLabel(kind, state?.let { Store.StoredFile(1, SourceFile("u", "a.pdf", "", "", kind, 1, 0), 3, it, partial, extent, content) }, inside)
        assertEquals("Read", label(FileState.INDEXED))
        assertEquals("Read", label(FileState.INDEXED, extent = ReadExtent.WHOLE))
        assertEquals("Partly read · Only the beginning is included", label(FileState.INDEXED, partial = true))
        assertEquals("Partly read · Only the beginning is included", label(FileState.INDEXED, extent = ReadExtent.FIRST_PART_DEEPENING))
        assertEquals("Partly read · More meaning search to come", label(FileState.INDEXED, extent = ReadExtent.DEEPENING))
        assertEquals("Partly read · Couldn’t finish meaning search", label(FileState.INDEXED, extent = ReadExtent.MEANING_LIMITED))
        assertEquals("Partly read · Waiting to read more", label(FileState.PENDING, content = true))
        assertEquals("Name only · No readable content", label(FileState.INDEXED, content = false))
        assertEquals("Waiting to read", label(FileState.PENDING))
        assertEquals("Waiting to read", label(null))
        assertEquals("Couldn’t read · Retriever tried 3 times", label(FileState.SKIPPED))
        assertEquals("Read", label(FileState.INDEXED, inside = false))
        assertEquals("Name only · Reading is off for this type", label(FileState.PENDING, inside = false))
        assertEquals("Name only · Reading is off for this type", label(FileState.SKIPPED, inside = false))
        assertEquals("Name only · Reading isn’t supported for this type", label(FileState.INDEXED, kind = Kind.OTHER))
    }
}
