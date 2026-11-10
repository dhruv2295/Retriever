package app.retriever.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Mascot is decorative (no semantics), so placement is checked in the source: each state's EmptyState names its mood. */
class MascotPlacementTest {
    private fun src(path: String) = File("src/main/kotlin/app/retriever/$path").readText()

    /** The mood argument of the EmptyState whose text contains [title] (a Words name or a `strings.` member). */
    private fun moodOf(path: String, title: String): String {
        val s = src(path)
        val at = s.indexOf(title)
        assertFalse("$title not in $path", at < 0)
        return Regex("mood = (if \\(\\w+\\) \\S+ else [\\w.]+|[\\w.]+)").find(s.substring(at, minOf(s.length, at + 800)))?.groupValues?.get(1)?.trim()
            ?: "none for $title"
    }

    @Test fun emptyStatesGrin() {
        assertEquals("MascotMood.NO_MATCH", moodOf("browse/BrowseScreen.kt", "strings.keepUsefulFilesClose"))
        assertEquals("MascotMood.NO_MATCH", moodOf("browse/BrowseScreen.kt", "strings.chooseFilesToSearch"))
        assertEquals("MascotMood.NO_MATCH", moodOf("browse/BrowseScreen.kt", "strings.nothingHereYet"))
        assertEquals("MascotMood.NO_MATCH", moodOf("duplicates/DuplicatesScreen.kt", "strings.noDuplicatesFound"))
        assertEquals("if (loadFailed) MascotMood.TROUBLE else MascotMood.NO_MATCH", moodOf("folders/FoldersScreen.kt", "strings.noFoldersYet"))
        assertEquals("mood", moodOf("search/SearchResults.kt", "Words.NO_MATCHES,"))
        assertEquals("mood", moodOf("search/SearchResults.kt", "strings.nothingLikeThisYet"))
        assertEquals("mood", moodOf("search/SearchResults.kt", "strings.noMatchesIn"))
    }

    @Test fun failuresUseTroubleNeverNoMatch() {
        assertEquals("MascotMood.TROUBLE", moodOf("search/SearchResults.kt", "Words.SEARCH_FAILED,"))
        assertEquals("MascotMood.TROUBLE", moodOf("duplicates/DuplicatesScreen.kt", "strings.couldntLookForDuplicates"))
        assertEquals("MascotMood.TROUBLE", moodOf("browse/BrowseScreen.kt", "strings.couldntLoadYourFiles"))
        for ((f, t) in listOf(
            "viewer/PlayerScreen.kt" to "strings.couldntPlay",
            "viewer/PhotoScreen.kt" to "strings.couldntShowPhoto",
            "viewer/PdfPageScreen.kt" to "strings.couldntOpenPdf",
            "viewer/DocScreen.kt" to "strings.couldntOpenFile",
        )) {
            assertEquals(f, "MascotMood.TROUBLE", moodOf(f, t))
        }
    }

    @Test fun workingScreensWalkAndNoErrorKeepsTheOldArt() {
        assertFalse(src("duplicates/DuplicatesScreen.kt").substringAfter("strings.lookingForCopies").isEmpty())
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
