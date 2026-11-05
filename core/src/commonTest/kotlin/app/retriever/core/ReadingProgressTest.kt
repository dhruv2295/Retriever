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
        // Using the phone is not a safeguard against Process for 15 minutes, which the person asked for; a sample still waits.
        assertNull(ReadingPolicy.blocker(prefs, device.copy(screenOn = true), false, true, 0))
        assertEquals(StopReason.BUSY, ReadingPolicy.blocker(Prefs(), device.copy(screenOn = true, charging = true), false, true, 0))
        assertEquals(StopReason.PAUSED, ReadingPolicy.blocker(prefs.copy(paused = true), device.copy(powerSave = true), true, true, 0))
    }

    @Test fun aFewNewFilesAreReadOnBatteryAndWhileThePhoneIsInUse() {
        val inUse = device.copy(screenOn = true)
        assertEquals(StopReason.UNPLUGGED, ReadingPolicy.blocker(Prefs(), device, false, false, 0))
        assertNull(ReadingPolicy.blocker(Prefs(), device, false, false, 0, fewWaiting = true))
        assertEquals(StopReason.BUSY, ReadingPolicy.blocker(Prefs(), inUse.copy(charging = true), false, false, 0))
        assertNull(ReadingPolicy.blocker(Prefs(), inUse, false, false, 0, fewWaiting = true))
        // Safeguards and the person's own choices still win.
        assertEquals(StopReason.LOW_BATTERY, ReadingPolicy.blocker(Prefs(), device.copy(batteryPct = 15), false, false, 0, fewWaiting = true))
        assertEquals(StopReason.HOT, ReadingPolicy.blocker(Prefs(), device.copy(thermal = Thermal.SEVERE), false, false, 0, fewWaiting = true))
        assertEquals(StopReason.PAUSED, ReadingPolicy.blocker(Prefs(paused = true), device, false, false, 0, fewWaiting = true))
        assertEquals(StopReason.MANUAL, ReadingPolicy.blocker(Prefs().withSchedule(ReadingSchedule.MANUAL), device, false, false, 0, fewWaiting = true))
    }

    @Test fun alsoOnBatteryReadsDownToTheFloorThenWaitsForTheCharger() {
        val battery = Prefs().withSchedule(ReadingSchedule.BATTERY)
        val floor = ReadingPolicy.BATTERY_FLOOR
        assertNull(ReadingPolicy.blocker(battery, device.copy(batteryPct = floor), false, false, 0))
        assertEquals(StopReason.UNPLUGGED, ReadingPolicy.blocker(battery, device.copy(batteryPct = floor - 1), false, false, 0))
        assertEquals(StopReason.UNPLUGGED, ReadingPolicy.deepenBlocker(battery, device.copy(batteryPct = floor - 1), false, false, 0))
        // A few new files still go on down to the low-battery line; the charger lifts the floor.
        assertNull(ReadingPolicy.blocker(battery, device.copy(batteryPct = floor - 1), false, false, 0, fewWaiting = true))
        assertNull(ReadingPolicy.blocker(battery, device.copy(batteryPct = 40, charging = true, pluggedIn = true), false, false, 0))
    }

    @Test fun laterPartsStayOnTheChargerWithTheScreenOff() {
        val plugged = device.copy(charging = true, pluggedIn = true)
        assertNull(ReadingPolicy.deepenBlocker(Prefs(), plugged, false, false, 0))
        assertEquals(StopReason.UNPLUGGED, ReadingPolicy.deepenBlocker(Prefs(), device, false, false, 0))
        assertEquals(StopReason.BUSY, ReadingPolicy.deepenBlocker(Prefs(), plugged.copy(screenOn = true), false, false, 0))
        assertNull(ReadingPolicy.deepenBlocker(Prefs(readNowUntil = 100), device.copy(screenOn = true), false, false, 0))
        assertNull(ReadingPolicy.deepenBlocker(Prefs(), device.copy(screenOn = true), true, true, 0))
    }

    @Test fun oneStatePrecedenceKeepsBlockersAheadOfActiveSampleAndTails() {
        for (sample in listOf(true, false)) {
            val p = pending.copy(sample = sample)
            assertEquals(ReadingPhase.PAUSED, ReadingPolicy.decide(p, StopReason.PAUSED, true, true, DeviceWords.ANDROID).phase)
            assertEquals("Paused while Low Power Mode is on", ReadingPolicy.decide(p, StopReason.POWER_SAVE, true, true, IOS).title)
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
        assertEquals("Taking a short break", ReadingPolicy.decide(pending, null, false, false, IOS).title)
    }

    /** Every file's first pass done, long files' later parts left: hours of work, never a short break beside a full bar. */
    @Test fun onlyLaterPartsLeftSaysEveryFileIsProcessed() {
        val later = ReadingProgress(read = 10, partlyRead = 2, laterPasses = 2)
        val between = ReadingPolicy.decide(later, null, false, false, DeviceWords.ANDROID)
        assertEquals(ReadingPhase.WAITING, between.phase)
        assertEquals("All files processed", between.title)
        assertEquals("Retriever processes the rest of some long files when your phone allows. Search works now.", between.detail)
        assertEquals("12 of 12 files processed", between.count)
        assertEquals("All files processed", ReadingPolicy.decide(later, StopReason.BUDGET, false, false, DeviceWords.ANDROID).title)
        assertEquals(
            "Retriever adds search by description when your phone allows. Search works now.",
            ReadingPolicy.decide(ReadingProgress(read = 5, meaningWaiting = 5), null, false, false, DeviceWords.ANDROID).detail,
        )
        // A stop still says why; files still waiting keep the short break.
        val busy = ReadingPolicy.decide(later, StopReason.BUSY, false, false, DeviceWords.ANDROID)
        assertEquals(ReadingPhase.BUSY, busy.phase)
        assertEquals("All files processed. The rest of 2 long files waits. Or open Retriever to carry on now.", busy.detail)
        assertEquals(null, busy.count, "no full bar beside work still to do")
        val meaningStopped = ReadingPolicy.decide(ReadingProgress(read = 5, meaningWaiting = 2), StopReason.UNPLUGGED, false, false, DeviceWords.ANDROID)
        assertEquals("3 of 5 files have search by description", meaningStopped.count)
        assertEquals("All files processed. Retriever carries on when your phone is charging.", meaningStopped.detail)
        // Paused with nothing left keeps the plain count.
        assertEquals("5 of 5 files processed", ReadingPolicy.decide(ReadingProgress(read = 5), StopReason.PAUSED, false, false, DeviceWords.ANDROID).count)
        assertEquals("Paused, your phone is full", ReadingPolicy.decide(later, StopReason.DISK_FULL, false, false, DeviceWords.ANDROID).title)
        assertEquals("Taking a short break", ReadingPolicy.decide(later.copy(scheduled = 1), null, false, false, DeviceWords.ANDROID).title)
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
