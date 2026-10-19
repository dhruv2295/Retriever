package app.retriever.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Mascot is decorative (no semantics), so placement is checked in the source: each state's EmptyState names its mood. */
class MascotPlacementTest {
    private fun src(path: String) = File("src/main/kotlin/app/retriever/$path").readText()

    /** The mood argument of the EmptyState whose text contains [title]. */
    private fun moodOf(path: String, title: String): String {
        val s = src(path)
        val at = s.indexOf(title)
        assertFalse("$title not in $path", at < 0)
        return Regex("mood = (if \\(\\w+\\) \\S+ else [\\w.]+|[\\w.]+)").find(s.substring(at, minOf(s.length, at + 800)))?.groupValues?.get(1)?.trim()
            ?: "none for $title"
    }

    @Test fun emptyStatesGrin() {
        assertEquals("MascotMood.NO_MATCH", moodOf("browse/BrowseScreen.kt", "Keep useful files close"))
        assertEquals("MascotMood.NO_MATCH", moodOf("browse/BrowseScreen.kt", "Choose files to search"))
        assertEquals("MascotMood.NO_MATCH", moodOf("browse/BrowseScreen.kt", "Nothing here yet"))
        assertEquals("MascotMood.NO_MATCH", moodOf("duplicates/DuplicatesScreen.kt", "No duplicates found"))
        assertEquals("if (loadFailed) MascotMood.TROUBLE else MascotMood.NO_MATCH", moodOf("folders/FoldersScreen.kt", "No folders yet"))
        assertEquals("mood", moodOf("search/SearchResults.kt", "No matches for"))
        assertEquals("mood", moodOf("search/SearchResults.kt", "Nothing like this yet"))
        assertEquals("mood", moodOf("search/SearchResults.kt", "No matches in your"))
    }

    @Test fun failuresUseTroubleNeverNoMatch() {
        assertEquals("MascotMood.TROUBLE", moodOf("search/SearchResults.kt", "Search hit a snag"))
        assertEquals("MascotMood.TROUBLE", moodOf("duplicates/DuplicatesScreen.kt", "Couldn't look for duplicates"))
        assertEquals("MascotMood.TROUBLE", moodOf("browse/BrowseScreen.kt", "Couldn't load your files"))
        for ((f, t) in listOf(
            "viewer/PlayerScreen.kt" to "Couldn't play this",
            "viewer/PhotoScreen.kt" to "Couldn't show this photo",
            "viewer/PdfPageScreen.kt" to "Couldn't open this PDF",
            "viewer/DocScreen.kt" to "Couldn't open this file",
        )) {
            assertEquals(f, "MascotMood.TROUBLE", moodOf(f, t))
        }
    }

    @Test fun workingScreensWalkAndNoErrorKeepsTheOldArt() {
        assertFalse(src("duplicates/DuplicatesScreen.kt").substringAfter("Looking for copies").isEmpty())
        assertEquals(true, src("duplicates/DuplicatesScreen.kt").contains("Mascot(MascotMood.WALKING"))
        assertEquals(true, src("home/HomeScreen.kt").contains("if (looking) MascotMood.WALKING else MascotMood.NO_MATCH"))
        for (f in listOf(
            "browse/BrowseScreen.kt",
            "folders/FoldersScreen.kt",
            "duplicates/DuplicatesScreen.kt",
            "home/HomeScreen.kt",
            "search/SearchResults.kt",
        )) {
            assertFalse("$f still paints illus_*", Regex("illus_(empty|reading)").containsMatchIn(src(f)))
        }
    }
}
