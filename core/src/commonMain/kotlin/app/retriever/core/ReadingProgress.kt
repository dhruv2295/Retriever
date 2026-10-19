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

/** A null title is a stop's: [ReadingPolicy.stopCopy] words it for the device. */
enum class ReadingPhase(val title: String? = null) {
    PAUSED,
    POWER_SAVE,
    HOT,
    LOW_BATTERY,
    BUSY,
    CHARGING,
    MANUAL,
    SAMPLE("Reading your sample"),
    SAMPLE_READY("Your sample is ready to try"),
    MEANING("Adding search by meaning"),
    DEEPENING("Reading more of your long files"),
    READING("Reading your files"),
    WAITING,
    LOOKING("Checking for new files…"),
    CAUGHT_UP("Up to date"),
    LIMITED("Scheduled reading finished"),
    NO_CONTENT("No content read yet"),
}

data class ReadingStatus(val phase: ReadingPhase, val title: String, val detail: String? = null) {
    val active: Boolean get() = phase == ReadingPhase.READING || phase == ReadingPhase.SAMPLE || phase == ReadingPhase.DEEPENING ||
        phase == ReadingPhase.MEANING
}

/** Home's status row; each app maps [phase] to its colour and icon. */
enum class HomePhase { SETTING_UP, READING, LOOKING, PAUSED, WAITING, CAUGHT_UP }

data class HomeStatus(val phase: HomePhase, val title: String, val detail: String? = null)

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

    /** Why reading stopped, as every surface says it. */
    fun stopCopy(stop: StopReason, words: DeviceWords): String = when (stop) {
        StopReason.PAUSED -> "Reading paused"
        StopReason.POWER_SAVE -> "Paused for ${words.powerSave}"
        StopReason.HOT -> "Paused to cool down"
        StopReason.LOW_BATTERY -> "Paused for low battery"
        StopReason.BUSY -> "Waiting until you put your ${words.device} down"
        StopReason.UNPLUGGED -> "Waiting to charge"
        StopReason.MANUAL -> "Reading stopped for now"
        StopReason.DONE, StopReason.BUDGET -> "Waiting for ${words.os}"
    }

    /**
     * Home's first row; null with nothing in the library, where Home shows its empty state instead. [libraryChosen]:
     * [SetupInputs.libraryChosen]; before it, the row leads back to setup's access step. Reading needs no model download.
     */
    fun homeStatus(libraryChosen: Boolean, libraryTotal: Long, reading: ReadingStatus): HomeStatus? = when {
        !libraryChosen -> HomeStatus(HomePhase.SETTING_UP, Words.CHOOSE_ACCESS)

        libraryTotal == 0L -> null

        else -> HomeStatus(
            when {
                reading.phase == ReadingPhase.PAUSED -> HomePhase.PAUSED
                reading.phase == ReadingPhase.CAUGHT_UP || reading.phase == ReadingPhase.LIMITED -> HomePhase.CAUGHT_UP
                reading.phase == ReadingPhase.LOOKING -> HomePhase.LOOKING
                reading.active -> HomePhase.READING
                else -> HomePhase.WAITING
            },
            reading.title,
            reading.detail,
        )
    }

    fun decide(progress: ReadingProgress, blocker: StopReason?, running: Boolean, reading: Boolean, words: DeviceWords): ReadingStatus {
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
            phase == ReadingPhase.MEANING -> "Adding search by meaning to ${progress.meaningWaiting} files"
            phase == ReadingPhase.DEEPENING -> "Reading more of ${progress.laterPasses} long files"
            else -> phase.title ?: stopCopy(blocker ?: StopReason.DONE, words)
        }
        val detail = when (phase) {
            ReadingPhase.PAUSED -> "Search still works. Resume whenever you’re ready."
            ReadingPhase.POWER_SAVE -> "Reading can resume when it’s off."
            ReadingPhase.HOT -> "Reading can resume when your ${words.device} cools down."
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

/**
 * Says how far Retriever has read one file, in the reading contract's words, for File info on both platforms. [stored] null is
 * a file the index has not met yet. Content already read stays read even if reading was switched off since; the index records
 * no reason for a failure, only how many times it tried.
 */
fun readingLabel(kind: Kind, stored: Store.StoredFile?, readInside: Boolean): String {
    val extent = stored?.extent
    return when {
        kind == Kind.OTHER -> "Name only · Reading isn’t supported for this type"

        stored?.contentRead == true -> when {
            stored.state == FileState.PENDING -> "Partly read · Waiting to read more"

            stored.state == FileState.SKIPPED -> "Partly read · Couldn’t finish reading"

            stored.partial || extent == ReadExtent.FIRST_PART || extent == ReadExtent.FIRST_PART_DEEPENING ||
                extent == ReadExtent.FIRST_PART_MEANING_LIMITED -> "Partly read · Only the beginning is included"

            extent == ReadExtent.MEANING_LIMITED -> "Partly read · Couldn’t finish meaning search"

            extent == ReadExtent.DEEPENING -> "Partly read · More meaning search to come"

            else -> "Read"
        }

        !readInside -> "Name only · Reading is off for this type"

        stored?.state == FileState.SKIPPED -> "Couldn’t read · Retriever tried ${stored.attempts} times"

        stored?.state == FileState.INDEXED -> "Name only · No readable content"

        else -> "Waiting to read"
    }
}
