package app.retriever.core.android

import android.content.Context
import android.content.res.Resources
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
import android.util.Size
import androidx.annotation.RequiresExtension
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import app.retriever.core.Chunker
import app.retriever.core.DeviceProfile
import app.retriever.core.DocFormat
import app.retriever.core.Extractor
import app.retriever.core.Kind
import app.retriever.core.Locator
import app.retriever.core.MAX_PAGE_PX
import app.retriever.core.OCR_SHORT_PX
import app.retriever.core.OCR_WIDTH
import app.retriever.core.OcrReading
import app.retriever.core.OcrScript
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.SourceFile
import app.retriever.core.TEXT_BYTES
import app.retriever.core.ZipBudget
import app.retriever.core.audioWindowSec
import app.retriever.core.audioWindows
import app.retriever.core.betterPageText
import app.retriever.core.chunkChars
import app.retriever.core.cutShort
import app.retriever.core.docFormat
import app.retriever.core.fitSize
import app.retriever.core.imageWithText
import app.retriever.core.momentAudioSec
import app.retriever.core.momentLocator
import app.retriever.core.momentParts
import app.retriever.core.ocrOrder
import app.retriever.core.ocrScripts
import app.retriever.core.pdfPieces
import app.retriever.core.readScripts
import app.retriever.core.slideOrder
import app.retriever.core.stripHtml
import app.retriever.core.stripRtf
import app.retriever.core.stripXml
import app.retriever.core.textEntry
import app.retriever.core.textPieces
import app.retriever.core.xlsxOrder
import app.retriever.core.xlsxText
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

private const val AUDIO_FILE_SECONDS = 300
private const val PDF_PREV_EXTENSION = 13

private class PdfPg(val width: Int, val height: Int, val text: String, val render: (Bitmap) -> Unit, page: AutoCloseable) : AutoCloseable by page

private class PdfDoc(val pageCount: Int, val hasTextLayer: Boolean, doc: AutoCloseable, val page: (Int) -> PdfPg) : AutoCloseable by doc

private fun pdfPage(page: AutoCloseable, width: Int, height: Int, text: () -> String, render: (Bitmap) -> Unit): PdfPg = try {
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

class AndroidExtractor(private val context: Context, private val log: MediaLog) :
    Extractor,
    AutoCloseable {
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
        val ctx = currentCoroutineContext()
        when (file.kind) {
            Kind.PHOTO -> photo(uri)
            Kind.SCREENSHOT -> screenshot(uri, maxChars)
            Kind.PDF -> pdf(uri, maxChars)
            Kind.DOC -> doc(file, uri, maxChars)
            Kind.AUDIO -> if (profile.audio && profile.model.audio) audio(uri, profile, ctx) else emptyList()
            Kind.VIDEO -> video(uri, profile, ctx)
            Kind.OTHER -> emptyList()
        }
    }

    private fun photo(uri: Uri): List<Piece> {
        val jpeg = ImageBytes.jpeg(context, uri, 512) ?: return emptyList()
        return listOf(Piece("", listOf(Part.Image(jpeg)), null))
    }

    private suspend fun screenshot(uri: Uri, maxChars: Int): List<Piece> {
        val jpeg = ImageBytes.jpeg(context, uri, 512) ?: return emptyList()
        // Not recycled: ML Kit may still hold the pixels when a cancelled read returns; the GC frees them.
        val ocr = ImageBytes.bitmap(context, uri, MAX_PAGE_PX, maxShort = OCR_SHORT_PX)?.let { ocr(it) }.orEmpty()
        return imageWithText("", jpeg, ocr, maxChars)
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
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return fd.use {
            // Password-protected PDFs (bank statements, policies) can't be read without the password; leave
            // them to the name chunk instead of failing the file three times.
            val opened = try {
                openPdf(fd)
            } catch (_: SecurityException) {
                return emptyList()
            }
            opened.use { renderer ->
                pdfPieces(
                    renderer.pageCount,
                    renderer.hasTextLayer,
                    text = { i -> renderer.page(i).use { it.text } },
                    scanned = { i, layer -> renderer.page(i).use { scannedPage(it, Locator.page(i + 1), layer, maxChars) } },
                    maxChars,
                )
            }
        }
    }

    private suspend fun scannedPage(page: PdfPg, locator: String, layer: String, maxChars: Int): List<Piece> {
        // OCR_WIDTH wide, but a receipt-shaped page (1:40) would be a huge bitmap: cap the height and narrow it.
        val height = (OCR_WIDTH.toLong() * page.height / page.width).coerceIn(1, MAX_PAGE_PX.toLong()).toInt()
        val width = (height.toLong() * page.width / page.height).coerceIn(1, OCR_WIDTH.toLong()).toInt()
        val bitmap = createBitmap(width, height)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return imageWithText(locator, out.toByteArray(), betterPageText(layer, ocr(bitmap)), maxChars)
    }

    private suspend fun doc(file: SourceFile, uri: Uri, maxChars: Int): List<Piece> {
        val format = docFormat(file.name, file.mime) ?: return emptyList()
        var cut = false
        val onCut = { cut = true }
        val text = when (format) {
            DocFormat.DOCX, DocFormat.ODF -> zipText(uri, { if (it == format.textEntry) 0 else null }, onCut, single = true)
            DocFormat.PPTX -> zipText(uri, ::slideOrder, onCut)
            DocFormat.XLSX -> xlsxText(zipText(uri, ::xlsxOrder, onCut, strip = { it }, xlsx = true))
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

    private suspend fun zipText(
        uri: Uri,
        order: (String) -> Int?,
        onCut: () -> Unit,
        strip: (String) -> String = ::stripXml,
        single: Boolean = false,
        xlsx: Boolean = false,
    ): String = context.contentResolver.openInputStream(uri)?.use { zipText(it, order, onCut, strip, single, xlsx) }.orEmpty()

    private fun audio(uri: Uri, profile: DeviceProfile, ctx: CoroutineContext): List<Piece> {
        val pcm = MediaDecoding.audioAt(context, uri, 0, AUDIO_FILE_SECONDS * 1000L, ctx, log)
        val pieces = audioWindows(pcm, audioWindowSec(profile.maxTokens)).map { (start, samples) ->
            Piece(Locator.time(start), listOf(Part.Audio(samples)), null)
        }
        return if (MediaDecoding.durationMs(context, uri, log) > AUDIO_FILE_SECONDS * 1000L) pieces + cutShort() else pieces
    }

    private fun video(uri: Uri, profile: DeviceProfile, ctx: CoroutineContext): List<Piece> {
        val audioSec = if (profile.audio && profile.model.audio) momentAudioSec(profile.maxTokens, profile.visionTokens) else 0
        return MediaDecoding.momentFrames(context, uri, profile.videoFrameEverySec, audioSec, ctx, log).map { (m, jpeg) ->
            val audio = if (audioSec > 1) MediaDecoding.audioAt(context, uri, m.audioFromMs, m.audioToMs, ctx, log) else null
            Piece(momentLocator(m, audio != null), momentParts(jpeg, m.frameMs, audio), null)
        }
    }
}

/**
 * Entries of a zip whose [order] is non-null, read and concatenated sorted by it. [single] stops after the first
 * match (docx, odf: one text entry). [xlsx] keeps [TEXT_BYTES] of the budget back from the sheets until
 * xl/sharedStrings.xml is read, so its strings always fit however big the sheets before it are.
 */
internal suspend fun zipText(
    input: InputStream,
    order: (String) -> Int?,
    onCut: () -> Unit,
    strip: (String) -> String = ::stripXml,
    single: Boolean = false,
    xlsx: Boolean = false,
): String {
    val entries = mutableListOf<Pair<Int, String>>()
    val budget = ZipBudget(onCut = onCut)
    var stringsRead = !xlsx
    ZipInputStream(input).use { zip ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: break
            val n = order(entry.name) ?: continue
            val reserve = if (stringsRead || n == 0) 0 else TEXT_BYTES
            entries += n to strip(budget.entry(reserve) { zip.readCapped(it, onCut) } ?: break)
            if (n == 0) stringsRead = true
            if (single) break
        }
    }
    return entries.sortedBy { it.first }.joinToString("\n") { it.second }
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
    /** Decoded to fit [maxSide] on the longer side and [maxShort] on the shorter. */
    fun bitmap(context: Context, uri: Uri, maxSide: Int, maxShort: Int = Int.MAX_VALUE): Bitmap? = try {
        decode(ImageDecoder.createSource(context.contentResolver, uri), maxSide, maxShort)
    } catch (e: Exception) {
        // ImageBytes.bitmap's loadThumbnail fallback adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt (Apache-2.0).
        runCatching { context.contentResolver.loadThumbnail(uri, Size(maxSide, maxSide), null) }.getOrNull()
    }

    /** The embed image: MediaStore's cached thumbnail when it has one (no full decode), else a decode at [maxSide]. */
    fun jpeg(context: Context, uri: Uri, maxSide: Int): ByteArray? =
        runCatching { compress(context.contentResolver.loadThumbnail(uri, Size(maxSide, maxSide), null)) }.getOrNull()
            ?: bitmap(context, uri, maxSide)?.let { runCatching { compress(it) }.getOrNull() }

    /** A bundled drawable as an embed image, decoded straight from the APK's resources. */
    fun jpeg(resources: Resources, id: Int, maxSide: Int): ByteArray? =
        runCatching { compress(decode(ImageDecoder.createSource(resources, id), maxSide, Int.MAX_VALUE)) }.getOrNull()

    private fun decode(source: ImageDecoder.Source, maxSide: Int, maxShort: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val (w, h) = fitSize(info.size.width, info.size.height, maxShort, maxSide)
        if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
    }

    private fun compress(bitmap: Bitmap): ByteArray = try {
        ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }.toByteArray()
    } finally {
        bitmap.recycle()
    }
}
