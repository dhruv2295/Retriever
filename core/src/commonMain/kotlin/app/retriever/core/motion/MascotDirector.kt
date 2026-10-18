package app.retriever.core.motion

/**
 * What the mascot is doing, set by the screen. The players (Android `Mascot`, iOS `MascotView`) ask [MascotDirector]
 * for the next step each time a step ends or the mood changes; this file is the whole play rule, tested once.
 */
enum class MascotMood {
    /** Reading or downloading is running: the dog walks with its paper. */
    WALKING,

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

    /** Something failed: a still, sheepish pose, never animated. */
    TROUBLE,
}

/**
 * One animated WebP in the shared Frames folder, and its resting pose. 2x the slot width; 12 drawings a second for quick
 * motion, fewer for slow motion (sit 8, welcome 10, doze 5).
 */
enum class MascotClip(val file: String, val still: String, val seconds: Double) {
    WALK("mascot_walk.webp", "mascot_walk_still.webp", 1.41),
    SIT("mascot_sit.webp", "mascot_doze_still.webp", 4.25),
    DOZE("mascot_doze.webp", "mascot_doze_still.webp", 3.2),
    WAKE("mascot_wake.webp", "mascot_walk_still.webp", 3.32),
    SNIFF("mascot_sniff.webp", "mascot_sniff_still.webp", 1.49),
    HUH("mascot_huh.webp", "mascot_huh_still.webp", 2.24),
    WELCOME("mascot_welcome.webp", "mascot_welcome_still.webp", 2.6),
    CAUGHT_UP("mascot_caughtup.webp", "mascot_caughtup_still.webp", 1.33),
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

    /** The pose shown when motion is off (Reduce Motion, Remove animations, off screen, Battery Saver, hot). */
    fun still(mood: MascotMood): MascotClip = when (mood) {
        MascotMood.WALKING -> MascotClip.WALK
        MascotMood.RESTING -> MascotClip.DOZE
        MascotMood.SEARCHING -> MascotClip.SNIFF
        MascotMood.NO_MATCH, MascotMood.NO_MATCH_QUIET -> MascotClip.HUH
        MascotMood.WELCOME -> MascotClip.WELCOME
        MascotMood.CAUGHT_UP -> MascotClip.CAUGHT_UP
        MascotMood.TROUBLE -> MascotClip.TROUBLE
    }

    /**
     * The step after [last] (null: the mascot just appeared, or motion was just turned back on; the gap is never
     * replayed). [onScreen] is seconds since this mascot appeared.
     */
    fun next(mood: MascotMood, last: MascotStep?, onScreen: Double): MascotStep {
        val clip = last?.clip
        return when (mood) {
            MascotMood.WALKING -> when {
                clip == MascotClip.SIT || clip == MascotClip.DOZE -> MascotStep(StepKind.ONCE, MascotClip.WAKE)

                onScreen < SETTLE_AFTER -> MascotStep(StepKind.LOOP, MascotClip.WALK, SETTLE_AFTER - onScreen)

                clip == MascotClip.WALK && last.kind == StepKind.LOOP ->
                    MascotStep(StepKind.HOLD, MascotClip.WALK, CADENCE_REST)

                else -> MascotStep(StepKind.LOOP, MascotClip.WALK, CADENCE_WALK)
            }

            MascotMood.RESTING -> when {
                // Opening a screen while already paused shows the doze, without acting out the sit.
                last == null -> MascotStep(StepKind.HOLD, MascotClip.DOZE)

                clip == MascotClip.WALK || clip == MascotClip.WAKE -> MascotStep(StepKind.ONCE, MascotClip.SIT)

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

            MascotMood.TROUBLE -> MascotStep(StepKind.HOLD, MascotClip.TROUBLE)
        }
    }

    private fun once(target: MascotClip, clip: MascotClip?) = if (clip == target) MascotStep(StepKind.HOLD, target) else MascotStep(StepKind.ONCE, target)
}
