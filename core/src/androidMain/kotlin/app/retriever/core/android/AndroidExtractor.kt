package app.retriever.core.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.util.Size
import app.retriever.core.Chunker
import app.retriever.core.DeviceProfile
import app.retriever.core.Extractor
import app.retriever.core.DocFormat
import app.retriever.core.docFormat
import app.retriever.core.textEntry
import app.retriever.core.Kind
import app.retriever.core.MIN_TEXT_CHARS
import app.retriever.core.PDF_PAGES
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.SourceFile
import app.retriever.core.TEXT_BYTES
import app.retriever.core.audioWindowSec
import app.retriever.core.audioWindows
import app.retriever.core.chunkChars
import app.retriever.core.momentAudioSec
import app.retriever.core.momentLocator
import app.retriever.core.momentParts
import app.retriever.core.slideOrder
import app.retriever.core.stripHtml
import app.retriever.core.stripXml
import app.retriever.core.textPieces
import app.retriever.core.timeLocator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val AUDIO_FILE_SECONDS = 300
private const val MAX_PAGE_PX = 4096

class AndroidExtractor(private val context: Context) : Extractor, AutoCloseable {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    override fun close() {
        latin.close()
        devanagari.close()
    }

    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(file.uri)
        val maxChars = chunkChars(profile.maxTokens)
        when (file.kind) {
            Kind.PHOTO -> photo(uri)
            Kind.SCREENSHOT -> screenshot(uri, maxChars)
            Kind.PDF -> pdf(uri, maxChars)
            Kind.DOC -> doc(file, uri, maxChars)
            Kind.AUDIO -> if (profile.audio && profile.model.audio) audio(uri, profile) else emptyList()
            Kind.VIDEO -> video(uri, profile)
            Kind.OTHER -> emptyList()
        }
    }

    private fun photo(uri: Uri): List<Piece> {
        val jpeg = ImageBytes.jpeg(context, uri, 512) ?: return emptyList()
        return listOf(Piece("", listOf(Part.Image(jpeg)), null))
    }

    private suspend fun screenshot(uri: Uri, maxChars: Int): List<Piece> {
        val jpeg = ImageBytes.jpeg(context, uri, 512) ?: return emptyList()
        val big = ImageBytes.bitmap(context, uri, 1600)
        val ocr = if (big == null) "" else try { ocr(big) } finally { big.recycle() }
        return imageWithText("", jpeg, ocr, maxChars)
    }

    /** OCR text is stored once: in its text pieces, or on the image piece when it is too short to chunk. */
    private fun imageWithText(locator: String, jpeg: ByteArray, ocr: String, maxChars: Int): List<Piece> {
        val text = textPieces(locator, ocr, maxChars)
        val keyword = if (text.isEmpty()) ocr.ifBlank { null } else null
        return listOf(Piece(locator, listOf(Part.Image(jpeg)), keyword)) + text
    }

    private suspend fun ocr(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val first = recognize(latin, image)
        if (first.length >= MIN_TEXT_CHARS) return first
        val second = recognize(devanagari, image)
        return if (second.length > first.length) second else first
    }

    private suspend fun recognize(client: TextRecognizer, image: InputImage): String = try {
        suspendCancellableCoroutine { cont ->
            client.process(image)
                .addOnSuccessListener { cont.resume(it.text.trim()) }
                .addOnFailureListener { cont.resume("") }
        }
    } catch (e: Exception) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        ""
    }

    private suspend fun pdf(uri: Uri, maxChars: Int): List<Piece> {
        val pieces = mutableListOf<Piece>()
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        fd.use {
            // Password-protected PDFs (bank statements, policies) can't be read without the password; leave
            // them to the name chunk instead of failing the file three times.
            val opened = try {
                PdfRenderer(fd)
            } catch (_: SecurityException) {
                return emptyList()
            }
            opened.use { renderer ->
                for (i in 0 until minOf(renderer.pageCount, PDF_PAGES)) {
                    val locator = "p. ${i + 1}"
                    try {
                        renderer.openPage(i).use { page ->
                            val text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                                page.textContents.joinToString("\n") { it.text }
                            } else ""
                            pieces += if (text.trim().length >= MIN_TEXT_CHARS) textPieces(locator, text, maxChars)
                            else scannedPage(page, locator, maxChars)
                        }
                    } catch (e: Exception) {
                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    }
                }
            }
        }
        return pieces
    }

    private suspend fun scannedPage(page: PdfRenderer.Page, locator: String, maxChars: Int): List<Piece> {
        // 1024 px wide, but a receipt-shaped page (1:40) would be a 168 MB bitmap: cap the height and narrow it.
        val height = (1024L * page.height / page.width).coerceIn(1, MAX_PAGE_PX.toLong()).toInt()
        val width = (height.toLong() * page.width / page.height).coerceIn(1, 1024).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return imageWithText(locator, out.toByteArray(), ocr(bitmap), maxChars)
        } finally {
            bitmap.recycle()
        }
    }

    private fun doc(file: SourceFile, uri: Uri, maxChars: Int): List<Piece> {
        val format = docFormat(file.name, file.mime) ?: return emptyList()
        val text = when (format) {
            DocFormat.DOCX, DocFormat.XLSX, DocFormat.ODF -> zipEntry(uri, format.textEntry!!)
            DocFormat.PPTX -> zipText(uri, ::slideOrder)
            DocFormat.EPUB -> epub(uri)
            DocFormat.HTML -> stripHtml(plainText(uri))
            DocFormat.TEXT -> plainText(uri)
        }
        return Chunker.chunks(text, maxChars = maxChars).map { Piece("", listOf(Part.Text(it)), it) }
    }

    private fun epub(uri: Uri): String {
        var n = 0
        val chapter = Regex(".*\\.(xhtml|html?)", RegexOption.IGNORE_CASE)
        return zipText(uri, { if (chapter.matches(it)) n++ else null }, ::stripHtml)
    }

    private fun plainText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { String(it.readUpTo(TEXT_BYTES), Charsets.UTF_8) }.orEmpty()

    private fun zipEntry(uri: Uri, name: String): String =
        zipText(uri, { if (it == name) 0 else null })

    // Entries whose [order] is non-null are read and concatenated sorted by it.
    private fun zipText(uri: Uri, order: (String) -> Int?, strip: (String) -> String = ::stripXml): String {
        val entries = mutableListOf<Pair<Int, String>>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val n = order(entry.name) ?: continue
                    entries += n to strip(String(zip.readUpTo(4 * TEXT_BYTES), Charsets.UTF_8))
                }
            }
        }
        return entries.sortedBy { it.first }.joinToString("\n") { it.second }
    }

    private fun audio(uri: Uri, profile: DeviceProfile): List<Piece> {
        val pcm = MediaDecoding.audioAt(context, uri, 0, AUDIO_FILE_SECONDS * 1000L)
        return audioWindows(pcm, audioWindowSec(profile.maxTokens)).map { (start, samples) ->
            Piece(timeLocator(start), listOf(Part.Audio(samples)), null)
        }
    }

    private fun video(uri: Uri, profile: DeviceProfile): List<Piece> {
        val audioSec = if (profile.audio && profile.model.audio) momentAudioSec(profile.maxTokens, profile.visionTokens) else 0
        return MediaDecoding.momentFrames(context, uri, profile.videoFrameEverySec, audioSec).map { (m, jpeg) ->
            val audio = if (audioSec > 1) MediaDecoding.audioAt(context, uri, m.audioFromMs, m.audioToMs) else null
            Piece(momentLocator(m, audio != null), momentParts(jpeg, m.frameMs, audio), null)
        }
    }
}

/** Up to [n] bytes; InputStream.readNBytes(int) is API 33, above minSdk 30. */
private fun InputStream.readUpTo(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val read = read(buf, off, n - off)
        if (read < 0) break
        off += read
    }
    return buf.copyOf(off)
}

object ImageBytes {
    fun bitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longer = maxOf(info.size.width, info.size.height)
            if (longer > maxSide) {
                val scale = maxSide.toFloat() / longer
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } catch (e: Exception) {
        // ImageBytes.bitmap's loadThumbnail fallback adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt (Apache-2.0).
        runCatching { context.contentResolver.loadThumbnail(uri, Size(maxSide, maxSide), null) }.getOrNull()
    }

    /** The embed image: MediaStore's cached thumbnail when it has one (no full decode), else a decode at [maxSide]. */
    fun jpeg(context: Context, uri: Uri, maxSide: Int): ByteArray? =
        runCatching { compress(context.contentResolver.loadThumbnail(uri, Size(maxSide, maxSide), null)) }.getOrNull()
            ?: bitmap(context, uri, maxSide)?.let { runCatching { compress(it) }.getOrNull() }

    private fun compress(bitmap: Bitmap): ByteArray = try {
        ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }.toByteArray()
    } finally {
        bitmap.recycle()
    }
}
