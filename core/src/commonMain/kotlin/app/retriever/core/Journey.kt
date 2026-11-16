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
 * [phase] and [title]: decide's own, so the stage it calls running is the one the line names (faces, once begun, run ahead
 * of new files). [facesLeft]: null while unknown (the count waits until every file is processed, Retriever.readingStatus).
 * Text-only phones read photos' words in the first pass, so their journey has no separate words stage.
 */
internal fun journey(progress: ReadingProgress, phase: ReadingPhase, title: String, peopleOn: Boolean, textOnly: Boolean, facesLeft: Long?): Journey? {
    if (progress.sample || progress.notNow) return null
    val c = Words.strings
    fun left(stage: Stage): Long? = when (stage) {
        Stage.NAMES -> if (progress.counted) 0L else null
        Stage.MEANING -> progress.scheduled + progress.rereads + progress.meaningWaiting
        Stage.WORDS -> progress.wordsWaiting
        Stage.PEOPLE -> facesLeft
        Stage.REST -> progress.laterPasses
    }
    val stages = Stage.entries.filter { !(it == Stage.WORDS && textOnly) }
    val now = when (phase) {
        ReadingPhase.COUNTING -> Stage.NAMES
        ReadingPhase.FACES -> Stage.PEOPLE
        ReadingPhase.DEEPENING -> Stage.REST
        ReadingPhase.CAUGHT_UP, ReadingPhase.LIMITED -> null
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
    val step = numbered.indexOfFirst { it.state == StageState.NOW }.takeIf { it >= 0 }?.let { i ->
        // A stop or a wait keeps its own title; the step line then names the stage it holds.
        c.stepOf(i + 1, numbered.size, stageTitle(numbered[i].stage, progress).takeIf { it != title })
    }
    return Journey(rows, step, textOnly = textOnly)
}

/** The title a running stage has on the line, the four existing ones. */
internal fun stageTitle(stage: Stage, progress: ReadingProgress): String {
    val c = Words.strings
    return when (stage) {
        Stage.NAMES -> c.gettingReadyTitle
        Stage.MEANING -> if (progress.scheduled == 0L && progress.meaningWaiting > 0) c.addingDescriptionTitle else c.processingYourFiles
        Stage.WORDS -> c.readingPhotoWordsTitle
        Stage.PEOPLE -> c.lookingForFacesTitle
        Stage.REST -> c.deepeningTitle
    }
}

/**
 * The journey as the person has lived it, from [Prefs.stagesDone]: a stage once finished stays ticked while an earlier one
 * takes new files, and a finished stage with work again holds new files ([Strings.newFiles]), never "Step 1 of 4" over a
 * journey already made. A stage newly finished gets the one note ([Prefs.stageNote]); the first sighting only records, so
 * an update never fires every stage's note at once. People's Turn on shows only where it can work ([peopleOffered]).
 * Returns what to show, and the prefs to save when they change.
 */
internal fun Journey.remembered(prefs: Prefs, peopleOffered: Boolean): Pair<Journey, Prefs?> {
    val known = prefs.stagesDone
    val c = Words.strings
    val shown = rows.filter { it.stage != Stage.PEOPLE || it.state != StageState.OFF || peopleOffered }.map {
        if (it.state == StageState.NEXT && it.left == 0L && known.orEmpty().contains(it.stage)) {
            it.copy(state = StageState.DONE, stateLabel = c.stageStateLabel(StageState.DONE))
        } else {
            it
        }
    }
    val done = shown.filter { it.state == StageState.DONE }.map { it.stage }.toSet()
    val saved = if (known == null || !known.containsAll(done)) {
        val note = if (known == null) prefs.stageNote else (done - known - Stage.NAMES).maxOrNull() ?: prefs.stageNote
        prefs.copy(stagesDone = known.orEmpty() + done, stageNote = note)
    } else {
        null
    }
    val running = now?.stage
    val step = if (running != null && running != Stage.NAMES && known.orEmpty().contains(running)) c.newFiles else step
    return copy(rows = shown, step = step, note = (saved ?: prefs).stageNote) to saved
}
