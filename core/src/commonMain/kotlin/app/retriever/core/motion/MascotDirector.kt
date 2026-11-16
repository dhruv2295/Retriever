package app.retriever.core.motion

import app.retriever.core.Stage

/**
 * What the mascot is doing, set by the screen. The players (Android `Mascot`, iOS `MascotView`) ask [MascotDirector]
 * for the next step each time a step ends or the mood changes; this file is the whole play rule, tested once.
 */
enum class MascotMood {
    /** Reading or downloading is running: the dog walks with its paper. */
    WALKING,

    /** The name scan runs: it sniffs along a row of folders. */
    SNIFFING_FOLDERS,

    /** Photos' meaning is being read: it tilts its head at a photo. */
    STUDYING_PHOTO,

    /** Words are being read: nose to a receipt. */
    READING_RECEIPT,

    /** Faces are being grouped: it wags at a person. */
    GREETING,

    /** The rest of long files: it digs into a long scroll. */
    DIGGING,

    /** Reading is paused (charger, phone in use, cooling down, Pause): it sits and dozes. */
    RESTING,

    /** Meaning results are still coming and nothing has matched yet: it sniffs the ground. */
    SEARCHING,

    /** Nothing matches: it looks up with a curious "huh?". */
    NO_MATCH,

    /** Same as [NO_MATCH] without the motion, for the screen to use once the person has seen it a few times. */
    NO_MATCH_QUIET,

    /** First-run welcome: one hello. */
    WELCOME,

    /** First open after the first full read: one happy bounce. */
    CAUGHT_UP,

    /** A stage just finished: one "found it", something in its mouth. */
    FOUND_IT,

    /** Something failed: a still, sheepish pose, never animated. */
    TROUBLE,
}

/**
 * One animated WebP in the shared Frames folder, and its resting pose. 2x the slot width; 12 drawings a second for quick
 * motion, fewer for slow motion (sit 8, welcome 10, doze 5). The stage clips and FOUND are drawn pose sheets, not video:
 * six drawings each, the loops played back and forth.
 */
enum class MascotClip(val file: String, val still: String, val seconds: Double) {
    WALK("mascot_walk.webp", "mascot_walk_still.webp", 1.25),
    SIT("mascot_sit.webp", "mascot_doze_still.webp", 4.25),
    DOZE("mascot_doze.webp", "mascot_doze_still.webp", 3.2),
    WAKE("mascot_wake.webp", "mascot_walk_still.webp", 3.32),
    SNIFF("mascot_sniff.webp", "mascot_sniff_still.webp", 1.49),
    HUH("mascot_huh.webp", "mascot_huh_still.webp", 2.24),
    WELCOME("mascot_welcome.webp", "mascot_welcome_still.webp", 2.6),
    CAUGHT_UP("mascot_caughtup.webp", "mascot_caughtup_still.webp", 1.33),
    FOLDERS("mascot_folders.webp", "mascot_folders_still.webp", 1.25),
    PHOTO("mascot_photo.webp", "mascot_photo_still.webp", 1.67),
    RECEIPT("mascot_receipt.webp", "mascot_receipt_still.webp", 2.0),
    GREET("mascot_greet.webp", "mascot_greet_still.webp", 1.0),
    DIG("mascot_dig.webp", "mascot_dig_still.webp", 0.6),
    FOUND("mascot_found.webp", "mascot_found_still.webp", 1.0),
    TROUBLE("mascot_trouble_still.webp", "mascot_trouble_still.webp", 0.0),
}

enum class StepKind {
    /** Loop the clip; when [MascotStep.seconds] pass or the mood changes, finish the current loop, then ask again. */
    LOOP,

    /** Play the clip once and hold its last frame. */
    ONCE,

    /** Show the clip's still for [MascotStep.seconds] (null: until the mood changes). */
    HOLD,
}

data class MascotStep(val kind: StepKind, val clip: MascotClip, val seconds: Double? = null)

object MascotDirector {
    /** After this long on one screen the walk turns into a cadence, so hours on Status stay cheap and calm. */
    const val SETTLE_AFTER = 120.0
    const val CADENCE_WALK = 10.0
    const val CADENCE_REST = 50.0
    const val DOZE_BREATHS = 3
    const val SNIFF_LOOPS = 2

    /** What the dog does while [stage] runs (null: no stage known, so it walks). */
    fun working(stage: Stage?): MascotMood = when (stage) {
        Stage.NAMES -> MascotMood.SNIFFING_FOLDERS
        Stage.MEANING -> MascotMood.STUDYING_PHOTO
        Stage.WORDS -> MascotMood.READING_RECEIPT
        Stage.PEOPLE -> MascotMood.GREETING
        Stage.REST -> MascotMood.DIGGING
        null -> MascotMood.WALKING
    }

    /** The pose shown when motion is off (Reduce Motion, Remove animations, off screen, Battery Saver, hot). */
    fun still(mood: MascotMood): MascotClip = when (mood) {
        MascotMood.WALKING -> MascotClip.WALK
        MascotMood.SNIFFING_FOLDERS -> MascotClip.FOLDERS
        MascotMood.STUDYING_PHOTO -> MascotClip.PHOTO
        MascotMood.READING_RECEIPT -> MascotClip.RECEIPT
        MascotMood.GREETING -> MascotClip.GREET
        MascotMood.DIGGING -> MascotClip.DIG
        MascotMood.RESTING -> MascotClip.DOZE
        MascotMood.SEARCHING -> MascotClip.SNIFF
        MascotMood.NO_MATCH, MascotMood.NO_MATCH_QUIET -> MascotClip.HUH
        MascotMood.WELCOME -> MascotClip.WELCOME
        MascotMood.CAUGHT_UP -> MascotClip.CAUGHT_UP
        MascotMood.FOUND_IT -> MascotClip.FOUND
        MascotMood.TROUBLE -> MascotClip.TROUBLE
    }

    /**
     * The step after [last] (null: the mascot just appeared, or motion was just turned back on; the gap is never
     * replayed). [onScreen] is seconds since this mascot appeared. [settles] false keeps WALKING walking for as long as
     * it is on screen (setup: a download that stands still reads as stuck); true settles into the cadence after
     * [SETTLE_AFTER], for Home and Status where reading runs for hours.
     */
    fun next(mood: MascotMood, last: MascotStep?, onScreen: Double, settles: Boolean = true): MascotStep {
        val clip = last?.clip
        return when (mood) {
            MascotMood.WALKING, MascotMood.SNIFFING_FOLDERS, MascotMood.STUDYING_PHOTO, MascotMood.READING_RECEIPT,
            MascotMood.GREETING, MascotMood.DIGGING,
            -> work(still(mood), last, onScreen, settles)

            MascotMood.RESTING -> when {
                // Opening a screen while already paused shows the doze, without acting out the sit.
                last == null -> MascotStep(StepKind.HOLD, MascotClip.DOZE)

                clip in workClips || clip == MascotClip.WAKE -> MascotStep(StepKind.ONCE, MascotClip.SIT)

                clip == MascotClip.SIT -> MascotStep(StepKind.LOOP, MascotClip.DOZE, DOZE_BREATHS * MascotClip.DOZE.seconds)

                else -> MascotStep(StepKind.HOLD, MascotClip.DOZE)
            }

            MascotMood.SEARCHING -> when {
                clip == MascotClip.SNIFF -> MascotStep(StepKind.HOLD, MascotClip.SNIFF)
                else -> MascotStep(StepKind.LOOP, MascotClip.SNIFF, SNIFF_LOOPS * MascotClip.SNIFF.seconds)
            }

            MascotMood.NO_MATCH -> when (clip) {
                MascotClip.HUH -> MascotStep(StepKind.HOLD, MascotClip.HUH)
                else -> MascotStep(StepKind.ONCE, MascotClip.HUH)
            }

            MascotMood.NO_MATCH_QUIET -> MascotStep(StepKind.HOLD, MascotClip.HUH)

            MascotMood.WELCOME -> once(MascotClip.WELCOME, clip)

            MascotMood.CAUGHT_UP -> once(MascotClip.CAUGHT_UP, clip)

            MascotMood.FOUND_IT -> once(MascotClip.FOUND, clip)

            MascotMood.TROUBLE -> MascotStep(StepKind.HOLD, MascotClip.TROUBLE)
        }
    }

    private val workClips = setOf(MascotClip.WALK, MascotClip.FOLDERS, MascotClip.PHOTO, MascotClip.RECEIPT, MascotClip.GREET, MascotClip.DIG)

    /** [target] loops, settling into the walk-and-rest cadence; only the walk acts out waking from a doze first. */
    private fun work(target: MascotClip, last: MascotStep?, onScreen: Double, settles: Boolean): MascotStep {
        val clip = last?.clip
        return when {
            target == MascotClip.WALK && (clip == MascotClip.SIT || clip == MascotClip.DOZE) -> MascotStep(StepKind.ONCE, MascotClip.WAKE)
            !settles -> MascotStep(StepKind.LOOP, target, SETTLE_AFTER)
            onScreen < SETTLE_AFTER -> MascotStep(StepKind.LOOP, target, SETTLE_AFTER - onScreen)
            clip == target && last.kind == StepKind.LOOP -> MascotStep(StepKind.HOLD, target, CADENCE_REST)
            else -> MascotStep(StepKind.LOOP, target, CADENCE_WALK)
        }
    }

    private fun once(target: MascotClip, clip: MascotClip?) = if (clip == target) MascotStep(StepKind.HOLD, target) else MascotStep(StepKind.ONCE, target)
}
