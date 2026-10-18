package app.retriever.setup

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import app.retriever.ShotTheme
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.Prefs
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.core.TasteProgress
import app.retriever.status.StatusActions
import app.retriever.status.StatusContent
import app.retriever.ui.components.ReadingNow
import app.retriever.ui.components.ReadyFiles
import app.retriever.ui.components.RecentlyRead
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingFilesTest {
    @get:Rule val rule = createComposeRule()

    private fun file(id: Int, kind: Kind = Kind.PHOTO) = SourceFile("demo:$id", "$id.jpg", "Photos", "image/jpeg", kind, 100, 1_760_000_000_000)
    private val recent = (1..4).map(::file)
    private val taste = TasteProgress(mapOf(Kind.PHOTO to (30 to 40)), emptySet(), 30, 60, true)

    @Before fun thumbnails() = registerReadingThumbnails((1..5).map { "demo:$it" })

    @After fun untrace() = Composer.setTracer(null)

    @Test fun setupShowsCurrentFileAndNonInteractiveReadyCells() {
        rule.setContent {
            ShotTheme(false, 1f) {
                Column {
                    ReadingFew(taste, SetupActions()) { read ->
                        ReadingNow(file(5, Kind.PDF), reduceMotion = true)
                        ReadyFiles(recent, read, reduceMotion = true)
                    }
                }
            }
        }
        rule.onNodeWithText("Reading now").assertExists()
        rule.onNodeWithText("PDF").assertExists()
        rule.onNodeWithText("+26 more").assertExists()
        rule.onNodeWithText("Photos 30/40").assertExists()
        rule.onNodeWithText("Try it now").assertExists()
        rule.onAllNodes(SemanticsMatcher("recent cell") { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("recent:") })
            .assertCountEquals(4)
        recent.forEach { rule.onNodeWithTag("recent:${it.uri}").assertHasNoClickAction() }
    }

    @Test fun pauseReasonReplacesTheFileAndSweep() {
        rule.setContent {
            ShotTheme(false, 1f) {
                Column {
                    ReadingFew(taste, SetupActions()) { read ->
                        SetupReadingFiles(null, recent, "Paused while you use other apps. It carries on when you come back.", read)
                    }
                }
            }
        }
        rule.onNodeWithText("Paused while you use other apps. It carries on when you come back.").assertExists()
        rule.onNodeWithTag("reading-sweep").assertDoesNotExist()
    }

    @Test fun reducedMotionSwapsInstantlyWithoutASweep() {
        val current = mutableStateOf(file(1))
        rule.mainClock.autoAdvance = false
        rule.setContent { ShotTheme(false, 1f) { ReadingNow(current.value, reduceMotion = true) } }
        rule.onNodeWithText("Photo").assertExists()
        rule.runOnIdle { current.value = file(2, Kind.PDF) }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithText("PDF").assertExists()
        rule.onNodeWithText("Photo").assertDoesNotExist()
        rule.onNodeWithTag("reading-sweep").assertDoesNotExist()
    }

    @Test fun insertingARecentFileDoesNotRecomposeExistingCellsAcrossRows() {
        var cells = 0
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.substringBefore(" (").endsWith(".RecentFileCell")) cells++
            }
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = true
        })
        val files = mutableStateOf(recent)
        rule.setContent { ShotTheme(false, 1f) { ReadyFiles(files.value, files.value.size, reduceMotion = true) } }
        rule.waitForIdle()
        assertEquals(4, cells)
        cells = 0
        files.value = listOf(file(5)) + recent.map { it.copy() }
        rule.waitForIdle()
        assertEquals("Only the new cell may compose, even across a row boundary", 1, cells)
    }

    @Test fun insertingInTheStatusStripDoesNotRecomposeExistingCells() {
        var cells = 0
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.substringBefore(" (").endsWith(".RecentFileCell")) cells++
            }
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = true
        })
        val files = mutableStateOf(recent)
        val open: (SourceFile) -> Unit = {}
        rule.setContent { ShotTheme(false, 1f) { RecentlyRead(files.value, open) } }
        rule.waitForIdle()
        assertEquals(4, cells)
        cells = 0
        files.value = listOf(file(5)) + recent.map { it.copy() }
        rule.waitForIdle()
        assertEquals(1, cells)
    }

    @Test fun sweepFramesNeverRecomposeTheReadingFile() {
        var runs = 0
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.substringBefore(" (").endsWith(".ReadingFile")) runs++
            }
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = true
        })
        rule.mainClock.autoAdvance = false
        rule.setContent { ShotTheme(false, 1f) { ReadingNow(file(1), size = 56.dp, reduceMotion = false) } }
        rule.mainClock.advanceTimeBy(300)
        assertTrue(runs > 0)
        runs = 0
        rule.mainClock.advanceTimeBy(1700)
        assertEquals(0, runs)
        rule.onNodeWithTag("reading-sweep").assertExists()
    }

    @Test fun statusOpensRecentFilesAndHidesThemWhenReadingStops() {
        val index = mutableStateOf(IndexState(running = true, pendingAtStart = 40, currentFile = file(5, Kind.PDF), recentFiles = recent))
        var opened: SourceFile? = null
        rule.setContent {
            ShotTheme(false, 1f) {
                StatusContent(
                    Store.Counts(mapOf(FileState.INDEXED to 30L, FileState.PENDING to 10L), emptyMap(), 0),
                    Prefs(),
                    index.value,
                    emptyList(),
                    false,
                    false,
                    Modifier,
                    actions = StatusActions(openFile = { opened = it }),
                )
            }
        }
        rule.onNodeWithText("Reading now").assertExists()
        rule.onNodeWithText("Recently read").assertExists()
        rule.onNodeWithTag("recent:${recent.first().uri}").performClick()
        assertEquals(recent.first(), opened)
        index.value = index.value.copy(running = false, currentFile = null)
        rule.onNodeWithText("Reading now").assertDoesNotExist()
        rule.onNodeWithText("Recently read").assertDoesNotExist()
    }
}

internal fun registerReadingThumbnails(uris: List<String>) {
    val bitmap = createBitmap(8, 8).apply { eraseColor(android.graphics.Color.BLUE) }
    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    bitmap.recycle()
    for (uri in uris) {
        shadowOf(RuntimeEnvironment.getApplication().contentResolver)
            .registerInputStreamSupplier(uri.toUri()) { ByteArrayInputStream(bytes) }
    }
}
