package app.rummage.core.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Size
import app.rummage.core.Chunker
import app.rummage.core.DeviceProfile
import app.rummage.core.Extractor
import app.rummage.core.Kind
import app.rummage.core.Part
import app.rummage.core.Piece
import app.rummage.core.SourceFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val PDF_PAGES = 30
private const val TEXT_BYTES = 300 * 1024
private const val MIN_TEXT_CHARS = 40
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "csv", "json", "xml", "html", "kt", "java", "py", "js", "ts",
    "swift", "go", "rs", "c", "cpp", "h", "yaml", "yml", "log",
)
private val TAG = Regex("<[^>]+>")
private val BLANKS = Regex("[ \t]+")
private val SLIDE = Regex("ppt/slides/slide(\\d+)\\.xml")

class AndroidExtractor(private val context: Context) : Extractor {
    private val ocrClient = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(file.uri)
        when (file.kind) {
            Kind.PHOTO -> photo(uri)
            Kind.SCREENSHOT -> screenshot(uri)
            Kind.PDF -> pdf(uri)
            Kind.DOC -> doc(file, uri)
            Kind.AUDIO -> if (profile.audio) audioPieces(uri, 300) else emptyList()
            Kind.VIDEO -> video(uri, profile)
            Kind.OTHER -> emptyList()
        }
    }

    private fun photo(uri: Uri): List<Piece> {
        val jpeg = ImageBytes.jpeg(context, uri, 512) ?: return emptyList()
        return listOf(Piece("", listOf(Part.Image(jpeg)), null))
    }

    private suspend fun screenshot(uri: Uri): List<Piece> {
        val jpeg = ImageBytes.jpeg(context, uri, 512) ?: return emptyList()
        val big = ImageBytes.bitmap(context, uri, 1600)
        val ocr = if (big == null) "" else ocr(big).also { big.recycle() }
        return listOf(Piece("", listOf(Part.Image(jpeg)), ocr.ifBlank { null })) + textPieces("", ocr)
    }

    private fun textPieces(locator: String, text: String): List<Piece> =
        if (text.length < MIN_TEXT_CHARS) emptyList()
        else Chunker.chunks(text).map { Piece(locator, listOf(Part.Text(it)), it) }

    private suspend fun ocr(bitmap: Bitmap): String = try {
        suspendCancellableCoroutine { cont ->
            ocrClient.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it.text.trim()) }
                .addOnFailureListener { cont.resume("") }
        }
    } catch (e: Exception) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        ""
    }

    private suspend fun pdf(uri: Uri): List<Piece> {
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
                            val text = page.textContents.joinToString("\n") { it.text }
                            if (text.trim().length >= MIN_TEXT_CHARS) {
                                pieces += textPieces(locator, text)
                            } else {
                                pieces += scannedPage(page, locator)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    }
                }
            }
        }
        return pieces
    }

    private suspend fun scannedPage(page: PdfRenderer.Page, locator: String): List<Piece> {
        val width = 1024
        val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val ocr = ocr(bitmap)
            return listOf(Piece(locator, listOf(Part.Image(out.toByteArray())), ocr.ifBlank { null })) +
                textPieces(locator, ocr)
        } finally {
            bitmap.recycle()
        }
    }

    private fun doc(file: SourceFile, uri: Uri): List<Piece> {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val mime = file.mime.lowercase()
        val text = when {
            ext == "docx" || "wordprocessingml" in mime -> zipEntry(uri, "word/document.xml")
            ext == "pptx" || "presentationml" in mime -> zipText(uri, ::slideNumber)
            ext == "xlsx" || "spreadsheetml" in mime -> zipEntry(uri, "xl/sharedStrings.xml")
            ext in TEXT_EXTENSIONS || mime.startsWith("text/") || mime == "application/json" -> plainText(uri)
            else -> return emptyList()
        }
        return Chunker.chunks(text).map { Piece("", listOf(Part.Text(it)), it) }
    }

    private fun slideNumber(name: String): Int? = SLIDE.matchEntire(name)?.groupValues?.get(1)?.toInt()

    private fun plainText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { String(it.readNBytes(TEXT_BYTES), Charsets.UTF_8) }.orEmpty()

    private fun zipEntry(uri: Uri, name: String): String =
        zipText(uri) { if (it == name) 0 else null }

    // Entries whose [order] is non-null are read and concatenated sorted by it.
    private fun zipText(uri: Uri, order: (String) -> Int?): String {
        val entries = mutableListOf<Pair<Int, String>>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val n = order(entry.name) ?: continue
                    entries += n to stripXml(String(zip.readNBytes(4 * TEXT_BYTES), Charsets.UTF_8))
                }
            }
        }
        return entries.sortedBy { it.first }.joinToString("\n") { it.second }
    }

    private fun stripXml(xml: String): String = xml
        .replace("</w:p>", "\n").replace("</a:p>", "\n").replace("</si>", "\n")
        .replace(TAG, "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace(BLANKS, " ")

    private fun audioPieces(uri: Uri, maxSeconds: Int): List<Piece> {
        val pcm = MediaDecoding.decodeAudio16kMono(context, uri, maxSeconds)
        return MediaDecoding.windows(pcm).map { (start, samples) ->
            Piece(MediaDecoding.locator(start), listOf(Part.Audio(samples)), null)
        }
    }

    private fun video(uri: Uri, profile: DeviceProfile): List<Piece> {
        val frames = if (profile.videoFrameEverySec > 0) {
            MediaDecoding.videoFrames(context, uri, profile.videoFrameEverySec, 120, 512).map { (sec, jpeg) ->
                Piece(MediaDecoding.locator(sec), listOf(Part.Image(jpeg)), null)
            }
        } else emptyList()
        return frames + if (profile.audio) audioPieces(uri, 120) else emptyList()
    }
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
        // Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt (Apache-2.0). Modified for Rummage: thumbnail fallback for any image ImageDecoder can't read.
        runCatching { context.contentResolver.loadThumbnail(uri, Size(maxSide, maxSide), null) }.getOrNull()
    }

    fun jpeg(context: Context, uri: Uri, maxSide: Int): ByteArray? {
        val bitmap = bitmap(context, uri, maxSide) ?: return null
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }
}
