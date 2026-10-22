package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals

class OcrScriptsTest {
    private val latin = OcrScript.LATIN

    @Test
    fun latinIsAlwaysIn() {
        assertEquals(setOf(latin), ocrScripts(emptyList()))
        assertEquals(setOf(latin), ocrScripts(listOf("en-US", "fr-FR", "es")))
    }

    @Test
    fun devanagariLanguagesAddDevanagari() {
        for (tag in listOf("hi", "hi-IN", "mr-IN", "ne-NP", "sa", "kok-IN")) {
            assertEquals(setOf(latin, OcrScript.DEVANAGARI), ocrScripts(listOf("en-IN", tag)), tag)
        }
    }

    @Test
    fun eastAsianLanguagesAddTheirOwnScript() {
        assertEquals(setOf(latin, OcrScript.CHINESE), ocrScripts(listOf("zh-Hans-CN")))
        assertEquals(setOf(latin, OcrScript.JAPANESE), ocrScripts(listOf("ja-JP")))
        assertEquals(setOf(latin, OcrScript.KOREAN), ocrScripts(listOf("ko_KR")))
    }

    @Test
    fun severalLanguagesUnion() {
        assertEquals(
            setOf(latin, OcrScript.DEVANAGARI, OcrScript.JAPANESE),
            ocrScripts(listOf("en-IN", "hi-IN", "ja-JP")),
        )
    }

    @Test
    fun theFirstLanguageGoesFirstAndDevanagariIsAlwaysAFallback() {
        assertEquals(listOf(latin, OcrScript.DEVANAGARI), ocrOrder(listOf("en-IN")))
        assertEquals(listOf(OcrScript.DEVANAGARI, latin), ocrOrder(listOf("hi-IN", "en-IN")))
        assertEquals(listOf(OcrScript.CHINESE, latin, OcrScript.DEVANAGARI), ocrOrder(listOf("zh-CN")))
        assertEquals(listOf(latin, OcrScript.JAPANESE, OcrScript.DEVANAGARI), ocrOrder(listOf("en-US", "ja-JP")))
    }

    @Test
    fun aClearReadingStopsTheFallbackAndTheBestReadingWins() = kotlinx.coroutines.test.runTest {
        val english = "Electricity bill for September, amount due 1,240 by the 5th"
        val hindi = "बिजली बिल सितंबर के लिए, कुल राशि 1,240 रुपये पाँच तारीख तक देय"
        val tried = mutableListOf<OcrScript>()
        val order = ocrOrder(listOf("en-IN"))
        fun sure(text: String) = OcrReading(text, 0.85f)
        // An English image: Latin reads it, so Devanagari never runs.
        assertEquals(
            english,
            readScripts(order) {
                tried += it
                sure(if (it == latin) english else hindi)
            },
        )
        assertEquals(listOf(latin), tried)
        // A Hindi bill on an English phone: Latin gets a few stray letters, Devanagari reads it.
        tried.clear()
        assertEquals(
            hindi,
            readScripts(order) {
                tried += it
                if (it == latin) OcrReading("ad 1,240", 0.4f) else sure(hindi)
            },
        )
        assertEquals(listOf(latin, OcrScript.DEVANAGARI), tried)
        // Or Latin guesses a long line of letters it doubts (as ML Kit does on Hindi): Devanagari still reads it.
        val guessed = "fastft faT y Ifer: 1,240 3ifaH fafe: 25 34Gr HGYT: 4471"
        assertEquals(hindi, readScripts(order) { if (it == latin) OcrReading(guessed, 0.42f) else OcrReading(hindi, 0.81f) })
        // Nothing reads well: the more confident reading stays.
        assertEquals("1,240 due", readScripts(order) { if (it == latin) OcrReading("1,240 due", 0.5f) else OcrReading.NONE })
    }

    @Test
    fun aBlankReadingStopsTheFallback() = kotlinx.coroutines.test.runTest {
        var reads = 0
        assertEquals(
            "",
            readScripts(ocrOrder(listOf("en-IN"))) {
                reads++
                OcrReading.NONE
            },
        )
        assertEquals(1, reads)
    }

    @Test
    fun aShortConfidentReadingStopsTheFallback() = kotlinx.coroutines.test.runTest {
        var reads = 0
        assertEquals(
            "Wi-Fi off",
            readScripts(ocrOrder(listOf("en-IN"))) {
                reads++
                OcrReading("Wi-Fi off", 0.9f)
            },
        )
        assertEquals(1, reads)
    }

    @Test
    fun aLowConfidenceGarbageReadingStillFallsThrough() = kotlinx.coroutines.test.runTest {
        var reads = 0
        readScripts(ocrOrder(listOf("en-IN"))) {
            reads++
            OcrReading("fastft faT y", 0.3f)
        }
        assertEquals(2, reads)
    }
}
