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
    /** Photos read with search by description whose words (Extraction.WORDS) are still to read; a subset of [read]. */
    val wordsWaiting: Long = 0,
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
    val unfinished: Boolean get() = scheduled > 0 || laterPasses > 0 || rereads > 0 || meaningWaiting > 0 || wordsWaiting > 0
}

enum class ReadingSchedule {
    CHARGING,
    BATTERY,
    MANUAL,
    ;

    val title: String get() = Words.strings.scheduleTitle(this)
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
    CHARGING,
    MANUAL,
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
enum class StatusAction {
    CONTINUE,
    ALLOW_ACCESS,
    STOP,
    RESUME,
    DISMISS,
    TRY_AGAIN,

    /** Starts an explicit untimed run. */
    START,

    /** The line's Pause while files are processed. */
    PAUSE,
    ;

    val label: String get() = Words.strings.statusAction(this)
}

private val ACTIVE = setOf(ReadingPhase.READING, ReadingPhase.DEEPENING, ReadingPhase.MEANING, ReadingPhase.FACES)
private val STOPS = setOf(
    ReadingPhase.PAUSED,
    ReadingPhase.POWER_SAVE,
    ReadingPhase.HOT,
    ReadingPhase.LOW_BATTERY,
    ReadingPhase.CHARGING_UP,
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
    /** The stages as one journey, for the step line and Processing's checklist; null for the demo and Not now. */
    val journey: Journey? = null,
) {
    val active: Boolean get() = phase in ACTIVE

    /**
     * Why reading stopped, or null: only a stop phase's title is a reason, never "All done" or "Checking…". Nor is
     * WAITING, the system's turn between batches: no blocker holds it, so it never freezes a screen.
     */
    val pause: String? get() = title.takeIf { phase in STOPS }

    /**
     * Processing's [Words.PAUSE] beside the line's own button: only while work is left, nothing has paused it, the line's
     * button is not Pause itself. Downloads have their own Stop button.
     */
    fun offersPause(progress: ReadingProgress): Boolean =
        progress.unfinished && phase != ReadingPhase.PAUSED && action != StatusAction.STOP && action != StatusAction.PAUSE &&
            action != StatusAction.RESUME && action != StatusAction.START
}

/**
 * The model download or its setup, as the platform knows it, for the status line; null when neither is under way.
 * [percent]: null while unknown. [stopped]: the download failed or waits for Wi-Fi, or the setup failed. [size]: the
 * platform's format of [Retriever.modelDownloadBytes]. [settingUp]: the download is done and the setup (the tune) runs.
 */
data class DownloadState(val percent: Int?, val stopped: Boolean, val size: String, val settingUp: Boolean = false)

/** Shared by the reader and every progress surface. Opening the app never overrides the saved schedule. */
object ReadingPolicy {
    const val LOW_BATTERY = 20
    const val BATTERY_RECOVERED = 23

    /** Automatic processing pauses below this level; an explicit session can override it. */
    const val BATTERY_FLOOR = 75
    const val RESUMED_BATTERY_FLOOR = 50

    fun batteryFloor(prefs: Prefs): Int = if (prefs.started) RESUMED_BATTERY_FLOOR else BATTERY_FLOOR

    /**
     * New files after the first pass: up to this many may bypass the automatic reserve, down to fifty.
     * Explicit Pause, manual scheduling and safety stops still apply.
     */
    const val FEW_WAITING = 100

    /** Pause, safety and the battery [floor] off the charger, shared by preparation, tuning and processing. */
    fun resourceBlocker(prefs: Prefs, device: DeviceState, lowBattery: Boolean = false, floor: Int = batteryFloor(prefs)): StopReason? = when {
        prefs.paused -> StopReason.PAUSED
        device.powerSave -> StopReason.POWER_SAVE
        device.hot -> StopReason.HOT
        !device.charging && (lowBattery || device.batteryPct in 0..LOW_BATTERY) -> if (device.pluggedIn) StopReason.CHARGING_UP else StopReason.LOW_BATTERY
        !device.pluggedIn && device.batteryPct in 0 until floor -> StopReason.UNPLUGGED
        else -> null
    }

    /**
     * Opening the app leaves the saved schedule unchanged; explicit sessions override it, never safety. [fewWaiting]: a
     * catch-up of new files runs down to [RESUMED_BATTERY_FLOOR] whatever the schedule. Later parts pass false.
     */
    fun blocker(prefs: Prefs, device: DeviceState, sampleSession: Boolean, lowBattery: Boolean = false, fewWaiting: Boolean = false): StopReason? {
        resourceBlocker(prefs, device, lowBattery, if (fewWaiting) RESUMED_BATTERY_FLOOR else batteryFloor(prefs))?.let { return it }
        return when {
            prefs.started -> null
            prefs.processingRequested && device.pluggedIn -> null
            sampleSession -> null
            prefs.automaticReading == ReadingSchedule.MANUAL -> StopReason.MANUAL
            prefs.automaticReading == ReadingSchedule.CHARGING && !device.pluggedIn && !fewWaiting -> StopReason.UNPLUGGED
            else -> null
        }
    }

    /** Why reading stopped, as every surface titles it; DONE and BUDGET are the system's turn between batches. */
    fun stopCopy(stop: StopReason, words: DeviceWords): String = Words.strings.stopCopy(stop, words)

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
    ): ReadingStatus? {
        val c = Words.strings
        return when {
            !hasAccess -> ReadingStatus(
                ReadingPhase.NO_ACCESS,
                c.noAccessTitle,
                c.noAccessDetail(words),
                StatusAction.ALLOW_ACCESS,
            )

            !libraryChosen -> ReadingStatus(ReadingPhase.SETTING_UP, c.finishSetupTitle, c.finishSetupDetail, StatusAction.CONTINUE)

            download != null && (!download.stopped || reading?.active != true) -> when {
                download.settingUp && download.stopped -> ReadingStatus(ReadingPhase.TUNING, Words.tuneFailed(words), action = StatusAction.TRY_AGAIN)

                download.settingUp -> ReadingStatus(ReadingPhase.TUNING, c.gettingReadyTitle, Words.tuneBody(words))

                download.stopped -> ReadingStatus(
                    ReadingPhase.DOWNLOAD_STOPPED,
                    c.downloadPausedTitle,
                    c.downloadPausedDetail(words, Words.keepTogether(download.size)),
                    StatusAction.TRY_AGAIN,
                )

                else -> ReadingStatus(
                    ReadingPhase.DOWNLOADING,
                    c.downloadingTitle(download.percent),
                    c.downloadingDetail,
                    StatusAction.STOP,
                    done = download.percent?.toLong(),
                    total = download.percent?.let { 100L },
                )
            }

            reading?.phase == ReadingPhase.COUNTING -> reading

            libraryTotal == 0L -> null

            else -> reading
        }
    }

    /**
     * The reading part of the status line. [sessionActive] is the explicit untimed intent. Processing is
     * shown only while the reader runs. [pluggedIn]: [DeviceState.pluggedIn]. A live count by the
     * one rule ([ReadingProgress.processed] of [ReadingProgress.libraryTotal]), not a percent: 1% of a big library is
     * minutes of work, and a count moves with every file (and a dip when new files come explains itself). "Counting your
     * files…" until they are all counted. No hours, nights or "by morning": [ESTIMATE_CALIBRATED]. [faces]: the running
     * batch's look for faces ([IndexState.faces]), which runs once the files are done; its own count then.
     * [facesWaiting]: photos still to look at once every file is processed. A stop or wait then counts them, not a full
     * file count beside work still to do. [people]: People's model when a faces pass found it missing ([IndexState.people]).
     * [readersMissing]: the batch waits for the text and label readers downloading ([IndexState.readersMissing]).
     * [battery]: [DeviceState.batteryPct]. [requested]: [Prefs.processingRequested]; below [RESUMED_BATTERY_FLOOR] off the
     * charger, a person's request asks to plug in. [processingStarted]: Resume rather than Start. [photosExcluded]: photos are not
     * among the kinds processed, so their words are no stage.
     */
    fun decide(
        progress: ReadingProgress,
        blocker: StopReason?,
        running: Boolean,
        reading: Boolean,
        words: DeviceWords,
        sessionActive: Boolean = false,
        pluggedIn: Boolean = false,
        faces: FacesProgress? = null,
        facesWaiting: FacesProgress? = null,
        people: PeopleModel? = null,
        readersMissing: Boolean = false,
        battery: Int = 100,
        requested: Boolean = sessionActive,
        processingStarted: Boolean = false,
        adaptive: Boolean = false,
        facesStarted: Boolean = false,
        peopleOn: Boolean = false,
        textOnly: Boolean = false,
        photosExcluded: Boolean = false,
    ): ReadingStatus {
        // The demo in front has its own count; Not now's numbers are the whole phone's.
        val c = Words.strings
        val demo = progress.sample && !progress.notNow
        val unfinished = progress.unfinished || ((facesWaiting?.pending ?: 0L) > 0)
        val onBattery = !pluggedIn && battery >= 0
        // A session on battery under the automatic floor, which it may process down to the session's.
        val onReserve = sessionActive && onBattery && battery < BATTERY_FLOOR
        // Faces come once every file and its search by description are done, and once begun ([facesStarted]) they finish
        // first (Retriever.runBatch). So a running batch with photos left is on faces from its start to its end. Only the
        // pass itself sets [faces]: around it, the sync and the grouping read as Finishing long files, swapping every batch.
        val facesNext = (facesWaiting?.pending ?: 0L) > 0 &&
            (facesStarted || (progress.waiting == 0L && progress.meaningWaiting == 0L && progress.wordsWaiting == 0L))
        val phase = when {
            !progress.counted && !demo -> ReadingPhase.COUNTING

            // Not now pauses, so it comes before the person's own Pause.
            progress.notNow -> ReadingPhase.NOT_NOW

            blocker == StopReason.PAUSED && unfinished -> ReadingPhase.PAUSED

            unfinished && blocker != null -> when (blocker) {
                StopReason.POWER_SAVE -> ReadingPhase.POWER_SAVE
                StopReason.HOT -> ReadingPhase.HOT
                StopReason.LOW_BATTERY -> ReadingPhase.LOW_BATTERY
                StopReason.CHARGING_UP -> ReadingPhase.CHARGING_UP
                StopReason.UNPLUGGED -> ReadingPhase.CHARGING
                StopReason.MANUAL -> ReadingPhase.MANUAL
                else -> ReadingPhase.WAITING
            }

            // A faces pass first waits for People's model while it downloads (Retriever.peopleModel).
            running && (faces != null || people == PeopleModel.GETTING || facesNext) -> ReadingPhase.FACES

            // The demo's own files go first, with search by description; Home already counts the whole phone.
            reading && progress.sample -> ReadingPhase.READING

            // While first reads remain, the meaning pass only interleaves with them: READING says so in its detail. Once
            // they are done, a running batch is on these passes even between files.
            (reading || running) && progress.scheduled == 0L && progress.meaningWaiting > 0 -> ReadingPhase.MEANING

            // Photos' words go before the long files' rest (IndexEngine.runBatch), as READING with their own title.
            (reading || running) && progress.scheduled == 0L && progress.laterPasses > 0 && progress.wordsWaiting == 0L -> ReadingPhase.DEEPENING

            reading || (running && unfinished) -> ReadingPhase.READING

            unfinished -> ReadingPhase.WAITING

            // The demo is done and the choice waits.
            progress.sample -> ReadingPhase.NOT_NOW

            running -> ReadingPhase.LOOKING

            progress.partlyRead > 0 -> ReadingPhase.LIMITED

            else -> ReadingPhase.CAUGHT_UP
        }
        // Between batches with every file's first pass done, only the hours-long passes are left: a short break beside a
        // full bar read as stuck.
        val allProcessed = progress.waiting == 0L && progress.rereads == 0L
        val firstPassDone = phase == ReadingPhase.WAITING && allProcessed &&
            (blocker == null || blocker == StopReason.DONE || blocker == StopReason.BUDGET)
        // A stop over only those passes says why it stopped, then that every file is processed and what is left.
        val restStopped = phase in STOPS && unfinished && allProcessed
        // Every file processed and searchable by description, the words in photos read now: no full bar beside the work.
        val photoWords = phase == ReadingPhase.READING && !progress.sample && progress.scheduled == 0L && progress.wordsWaiting > 0
        // Play services delivers the text and label readers after install; the first batch may wait for them.
        val gettingReaders = readersMissing && running && phase == ReadingPhase.READING
        // The count and the bar are the same numbers. None while counting, or while deepening:
        // its first reads are all done, and a full bar beside work going on reads as stuck. A stop over the meaning pass
        // counts that pass; one over only later parts has none, like deepening. Faces come after the files and their search
        // by description (Retriever.runBatch), so a stop counts them only once both are done.
        val looking = when {
            phase == ReadingPhase.FACES -> faces ?: facesWaiting
            phase in STOPS || phase == ReadingPhase.WAITING -> facesWaiting?.takeIf { facesNext }
            else -> null
        }
        // Between batches with only faces or the meaning pass left, their title, never "All files processed" over work waiting.
        val title = when {
            gettingReaders -> c.gettingReadyTitle

            phase == ReadingPhase.WAITING && firstPassDone -> when {
                looking != null -> c.lookingForFacesTitle
                progress.meaningWaiting > 0 -> c.addingDescriptionTitle
                else -> c.allFilesProcessedTitle
            }

            phase == ReadingPhase.WAITING && blocker != StopReason.DISK_FULL ->
                if (sessionActive && blocker == null) c.processingYourFiles else stopCopy(StopReason.PAUSED, words)

            else -> when (phase) {
                ReadingPhase.COUNTING -> c.gettingReadyTitle
                ReadingPhase.NOT_NOW -> c.notNowTitle
                ReadingPhase.MEANING -> c.addingDescriptionTitle
                ReadingPhase.FACES -> c.lookingForFacesTitle
                ReadingPhase.DEEPENING -> c.deepeningTitle
                ReadingPhase.READING -> if (photoWords) c.readingPhotoWordsTitle else c.processingYourFiles
                ReadingPhase.LOOKING -> c.lookingTitle
                ReadingPhase.CAUGHT_UP, ReadingPhase.LIMITED -> c.caughtUpTitle
                else -> stopCopy(blocker ?: StopReason.DONE, words)
            }
        }
        val sentence = if (gettingReaders) {
            c.gettingReadersDetail
        } else {
            when (phase) {
                ReadingPhase.PAUSED -> c.pausedDetail

                ReadingPhase.POWER_SAVE -> c.powerSaveDetail(words)

                ReadingPhase.HOT -> c.hotDetail

                ReadingPhase.LOW_BATTERY -> c.lowBatteryDetail

                ReadingPhase.CHARGING_UP -> c.chargingUpDetail

                ReadingPhase.CHARGING -> c.chargingDetail(words)

                ReadingPhase.MANUAL -> c.manualDetail

                ReadingPhase.MEANING -> c.meaningDetail(words)

                // Faces come last (Retriever.runBatch): every file is processed by now.
                ReadingPhase.FACES -> if (faces == null && people == PeopleModel.GETTING) c.peopleDownloadDetail else c.facesDetail

                ReadingPhase.DEEPENING -> c.deepeningDetail

                ReadingPhase.LIMITED -> c.limitedDetail

                ReadingPhase.READING -> when {
                    progress.meaningWaiting > 0 -> c.readingMeaningDetail
                    else -> c.readingDetail(words)
                }

                ReadingPhase.WAITING if firstPassDone -> when {
                    looking != null -> c.facesWaitingDetail(words)
                    progress.wordsWaiting > 0 -> c.waitingPhotoWordsDetail(words)
                    progress.laterPasses > 0 -> c.waitingLongFilesDetail(words)
                    else -> c.waitingMeaningDetail(words)
                }

                ReadingPhase.WAITING -> if (blocker == StopReason.DISK_FULL) c.diskFullDetail else c.waitingDetail(words)

                ReadingPhase.CAUGHT_UP -> c.caughtUpDetail.takeIf { blocker != StopReason.MANUAL }

                else -> null
            }
        }
        // Between batches too: a Pixel's line flipped from "24,373 of 27,128" to "28,028 of 28,028 files processed", a full
        // bar beside 2,750 photos still waiting.
        val meaning = phase == ReadingPhase.MEANING || ((restStopped || firstPassDone) && progress.meaningWaiting > 0)
        val lead = when {
            people == PeopleModel.WAITING_FOR_WIFI && facesWaiting != null && !running -> c.peopleWaitsForWifi
            phase == ReadingPhase.DEEPENING -> c.deepeningLead(progress.laterPasses)
            photoWords -> c.deepeningLead(progress.wordsWaiting)
            restStopped && !meaning && progress.laterPasses > 0 -> c.longFilesWait(progress.laterPasses)
            restStopped -> c.allFilesProcessedLead
            else -> null
        }
        // Faces keep their own detail: the swap below described the files' pass all over again.
        val filesPass = phase in ACTIVE && phase != ReadingPhase.FACES
        // Already asked for and below the floor: only the charger starts it, so no button that would do nothing.
        val plugIn = requested && onBattery && battery < RESUMED_BATTERY_FLOOR &&
            (phase == ReadingPhase.CHARGING || phase == ReadingPhase.LOW_BATTERY)
        val detail = when {
            plugIn -> c.processingPlugInDetail

            phase == ReadingPhase.CHARGING && onBattery && battery < (if (sessionActive) RESUMED_BATTERY_FLOOR else BATTERY_FLOOR) ->
                c.automaticProcessingPreferenceDetail

            phase == ReadingPhase.WAITING && !firstPassDone && sessionActive && blocker == null -> when {
                onReserve -> c.processingBatteryOverrideDetail
                adaptive -> c.processingAdaptiveDetail
                else -> c.readingDetail(words)
            }

            phase == ReadingPhase.WAITING && !firstPassDone && blocker != StopReason.DISK_FULL -> c.processingReadyDetail

            filesPass && onReserve -> c.processingBatteryOverrideDetail

            filesPass && adaptive -> c.processingAdaptiveDetail

            else -> listOfNotNull(lead, sentence).joinToString(c.sentenceGap).ifEmpty { null }
        }
        // The line always has the one button that changes what happens: Start processing, Pause or Resume.
        val action = when (phase) {
            ReadingPhase.PAUSED -> StatusAction.RESUME

            ReadingPhase.CHARGING if plugIn -> null

            ReadingPhase.CHARGING, ReadingPhase.MANUAL -> if (processingStarted || sessionActive) StatusAction.RESUME else StatusAction.START

            ReadingPhase.NOT_NOW -> StatusAction.START

            ReadingPhase.CAUGHT_UP, ReadingPhase.LIMITED -> StatusAction.DISMISS

            ReadingPhase.READING, ReadingPhase.MEANING, ReadingPhase.DEEPENING, ReadingPhase.FACES -> StatusAction.PAUSE

            ReadingPhase.WAITING -> if (unfinished) {
                if (sessionActive) {
                    StatusAction.PAUSE
                } else if (processingStarted) {
                    StatusAction.RESUME
                } else {
                    StatusAction.START
                }
            } else {
                null
            }

            else -> null
        }
        val (done, total) = when {
            looking != null -> looking.done to looking.done + looking.pending
            meaning -> progress.contentRead - progress.meaningWaiting to progress.contentRead
            else -> progress.processed to progress.libraryTotal
        }
        val laterOnly = phase == ReadingPhase.DEEPENING || photoWords || (restStopped && !meaning && looking == null)
        val none = !progress.counted || laterOnly || (phase == ReadingPhase.FACES && looking == null) || total == 0L
        // Faces are counted once every file is processed; before that their count is unknown, not zero.
        val facesLeft = (faces ?: facesWaiting)?.pending ?: 0L.takeIf { progress.waiting == 0L }
        val journey = journey(progress, phase, peopleOn, textOnly, facesLeft, !photosExcluded, facesNext = looking != null, photoWords)
        if (none) return ReadingStatus(phase, title, detail, action, journey = journey)
        val count = when {
            looking != null -> Words.facesOf(done, total)
            meaning -> Words.meaningOf(done, total)
            else -> Words.readOf(done, total)
        }
        return ReadingStatus(phase, title, detail, action, count, done, total, journey)
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
    val c = Words.strings
    return when {
        kind == Kind.OTHER -> c.foundByOther

        stored?.contentRead == true -> when {
            stored.state == FileState.PENDING -> c.foundByBeginningRestPending

            stored.state == FileState.SKIPPED -> c.foundByPartCouldntFinish

            stored.partial || extent == ReadExtent.FIRST_PART || extent == ReadExtent.FIRST_PART_DEEPENING ||
                extent == ReadExtent.FIRST_PART_MEANING_LIMITED -> c.foundByBeginning

            extent == ReadExtent.MEANING_LIMITED -> c.foundByInsideMeaningLimited

            extent == ReadExtent.DEEPENING -> c.foundByInsideDeepening

            else -> c.foundByInside
        }

        !readInside -> c.foundByReadingOff

        stored?.state == FileState.SKIPPED -> c.foundBySkipped(stored.attempts.toLong())

        stored?.state == FileState.INDEXED -> c.foundByNothingInside

        else -> c.foundByNotYet
    }
}

internal const val SESSION_GENERATION_KEY = "reading_session_generation"

internal const val SETUP_SESSION_KEY = "setup_processing_session"
