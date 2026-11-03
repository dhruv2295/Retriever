package app.retriever.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.retriever.ShotTheme
import app.retriever.core.Backend
import app.retriever.core.FileState
import app.retriever.core.HomeFacts
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.Prefs
import app.retriever.core.ReadingProgress
import app.retriever.core.SearchEngine
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.search.HomeShell
import app.retriever.search.SearchActions
import app.retriever.search.SearchPane
import app.retriever.search.SearchUi
import app.retriever.search.answered
import app.retriever.setup.SetupContent
import app.retriever.setup.SetupUi
import app.retriever.shot
import app.retriever.status.StatusContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The search, setup and status screens at tablet widths, to androidApp/build/shots/adaptive_*.png. Each subclass sets
 * its window with @Config. The fixtures are the ones SearchShotsTest and SetupStatusShotsTest build.
 */
abstract class AdaptiveShotsTest(private val size: String) {
    @get:Rule val rule = createComposeRule()

    private val counts = mapOf(Kind.PHOTO to 4_210L, Kind.SCREENSHOT to 812L, Kind.VIDEO to 96L, Kind.PDF to 143L, Kind.DOC to 57L, Kind.AUDIO to 12L)

    private fun file(name: String, kind: Kind, days: Int = 1) =
        SourceFile("demo:$name", name, "Documents", "application/octet-stream", kind, 1_200_000, 1_760_000_000_000 - days * 86_400_000L)

    private val ready = SearchUi(setUp = true)
    private val readyHome = HomeUi(libraryChosen = true, counts = counts, progress = ReadingProgress(read = 3_100, scheduled = 2_230))

    private val photoHits = (1..12).map { i ->
        SearchEngine.Hit(file("IMG_20250914_%02d.jpg".format(i), Kind.PHOTO), i.toLong(), 0L, "", null, 1.0 / i)
    }

    private val docHits = listOf("Electricity bill March.pdf" to Kind.PDF, "Old bill.docx" to Kind.DOC, "Screenshot_bill.png" to Kind.SCREENSHOT)
        .mapIndexed { i, (name, kind) ->
            SearchEngine.Hit(
                file(name, kind),
                100L + i,
                200L + i,
                if (kind ==
                    Kind.PDF
                ) {
                    "p. 2"
                } else {
                    ""
                },
                "Amount due for the electricity \u0002bill\u0003 this month",
                1.0 / (i + 1),
            )
        }

    private val reading = app.retriever.readCounts(
        mapOf(FileState.INDEXED to 3_412L, FileState.PENDING to 8_118L, FileState.SKIPPED to 3L),
        mapOf(Kind.PHOTO to 9_000L, Kind.PDF to 2_533L),
        tailChunks = 12,
    )
    private val running = IndexState(
        running = true,
        processed = 120,
        pendingAtStart = 8_238,
        current = "IMG_20250914_183022.jpg",
        backend = Backend.GPU,
        detail = "the vision encoder runs on the processor",
    )
    private val prefs = Prefs(excludedFolders = setOf("Android/media/WhatsApp/Stickers"))
    private val skippedFiles = listOf("Scan 3.pdf" to "Download", "Old notes.docx" to "Documents", "IMG_0001.heic" to "DCIM/Camera")
        .mapIndexed { i, (name, folder) ->
            Store.StoredFile(i.toLong(), SourceFile("demo:$i", name, folder, "application/octet-stream", Kind.OTHER, 1_000, 0), 3)
        }

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { ShotTheme(false) { content() } }
        rule.waitForIdle()
        rule.shot(name)
    }

    @Test fun homeCaughtUp() = shot("adaptive_home_caught_up_$size") {
        val facts = HomeFacts(
            newMedia = (1..15).map { file("IMG_$it.jpg", Kind.PHOTO, it) },
            newShots = (1..8).map { file("Screenshot_$it.png", Kind.SCREENSHOT, it) },
            newDocs = listOf(file("Lease.pdf", Kind.PDF), file("Trip.docx", Kind.DOC, 14)),
            starred = emptyList(),
            starredCount = 0,
            folders = 12,
            largeCount = 0,
            largeBytes = 0,
        )
        val home = readyHome.copy(progress = ReadingProgress(read = 5_330), home = facts)
        val actions = SearchActions()
        HomeShell(ready, actions, open = {}) { padding, state, side ->
            HomeBody(home, false, actions, state, padding, side)
        }
    }

    @Test fun resultsGrid() = shot("adaptive_results_grid_$size") {
        SearchPane(ready.answered("photo", photoHits), readyHome, SearchActions())
    }

    @Test fun resultsDocuments() = shot("adaptive_results_docs_$size") {
        SearchPane(ready.answered("bill", docHits + photoHits.take(3)), readyHome, SearchActions())
    }

    @Test fun setupFirstStep() = shot("adaptive_setup_first_step_$size") {
        SetupContent(SetupUi())
    }

    @Test fun statusReading() = shot("adaptive_status_reading_$size") {
        StatusContent(reading, prefs, running, skippedFiles, skippedOpen = false, batteryHint = true)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w840dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveShotsTest840 : AdaptiveShotsTest("w840")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1280dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveShotsTest1280 : AdaptiveShotsTest("w1280")
