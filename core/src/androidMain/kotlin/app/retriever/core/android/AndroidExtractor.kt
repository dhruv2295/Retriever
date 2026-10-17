package app.retriever.core.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import android.util.Size
import app.retriever.core.Chunker
import app.retriever.core.DeviceProfile
import app.retriever.core.Extractor
import app.retriever.core.DocFormat
import app.retriever.core.docFormat
import app.retriever.core.textEntry
import app.retriever.core.Kind
import app.retriever.core.OcrReading
import app.retriever.core.OcrScript
import app.retriever.core.ocrOrder
import app.retriever.core.ocrScripts
import app.retriever.core.readScripts
import app.retriever.core.MIN_TEXT_CHARS
import app.retriever.core.PDF_OCR_PAGES
import app.retriever.core.PDF_TEXT_PAGES
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.ZipBudget
import app.retriever.core.SourceFile
import app.retriever.core.TEXT_BYTES
import app.retriever.core.audioWindowSec
import app.retriever.core.audioWindows
import app.retriever.core.betterPageText
import app.retriever.core.chunkChars
import app.retriever.core.cutShort
import app.retriever.core.looksBroken
import app.retriever.core.momentAudioSec
import app.retriever.core.momentLocator
import app.retriever.core.momentParts
import app.retriever.core.slideOrder
import app.retriever.core.xlsxOrder
import app.retriever.core.xlsxText
import app.retriever.core.stripHtml
import app.retriever.core.stripRtf
import app.retriever.core.stripXml
import app.retriever.core.textPieces
import app.retriever.core.timeLocator
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val AUDIO_FILE_SECONDS = 300
private const val MAX_PAGE_PX = 4096
private const val PDF_PREV_EXTENSION = 13

private class PdfPg(val width: Int, val height: Int, val text: String, val render: (Bitmap) -> Unit, page: AutoCloseable) :
    AutoCloseable by page

private class PdfDoc(val pageCount: Int, val hasTextLayer: Boolean, doc: AutoCloseable, val page: (Int) -> PdfPg) :
    AutoCloseable by doc

private fun pdfPage(page: AutoCloseable, width: Int, height: Int, text: () -> String, render: (Bitmap) -> Unit): PdfPg =
    try {
        PdfPg(width, height, text(), render, page)
    } catch (e: Throwable) {
        page.close()
        throw e
    }

/** Android 15 reads the text layer with PdfRenderer; 11-14 with PdfRendererPreV where the S extension 13 is present; else none. */
private fun openPdf(fd: ParcelFileDescriptor): PdfDoc = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> PdfRenderer(fd).let { r ->
        PdfDoc(r.pageCount, true, r) { i ->
            val p = r.openPage(i)
            pdfPage(p, p.width, p.height, { p.textContents.joinToString("\n") { it.text } }) {
                p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }
    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PDF_PREV_EXTENSION -> openPdfPreV(fd)
    else -> PdfRenderer(fd).let { r ->
        PdfDoc(r.pageCount, false, r) { i ->
            val p = r.openPage(i)
            pdfPage(p, p.width, p.height, { "" }) { p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }
    }
}

// ponytail: on Android 16 textContents drops 1-2 trailing characters of some runs (platform issue); the gate stays open.
@RequiresExtension(extension = Build.VERSION_CODES.S, version = PDF_PREV_EXTENSION)
private fun openPdfPreV(fd: ParcelFileDescriptor): PdfDoc = PdfRendererPreV(fd).let { r ->
    PdfDoc(r.pageCount, true, r) { i ->
        val p = r.openPage(i)
        pdfPage(p, p.width, p.height, { p.textContents.joinToString("\n") { it.text } }) {
            p.render(it, null, null, RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build())
        }
    }
}

class AndroidExtractor(private val context: Context) : Extractor, AutoCloseable {
    private val recognizers = ConcurrentHashMap<OcrScript, TextRecognizer>()

    private fun recognizer(script: OcrScript): TextRecognizer = recognizers.computeIfAbsent(script) {
        TextRecognition.getClient(
            when (it) {
                OcrScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                OcrScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                OcrScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                OcrScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                OcrScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            },
        )
    }

    override fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }

    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = withContext(Dispatchers.IO) {
        val uri = file.uri.toUri()
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

    private val languageTags get() = LocaleList.getDefault().let { list -> List(list.size()) { list[it].toLanguageTag() } }

    /**
     * Chinese, Japanese and Korean recognizers come through Play services, which fetches each on its first use. Asking
     * for the phone's own ones up front, once, means the first screenshots are read with them instead of without.
     */
    private val unbundledRequested by lazy {
        val wanted = ocrScripts(languageTags) - OcrScript.LATIN - OcrScript.DEVANAGARI
        if (wanted.isNotEmpty()) {
            val request = ModuleInstallRequest.newBuilder().apply { wanted.forEach { addApi(recognizer(it)) } }.build()
            ModuleInstall.getClient(context).installModules(request)
        }
    }

    // ponytail: an image read while its script's recognizer is still downloading keeps the other scripts' text and is
    // not read again; failing it instead would skip every screenshot on a phone without Play services.
    private suspend fun ocr(bitmap: Bitmap): String {
        unbundledRequested
        val image = InputImage.fromBitmap(bitmap, 0)
        return readScripts(ocrOrder(languageTags)) { recognize(recognizer(it), image) }
    }

    /** The text and the recognizer's own confidence in it, averaged over its lines by length. */
    private suspend fun recognize(client: TextRecognizer, image: InputImage): OcrReading = try {
        suspendCancellableCoroutine { cont ->
            client.process(image)
                .addOnSuccessListener { text ->
                    val lines = text.textBlocks.flatMap { it.lines }
                    val chars = lines.sumOf { it.text.length }
                    val confidence = if (chars == 0) 0f else lines.sumOf { it.confidence.toDouble() * it.text.length }.toFloat() / chars
                    cont.resume(OcrReading(text.text.trim(), confidence))
                }
                .addOnFailureListener { cont.resume(OcrReading.NONE) }
        }
    } catch (e: Exception) {
        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        OcrReading.NONE
    }

    private suspend fun pdf(uri: Uri, maxChars: Int): List<Piece> {
        val pieces = mutableListOf<Piece>()
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        fd.use {
            // Password-protected PDFs (bank statements, policies) can't be read without the password; leave
            // them to the name chunk instead of failing the file three times.
            val opened = try {
                openPdf(fd)
            } catch (_: SecurityException) {
                return emptyList()
            }
            opened.use { renderer ->
                // Without a text layer every page is an OCR page and the OCR cap is the ceiling.
                var ocrPages = 0
                var skipped = false
                for (i in 0 until minOf(renderer.pageCount, PDF_TEXT_PAGES)) {
                    currentCoroutineContext().ensureActive()
                    // With no text layer, a page past the OCR cap has nothing to give: don't open the rest.
                    if (!renderer.hasTextLayer && ocrPages >= PDF_OCR_PAGES) {
                        skipped = true
                        break
                    }
                    val locator = "p. ${i + 1}"
                    try {
                        renderer.page(i).use { page ->
                            val text = page.text
                            val hasText = text.trim().length >= MIN_TEXT_CHARS
                            pieces += when {
                                hasText && !looksBroken(text) -> textPieces(locator, text, maxChars)
                                ocrPages < PDF_OCR_PAGES -> {
                                    ocrPages++
                                    scannedPage(page, locator, text, maxChars)
                                }
                                else -> {
                                    skipped = true
                                    if (hasText) textPieces(locator, text, maxChars) else emptyList()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    }
                }
                if (renderer.pageCount > PDF_TEXT_PAGES || skipped) pieces += cutShort()
            }
        }
        return pieces
    }

    private suspend fun scannedPage(page: PdfPg, locator: String, layer: String, maxChars: Int): List<Piece> {
        // 1024 px wide, but a receipt-shaped page (1:40) would be a 168 MB bitmap: cap the height and narrow it.
        val height = (1024L * page.height / page.width).coerceIn(1, MAX_PAGE_PX.toLong()).toInt()
        val width = (height.toLong() * page.width / page.height).coerceIn(1, 1024).toInt()
        val bitmap = createBitmap(width, height)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            return imageWithText(locator, out.toByteArray(), betterPageText(layer, ocr(bitmap)), maxChars)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun doc(file: SourceFile, uri: Uri, maxChars: Int): List<Piece> {
        val format = docFormat(file.name, file.mime) ?: return emptyList()
        var cut = false
        val onCut = { cut = true }
        val text = when (format) {
            DocFormat.DOCX, DocFormat.ODF -> zipText(uri, { if (it == format.textEntry) 0 else null }, onCut)
            DocFormat.PPTX -> zipText(uri, ::slideOrder, onCut)
            DocFormat.XLSX -> xlsxText(zipText(uri, ::xlsxOrder, onCut, strip = { it }))
            DocFormat.EPUB -> epub(uri, onCut)
            DocFormat.HTML -> stripHtml(plainText(uri, onCut))
            DocFormat.RTF -> stripRtf(plainText(uri, onCut))
            DocFormat.TEXT -> plainText(uri, onCut)
        }
        val pieces = Chunker.chunks(text, maxChars = maxChars).map { Piece("", listOf(Part.Text(it)), it) }
        return if (cut) pieces + cutShort() else pieces
    }

    private suspend fun epub(uri: Uri, onCut: () -> Unit): String {
        var n = 0
        val chapter = Regex(".*\\.(xhtml|html?)", RegexOption.IGNORE_CASE)
        return zipText(uri, { if (chapter.matches(it)) n++ else null }, onCut, ::stripHtml)
    }

    private fun plainText(uri: Uri, onCut: () -> Unit): String =
        context.contentResolver.openInputStream(uri)?.use { it.readCapped(TEXT_BYTES, onCut) }.orEmpty()

    // Entries whose [order] is non-null are read and concatenated sorted by it.
    private suspend fun zipText(uri: Uri, order: (String) -> Int?, onCut: () -> Unit, strip: (String) -> String = ::stripXml): String {
        val entries = mutableListOf<Pair<Int, String>>()
        val budget = ZipBudget(onCut = onCut)
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val n = order(entry.name) ?: continue
                    entries += n to strip(budget.entry { zip.readCapped(it, onCut) } ?: break)
                }
            }
        }
        return entries.sortedBy { it.first }.joinToString("\n") { it.second }
    }

    private fun audio(uri: Uri, profile: DeviceProfile): List<Piece> {
        val pcm = MediaDecoding.audioAt(context, uri, 0, AUDIO_FILE_SECONDS * 1000L)
        val pieces = audioWindows(pcm, audioWindowSec(profile.maxTokens)).map { (start, samples) ->
            Piece(timeLocator(start), listOf(Part.Audio(samples)), null)
        }
        return if (MediaDecoding.durationMs(context, uri) > AUDIO_FILE_SECONDS * 1000L) pieces + cutShort() else pieces
    }

    private fun video(uri: Uri, profile: DeviceProfile): List<Piece> {
        val audioSec = if (profile.audio && profile.model.audio) momentAudioSec(profile.maxTokens, profile.visionTokens) else 0
        return MediaDecoding.momentFrames(context, uri, profile.videoFrameEverySec, audioSec).map { (m, jpeg) ->
            val audio = if (audioSec > 1) MediaDecoding.audioAt(context, uri, m.audioFromMs, m.audioToMs) else null
            Piece(momentLocator(m, audio != null), momentParts(jpeg, m.frameMs, audio), null)
        }
    }
}

/**
 * The first [cap] bytes as UTF-8; [onCut] runs when there was more. Grows as it reads, so a small file or zip entry
 * never costs a [cap]-sized buffer (readNBytes(int) is API 33, above minSdk 30).
 */
fun InputStream.readCapped(cap: Int, onCut: () -> Unit): String {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() <= cap) {
        val read = read(buf, 0, minOf(buf.size, cap + 1 - out.size()))
        if (read < 0) break
        out.write(buf, 0, read)
    }
    val bytes = out.toByteArray()
    if (bytes.size > cap) onCut()
    return String(bytes, 0, minOf(bytes.size, cap), Charsets.UTF_8)
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
