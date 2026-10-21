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
    SAMPLE_READY,
    MEANING,
    DEEPENING,
    READING,
    WAITING,
    LOOKING,
    CAUGHT_UP,
    LIMITED,
    NO_CONTENT,
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

private val ACTIVE = setOf(ReadingPhase.READ_NOW, ReadingPhase.READING, ReadingPhase.SAMPLE, ReadingPhase.DEEPENING, ReadingPhase.MEANING)
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
 * The one status line, on Home, Processing, setup and the notification: what is happening, one plain [detail], and
 * an [action] only when there is something to do. [percent]: the line's figure (the share read, never falling; the
 * meaning pass's share for MEANING; the download's for DOWNLOADING), null when it has none. Each app draws the bar only
 * while [active] or downloading.
 */
data class ReadingStatus(val phase: ReadingPhase, val title: String, val detail: String? = null, val action: StatusAction? = null, val percent: Int? = null) {
    val active: Boolean get() = phase in ACTIVE

    /**
     * Why reading stopped, or null: only a stop phase's title is a reason, never "All done" or "Checking…". Nor is
     * WAITING, the system's turn between batches: no blocker holds it, so it never freezes a screen.
     */
    val pause: String? get() = title.takeIf { phase in STOPS }
}

/** The share of files read, whole percent, 100 only with nothing waiting. */
internal fun percentRead(p: ReadingProgress): Int = if (p.waiting == 0L) 100 else (p.contentRead * 100 / (p.contentRead + p.waiting)).toInt()

/**
 * The highest "N% done" shown and how many files were counted then ([ReadingProgress.contentRead] + waiting). The figure
 * never falls: when new files lower the share, the line names them ("Processing 42 new files") until the old one is passed.
 */
data class ReadMark(val percent: Int = 0, val files: Long = 0) {
    fun next(p: ReadingProgress): ReadMark = percentRead(p).let { if (it >= percent) ReadMark(it, p.contentRead + p.waiting) else this }

    /** Files that came since [percent] was shown, while the share is under it; 0 otherwise. */
    internal fun newFiles(p: ReadingProgress): Long = if (percentRead(p) < percent) (p.contentRead + p.waiting - files).coerceAtLeast(0) else 0

    internal fun encode() = "$percent,$files"

    internal companion object {
        fun decode(s: String?): ReadMark? = s?.split(',')?.takeIf { it.size == 2 }?.let { (p, f) ->
            ReadMark(p.toIntOrNull() ?: return null, f.toLongOrNull() ?: return null)
        }
    }
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

    fun blocker(prefs: Prefs, device: DeviceState, visible: Boolean, sampleSession: Boolean, now: Long, lowBattery: Boolean = false): StopReason? = when {
        prefs.paused -> StopReason.PAUSED
        device.powerSave -> StopReason.POWER_SAVE
        device.hot -> StopReason.HOT
        !device.charging && (lowBattery || device.batteryPct in 0..LOW_BATTERY) -> if (device.pluggedIn) StopReason.CHARGING_UP else StopReason.LOW_BATTERY
        device.screenOn && !visible -> StopReason.BUSY
        sampleSession || prefs.readNowUntil > now -> null
        prefs.automaticReading == ReadingSchedule.MANUAL -> StopReason.MANUAL
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
        StopReason.UNPLUGGED -> "Waiting until you plug in"
        StopReason.MANUAL -> "Processing only when you start it"
        StopReason.DONE, StopReason.BUDGET -> "Taking a short break"
    }

    /**
     * Home's status line: lost access first, then setup's access step, then the model download or its setup (a stopped
     * download gives way while reading runs), else [reading]: null before core's live status arrives, so a first frame
     * never shows a share under the saved mark. Null with nothing in the library, where Home shows its empty
     * state instead. [libraryChosen]: [SetupInputs.libraryChosen].
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
                "Downloading search by meaning" + (download.percent?.let { " · $it%" } ?: ""),
                "You can keep using Retriever.",
                StatusAction.STOP,
                download.percent,
            )
        }

        libraryTotal == 0L -> null

        else -> reading
    }

    /**
     * The reading part of the status line. [readNowMinutes]: what is left of a Read for 15 minutes, rounded up, 0 with
     * none; it shows from the tap, before the reader starts. [pluggedIn]: [DeviceState.pluggedIn]. [mark]: the figure shown
     * so far ([ReadMark.next] of [progress] is taken here too). No hours, nights or "by morning": [ESTIMATE_CALIBRATED].
     */
    fun decide(
        progress: ReadingProgress,
        blocker: StopReason?,
        running: Boolean,
        reading: Boolean,
        words: DeviceWords,
        readNowMinutes: Int = 0,
        pluggedIn: Boolean = false,
        mark: ReadMark = ReadMark(),
    ): ReadingStatus {
        val phase = when {
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

            reading && progress.sample -> ReadingPhase.SAMPLE

            // While first reads remain, the meaning pass only interleaves with them: READING says so in its detail.
            reading && progress.scheduled == 0L && progress.meaningWaiting > 0 -> ReadingPhase.MEANING

            reading && progress.scheduled == 0L && progress.laterPasses > 0 -> ReadingPhase.DEEPENING

            reading || (running && progress.unfinished) -> ReadingPhase.READING

            progress.unfinished -> ReadingPhase.WAITING

            progress.sample && progress.contentRead == 0L -> ReadingPhase.NO_CONTENT

            progress.sample -> ReadingPhase.SAMPLE_READY

            running -> ReadingPhase.LOOKING

            progress.partlyRead > 0 -> ReadingPhase.LIMITED

            else -> ReadingPhase.CAUGHT_UP
        }
        val shown = mark.next(progress)
        val newFiles = shown.newFiles(progress)
        val meaning = progress.contentRead.takeIf { it > 0 }?.let { ((it - progress.meaningWaiting) * 100 / it).toInt() }
        val readShare = "${shown.percent}% done"
        val title = when (phase) {
            ReadingPhase.CHARGING -> "${stopCopy(StopReason.UNPLUGGED, words)} · $readShare"

            ReadingPhase.READ_NOW -> "Processing for 15 minutes · $readNowMinutes min left"

            ReadingPhase.SAMPLE -> "Trying a few of your files"

            ReadingPhase.SAMPLE_READY -> "Ready to try"

            ReadingPhase.MEANING -> "Adding search by meaning · ${meaning ?: 0}%"

            ReadingPhase.DEEPENING -> "Processing the rest of some long files"

            ReadingPhase.READING -> if (newFiles > 0) {
                "Processing ${Words.plural(newFiles, "new file")}"
            } else {
                "${if (pluggedIn) "Processing while you charge" else Words.PROCESSING_YOUR_FILES} · $readShare"
            }

            ReadingPhase.LOOKING -> "Checking for new files…"

            ReadingPhase.CAUGHT_UP -> "All done. Retriever has processed everything it can"

            ReadingPhase.LIMITED -> "All done. A few long files were processed only in part"

            ReadingPhase.NO_CONTENT -> "Ready to find files by name"

            else -> stopCopy(blocker ?: StopReason.DONE, words)
        }
        val detail = when (phase) {
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

            ReadingPhase.SAMPLE_READY -> "Search for a word you remember."

            ReadingPhase.MEANING -> "So you can search by what things mean, and by what’s in your photos. On this ${words.device} only."

            ReadingPhase.DEEPENING, ReadingPhase.LIMITED -> "You can find them by their beginning."

            ReadingPhase.READING -> if (progress.meaningWaiting > 0) {
                "Also adding search by meaning · ${meaning ?: 0}%"
            } else {
                if (words.ios) {
                    "Search works the whole time. Retriever processes your files while it’s open, and sometimes when iOS allows."
                } else {
                    "You can close Retriever; it carries on. Search works the whole time."
                }
            }

            ReadingPhase.WAITING -> "Your ${words.device} decides when Retriever carries on. Nothing is lost."

            ReadingPhase.CAUGHT_UP -> "It processes new files by itself.".takeIf { blocker != StopReason.MANUAL }

            ReadingPhase.NO_CONTENT ->
                "To find them by what’s inside, Retriever processes them here on this ${words.device}: " +
                    "it looks at the words and pictures inside each file. Nothing is sent anywhere."

            else -> null
        }
        val action = when (phase) {
            ReadingPhase.PAUSED -> StatusAction.RESUME
            ReadingPhase.CHARGING, ReadingPhase.MANUAL -> StatusAction.READ_FOR_15
            ReadingPhase.READ_NOW -> StatusAction.STOP
            ReadingPhase.SAMPLE_READY, ReadingPhase.NO_CONTENT -> StatusAction.CHOOSE_WHEN
            ReadingPhase.CAUGHT_UP, ReadingPhase.LIMITED -> StatusAction.DISMISS
            else -> null
        }
        // The sample has its own count, so no share of the whole phone while it runs.
        val percent = when (phase) {
            ReadingPhase.MEANING -> meaning
            ReadingPhase.SAMPLE, ReadingPhase.SAMPLE_READY, ReadingPhase.NO_CONTENT -> null
            else -> shown.percent
        }
        return ReadingStatus(phase, title, detail, action, percent)
    }
}

internal const val SESSION_START_KEY = "reading_session_start_chunk"
internal const val SESSION_READ_KEY = "reading_session_read"
internal const val READ_MARK_KEY = "read_mark"

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

            extent == ReadExtent.MEANING_LIMITED -> "Name and what’s inside. Search by meaning couldn’t finish."

            extent == ReadExtent.DEEPENING -> "Name and what’s inside. Search by meaning is still to come."

            else -> "Name and what’s inside"
        }

        !readInside -> "Its name. Processing is off for this kind of file."

        stored?.state == FileState.SKIPPED -> "Its name. Retriever couldn’t process it after ${Words.plural(stored.attempts.toLong(), "try", "tries")}."

        stored?.state == FileState.INDEXED -> "Its name. Nothing inside could be processed."

        else -> "Its name. Not processed yet."
    }
}
