package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Index bytes per file, by kind, with and without the model, on the real schema (files, chunks, both FTS4 tables and
 * their indexes). Pieces are shaped by production's rules ([imageWithText], [textPieces], [draftChunks]) from sizes
 * measured on eval/corpus: Vision OCR characters and labels at confidence >= 0.7 per photo and screenshot, and
 * pdftotext / document.xml characters per document. The text itself is eval/src's, so FTS sees real words.
 */
class StorageBenchTest {
    private val photoOcr = listOf(0, 0, 0, 0, 76, 1, 49, 0, 0, 0, 376, 587, 65, 0, 0, 17)
    private val photoLabels = listOf(5, 5, 3, 2, 3, 5, 11, 5, 8, 3, 2, 2, 0, 0, 0, 8)
    private val shotOcr = listOf(330, 250, 111, 106, 476, 89)
    private val shotLabels = listOf(2, 2, 0, 2, 2, 2)
    private val docChars = listOf(403, 562, 941, 1089, 427, 724, 352)
    private val labelWords = listOf("outdoor", "sky", "cloud", "plant", "tree", "grass", "people", "adult", "food", "structure", "building")

    private val words: String = File("../eval/src").listFiles { f -> f.extension == "html" }.orEmpty().sortedBy { it.name }
        .joinToString(" ") { stripHtml(it.readText()) }.replace(Regex("\\s+"), " ")

    private fun text(i: Int, length: Int): String = if (length == 0) "" else words.substring((i * 997) % (words.length - length)).take(length)

    private fun labels(n: Int, i: Int): String? = if (n == 0) null else List(n) { labelWords[(i + it) % labelWords.size] }.joinToString("\n")

    private fun file(kind: Kind, i: Int): SourceFile {
        val (name, folder, mime) = when (kind) {
            Kind.PHOTO -> Triple("IMG_20240518_${142233 + i}.jpg", "DCIM/Camera", "image/jpeg")
            Kind.SCREENSHOT -> Triple("Screenshot_20240518-${142233 + i}_Chrome.png", "Pictures/Screenshots", "image/png")
            Kind.VIDEO -> Triple("VID_20240518_${142233 + i}.mp4", "DCIM/Camera", "video/mp4")
            Kind.AUDIO -> Triple("Recording ${100 + i}.m4a", "Recordings", "audio/mp4")
            else -> Triple("Statement May 2024 $i.pdf", "Download", "application/pdf")
        }
        return SourceFile("content://media/external/file/${1_000_000_000L + i}", name, folder, mime, kind, 2_000_000L + i, 1_715_000_000_000L + i, null)
    }

    private fun pieces(kind: Kind, i: Int, moments: Int): List<Piece> {
        val jpeg = ByteArray(30_000)
        return when (kind) {
            Kind.PHOTO -> imageWithText("", jpeg, text(i, photoOcr[i % photoOcr.size]), 800, labels(photoLabels[i % photoLabels.size], i))
            Kind.SCREENSHOT -> imageWithText("", jpeg, text(i, shotOcr[i % shotOcr.size]), 800, labels(shotLabels[i % shotLabels.size], i))
            Kind.VIDEO -> moments(moments * 5_000L, 5, 0).map { Piece(momentLocator(it, false), momentParts(jpeg, it.frameMs, null), null) }
            Kind.AUDIO -> emptyList()
            else -> textPieces(Locator.page(1), text(i, docChars[i % docChars.size]), 800)
        }
    }

    private fun bytes(driver: SqlDriver): Long {
        driver.execute(null, "VACUUM", 0)
        fun pragma(name: String) = driver.executeQuery(null, "PRAGMA $name", { c -> QueryResult.Value(if (c.next().value) c.getLong(0)!! else 0L) }, 0).value
        return pragma("page_count") * pragma("page_size")
    }

    /** Bytes per file of [kind] over [n] files: the store with them minus the empty store. */
    private suspend fun perFile(kind: Kind, model: Boolean, n: Int = 2000, moments: Int = 8): Double {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { IndexDb.Schema.create(it) }
        val store = Store(driver).also { if (model) it.resetForModel(SIG) }
        val empty = bytes(driver)
        val files = List(n) { file(kind, it) }
        store.upsertFiles(files)
        val extractor = FakeExtractor(files.mapIndexed { i, f -> f.name to pieces(kind, i, moments) }.toMap())
        IndexEngine(store, extractor, if (model) FakeEmbedder() else null, FakeMonitor(), settingsOf()).runBatch(1.hours, requireCharging = false)
        return (bytes(driver) - empty).toDouble() / n
    }

    @Test
    fun indexBytesPerFile() = runTest {
        val rows = listOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.DOC, Kind.AUDIO).map { kind ->
            Triple(kind.name, perFile(kind, model = false), perFile(kind, model = true))
        } + listOf(
            Triple("VIDEO (8 moments)", perFile(Kind.VIDEO, model = false), perFile(Kind.VIDEO, model = true)),
            Triple("VIDEO (1 moment)", perFile(Kind.VIDEO, model = false, moments = 1), perFile(Kind.VIDEO, model = true, moments = 1)),
            Triple("VIDEO name only", perFile(Kind.VIDEO, model = false, moments = 0), perFile(Kind.VIDEO, model = true, moments = 0)),
        )
        println("kind\tno model B/file\tmodel B/file")
        rows.forEach { (k, a, b) -> println("$k\t${a.toLong()}\t${b.toLong()}") }
        val photo = rows.first()
        assertTrue(photo.second < 2_000 && photo.third < 2_500, "a photo's index row stays near a kilobyte: $photo")
    }
}
