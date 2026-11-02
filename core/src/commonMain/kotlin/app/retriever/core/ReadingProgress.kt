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
    /** Files read without vectors that the model will add search by description to; a subset count, never a partition bucket. */
    val meaningWaiting: Long = 0,
    /** The phone's files have all been counted once (the first full scan is written); until then the totals still grow. */
    val counted: Boolean = true,
    /** Not now was chosen after the demo: only names can be found until the person chooses when to process. */
    val notNow: Boolean = false,
) {
    val contentRead: Long get() = read + partlyRead
    val waiting: Long get() = scheduled + notScheduled
    val libraryTotal: Long get() = processed + waiting

    /** The one count rule: every file no longer waiting, searchable inside, found by name only or couldn't be opened. */
    val processed: Long get() = contentRead + nameOnly + couldntRead

    /** The files still to process, null until they are all counted ("the rest of your files"). */
    val rest: Long? get() = waiting.takeIf { counted }
    val unfinished: Boolean get() = scheduled > 0 || laterPasses > 0 || rereads > 0 || meaningWaiting > 0
}

enum class ReadingSchedule(val title: String) {
    CHARGING("While charging"),
    BATTERY("Also on battery"),
    MANUAL("When I start it"),
}

/**
 * Every state the status line can be in. The first five come from [ReadingPolicy.line] (setup, access, the meaning
 * download); the rest from [ReadingPolicy.decide]. Each app maps a phase to its icon and mascot only.
 */
enum class ReadingPhase {
    SETTING_UP,
    NO_ACCESS,
    DOWNLOADING,
    DOWNLOAD_STOPPED,
    TUNING,
    COUNTING,
    NOT_NOW,
    PAUSED,
    POWER_SAVE,
    HOT,
    LOW_BATTERY,
    CHARGING_UP,
    BUSY,
    CHARGING,
    MANUAL,
    READ_NOW,
    SAMPLE,
    MEANING,
    FACES,
    DEEPENING,
    READING,
    WAITING,
    LOOKING,
    CAUGHT_UP,
    LIMITED,
}

/** The status line's one button. Each app maps it to its own button and call; [label] is what the button says. */
enum class StatusAction(val label: String) {
    CONTINUE(Words.CONTINUE),
    ALLOW_ACCESS("Allow access"),
    CHOOSE_WHEN(Words.CHOOSE_WHEN),
    READ_FOR_15(Words.READ_FOR_15),
    STOP("Stop"),
    RESUME("Resume"),
    DISMISS("Dismiss"),
    TRY_AGAIN(Words.TRY_AGAIN),
}

private val ACTIVE = setOf(ReadingPhase.READ_NOW, ReadingPhase.READING, ReadingPhase.SAMPLE, ReadingPhase.DEEPENING, ReadingPhase.MEANING, ReadingPhase.FACES)
private val STOPS = setOf(
    ReadingPhase.PAUSED,
    ReadingPhase.POWER_SAVE,
    ReadingPhase.HOT,
    ReadingPhase.LOW_BATTERY,
    ReadingPhase.CHARGING_UP,
    ReadingPhase.BUSY,
    ReadingPhase.CHARGING,
    ReadingPhase.MANUAL,
)

/**
 * The one status line, on Home, Processing, setup and the notification: what is happening, the [count] line ("30 of
 * 25,793 files processed", [Words.readOf]), one plain [detail], and an [action] only when there is something to do. The bar
 * is exactly [done] / [total], the count's own numbers (the meaning pass's for MEANING, the download's percent of 100 for
 * DOWNLOADING); all three are null with no bar. The apps draw it from these two numbers, never a percent.
 */
data class ReadingStatus(
    val phase: ReadingPhase,
    val title: String,
    val detail: String? = null,
    val action: StatusAction? = null,
    val count: String? = null,
    val done: Long? = null,
    val total: Long? = null,
) {
    val active: Boolean get() = phase in ACTIVE

    /**
     * Why reading stopped, or null: only a stop phase's title is a reason, never "All done" or "Checking…". Nor is
     * WAITING, the system's turn between batches: no blocker holds it, so it never freezes a screen.
     */
    val pause: String? get() = title.takeIf { phase in STOPS }

    /**
     * Processing's [Words.PAUSE] beside the line's own button: only while work is left, nothing has paused it, and no
     * Process for 15 minutes runs. During one the line's Stop ends it; a Pause beside it looked the same and stopped all
     * processing.
     */
    fun offersPause(progress: ReadingProgress): Boolean = progress.unfinished && phase != ReadingPhase.PAUSED && action != StatusAction.STOP
}

/**
 * The model download or its setup, as the platform knows it, for the status line; null when neither is under way.
 * [percent]: null while unknown. [stopped]: the download failed or waits for Wi-Fi, or the setup failed. [size]: the
 * platform's format of [Retriever.modelDownloadBytes]. [settingUp]: the download is done and the setup (the tune) runs.
 */
data class DownloadState(val percent: Int?, val stopped: Boolean, val size: String, val settingUp: Boolean = false)

/** Shared by the reader and every progress surface. Opening the app never overrides the saved schedule. */
object ReadingPolicy {
    const val SESSION_MS = 15 * 60 * 1000L
    const val LOW_BATTERY = 20
    const val BATTERY_RECOVERED = 23

    /**
     * New files after the first pass: up to this many are processed at once, on battery and while the phone is in use. At
     * about 1.5 s a photo that is a couple of minutes; only the one-time catch-up waits for the charger.
     */
    const val FEW_WAITING = 100

    /** Process for 15 minutes is the person asking now: it carries on while they use the phone (on the CPU, see `onScreen`). */
    fun blocker(
        prefs: Prefs,
        device: DeviceState,
        visible: Boolean,
        sampleSession: Boolean,
        now: Long,
        lowBattery: Boolean = false,
        fewWaiting: Boolean = false,
    ): StopReason? = when {
        prefs.paused -> StopReason.PAUSED
        device.powerSave -> StopReason.POWER_SAVE
        device.hot -> StopReason.HOT
        !device.charging && (lowBattery || device.batteryPct in 0..LOW_BATTERY) -> if (device.pluggedIn) StopReason.CHARGING_UP else StopReason.LOW_BATTERY
        prefs.readNowUntil > now -> null
        device.screenOn && !visible && !fewWaiting -> StopReason.BUSY
        sampleSession -> null
        prefs.automaticReading == ReadingSchedule.MANUAL -> StopReason.MANUAL
        prefs.automaticReading == ReadingSchedule.CHARGING && !device.pluggedIn && !fewWaiting -> StopReason.UNPLUGGED
        else -> null
    }

    /**
     * Embedding long files' later parts is the big, one-time work: it keeps the charger-and-screen-off rule even when few new
     * files wait. Null when it may run now.
     */
    fun deepenBlocker(prefs: Prefs, device: DeviceState, visible: Boolean, sampleSession: Boolean, now: Long): StopReason? = when {
        sampleSession && visible -> null
        prefs.readNowUntil > now -> null
        device.screenOn && !visible -> StopReason.BUSY
        prefs.automaticReading == ReadingSchedule.CHARGING && !device.pluggedIn -> StopReason.UNPLUGGED
        else -> null
    }

    /** Why reading stopped, as every surface titles it; DONE and BUDGET are the system's turn between batches. */
    fun stopCopy(stop: StopReason, words: DeviceWords): String = when (stop) {
        StopReason.PAUSED -> "Paused"
        StopReason.POWER_SAVE -> "Paused while ${words.powerSave} is on"
        StopReason.HOT -> "Paused to let your ${words.device} cool down"
        StopReason.LOW_BATTERY -> "Paused, battery is low"
        StopReason.CHARGING_UP -> "Charging up first"
        StopReason.BUSY -> "Waiting until you put your ${words.device} down"
        StopReason.UNPLUGGED -> "Waits until you charge"
        StopReason.MANUAL -> "Processing only when you start it"
        StopReason.DISK_FULL -> "Paused, your ${words.device} is full"
        StopReason.DONE, StopReason.BUDGET -> "Taking a short break"
    }

    /**
     * Home's status line: lost access first, then setup's access step, then the model download or its setup (a stopped
     * download gives way while reading runs), else [reading]: null before core's live status arrives. Null with nothing
     * in the library, where Home shows its empty state instead. [libraryChosen]: [SetupInputs.libraryChosen].
     */
    fun line(
        reading: ReadingStatus?,
        libraryChosen: Boolean,
        hasAccess: Boolean,
        libraryTotal: Long,
        words: DeviceWords,
        download: DownloadState? = null,
    ): ReadingStatus? = when {
        !hasAccess -> ReadingStatus(
            ReadingPhase.NO_ACCESS,
            "Retriever can’t see your files",
            "Search still works on the files it has already processed." + if (words.ios) " This opens your ${words.device}’s Settings." else "",
            StatusAction.ALLOW_ACCESS,
        )

        !libraryChosen -> ReadingStatus(ReadingPhase.SETTING_UP, "Finish setting up", "Choose what Retriever can look through.", StatusAction.CONTINUE)

        download != null && (!download.stopped || reading?.active != true) -> when {
            download.settingUp && download.stopped -> ReadingStatus(ReadingPhase.TUNING, Words.tuneFailed(words), action = StatusAction.TRY_AGAIN)

            download.settingUp -> ReadingStatus(ReadingPhase.TUNING, Words.tuneTitle(words), Words.tuneBody(words))

            download.stopped -> ReadingStatus(
                ReadingPhase.DOWNLOAD_STOPPED,
                "Download paused",
                "It carries on when you’re on Wi-Fi. If your ${words.device} is full, it needs ${Words.keepTogether(download.size)} free.",
                StatusAction.TRY_AGAIN,
            )

            else -> ReadingStatus(
                ReadingPhase.DOWNLOADING,
                "Downloading search by description" + (download.percent?.let { " · $it%" } ?: ""),
                "You can keep using Retriever.",
                StatusAction.STOP,
                done = download.percent?.toLong(),
                total = download.percent?.let { 100L },
            )
        }

        reading?.phase == ReadingPhase.COUNTING -> reading

        libraryTotal == 0L -> null

        else -> reading
    }

    /**
     * The reading part of the status line. [readNowMinutes]: what is left of a Read for 15 minutes, rounded up, 0 with
     * none; it shows from the tap, before the reader starts. [pluggedIn]: [DeviceState.pluggedIn]. A live count by the
     * one rule ([ReadingProgress.processed] of [ReadingProgress.libraryTotal]), not a percent: 1% of a big library is
     * minutes of work, and a count moves with every file (and a dip when new files come explains itself). "Counting your
     * files…" until they are all counted. No hours, nights or "by morning": [ESTIMATE_CALIBRATED]. [faces]: the running
     * batch's look for faces ([IndexState.faces]), which files wait for; its own count then, or the line stands still.
     * [facesWaiting]: photos still to look at once every file is processed. A stop or wait then counts them, not a full
     * file count beside work still to do.
     */
    fun decide(
        progress: ReadingProgress,
        blocker: StopReason?,
        running: Boolean,
        reading: Boolean,
        words: DeviceWords,
        readNowMinutes: Int = 0,
        pluggedIn: Boolean = false,
        faces: FacesProgress? = null,
        facesWaiting: FacesProgress? = null,
    ): ReadingStatus {
        // The demo in front has its own count; Not now's numbers are the whole phone's.
        val demo = progress.sample && !progress.notNow
        val phase = when {
            !progress.counted && !demo -> ReadingPhase.COUNTING

            // Not now pauses, so it comes before the person's own Pause.
            progress.notNow -> ReadingPhase.NOT_NOW

            blocker == StopReason.PAUSED -> ReadingPhase.PAUSED

            progress.unfinished && blocker != null -> when (blocker) {
                StopReason.POWER_SAVE -> ReadingPhase.POWER_SAVE
                StopReason.HOT -> ReadingPhase.HOT
                StopReason.LOW_BATTERY -> ReadingPhase.LOW_BATTERY
                StopReason.CHARGING_UP -> ReadingPhase.CHARGING_UP
                StopReason.BUSY -> ReadingPhase.BUSY
                StopReason.UNPLUGGED -> ReadingPhase.CHARGING
                StopReason.MANUAL -> ReadingPhase.MANUAL
                else -> ReadingPhase.WAITING
            }

            readNowMinutes > 0 && progress.unfinished -> ReadingPhase.READ_NOW

            running && faces != null -> ReadingPhase.FACES

            reading && progress.sample -> ReadingPhase.SAMPLE

            // While first reads remain, the meaning pass only interleaves with them: READING says so in its detail. Once
            // they are done, a running batch is on these passes even between files.
            (reading || running) && progress.scheduled == 0L && progress.meaningWaiting > 0 -> ReadingPhase.MEANING

            (reading || running) && progress.scheduled == 0L && progress.laterPasses > 0 -> ReadingPhase.DEEPENING

            reading || (running && progress.unfinished) -> ReadingPhase.READING

            progress.unfinished -> ReadingPhase.WAITING

            // The demo is done and the choice waits.
            progress.sample -> ReadingPhase.NOT_NOW

            running -> ReadingPhase.LOOKING

            progress.partlyRead > 0 -> ReadingPhase.LIMITED

            else -> ReadingPhase.CAUGHT_UP
        }
        // Between batches with every file's first pass done, only the hours-long passes are left: a short break beside a
        // full bar read as stuck.
        val firstPassDone = phase == ReadingPhase.WAITING && progress.waiting == 0L && progress.rereads == 0L &&
            (blocker == null || blocker == StopReason.DONE || blocker == StopReason.BUDGET)
        val title = when (phase) {
            ReadingPhase.WAITING if firstPassDone -> "All files processed"
            ReadingPhase.COUNTING -> "Counting your files…"
            ReadingPhase.NOT_NOW -> "Only names can be found for now"
            ReadingPhase.CHARGING -> stopCopy(StopReason.UNPLUGGED, words)
            ReadingPhase.READ_NOW -> "Processing for 15 minutes · $readNowMinutes min left"
            ReadingPhase.SAMPLE -> "Processing the demo files"
            ReadingPhase.MEANING -> "Adding search by description"
            ReadingPhase.FACES -> "Looking for faces in your photos"
            ReadingPhase.DEEPENING -> "Processing the rest of some long files"
            ReadingPhase.READING -> if (pluggedIn) "Processing while you charge" else Words.PROCESSING_YOUR_FILES
            ReadingPhase.LOOKING -> "Checking for new files…"
            ReadingPhase.CAUGHT_UP -> "All done. Retriever has processed everything it can"
            ReadingPhase.LIMITED -> "All done. A few long files were processed only in part"
            else -> stopCopy(blocker ?: StopReason.DONE, words)
        }
        val sentence = when (phase) {
            ReadingPhase.PAUSED -> "Search still works."

            ReadingPhase.POWER_SAVE -> "Carries on when ${words.powerSave} is off."

            ReadingPhase.HOT -> "Carries on by itself."

            ReadingPhase.LOW_BATTERY -> "Plug in and Retriever carries on."

            ReadingPhase.CHARGING_UP -> "Processing starts once your battery has a bit more charge."

            ReadingPhase.BUSY -> "Or open Retriever to carry on now."

            ReadingPhase.CHARGING -> "Retriever carries on when your ${words.device} is charging."

            ReadingPhase.MANUAL -> "You can find any file by its name."

            ReadingPhase.READ_NOW -> "Uses some battery. " + if (words.ios) "Keep Retriever open." else "Stops on its own."

            ReadingPhase.SAMPLE -> "About a minute. Nothing is changed or sent."

            ReadingPhase.MEANING -> "So you can search by what things mean, and by what’s in your photos. On this ${words.device} only."

            ReadingPhase.FACES -> "Then Retriever gets back to your files. Search works the whole time."

            ReadingPhase.DEEPENING, ReadingPhase.LIMITED -> "You can find them by their beginning."

            ReadingPhase.READING -> when {
                progress.meaningWaiting > 0 -> "Also adding search by description."
                words.ios -> "Search works the whole time. Retriever processes your files while it’s open, and sometimes when iOS allows."
                else -> "You can close Retriever; it carries on. Search works the whole time."
            }

            ReadingPhase.WAITING if firstPassDone -> if (progress.laterPasses > 0) {
                "Retriever processes the rest of some long files when your ${words.device} allows. Search works now."
            } else {
                "Retriever adds search by description when your ${words.device} allows. Search works now."
            }

            ReadingPhase.WAITING -> if (blocker == StopReason.DISK_FULL) {
                "Free up some space and Retriever carries on."
            } else {
                "Your ${words.device} decides when Retriever carries on. Nothing is lost."
            }

            ReadingPhase.CAUGHT_UP -> "It processes new files by itself.".takeIf { blocker != StopReason.MANUAL }

            else -> null
        }
        val detail = if (phase == ReadingPhase.DEEPENING) "${Words.notReadYet(progress.laterPasses)}. $sentence" else sentence
        val action = when (phase) {
            ReadingPhase.PAUSED -> StatusAction.RESUME
            ReadingPhase.CHARGING, ReadingPhase.MANUAL -> StatusAction.READ_FOR_15
            ReadingPhase.READ_NOW -> StatusAction.STOP
            ReadingPhase.NOT_NOW -> StatusAction.CHOOSE_WHEN
            ReadingPhase.CAUGHT_UP, ReadingPhase.LIMITED -> StatusAction.DISMISS
            else -> null
        }
        // The count and the bar are the same numbers. None while counting, for the demo (it has its own), or while deepening:
        // its first reads are all done, and a full bar beside work going on reads as stuck.
        val meaning = phase == ReadingPhase.MEANING
        val looking = when {
            phase == ReadingPhase.FACES -> faces
            phase in STOPS || phase == ReadingPhase.WAITING -> facesWaiting?.takeIf { progress.waiting == 0L }
            else -> null
        }
        val (done, total) = when {
            looking != null -> looking.done to looking.done + looking.pending
            meaning -> progress.contentRead - progress.meaningWaiting to progress.contentRead
            else -> progress.processed to progress.libraryTotal
        }
        val none = !progress.counted || phase == ReadingPhase.DEEPENING || (demo && phase != ReadingPhase.NOT_NOW) || total == 0L
        if (none) return ReadingStatus(phase, title, detail, action)
        val count = when {
            looking != null -> Words.facesOf(done, total)
            meaning -> Words.meaningOf(done, total)
            else -> Words.readOf(done, total)
        }
        return ReadingStatus(phase, title, detail, action, count, done, total)
    }
}

internal const val SESSION_START_KEY = "reading_session_start_chunk"
internal const val SESSION_READ_KEY = "reading_session_read"

/**
 * What one file can be found by, for File info's [Words.FOUND_BY] row on both platforms. [stored] null is a file the
 * index has not met yet. Content already read stays read even if reading was switched off since; the index records no
 * reason for a failure, only how many times it tried.
 */
fun readingLabel(kind: Kind, stored: Store.StoredFile?, readInside: Boolean): String {
    val extent = stored?.extent
    return when {
        kind == Kind.OTHER -> "Its name. Retriever can’t look inside this kind of file yet."

        stored?.contentRead == true -> when {
            stored.state == FileState.PENDING -> "Name and its beginning. The rest is not processed yet."

            stored.state == FileState.SKIPPED -> "Name and part of what’s inside. Retriever couldn’t finish processing it."

            stored.partial || extent == ReadExtent.FIRST_PART || extent == ReadExtent.FIRST_PART_DEEPENING ||
                extent == ReadExtent.FIRST_PART_MEANING_LIMITED -> "Name and its beginning"

            extent == ReadExtent.MEANING_LIMITED -> "Name and what’s inside. Search by description couldn’t finish."

            extent == ReadExtent.DEEPENING -> "Name and what’s inside. Search by description is still to come."

            else -> "Name and what’s inside"
        }

        !readInside -> "Its name. Processing is off for this kind of file."

        stored?.state == FileState.SKIPPED -> "Its name. Retriever couldn’t process it after ${Words.plural(stored.attempts.toLong(), "try", "tries")}."

        stored?.state == FileState.INDEXED -> "Its name. Nothing inside could be processed."

        else -> "Its name. Not processed yet."
    }
}
