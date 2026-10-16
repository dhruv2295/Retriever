package app.retriever

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.retriever.core.Backend
import app.retriever.core.Changes
import app.retriever.core.DIMS
import app.retriever.core.DeviceProfile
import app.retriever.core.Embedder
import app.retriever.core.EmbedderFactory
import app.retriever.core.Extractor
import app.retriever.core.FileSource
import app.retriever.core.Kind
import app.retriever.core.ModelBuild
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.Signature
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.core.android.ImageBytes
import java.io.File

/**
 * Debug-only seeded state: a dozen fixed files, a fake embedder and its own database, so every screen can be
 * checked without the model or storage access. On while filesDir/demo exists; DebugActivity toggles it.
 */
object Demo {
    private const val DB_NAME = "retriever-demo.db"

    private fun flag(context: Context) = File(context.filesDir, "demo")

    fun isOn(context: Context): Boolean = flag(context).exists()

    fun parts(context: Context): DemoParts? {
        if (!isOn(context)) return null
        return DemoParts(DemoSource, DemoExtractor(context.applicationContext), FakeEmbedderFactory, DB_NAME) { true }
    }

    /** Flips the flag and restarts the process, since AppGraph reads it once. */
    fun toggle(context: Context) {
        flag(context).let { if (it.exists()) it.delete() else it.createNewFile() }
        context.startActivity(Intent.makeRestartActivityTask(Intent(context, MainActivity::class.java).component))
        Runtime.getRuntime().exit(0)
    }
}

private class DemoFile(val file: SourceFile, val locator: String, val text: String)

private const val DAY_MS = 86_400_000L
private const val EPOCH = 1_760_000_000_000L

private fun demo(i: Int, name: String, folder: String, mime: String, kind: Kind, uri: String, locator: String, text: String) =
    DemoFile(SourceFile(uri, name, folder, mime, kind, 40_000L + i * 1_000, EPOCH - i * DAY_MS), locator, text)

private fun drawable(name: String) = "android.resource://app.retriever/drawable/$name"

private fun doc(i: Int, name: String, text: String) =
    demo(i, name, "Documents", "text/plain", Kind.DOC, "demo:doc/$name", "", text)

private val FILES = listOf(
    demo(0, "IMG_dog_park.webp", "DCIM/Camera", "image/webp", Kind.PHOTO, drawable("illus_welcome"), "", "dog fetching a ball in the park"),
    demo(1, "IMG_reading.webp", "DCIM/Camera", "image/webp", Kind.PHOTO, drawable("illus_reading"), "", "reading a book on the sofa"),
    demo(2, "IMG_empty_room.webp", "DCIM/Camera", "image/webp", Kind.PHOTO, drawable("illus_empty"), "", "empty room before moving in"),
    doc(3, "Rent agreement.txt", "Lease agreement for the flat in Indiranagar with a two month security deposit."),
    doc(4, "Electricity bill September.txt", "Electricity bill for September: 412 units, due on the 15th."),
    doc(5, "Launch meeting notes.md", "Notes from the team meeting about the launch date and the beta testers."),
    doc(6, "Masala chai.txt", "Recipe for masala chai with ginger, cardamom and a little black pepper."),
    doc(7, "Flight booking.txt", "Flight booking confirmation for Bengaluru to Delhi on Friday morning."),
    doc(8, "Workout plan.txt", "Workout plan for the week: squats on Monday, a long run on Saturday."),
    demo(9, "Invoice 48213.pdf", "Download", "application/pdf", Kind.PDF, "demo:pdf/invoice", "p. 1", "Invoice 48213 for four office chairs, paid by card."),
    demo(10, "Beach trip.mp4", "DCIM/Camera", "video/mp4", Kind.VIDEO, "demo:video/beach", "0:12", "waves rolling in at sunset on the beach"),
    demo(11, "Voice memo.m4a", "Recordings", "audio/mp4", Kind.AUDIO, "demo:audio/memo", "0:30", "remind me to call the plumber about the kitchen sink"),
)

private object DemoSource : FileSource {
    override suspend fun changes(since: String?) = Changes(FILES.map { it.file }, emptyList(), "demo", isFullScan = true)
}

private class DemoExtractor(private val context: Context) : Extractor {
    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
        val d = FILES.first { it.file.uri == file.uri }
        if (file.kind != Kind.PHOTO) return listOf(Piece(d.locator, listOf(Part.Text(d.text)), d.text))
        val jpeg = ImageBytes.jpeg(context, Uri.parse(file.uri), 512) ?: return emptyList()
        return listOf(Piece("", listOf(Part.Image(jpeg)), d.text))
    }
}

/** Hashed bag of words for text and hashed bytes for images: matching words score, nothing else does. */
private object FakeEmbedderFactory : EmbedderFactory {
    override fun backends(build: ModelBuild) = listOf(Backend.CPU)

    override fun open(profile: DeviceProfile, build: ModelBuild): Embedder = FakeEmbedder
}

private object FakeEmbedder : Embedder {
    override val signature = Signature("DEMO/vt70/d256/t512")
    override val backend = Backend.CPU

    override suspend fun embedRaw(parts: List<Part>): FloatArray {
        val v = FloatArray(DIMS)
        for (p in parts) {
            when (p) {
                is Part.Text -> Store.ftsTokens(p.text).forEach { v[Math.floorMod(it.hashCode(), DIMS)] += 1f }
                is Part.Image -> for (i in p.jpeg.indices step 64) v[Math.floorMod(p.jpeg[i] * 31 + i, DIMS)] += 1f
                is Part.Audio -> v[0] += 1f
            }
        }
        return v
    }

    override fun close() {}
}
