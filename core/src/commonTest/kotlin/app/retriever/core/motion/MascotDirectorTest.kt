package app.retriever.core.motion

import app.retriever.core.Stage
import app.retriever.core.motion.MascotClip.DOZE
import app.retriever.core.motion.MascotClip.HUH
import app.retriever.core.motion.MascotClip.SIT
import app.retriever.core.motion.MascotClip.SNIFF
import app.retriever.core.motion.MascotClip.WAKE
import app.retriever.core.motion.MascotClip.WALK
import app.retriever.core.motion.StepKind.HOLD
import app.retriever.core.motion.StepKind.LOOP
import app.retriever.core.motion.StepKind.ONCE
import kotlin.test.Test
import kotlin.test.assertEquals

class MascotDirectorTest {
    /**
     * Plays steps the way a player does: a one-shot runs to its end; a loop runs until its time is up or the mood
     * changes, then finishes the loop it is in; a hold lasts its time or until the mood changes.
     */
    private fun run(mood: (Double) -> MascotMood, until: Double, settles: Boolean = true): List<Pair<Double, MascotStep>> {
        var t = 0.0
        var last: MascotStep? = null
        val out = mutableListOf<Pair<Double, MascotStep>>()
        while (t < until) {
            val now = mood(t)
            val step = MascotDirector.next(now, last, t, settles)
            out += t to step
            var changeAt = t
            while (changeAt < until && mood(changeAt) == now) changeAt += 0.05
            val end = minOf(changeAt, t + (step.seconds ?: Double.MAX_VALUE))
            t = when (step.kind) {
                ONCE -> t + step.clip.seconds
                LOOP -> t + maxOf(1.0, kotlin.math.ceil((end - t) / step.clip.seconds)) * step.clip.seconds
                HOLD -> end
            }
            last = step
        }
        return out
    }

    @Test
    fun walksSteadilyThenSettlesIntoACadenceAfterTwoMinutes() {
        val steps = run({ MascotMood.WALKING }, 400.0)
        assertEquals(MascotStep(LOOP, WALK, 120.0), steps.first().second)
        val after = steps.filter { it.first >= 120.0 }.map { it.second }
        assertEquals(listOf(HOLD to WALK, LOOP to WALK, HOLD to WALK), after.take(3).map { it.kind to it.clip })
        assertEquals(MascotDirector.CADENCE_REST, after.first { it.kind == HOLD }.seconds)
        assertEquals(MascotDirector.CADENCE_WALK, after.first { it.kind == LOOP }.seconds)
    }

    @Test
    fun setupKeepsWalkingForAsLongAsItIsOnScreen() {
        val steps = run({ MascotMood.WALKING }, 3600.0, settles = false).map { it.second }
        assertEquals(true, steps.all { it.kind == LOOP && it.clip == WALK })
    }

    @Test
    fun setupStillWakesAndRestsLikeEverywhereElse() {
        assertEquals(MascotStep(ONCE, MascotClip.WAKE), MascotDirector.next(MascotMood.WALKING, MascotStep(HOLD, DOZE), 300.0, settles = false))
        assertEquals(MascotStep(ONCE, MascotClip.SIT), MascotDirector.next(MascotMood.RESTING, MascotStep(LOOP, WALK), 300.0, settles = false))
    }

    @Test
    fun pausingSitsDozesThreeBreathsThenHoldsStill() {
        val steps = run({ if (it < 5) MascotMood.WALKING else MascotMood.RESTING }, 60.0).map { it.second }
        val rest = steps.dropWhile { it.clip == WALK }
        assertEquals(MascotStep(ONCE, SIT), rest[0])
        assertEquals(MascotStep(LOOP, DOZE, 3 * DOZE.seconds), rest[1])
        assertEquals(MascotStep(HOLD, DOZE), rest[2])
    }

    @Test
    fun openingAScreenWhilePausedShowsTheDozeWithoutTheSit() {
        assertEquals(MascotStep(HOLD, DOZE), MascotDirector.next(MascotMood.RESTING, null, 0.0))
    }

    @Test
    fun resumingWakesThenWalks() {
        for (from in listOf(MascotStep(HOLD, DOZE), MascotStep(LOOP, DOZE), MascotStep(ONCE, SIT))) {
            val wake = MascotDirector.next(MascotMood.WALKING, from, 30.0)
            assertEquals(MascotStep(ONCE, WAKE), wake)
            assertEquals(WALK, MascotDirector.next(MascotMood.WALKING, wake, 33.0).clip)
        }
    }

    @Test
    fun pausingRightAfterWakingSitsAgain() {
        assertEquals(MascotStep(ONCE, SIT), MascotDirector.next(MascotMood.RESTING, MascotStep(ONCE, WAKE), 10.0))
    }

    @Test
    fun sniffsTwiceThenHoldsAndNoMatchLooksUpOnce() {
        val steps = run({ if (it < 6) MascotMood.SEARCHING else MascotMood.NO_MATCH }, 20.0).map { it.second }
        assertEquals(
            listOf(MascotStep(LOOP, SNIFF, 2 * SNIFF.seconds), MascotStep(HOLD, SNIFF), MascotStep(ONCE, HUH), MascotStep(HOLD, HUH)),
            steps,
        )
    }

    @Test
    fun quietNoMatchNeverMoves() {
        assertEquals(MascotStep(HOLD, HUH), MascotDirector.next(MascotMood.NO_MATCH_QUIET, null, 0.0))
        assertEquals(MascotStep(HOLD, MascotClip.TROUBLE), MascotDirector.next(MascotMood.TROUBLE, null, 0.0))
        assertEquals(MascotClip.TROUBLE, MascotDirector.still(MascotMood.TROUBLE))
        assertEquals(MascotStep(HOLD, HUH), MascotDirector.next(MascotMood.NO_MATCH_QUIET, MascotStep(LOOP, SNIFF), 3.0))
    }

    @Test
    fun welcomeAndCaughtUpPlayOnceThenHold() {
        for (mood in listOf(MascotMood.WELCOME, MascotMood.CAUGHT_UP, MascotMood.FOUND_IT)) {
            val first = MascotDirector.next(mood, null, 0.0)
            assertEquals(ONCE, first.kind)
            assertEquals(MascotStep(HOLD, first.clip), MascotDirector.next(mood, first, 3.0))
        }
    }

    @Test
    fun aRunningStageLoopsItsClipAndSitsWhenHeld() {
        assertEquals(MascotMood.GREETING, MascotDirector.working(Stage.PEOPLE))
        assertEquals(MascotMood.WALKING, MascotDirector.working(null))
        val steps = run({ if (it < 30) MascotDirector.working(Stage.WORDS) else MascotMood.RESTING }, 40.0).map { it.second }
        assertEquals(listOf(LOOP, ONCE, LOOP), steps.take(3).map { it.kind })
        assertEquals(listOf(MascotClip.RECEIPT, SIT, DOZE), steps.take(3).map { it.clip })
        // Long on screen, a stage settles into the same walk-and-rest cadence as the walk.
        val late = MascotDirector.next(MascotMood.DIGGING, MascotStep(LOOP, MascotClip.DIG), 200.0)
        assertEquals(MascotStep(HOLD, MascotClip.DIG, MascotDirector.CADENCE_REST), late)
    }

    @Test
    fun everyMoodHasAStillForReducedMotion() {
        for (mood in MascotMood.entries) assertEquals(true, MascotDirector.still(mood).still.endsWith("_still.webp"))
        assertEquals(DOZE, MascotDirector.still(MascotMood.RESTING))
    }
}
