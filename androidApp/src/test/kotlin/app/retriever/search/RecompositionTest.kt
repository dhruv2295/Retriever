package app.retriever.search

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import app.retriever.ShotTheme
import app.retriever.core.Kind
import app.retriever.core.SearchEngine
import app.retriever.core.SourceFile
import app.retriever.home.HomeUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Counts how often the result tiles run their bodies, through the compiler's own trace markers (the hook Layout Inspector
 * uses), so the tiles need no test code. A skipped tile runs nothing and counts nothing.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecompositionTest {
    @get:Rule val rule = createComposeRule()

    private val runs = mutableMapOf<String, Int>()

    private val tracer = object : CompositionTracer {
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (").substringAfterLast('.')
            if (name in setOf("ThumbCell", "FileRow", "HitMenu")) runs.merge(name, 1, Int::plus)
        }

        override fun traceEventEnd() = Unit

        override fun isTraceInProgress() = true
    }

    @After fun untrace() = Composer.setTracer(null)

    private fun file(name: String, kind: Kind) = SourceFile("demo:$name", name, "Documents", "application/octet-stream", kind, 1_200_000, 1_760_000_000_000)

    private fun hit(id: Long, file: SourceFile) = SearchEngine.Hit(file, id, 0, "", null, 1.0 / id)

    private val hits = listOf(
        hit(1, file("Bill.pdf", Kind.PDF)),
        hit(2, file("IMG_1.jpg", Kind.PHOTO)),
        hit(3, file("IMG_2.jpg", Kind.PHOTO)),
        hit(4, file("Notes.docx", Kind.DOC)),
    )

    private val ui = SearchUi(setUp = true).answered("bill", hits)
    private val home = HomeUi(setUp = true, counts = mapOf(Kind.PDF to 1L, Kind.PHOTO to 2L, Kind.DOC to 1L), searchable = 4)

    private fun show(): Pair<MutableState<SearchUi>, MutableState<HomeUi>> {
        val u = mutableStateOf(ui)
        val h = mutableStateOf(home)
        val actions = SearchActions()
        rule.setContent { ShotTheme(false, 1f) { SearchPane(u.value, h.value, actions) } }
        rule.waitForIdle()
        return u to h
    }

    private fun tileRuns() = runs.filterKeys { it != "HitMenu" }.values.sum()

    @Test fun equalFilesInANewAnswerDoNotRecomposeTiles() {
        Composer.setTracer(tracer)
        val (u, _) = show()
        assertTrue("tiles never composed: $runs", tileRuns() >= hits.size)
        runs.clear()
        // A changed parent state must recompose, while each copied file and hit still compares equal.
        u.value = ui.answered("bill", hits.map { it.copy(file = it.file.copy()) }).copy(answer = ui.answer + 1)
        rule.waitForIdle()
        assertEquals("equal files recomposed: $runs", 0, tileRuns())
    }

    @Test fun countsAndHomeChangesLeaveTheTilesAlone() {
        Composer.setTracer(tracer)
        val (_, h) = show()
        assertTrue("tiles never composed: $runs", tileRuns() >= hits.size)
        runs.clear()
        h.value = home.copy(counts = mapOf(Kind.PDF to 2L, Kind.PHOTO to 3L, Kind.DOC to 1L), searchable = 6, waiting = 9)
        rule.waitForIdle()
        assertEquals("tiles recomposed on a count change: $runs", emptyMap<String, Int>(), runs)
    }

    @Test fun starringOneFileRecomposesNoTile() {
        Composer.setTracer(tracer)
        val (u, _) = show()
        rule.onNodeWithText("Notes.docx", substring = true).performTouchInput { longClick() }
        rule.onNodeWithText("Star").assertExists()
        runs.clear()
        u.value = u.value.copy(starredUris = setOf("demo:Notes.docx"))
        rule.waitForIdle()
        // The open menu follows the star; the grid and its tiles do not run again.
        rule.onNodeWithText("Remove star").assertExists()
        assertEquals("tiles recomposed on a star: $runs", emptyMap<String, Int>(), runs)
    }
}
