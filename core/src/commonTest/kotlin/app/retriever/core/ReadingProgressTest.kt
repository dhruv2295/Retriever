package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val IOS = DeviceWords.ios("iPhone")

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

    @Test fun sessionsOverrideTheScheduleNeverSafety() {
        val session = Prefs(started = true)
        assertEquals(StopReason.UNPLUGGED, ReadingPolicy.blocker(Prefs(), device, false))
        assertNull(ReadingPolicy.blocker(session, device, false))
        assertNull(ReadingPolicy.blocker(Prefs(), device, true), "the demo in front")
        assertEquals(
            StopReason.MANUAL,
            ReadingPolicy.blocker(Prefs().withSchedule(ReadingSchedule.MANUAL), device.copy(charging = true, pluggedIn = true), false),
        )
        assertEquals(StopReason.POWER_SAVE, ReadingPolicy.blocker(session, device.copy(powerSave = true), true))
        assertEquals(StopReason.HOT, ReadingPolicy.blocker(session, device.copy(thermal = Thermal.SEVERE), true))
        assertEquals(StopReason.LOW_BATTERY, ReadingPolicy.blocker(session, device.copy(batteryPct = 20), true))
        assertEquals(StopReason.PAUSED, ReadingPolicy.blocker(session.copy(paused = true), device.copy(powerSave = true), true))
    }

    @Test fun alsoOnBatteryReadsDownToTheFloorThenWaitsForTheCharger() {
        val battery = Prefs().withSchedule(ReadingSchedule.BATTERY)
        val floor = ReadingPolicy.BATTERY_FLOOR
        assertNull(ReadingPolicy.blocker(battery, device.copy(batteryPct = floor), false))
        assertEquals(StopReason.UNPLUGGED, ReadingPolicy.blocker(battery, device.copy(batteryPct = floor - 1), false))
        // Small backlogs bypass the automatic reserve down to fifty; charging lifts the battery floor.
        assertNull(ReadingPolicy.blocker(battery, device.copy(batteryPct = floor - 1), false, fewWaiting = true))
        assertEquals(StopReason.UNPLUGGED, ReadingPolicy.blocker(battery, device.copy(batteryPct = 49), false, fewWaiting = true))
        assertNull(ReadingPolicy.blocker(battery, device.copy(batteryPct = 40, charging = true, pluggedIn = true), false))
    }

    @Test fun savedQueuedProgressOffersResumeAndActualOtherAppProcessingOffersPause() {
        val queued = ReadingPolicy.decide(pending, null, false, false, DeviceWords.ANDROID, processingStarted = true)
        assertEquals(ReadingPhase.WAITING, queued.phase)
        assertEquals(StatusAction.RESUME, queued.action)
        assertFalse(queued.active)
        assertFalse(queued.offersPause(pending))
        val active = ReadingPolicy.decide(pending, null, true, false, DeviceWords.ANDROID, adaptive = true)
        assertEquals(ReadingPhase.READING, active.phase)
        assertEquals(StatusAction.PAUSE, active.action)
        assertEquals(Words.strings.processingAdaptiveDetail, active.detail)
        assertEquals(pending.processed to pending.libraryTotal, active.done to active.total)
        val betweenBatches = ReadingPolicy.decide(pending, null, false, false, DeviceWords.ANDROID, sessionActive = true)
        assertEquals(StatusAction.PAUSE, betweenBatches.action)
        assertEquals(Words.strings.processingYourFiles, betweenBatches.title)
        assertFalse(betweenBatches.active, "a held session's gap does not claim inference is running")
        val unstarted = ReadingPolicy.decide(pending, StopReason.UNPLUGGED, false, false, DeviceWords.ANDROID, battery = 74)
        assertEquals(StatusAction.START, unstarted.action, "an automatic battery wait does not establish processing history")
        assertEquals(Words.strings.automaticProcessingPreferenceDetail, unstarted.detail)
        val asked = ReadingPolicy.decide(
            pending,
            StopReason.UNPLUGGED,
            false,
            false,
            DeviceWords.ANDROID,
            battery = 49,
            requested = true,
            processingStarted = true,
        )
        assertEquals(Words.strings.processingPlugInDetail, asked.detail, "below fifty, a person's request asks to plug in")
        assertNull(asked.action, "only the charger starts it below fifty, so no Resume that does nothing")
        val complete = ReadingPolicy.decide(
            ReadingProgress(read = 4, partlyRead = 2, couldntRead = 1),
            StopReason.PAUSED,
            false,
            false,
            DeviceWords.ANDROID,
            processingStarted = true,
        )
        assertEquals(ReadingPhase.LIMITED, complete.phase)
        assertEquals(StatusAction.DISMISS, complete.action, "a saved pause has no Resume once all possible work is done")
        assertEquals(7L, complete.done, "completed coverage keeps failures and partial reads distinct")
        val completeOnBattery = ReadingPolicy.decide(
            ReadingProgress(read = 4),
            StopReason.UNPLUGGED,
            false,
            false,
            DeviceWords.ANDROID,
            battery = 60,
        )
        assertEquals(ReadingPhase.CAUGHT_UP, completeOnBattery.phase)
        assertEquals(Words.strings.caughtUpDetail, completeOnBattery.detail, "complete work has no stale battery wait detail")
    }

    @Test fun oneStatePrecedenceKeepsBlockersAheadOfActiveSampleAndTails() {
        for (sample in listOf(true, false)) {
            val p = pending.copy(sample = sample)
            assertEquals(ReadingPhase.PAUSED, ReadingPolicy.decide(p, StopReason.PAUSED, true, true, DeviceWords.ANDROID).phase)
            assertEquals("Paused · continues when Low Power Mode is off", ReadingPolicy.decide(p, StopReason.POWER_SAVE, true, true, IOS).title)
            assertEquals(ReadingPhase.CHARGING, ReadingPolicy.decide(p, StopReason.UNPLUGGED, true, true, DeviceWords.ANDROID).phase)
            assertEquals(ReadingPhase.WAITING, ReadingPolicy.decide(p, null, false, false, DeviceWords.ANDROID).phase)
        }
        assertEquals(ReadingPhase.DEEPENING, ReadingPolicy.decide(pending.copy(scheduled = 0), null, true, true, DeviceWords.ANDROID).phase)
        val upgrade = ReadingProgress(read = 5, meaningWaiting = 5)
        assertEquals(5, upgrade.contentRead, "waiting for meaning is a subset of Read, never another bucket")
        assertEquals(ReadingPhase.MEANING, ReadingPolicy.decide(upgrade, null, true, true, DeviceWords.ANDROID).phase)
        assertEquals("Adding search by description" to 0L, ReadingPolicy.decide(upgrade, null, true, true, DeviceWords.ANDROID).let { it.title to it.done })
        assertEquals(ReadingPhase.CHARGING, ReadingPolicy.decide(upgrade, StopReason.UNPLUGGED, false, false, DeviceWords.ANDROID).phase)
        assertEquals(ReadingPhase.LIMITED, ReadingPolicy.decide(ReadingProgress(partlyRead = 2), null, false, false, DeviceWords.ANDROID).phase)
        assertEquals(ReadingPhase.NOT_NOW, ReadingPolicy.decide(ReadingProgress(read = 2, sample = true), null, false, false, DeviceWords.ANDROID).phase)
        assertEquals(ReadingPhase.CAUGHT_UP, ReadingPolicy.decide(ReadingProgress(read = 2), null, false, false, DeviceWords.ANDROID).phase)
        assertEquals("Paused", ReadingPolicy.decide(pending, null, false, false, IOS).title)
    }

    /** Every file's first pass done, long files' later parts left: hours of work, never a short break beside a full bar. */
    @Test fun onlyLaterPartsLeftSaysEveryFileIsProcessed() {
        val later = ReadingProgress(read = 10, partlyRead = 2, laterPasses = 2)
        val between = ReadingPolicy.decide(later, null, false, false, DeviceWords.ANDROID)
        assertEquals(ReadingPhase.WAITING, between.phase)
        assertEquals("All files processed", between.title)
        assertEquals(Words.strings.waitingLongFilesDetail(DeviceWords.ANDROID), between.detail)
        assertEquals("12 of 12 files processed", between.count)
        assertEquals("All files processed", ReadingPolicy.decide(later, StopReason.BUDGET, false, false, DeviceWords.ANDROID).title)
        val meaningLeft = ReadingPolicy.decide(ReadingProgress(read = 5, meaningWaiting = 2), null, false, false, DeviceWords.ANDROID)
        assertEquals(Words.strings.waitingMeaningDetail(DeviceWords.ANDROID), meaningLeft.detail)
        assertEquals("3 of 5 files have search by description", meaningLeft.count, "not a full bar of files processed")
        assertEquals("Adding search by description", meaningLeft.title)
        // A stop still says why; files still waiting keep the short break.
        val hot = ReadingPolicy.decide(later, StopReason.HOT, false, false, DeviceWords.ANDROID)
        assertEquals(ReadingPhase.HOT, hot.phase)
        assertEquals("All files processed. The rest of 2 long files waits. Carries on by itself.", hot.detail)
        assertEquals(null, hot.count, "no full bar beside work still to do")
        val meaningStopped = ReadingPolicy.decide(ReadingProgress(read = 5, meaningWaiting = 2), StopReason.UNPLUGGED, false, false, DeviceWords.ANDROID)
        assertEquals("3 of 5 files have search by description", meaningStopped.count)
        assertEquals("All files processed. Retriever carries on when your phone is charging.", meaningStopped.detail)
        // Paused with nothing left keeps the plain count.
        assertEquals("5 of 5 files processed", ReadingPolicy.decide(ReadingProgress(read = 5), StopReason.PAUSED, false, false, DeviceWords.ANDROID).count)
        assertEquals("Paused, your phone is full", ReadingPolicy.decide(later, StopReason.DISK_FULL, false, false, DeviceWords.ANDROID).title)
        assertEquals("Paused", ReadingPolicy.decide(later.copy(scheduled = 1), null, false, false, DeviceWords.ANDROID).title)
        // A batch on the later parts names them between two files too, with no full bar.
        val running = ReadingPolicy.decide(later, null, true, false, DeviceWords.ANDROID)
        assertEquals(ReadingPhase.DEEPENING, running.phase)
        assertEquals(null, running.done)
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
        val whole = "Name and what’s inside"
        assertEquals(whole, label(FileState.INDEXED))
        assertEquals(whole, label(FileState.INDEXED, extent = ReadExtent.WHOLE))
        assertEquals("Name and its beginning", label(FileState.INDEXED, partial = true))
        assertEquals("Name and its beginning", label(FileState.INDEXED, extent = ReadExtent.FIRST_PART_DEEPENING))
        assertEquals("Name and what’s inside. Search by description is still to come.", label(FileState.INDEXED, extent = ReadExtent.DEEPENING))
        assertEquals("Name and what’s inside. Search by description couldn’t finish.", label(FileState.INDEXED, extent = ReadExtent.MEANING_LIMITED))
        assertEquals("Name and its beginning. The rest is not processed yet.", label(FileState.PENDING, content = true))
        assertEquals("Its name. Nothing inside could be processed.", label(FileState.INDEXED, content = false))
        assertEquals("Its name. Not processed yet.", label(FileState.PENDING))
        assertEquals("Its name. Not processed yet.", label(null))
        assertEquals("Its name. Retriever couldn’t process it after 3 tries.", label(FileState.SKIPPED))
        assertEquals(whole, label(FileState.INDEXED, inside = false))
        assertEquals("Its name. Processing is off for this kind of file.", label(FileState.PENDING, inside = false))
        assertEquals("Its name. Processing is off for this kind of file.", label(FileState.SKIPPED, inside = false))
        assertEquals("Its name. Retriever can’t look inside this kind of file yet.", label(FileState.INDEXED, kind = Kind.OTHER))
    }
}
