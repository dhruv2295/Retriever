package app.retriever.core.android

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
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
import androidx.core.graphics.scale
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
import app.retriever.core.OcrStrips
import app.retriever.core.PDF_HEAD_OCR_PAGES
import app.retriever.core.PDF_OCR_PAGES
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.SourceFile
import app.retriever.core.TEXT_BYTES
import app.retriever.core.ZipBudget
import app.retriever.core.betterPageText
import app.retriever.core.chunkChars
import app.retriever.core.cutShort
import app.retriever.core.decodeText
import app.retriever.core.docFormat
import app.retriever.core.fitSize
import app.retriever.core.imageWithText
import app.retriever.core.joinStrips
import app.retriever.core.later
import app.retriever.core.mlKitExecutor
import app.retriever.core.momentLocator
import app.retriever.core.momentParts
import app.retriever.core.ocrOrder
import app.retriever.core.ocrScripts
import app.retriever.core.ocrStrips
import app.retriever.core.pdfPieces
import app.retriever.core.readScripts
import app.retriever.core.slideOrder
import app.retriever.core.stripHtml
import app.retriever.core.stripRtf
import app.retriever.core.stripXml
import app.retriever.core.textEntry
import app.retriever.core.textPieces
import app.retriever.core.traced
import app.retriever.core.tracedAsync
import app.retriever.core.xlsxOrder
import app.retriever.core.xlsxText
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

private const val PDF_PREV_EXTENSION = 13

/** The model's and the labeler's input for a photo or screenshot; iOS asks PhotoKit for the same. */
private const val EMBED_PX = 512

/** A scanned PDF page as the model sees it, on the first read and in visual(): 512 px on its short side, at most 1024 on its long. */
private const val PAGE_SHORT_PX = 512
private const val PAGE_LONG_PX = 1024

private class PdfPg(page: AutoCloseable, val width: Int, val height: Int, text: () -> String, val render: (Bitmap) -> Unit) : AutoCloseable by page {
    /** Read on first use only: a page pdfPieces sends to OCR is opened again, and its layer is passed in by then. */
    val text by lazy(text)
}

private class PdfDoc(val pageCount: Int, val hasTextLayer: Boolean, doc: AutoCloseable, val page: (Int) -> PdfPg) : AutoCloseable by doc

/** Android 15 reads the text layer with PdfRenderer; 11-14 with PdfRendererPreV where the S extension 13 is present; else none. */
private fun openPdf(fd: ParcelFileDescriptor): PdfDoc = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> PdfRenderer(fd).let { r ->
        PdfDoc(r.pageCount, true, r) { i ->
            val p = r.openPage(i)
            PdfPg(p, p.width, p.height, { p.textContents.joinToString("\n") { it.text } }) {
                p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PDF_PREV_EXTENSION -> openPdfPreV(fd)

    else -> PdfRenderer(fd).let { r ->
        PdfDoc(r.pageCount, false, r) { i ->
            val p = r.openPage(i)
            PdfPg(p, p.width, p.height, { "" }) { p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }
    }
}

// ponytail: on Android 16 textContents drops 1-2 trailing characters of some runs (platform issue); the gate stays open.
@RequiresExtension(extension = Build.VERSION_CODES.S, version = PDF_PREV_EXTENSION)
private fun openPdfPreV(fd: ParcelFileDescriptor): PdfDoc = PdfRendererPreV(fd).let { r ->
    PdfDoc(r.pageCount, true, r) { i ->
        val p = r.openPage(i)
        PdfPg(p, p.width, p.height, { p.textContents.joinToString("\n") { it.text } }) {
            p.render(it, null, null, RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build())
        }
    }
}

/** Labels only add to a picture: when labeling fails, the picture and its text are kept without any. */
internal suspend fun optionalLabels(log: MediaLog, label: suspend () -> String): String = try {
    label()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log("image labeling failed; keeping the picture without labels", e)
    ""
}

class AndroidExtractor(private val context: Context, private val log: MediaLog) :
    Extractor,
    AutoCloseable {
    private val labeler = lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).setExecutor(mlKitExecutor).build())
    }
    private val recognizers = ConcurrentHashMap<OcrScript, TextRecognizer>()

    private fun recognizer(script: OcrScript): TextRecognizer = recognizers.computeIfAbsent(script) {
        TextRecognition.getClient(
            when (it) {
                OcrScript.LATIN -> TextRecognizerOptions.Builder().setExecutor(mlKitExecutor).build()
                OcrScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().setExecutor(mlKitExecutor).build()
                OcrScript.CHINESE -> ChineseTextRecognizerOptions.Builder().setExecutor(mlKitExecutor).build()
                OcrScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().setExecutor(mlKitExecutor).build()
                OcrScript.KOREAN -> KoreanTextRecognizerOptions.Builder().setExecutor(mlKitExecutor).build()
            },
        )
    }

    override fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
        if (labeler.isInitialized()) labeler.value.close()
    }

    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = read(file, profile, head = false)

    override suspend fun extractHead(file: SourceFile, profile: DeviceProfile): List<Piece> = read(file, profile, head = true)

    private suspend fun read(file: SourceFile, profile: DeviceProfile, head: Boolean): List<Piece> {
        currentCoroutineContext().ensureActive()
        val uri = file.uri.toUri()
        val maxChars = chunkChars(profile.maxTokens)
        val ctx = currentCoroutineContext()
        return when (file.kind) {
            Kind.PHOTO, Kind.SCREENSHOT -> picture(uri, maxChars, file.kind == Kind.PHOTO)
            Kind.PDF -> pdf(uri, maxChars, if (head) PDF_HEAD_OCR_PAGES else PDF_OCR_PAGES)
            Kind.DOC -> doc(file, uri, maxChars)
            Kind.AUDIO -> emptyList()
            Kind.VIDEO -> video(uri, profile, ctx, head)
            Kind.OTHER -> emptyList()
        }.also { currentCoroutineContext().ensureActive() }
    }

    override suspend fun later(file: SourceFile, locator: String, profile: DeviceProfile): List<Piece>? {
        currentCoroutineContext().ensureActive()
        val uri = file.uri.toUri()
        return when (file.kind) {
            Kind.PDF -> {
                val page = Locator.pageOf(locator)?.minus(1) ?: return emptyList()
                val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                fd.use {
                    openPdf(fd).use { pdf ->
                        if (page >= pdf.pageCount) emptyList() else pdf.page(page).use { scannedPage(it, locator, it.text, chunkChars(profile.maxTokens)) }
                    }
                }
            }

            // A moment whose frame turned out black holds nothing, as the first read would have dropped it.
            Kind.VIDEO -> MediaDecoding.frameJpeg(context, uri, Locator.msOf(locator) ?: return emptyList())
                ?.let { listOf(Piece(locator, listOf(Part.Image(it)), null)) }.orEmpty()

            else -> super.later(file, locator, profile)
        }
    }

    private suspend fun picture(uri: Uri, maxChars: Int, photo: Boolean): List<Piece> {
        val maxSide = if (photo) OCR_WIDTH else MAX_PAGE_PX
        var tall: OcrStrips? = null
        // Sections for a Perfetto capture: which of the four steps a photo's time goes to.
        // GC owns these pixels: native ML Kit tasks may still hold them after coroutine cancellation.
        val bitmap = traced("pic.decode") {
            ImageBytes.bitmap(context, uri, maxSide) { w, h ->
                tall = if (photo) null else ocrStrips(w, h)
                // A screenshot too tall to OCR whole is read in strips below, so this decode only feeds the model and labels.
                if (tall != null) fitSize(w, h, EMBED_PX, EMBED_PX) else fitSize(w, h, OCR_SHORT_PX, maxSide)
            }
        } ?: return emptyList()
        val strips = tall
        // The model and the labeler see the OCR decode scaled down: no second decode, and no MediaProvider thumbnail.
        val thumb = traced("pic.thumb") { ImageBytes.scaled(bitmap, EMBED_PX, EMBED_PX) }
        val text = tracedAsync("pic.ocr") { if (strips == null) ocr(bitmap) else tallText(uri, strips) }
        // The same 512 px pixels label and embed; only after labeling, as compress recycles them.
        val labels = optionalLabels(log) { tracedAsync("pic.labels") { labels(thumb) } }
        val jpeg = runCatching { ImageBytes.compress(thumb) }.getOrNull() ?: return emptyList()
        val pieces = imageWithText("", jpeg, text, maxChars, labels)
        return if (strips?.cut == true) pieces + cutShort() else pieces
    }

    /** A screenshot's text strip by strip; read whole at the long-side cap, as before strips, when it can't be. */
    private suspend fun tallText(uri: Uri, strips: OcrStrips): String = ImageBytes.strips(context, uri, strips) { ocr(it) }?.let(::joinStrips)
        ?: ImageBytes.bitmap(context, uri, MAX_PAGE_PX, maxShort = OCR_SHORT_PX)?.let { ocr(it) }.orEmpty()

    private suspend fun labels(bitmap: Bitmap): String =
        labeler.value.process(InputImage.fromBitmap(bitmap, 0)).awaitResult().joinToString("\n") { it.text.lowercase() }

    override suspend fun visual(file: SourceFile, locator: String): Part.Image? {
        currentCoroutineContext().ensureActive()
        val uri = file.uri.toUri()
        val bytes = when (file.kind) {
            Kind.PHOTO, Kind.SCREENSHOT -> ImageBytes.jpeg(context, uri, EMBED_PX)

            Kind.PDF -> {
                val page = Locator.pageOf(locator)?.minus(1) ?: return null
                context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        if (page >= pdf.pageCount) return null
                        pdf.openPage(page).use { pg ->
                            val (w, h) = fitSize(pg.width, pg.height, PAGE_SHORT_PX, PAGE_LONG_PX)
                            val bitmap = createBitmap(w, h)
                            bitmap.eraseColor(Color.WHITE)
                            pg.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            ImageBytes.compress(bitmap)
                        }
                    }
                }
            }

            Kind.VIDEO -> MediaDecoding.frameJpeg(context, uri, Locator.msOf(locator) ?: return null)

            else -> null
        }
        return bytes?.let { Part.Image(it) }
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

    /** The text and the recognizer's own confidence in it, averaged over its lines by length; nothing when it fails. */
    private suspend fun recognize(client: TextRecognizer, image: InputImage): OcrReading {
        val text = try {
            client.process(image).awaitResult()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return OcrReading.NONE
        }
        val lines = text.textBlocks.flatMap { it.lines }
        val chars = lines.sumOf { it.text.length }
        val confidence = if (chars == 0) 0f else lines.sumOf { it.confidence.toDouble() * it.text.length }.toFloat() / chars
        return OcrReading(text.text.trim(), confidence)
    }

    private suspend fun pdf(uri: Uri, maxChars: Int, ocrNow: Int): List<Piece> {
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
                    ocrNow,
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
        // The model gets the page at visual()'s size, as after a model change, not the 3 MP OCR render.
        return imageWithText(locator, ImageBytes.pageJpeg(bitmap), betterPageText(layer, ocr(bitmap)), maxChars)
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
        val pieces = Chunker.chunks(text, maxChars = maxChars).map { Piece("", listOf(Part.Text(it)), it, it) }
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

    private fun video(uri: Uri, profile: DeviceProfile, ctx: CoroutineContext, head: Boolean): List<Piece> =
        MediaDecoding.momentFrames(context, uri, profile.videoFrameEverySec, ctx, log, head = head).map { (m, jpeg) ->
            if (jpeg == null) later(momentLocator(m, false)) else Piece(momentLocator(m, false), momentParts(jpeg, m.frameMs, null), null)
        }
}

/**
 * An ML Kit task's result, waited for without a main-thread callback. A task cancelled while the caller is still active
 * (its client was closed) fails like any other task rather than looking like a stop.
 */
internal suspend fun <T> Task<T>.awaitResult(): T = try {
    await()
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    throw IllegalStateException("ML Kit task cancelled", e)
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
 * The first [cap] bytes as text ([decodeText]); [onCut] runs when there was more. Grows as it reads, so a small file or
 * zip entry never costs a [cap]-sized buffer (readNBytes(int) is API 33, above minSdk 30).
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
    return decodeText(if (bytes.size > cap) bytes.copyOf(cap) else bytes)
}

object ImageBytes {
    /** Decoded to fit [maxSide] on the longer side and [maxShort] on the shorter. */
    fun bitmap(context: Context, uri: Uri, maxSide: Int, maxShort: Int = Int.MAX_VALUE): Bitmap? =
        bitmap(context, uri, maxSide) { w, h -> fitSize(w, h, maxShort, maxSide) }

    /**
     * Decoded at the size [fit] picks from the image's own (scaled down only); MediaStore's thumbnail within
     * [fallbackSide] when the platform can't decode the file.
     */
    fun bitmap(context: Context, uri: Uri, fallbackSide: Int, fit: (width: Int, height: Int) -> Pair<Int, Int>): Bitmap? = try {
        decode(ImageDecoder.createSource(context.contentResolver, uri), fit)
    } catch (e: Exception) {
        // ImageBytes.bitmap's loadThumbnail fallback adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt (Apache-2.0).
        runCatching { context.contentResolver.loadThumbnail(uri, Size(fallbackSide, fallbackSide), null) }.getOrNull()
    }

    /** [bitmap] scaled down to fit [maxShort] x [maxLong], always as a new bitmap: [compress] recycles what it is given. */
    fun scaled(bitmap: Bitmap, maxShort: Int, maxLong: Int): Bitmap {
        val (w, h) = fitSize(bitmap.width, bitmap.height, maxShort, maxLong)
        return if (w == bitmap.width && h == bitmap.height) bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) else bitmap.scale(w, h)
    }

    /** A rendered PDF page as the model's image, at the size visual() renders it; [page] stays whole for OCR. */
    fun pageJpeg(page: Bitmap): ByteArray = compress(scaled(page, PAGE_SHORT_PX, PAGE_LONG_PX))

    /**
     * [read] over [plan]'s bands of [uri], decoded one at a time by a region decoder so no bitmap is larger than one
     * strip; null when the file can't be region-decoded, a band fails, or the file is not the size [plan] is for.
     */
    suspend fun <T> strips(context: Context, uri: Uri, plan: OcrStrips, read: suspend (Bitmap) -> T): List<T>? {
        val fd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        } ?: return null
        return fd.use {
            val decoder = try {
                regionDecoder(fd)
            } catch (_: Exception) {
                return null
            }
            try {
                // The plan's size came from ImageDecoder, which applies a JPEG's EXIF rotation; the region decoder does not.
                if (decoder.width != plan.width || decoder.height != plan.height) return null
                val options = BitmapFactory.Options().apply { inSampleSize = plan.sample }
                plan.bands.map { rows ->
                    val bitmap = decoder.decodeRegion(Rect(0, rows.first, plan.width, rows.last + 1), options) ?: return null
                    // Recycled only once read returns: native ML Kit tasks may still hold the pixels after a cancellation.
                    read(bitmap).also { bitmap.recycle() }
                }
            } finally {
                decoder.recycle()
            }
        }
    }

    private fun regionDecoder(fd: ParcelFileDescriptor): BitmapRegionDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(fd)
    } else {
        // Android 11 has only this file-descriptor variant.
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(fd.fileDescriptor, false)
    }

    /**
     * The embed image, decoded here at [maxSide]. Not ContentResolver.loadThumbnail: that is MediaProvider's UI thumbnail,
     * often a camera's small EXIF one, and on a cache miss it decodes the image again and writes a file to shared storage.
     */
    fun jpeg(context: Context, uri: Uri, maxSide: Int): ByteArray? = bitmap(context, uri, maxSide)?.let { runCatching { compress(it) }.getOrNull() }

    /** A bundled drawable as an embed image, decoded straight from the APK's resources. */
    fun jpeg(resources: Resources, id: Int, maxSide: Int): ByteArray? =
        runCatching { compress(decode(ImageDecoder.createSource(resources, id)) { w, h -> fitSize(w, h, Int.MAX_VALUE, maxSide) }) }.getOrNull()

    private fun decode(source: ImageDecoder.Source, fit: (Int, Int) -> Pair<Int, Int>): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val (w, h) = fit(info.size.width, info.size.height)
        if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
    }

    internal fun compress(bitmap: Bitmap): ByteArray = try {
        ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }.toByteArray()
    } finally {
        bitmap.recycle()
    }
}
