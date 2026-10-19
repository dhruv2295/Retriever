package app.retriever.core

/** Distinct accessible file revisions. Partly read is included in contentRead, never added twice. */
data class ReadingProgress(
    val read: Long = 0,
    val partlyRead: Long = 0,
    val scheduled: Long = 0,
    val notScheduled: Long = 0,
    val nameOnly: Long = 0,
    val couldntRead: Long = 0,
    val laterPasses: Long = 0,
    val sample: Boolean = false,
    /** A subset of Partly read whose first pass was requeued (for example after a reading setting changed). */
    val rereads: Long = 0,
    /** Files read without vectors that the model will add search by meaning to; a subset count, never a partition bucket. */
    val meaningWaiting: Long = 0,
) {
    val contentRead: Long get() = read + partlyRead
    val waiting: Long get() = scheduled + notScheduled
    val libraryTotal: Long get() = contentRead + waiting + nameOnly + couldntRead
    val unfinished: Boolean get() = scheduled > 0 || laterPasses > 0 || rereads > 0 || meaningWaiting > 0
}

enum class ReadingSchedule(val title: String) {
    CHARGING("While charging"),
    BATTERY("Also on battery"),
    MANUAL("When I start it"),
}

enum class ReadingPhase(val title: String) {
    PAUSED("Reading paused"),
    POWER_SAVE("Paused for Battery Saver"),
    HOT("Paused to cool down"),
    LOW_BATTERY("Paused for low battery"),
    BUSY("Waiting until you put your phone down"),
    CHARGING("Waiting to charge"),
    MANUAL("Reading stopped for now"),
    SAMPLE("Reading your sample"),
    SAMPLE_READY("Your sample is ready to try"),
    MEANING("Adding search by meaning"),
    DEEPENING("Reading more of your long files"),
    READING("Reading your files"),
    WAITING("Waiting for Android"),
    LOOKING("Checking for new files…"),
    CAUGHT_UP("Up to date"),
    LIMITED("Scheduled reading finished"),
    NO_CONTENT("No content read yet"),
}

data class ReadingStatus(val phase: ReadingPhase, val title: String, val detail: String? = null) {
    val active: Boolean get() = phase == ReadingPhase.READING || phase == ReadingPhase.SAMPLE || phase == ReadingPhase.DEEPENING ||
        phase == ReadingPhase.MEANING
}

/** Shared by the reader and every progress surface. Opening the app never overrides the saved schedule. */
object ReadingPolicy {
    const val SESSION_MS = 15 * 60 * 1000L
    const val LOW_BATTERY = 20
    const val BATTERY_RECOVERED = 23

    fun blocker(prefs: Prefs, device: DeviceState, visible: Boolean, sampleSession: Boolean, now: Long, lowBattery: Boolean = false): StopReason? = when {
        prefs.paused -> StopReason.PAUSED
        device.powerSave -> StopReason.POWER_SAVE
        device.hot -> StopReason.HOT
        !device.charging && (lowBattery || device.batteryPct in 0..LOW_BATTERY) -> StopReason.LOW_BATTERY
        device.screenOn && !visible -> StopReason.BUSY
        sampleSession || prefs.readNowUntil > now -> null
        prefs.automaticReading == ReadingSchedule.MANUAL -> StopReason.MANUAL
        prefs.automaticReading == ReadingSchedule.CHARGING && !device.charging -> StopReason.UNPLUGGED
        else -> null
    }

    fun decide(progress: ReadingProgress, blocker: StopReason?, running: Boolean, reading: Boolean, ios: Boolean): ReadingStatus {
        val phase = when {
            blocker == StopReason.PAUSED -> ReadingPhase.PAUSED

            progress.unfinished && blocker != null -> when (blocker) {
                StopReason.POWER_SAVE -> ReadingPhase.POWER_SAVE
                StopReason.HOT -> ReadingPhase.HOT
                StopReason.LOW_BATTERY -> ReadingPhase.LOW_BATTERY
                StopReason.BUSY -> ReadingPhase.BUSY
                StopReason.UNPLUGGED -> ReadingPhase.CHARGING
                StopReason.MANUAL -> ReadingPhase.MANUAL
                else -> ReadingPhase.WAITING
            }

            reading && progress.sample -> ReadingPhase.SAMPLE

            reading && progress.scheduled == 0L && progress.meaningWaiting > 0 -> ReadingPhase.MEANING

            reading && progress.scheduled == 0L && progress.laterPasses > 0 -> ReadingPhase.DEEPENING

            reading -> ReadingPhase.READING

            running && progress.unfinished -> ReadingPhase.READING

            progress.unfinished -> ReadingPhase.WAITING

            progress.sample && progress.contentRead == 0L -> ReadingPhase.NO_CONTENT

            progress.sample -> ReadingPhase.SAMPLE_READY

            running -> ReadingPhase.LOOKING

            progress.partlyRead > 0 || progress.couldntRead > 0 -> ReadingPhase.LIMITED

            else -> ReadingPhase.CAUGHT_UP
        }
        val title = when {
            phase == ReadingPhase.READING && !reading -> "Starting reading…"
            ios && phase == ReadingPhase.POWER_SAVE -> "Paused for Low Power Mode"
            ios && phase == ReadingPhase.WAITING -> "Waiting for iOS"
            phase == ReadingPhase.MEANING -> "Adding search by meaning to ${progress.meaningWaiting} files"
            phase == ReadingPhase.DEEPENING -> "Reading more of ${progress.laterPasses} long files"
            else -> phase.title
        }
        val detail = when (phase) {
            ReadingPhase.PAUSED -> "Search still works. Resume whenever you’re ready."
            ReadingPhase.POWER_SAVE -> "Reading can resume when it’s off."
            ReadingPhase.HOT -> "Reading can resume when your phone cools down."
            ReadingPhase.LOW_BATTERY -> "Plug in to continue."
            ReadingPhase.BUSY -> "Reading can continue when the screen is off or Retriever is open."
            ReadingPhase.CHARGING -> "Plug in, or choose Read now for a short session."
            ReadingPhase.MANUAL -> "Keep searching names and the content read so far."
            ReadingPhase.WAITING -> "Progress is saved. Open Retriever to continue when your reading settings allow."
            ReadingPhase.MEANING -> "Search by words already works for these files."
            ReadingPhase.SAMPLE_READY -> "Choose when to read more."
            ReadingPhase.NO_CONTENT -> "You can still search by name. Review files or choose when to read more."
            ReadingPhase.CAUGHT_UP -> "New files will be read using your schedule."
            else -> null
        }
        return ReadingStatus(phase, title, detail)
    }
}

internal const val SESSION_START_KEY = "reading_session_start_chunk"
internal const val SESSION_READ_KEY = "reading_session_read"
