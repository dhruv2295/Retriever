package app.retriever.setup

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import app.retriever.core.ReadingPhase
import app.retriever.core.ReadingStatus
import app.retriever.core.SampleFile
import app.retriever.core.SamplePile
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.status.StatusActions
import app.retriever.status.StatusContent
import app.retriever.ui.Format
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

    @Before fun thumbnails() = registerReadingThumbnails((1..5).map { "demo:$it" })

    @After fun untrace() = Composer.setTracer(null)

    @Test fun fourCellsShareARowAtSeveralWidthsAndTheLastRowKeepsItsSize() {
        val width = mutableStateOf(239.dp)
        rule.setContent { ShotTheme(false, 1f) { Column(Modifier.width(width.value)) { ReadyFiles((1..5).map(::file), onOpen = {}) } } }
        for (dp in listOf(239, 280, 327, 360)) {
            rule.runOnIdle { width.value = dp.dp }
            val cells = (1..5).map { rule.onNodeWithTag("recent:demo:$it").fetchSemanticsNode().boundsInRoot }
            assertEquals(1, cells.take(4).map { it.top }.distinct().size)
            assertTrue(cells[4].top > cells[0].bottom)
            cells.forEach { assertEquals(cells[0].width, it.width, 1f) }
        }
    }

    @Test fun trialReadyFilesOpenInTheViewer() {
        var opened: SourceFile? = null
        rule.setContent { ShotTheme(false) { ReadyFiles(recent, onOpen = { opened = it }) } }
        rule.onNodeWithTag("recent:${recent[0].uri}").performClick()
        assertEquals(recent[0], opened)
    }

    /**
     * The screenshot bug: a document's text tile among photos widened its row to two cells. Every tile is one square
     * size, four to a row, two past large text.
     */
    @Test fun tryGridTilesShareOneSizeWithADocumentAmongPhotos() {
        val mixed = listOf(file(1), file(2, Kind.DOC), file(3), file(4, Kind.PDF), file(5), file(6, Kind.VIDEO))
        val scale = mutableStateOf(1f)
        rule.setContent { ShotTheme(false, scale.value) { ReadyFiles(mixed, onOpen = {}) } }
        for ((s, columns) in listOf(1f to 4, 2f to 2)) {
            rule.runOnIdle { scale.value = s }
            val cells = mixed.map { rule.onNodeWithTag("recent:${it.uri}").fetchSemanticsNode().boundsInRoot }
            cells.forEach {
                assertEquals(cells[0].width, it.width, 1f)
                assertEquals(it.width, it.height, 1f)
            }
            assertEquals(columns, cells.count { it.top == cells[0].top })
        }
    }

    // --- The sample's pile ---

    /** Every fifth file a document, so the pile always holds a text tile. */
    private fun card(i: Int) = SampleFile(i, file(i, if (i % 5 == 1) Kind.DOC else Kind.PHOTO), read = true, note = null)

    private fun pile(cards: State<List<SampleFile>>, reduceMotion: Boolean = true, onSeeAll: () -> Unit = {}) = rule.setContent {
        ShotTheme(false, 1f) {
            SamplePile(SamplePile.shown(cards.value), listOf(Kind.PHOTO, Kind.PDF, Kind.DOC), reduceMotion, "12 of 40 files read", Words.seeAll(40), onSeeAll)
        }
    }

    private fun pileNode() = rule.onNodeWithContentDescription("12 of 40 files read")

    @Test fun thePileKeepsOneFootprintFromEmptyToFull() {
        val cards = mutableStateOf(emptyList<SampleFile>())
        pile(cards)
        val bounds = listOf(0, 1, 12, 20).map { n ->
            rule.runOnIdle { cards.value = (0 until n).map(::card) }
            pileNode().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        assertEquals(bounds.toString(), 1, bounds.distinct().size)
    }

    @Test fun thePileHoldsTheLastTwelveReadDocumentsIncluded() {
        pile(mutableStateOf((0 until 20).map(::card)))
        rule.onAllNodes(
            SemanticsMatcher("a pile card") {
                it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith("card:") == true
            },
            useUnmergedTree = true,
        )
            .assertCountEquals(SamplePile.SHOWN)
        rule.onNodeWithTag("card:7", useUnmergedTree = true).assertDoesNotExist()
        (8 until 20).forEach { rule.onNodeWithTag("card:$it", useUnmergedTree = true).assertIsDisplayed() }
        rule.onNodeWithTag("card:16", useUnmergedTree = true).assertIsDisplayed() // a document
    }

    @Test fun thePileIsOneNodeThatSaysTheCountAndOpensSeeAll() {
        var opened = 0
        pile(mutableStateOf((0 until 20).map(::card)), onSeeAll = { opened++ })
        rule.onAllNodes(hasContentDescription("files read", substring = true)).assertCountEquals(1)
        pileNode()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher("See all as its action") { it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == Words.seeAll(40) })
            .performClick()
        assertEquals(1, opened)
        // The cards say nothing of their own.
        rule.onAllNodes(hasContentDescription(".jpg", substring = true)).assertCountEquals(0)
    }

    /** Bounds of the card added last: one frame after it arrives, and settled. */
    private fun newCard(reduceMotion: Boolean): Pair<Rect, Rect> {
        val cards = mutableStateOf((0 until 3).map(::card))
        rule.mainClock.autoAdvance = false
        pile(cards, reduceMotion)
        rule.mainClock.advanceTimeByFrame()
        cards.value = (0 until 4).map(::card)
        // Idle delivers the change without moving the clock; one frame then composes and places the new card.
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        val arriving = rule.onNodeWithTag("card:3", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.mainClock.advanceTimeBy(2_000)
        return arriving to rule.onNodeWithTag("card:3", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    }

    @Test fun aNewCardSettlesFromAbove() {
        val (arriving, settled) = newCard(reduceMotion = false)
        assertTrue("$arriving should start above $settled", arriving.center.y < settled.center.y - 1f)
    }

    @Test fun reducedMotionDrawsTheNewCardAtItsSlot() {
        val (arriving, settled) = newCard(reduceMotion = true)
        assertEquals(settled, arriving)
    }

    @Test fun aNewCardComposesAloneOnThePile() {
        var tiles = 0
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.substringBefore(" (").endsWith(".FileTile")) tiles++
            }
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = true
        })
        val cards = mutableStateOf((0 until 5).map(::card))
        pile(cards)
        rule.waitForIdle()
        assertEquals(5, tiles)
        tiles = 0
        cards.value = (0 until 6).map(::card).map { it.copy() }
        rule.waitForIdle()
        assertEquals("Only the new card may compose", 1, tiles)
    }

    @Test fun recentAndReadyCellsAnnounceNameKindAndDate() {
        rule.setContent {
            ShotTheme(false, 1f) {
                Column {
                    ReadyFiles(listOf(recent[0]), onOpen = {})
                    RecentlyRead(listOf(recent[1])) {}
                }
            }
        }
        recent.take(2).forEach { file ->
            rule.onNodeWithTag("recent:${file.uri}")
                .assertContentDescriptionEquals("${file.name}, ${Words.kindLabel(file.kind)}, ${Format.date(file.modifiedAt)}")
        }
    }

    @Test fun pauseReasonReplacesTheFileAndSweep() {
        rule.setContent { ShotTheme(false, 1f) { ReadingNow(null, "Paused while you use other apps. It carries on when you come back.") } }
        rule.onNodeWithText("Paused while you use other apps. It carries on when you come back.").assertExists()
        rule.onNodeWithTag("reading-sweep").assertDoesNotExist()
    }

    @Test fun reducedMotionSwapsWithoutAnimation() {
        val current = mutableStateOf(file(1))
        rule.setContent { ShotTheme(false, 1f) { ReadingNow(current.value, reduceMotion = true) } }
        rule.onNodeWithText("Photo").assertExists()
        rule.runOnIdle { current.value = file(2, Kind.PDF) }
        rule.waitForIdle()
        rule.onNodeWithText("PDF").assertExists()
        rule.onNodeWithText("Photo").assertDoesNotExist()
        rule.onNodeWithTag("reading-sweep").assertDoesNotExist()
        rule.onNodeWithTag("reading-crossfade").assertDoesNotExist()
    }

    @Test fun insertingARecentFileDoesNotRecomposeExistingCellsAcrossRows() {
        var cells = 0
        Composer.setTracer(object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.substringBefore(" (").endsWith(".FileTile")) cells++
            }
            override fun traceEventEnd() = Unit
            override fun isTraceInProgress() = true
        })
        val files = mutableStateOf(recent)
        val open: (SourceFile) -> Unit = {}
        rule.setContent { ShotTheme(false, 1f) { ReadyFiles(files.value, open) } }
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
                if (info.substringBefore(" (").endsWith(".FileTile")) cells++
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

    @Test fun statusOpensRecentFilesAndKeepsThemWhenReadingStops() {
        val index = mutableStateOf(IndexState(running = true, pendingAtStart = 40, currentFile = file(5, Kind.PDF), recentFiles = recent))
        var opened: SourceFile? = null
        rule.setContent {
            ShotTheme(false, 1f) {
                StatusContent(
                    app.retriever.readCounts(mapOf(FileState.INDEXED to 30L, FileState.PENDING to 10L), emptyMap(), 0),
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
        // The active tile goes; what was already read stays on show, and only the sentence above changes.
        rule.onNodeWithText("Reading now").assertDoesNotExist()
        rule.onNodeWithText("Recently read").assertExists()
        index.value = index.value.copy(recentFiles = emptyList())
        rule.onNodeWithText("Recently read").assertDoesNotExist()
    }

    private fun pausedStatus(index: IndexState, batteryHint: Boolean) = rule.setContent {
        ShotTheme(false, 1f) {
            StatusContent(
                app.retriever.readCounts(mapOf(FileState.INDEXED to 30L, FileState.PENDING to 10L), emptyMap(), 0),
                Prefs(paused = true),
                index,
                emptyList(),
                false,
                batteryHint,
                Modifier,
                readingStatus = ReadingStatus(ReadingPhase.PAUSED, "Reading paused", "Search still works. Resume whenever you’re ready."),
            )
        }
    }

    @Test fun aPausedStatusShowsOneReasonAndKeepsWhatWasRead() {
        pausedStatus(IndexState(recentFiles = recent), batteryHint = true)
        rule.onNodeWithText("Reading paused").assertExists()
        rule.onNodeWithText("Search still works. Resume whenever you’re ready.").assertExists()
        rule.onNodeWithText("Recently read").assertExists()
        rule.onNodeWithText("Reading now").assertDoesNotExist()
        // The pause is the reason and Resume the remedy: no second remedy beside them.
        rule.onNodeWithText("Resume reading").assertExists()
        rule.onNodeWithText("Let Retriever keep reading in the background").assertDoesNotExist()
    }

    @Test fun aFailureReplacesTheWaitSentenceInsteadOfJoiningIt() {
        pausedStatus(IndexState(lastError = "Couldn’t open the search model", recentFiles = recent), batteryHint = true)
        rule.onNodeWithText("Couldn’t open the search model").assertExists()
        rule.onNodeWithText("Search still works. Resume whenever you’re ready.").assertDoesNotExist()
        rule.onNodeWithText("Let Retriever keep reading in the background").assertDoesNotExist()
    }

    @Test fun theBackgroundHintStaysBesideAScheduleWaitButNotBesideAFailure() {
        rule.setContent {
            ShotTheme(false, 1f) {
                StatusContent(
                    app.retriever.readCounts(mapOf(FileState.INDEXED to 30L, FileState.PENDING to 10L), emptyMap(), 0),
                    Prefs(),
                    IndexState(),
                    emptyList(),
                    false,
                    true,
                    Modifier,
                    readingStatus = ReadingStatus(ReadingPhase.CHARGING, "Waiting to charge", "Plug in, or choose Read now for a short session."),
                )
            }
        }
        rule.onNodeWithText("Plug in, or choose Read now for a short session.").assertExists()
        rule.onNodeWithText("Let Retriever keep reading in the background").assertExists()
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
