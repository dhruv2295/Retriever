package app.retriever

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import app.retriever.core.DeviceWords
import app.retriever.core.FacesProgress
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.Prefs
import app.retriever.core.ReadingPolicy
import app.retriever.core.ReadingProgress
import app.retriever.core.ReadingStatus
import app.retriever.core.SourceFile
import app.retriever.core.Stage
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.home.HomeBody
import app.retriever.home.HomeUi
import app.retriever.indexing.Notifications
import app.retriever.search.HomeShell
import app.retriever.search.SearchActions
import app.retriever.search.SearchUi
import app.retriever.status.StatusContent
import app.retriever.ui.RetrieverTheme

/**
 * Debug builds only: the processing journey's real screens on made-up counts (20,000 photos, 1,000 documents), for design review.
 * `adb shell am start -n app.retriever/.JourneyShotActivity --es shot meaning|words|held|done|people|home|note|notification`,
 * plus `--ez home true` for Home in that state.
 */
class JourneyShotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val shot = intent.getStringExtra("shot") ?: "meaning"
        val (progress, status, index) = state(if (shot == "home" || shot == "notification") "words" else shot)
        val counts = Store.Counts(
            byState = mapOf(FileState.INDEXED to progress.processed),
            byKind = mapOf(Kind.PHOTO to 20_000L, Kind.PDF to 600L, Kind.DOC to 400L),
            tailChunks = 0,
            tailFiles = progress.laterPasses,
            progress = progress,
        )
        if (shot == "notification") {
            Notifications.createChannel(this)
            getSystemService(NotificationManager::class.java).notify(Notifications.PROGRESS_ID, Notifications.progress(this, progress, status))
        }
        setContent {
            RetrieverTheme {
                Surface {
                    when {
                        shot == "home" || shot == "note" || intent.getBooleanExtra("home", false) -> {
                            val shown = if (shot == "note") status.copy(journey = status.journey?.copy(note = Stage.MEANING)) else status
                            val ui = HomeUi(libraryChosen = true, counts = counts.byKind, progress = progress, readingStatus = shown)
                            HomeShell(SearchUi(), SearchActions(), open = {}) { padding, list ->
                                HomeBody(ui, index.running, SearchActions(), list, padding)
                            }
                        }

                        else -> StatusContent(counts, Prefs(), index, emptyList(), false, false, status)
                    }
                }
            }
        }
    }

    private fun file(i: Int) =
        SourceFile("demo:IMG_$i", "IMG_$i.jpg", "Camera", "image/jpeg", Kind.PHOTO, 2_400_000, 1_760_000_000_000L, takenAt = 1_760_000_000_000L)

    private fun state(shot: String): Triple<ReadingProgress, ReadingStatus, IndexState> {
        val files = (0..4).map { file(2041 - it) }
        val running = IndexState(running = true, currentFile = files[0], recentFiles = files.drop(1))
        val step2 = ReadingProgress(read = 21_000, wordsWaiting = 7_400, laterPasses = 600)
        fun decide(
            p: ReadingProgress,
            index: IndexState = running,
            stop: StopReason? = null,
            pluggedIn: Boolean = true,
            battery: Int = 100,
            faces: FacesProgress? = null,
        ): Triple<ReadingProgress, ReadingStatus, IndexState> = Triple(
            p,
            ReadingPolicy.decide(
                p, stop, index.running, index.currentFile != null, DeviceWords.ANDROID, pluggedIn = pluggedIn, faces = faces, facesWaiting = faces,
                battery = battery, peopleOn = faces != null,
            ),
            index,
        )
        return when (shot) {
            "meaning" -> decide(ReadingProgress(read = 12_000, scheduled = 9_000, wordsWaiting = 12_000, laterPasses = 600))
            "held" -> decide(step2, IndexState(recentFiles = files.drop(1)), StopReason.UNPLUGGED, pluggedIn = false, battery = 40)
            "done" -> decide(ReadingProgress(read = 21_000), IndexState(recentFiles = files.drop(1)))
            "people" -> decide(ReadingProgress(read = 21_000, laterPasses = 600), faces = FacesProgress(9_200, 10_800))
            else -> decide(step2)
        }
    }
}
