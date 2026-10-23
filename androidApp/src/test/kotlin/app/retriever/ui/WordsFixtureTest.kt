package app.retriever.ui

import app.retriever.core.DeviceWords
import app.retriever.core.ReadingPolicy
import app.retriever.core.StopReason
import app.retriever.core.Words
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/** core/fixtures/words.tsv, asserted here and in iOS's WordsFixtureTests: both apps show the same shared sentences. */
class WordsFixtureTest {
    @Before fun locale() = Locale.setDefault(Locale.US)

    @Test fun everyFixtureRowReadsTheSame() {
        val words = mapOf("android" to DeviceWords.ANDROID, "iphone" to DeviceWords.ios("iPhone"), "ipad" to DeviceWords.ios("iPad"))
        val rows = File("../core/fixtures/words.tsv").readLines().filterNot { it.startsWith("#") }.map { it.split('\t') }
        for (row in rows) {
            val actual = when (row[0]) {
                "stop" -> ReadingPolicy.stopCopy(StopReason.valueOf(row[1]), words.getValue(row[2]))
                "spoken" -> Words.spokenPosition(row[1])
                "readCount" -> Words.readCount(row[1].toLong(), row[2].toLong())
                "textOnlyNote" -> Words.textOnlyNote(row[1].toDouble(), words.getValue(row[2]))
                else -> error("unknown row ${row[0]}")
            }
            assertEquals(row.toString(), row.last().ifEmpty { null }, actual)
        }
        assertEquals(29, rows.size)
    }
}
