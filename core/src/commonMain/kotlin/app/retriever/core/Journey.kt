package app.retriever.core

/** Processing's stages, in the order Retriever.runBatch runs them; each one unlocks a way to search. */
enum class Stage {
    NAMES,
    MEANING,
    WORDS,
    PEOPLE,
    REST,
}

enum class StageState {
    DONE,
    NOW,
    NEXT,

    /** People while it is off: optional, never pending. */
    OFF,
}

/**
 * One checklist row: [label] says what the stage unlocks, [stateLabel] its state in words (for TalkBack and VoiceOver too),
 * [left] what it still has to do, null while unknown.
 */
data class StageRow(val stage: Stage, val state: StageState, val label: String, val stateLabel: String, val left: Long? = null)

/**
 * The stages as one journey, read from the counts [ReadingPolicy.decide] already has. [step]: "Step 2 of 4", numbering only
 * the stages after the name scan that apply here, so a skipped stage never leaves a gap; null with no stage running.
 * [note]: a stage that just finished, for its one "now you can" moment (Retriever sets it from [Prefs.stageNote]); its words
 * are [Strings.stageNoteTitle] and [Strings.stageNote] with [textOnly].
 */
data class Journey(val rows: List<StageRow>, val step: String? = null, val note: Stage? = null, val textOnly: Boolean = false) {
    val now: StageRow? get() = rows.firstOrNull { it.state == StageState.NOW }
    val finished: Boolean get() = rows.none { it.state == StageState.NOW || it.state == StageState.NEXT }
}

/**
 * [phase], [facesNext] and [photoWords]: decide's own, so the stage it calls running is the one its count is for (faces, once
 * begun, run ahead of new files, a stop included). [facesLeft]: null while unknown (the count waits until every file is
 * processed, Retriever.readingStatus). Text-only phones read photos' words in the first pass, and a library without photos
 * ([photos] false) has none to read, so neither has a words stage. The long files' rest shows only while some file has later
 * parts, or files still to process may: a phone with none stops counting it once its first pass is done.
 */
internal fun journey(
    progress: ReadingProgress,
    phase: ReadingPhase,
    peopleOn: Boolean,
    textOnly: Boolean,
    facesLeft: Long?,
    photos: Boolean = true,
    facesNext: Boolean = false,
    photoWords: Boolean = false,
): Journey? {
    if (progress.sample || progress.notNow) return null
    val c = Words.strings
    fun left(stage: Stage): Long? = when (stage) {
        Stage.NAMES -> if (progress.counted) 0L else null
        Stage.MEANING -> progress.scheduled + progress.rereads + progress.meaningWaiting
        Stage.WORDS -> progress.wordsWaiting
        Stage.PEOPLE -> facesLeft
        Stage.REST -> progress.laterPasses
    }
    // Files waiting for search by description hold their later parts back until it is added (Store's outcome).
    val restMayCome = progress.laterPasses > 0 || progress.waiting > 0 || progress.rereads > 0 || progress.meaningWaiting > 0 || !progress.counted
    val stages = Stage.entries.filter {
        when (it) {
            Stage.WORDS -> photos && !textOnly
            Stage.REST -> restMayCome
            else -> true
        }
    }
    val now = when {
        phase == ReadingPhase.COUNTING -> Stage.NAMES
        phase == ReadingPhase.FACES || (facesNext && peopleOn) -> Stage.PEOPLE
        phase == ReadingPhase.DEEPENING -> Stage.REST
        photoWords && Stage.WORDS in stages -> Stage.WORDS
        phase == ReadingPhase.CAUGHT_UP || phase == ReadingPhase.LIMITED -> null
        else -> stages.firstOrNull { (it != Stage.PEOPLE || peopleOn) && (left(it) ?: 1L) > 0 }
    }
    val rows = stages.map { stage ->
        val state = when {
            stage == Stage.PEOPLE && !peopleOn -> StageState.OFF
            stage == now -> StageState.NOW
            left(stage) == 0L && (now == null || stage < now) -> StageState.DONE
            else -> StageState.NEXT
        }
        StageRow(stage, state, c.stageUnlock(stage, textOnly), c.stageStateLabel(state), left(stage))
    }
    val numbered = rows.filter { it.stage != Stage.NAMES && it.state != StageState.OFF }
    // A stop keeps its reason as the title; the checklist names the stage it holds.
    val step = numbered.indexOfFirst { it.state == StageState.NOW }.takeIf { it >= 0 }?.let { c.stepOf(it + 1, numbered.size) }
    return Journey(rows, step, textOnly = textOnly)
}

/**
 * The journey as the person has lived it, from [Prefs.stagesDone]: a stage once finished stays ticked while an earlier one
 * takes new files, and a finished stage with work again holds new files ([Strings.newFiles]), never "Step 1 of 4" over a
 * journey already made. A stage newly finished gets the one note ([Prefs.stageNote]); the first sighting only records, so
 * an update never fires every stage's note at once. Once every stage is done the line's "All done" is the one celebration:
 * the last stage's note is dropped, not kept to surface beside new files weeks later. People's Turn on shows only where it
 * can work ([peopleOffered]). Returns what to show, and the prefs to save when they change.
 */
internal fun Journey.remembered(prefs: Prefs, peopleOffered: Boolean): Pair<Journey, Prefs?> {
    val known = prefs.stagesDone
    val c = Words.strings
    val shown = copy(
        // The long files' rest, finished before and with nothing left, would only come back ticked with each batch of new files.
        rows = rows.filter {
            (it.stage != Stage.PEOPLE || it.state != StageState.OFF || peopleOffered) &&
                !(it.stage == Stage.REST && it.left == 0L && it.state != StageState.NOW && known.orEmpty().contains(Stage.REST))
        }.map {
            if (it.state == StageState.NEXT && it.left == 0L && known.orEmpty().contains(it.stage)) {
                it.copy(state = StageState.DONE, stateLabel = c.stageStateLabel(StageState.DONE))
            } else {
                it
            }
        },
    )
    // A finished journey has finished the long files' rest too, shown or not, so a long file later reads as new files.
    val done = shown.rows.filter { it.state == StageState.DONE }.map { it.stage }.toSet() + listOfNotNull(Stage.REST.takeIf { shown.finished })
    val note = when {
        shown.finished -> null
        known == null -> prefs.stageNote
        else -> (done - known - Stage.NAMES).maxOrNull() ?: prefs.stageNote
    }
    val saved = prefs.copy(stagesDone = known.orEmpty() + done, stageNote = note).takeIf { it != prefs }
    val running = now?.stage
    val step = if (running != null && running != Stage.NAMES && known.orEmpty().contains(running)) c.newFiles else step
    return shown.copy(step = step, note = note) to saved
}
