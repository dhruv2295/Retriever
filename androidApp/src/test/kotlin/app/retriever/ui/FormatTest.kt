package app.retriever.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.Locale

class FormatTest {
    @Before fun locale() = Locale.setDefault(Locale.US)

    @Test fun tryItHeadersCountTheScope() {
        assertEquals("Closest in these 79 files", Format.closestIn(79))
        assertEquals("Still reading 1,200 more", Format.stillReading(1200))
    }

    @Test fun uncalibratedCommitSentenceNamesNoTime() {
        assertEquals("Reading the other 26,214 takes hours; Retriever measures this phone as it goes.", Format.restSentence(26_214, null))
        assertEquals("Reading the rest takes hours; Retriever measures this phone as it goes.", Format.restSentence(null, null))
    }

    @Test fun noHourFigureWhileUncalibrated() {
        val figure = Regex("""\d+(,\d+)?( to \d+)? (hours?|minutes?|nights?|days?)""")
        listOf(Format.restSentence(26_214, null), Format.restSentence(null, null)).forEach { assertFalse(it, figure.containsMatchIn(it)) }
    }

    @Test fun rangesForWhenTheEstimateIsCalibrated() {
        assertEquals("40 to 55 minutes", Format.minutesRange(40, 55))
        assertEquals("1 minute", Format.minutesRange(1, 1))
        assertEquals("5 to 7 hours", Format.minutesRange(300, 420))
        assertEquals("2 hours", Format.minutesRange(110, 140))
    }
}
