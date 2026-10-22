@file:OptIn(BetaInteropApi::class)

package app.retriever.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The same pieces and locators as AndroidExtractor, over Swift's file access. A photo with no image to read (an
 * iCloud-only asset off Wi-Fi) fails, so it is tried again on a later batch and, after three tries, is listed as
 * skipped with a way to retry, rather than counted as read by its name alone.
 */
class IosExtractor(private val native: NativeExtractor) : Extractor {
    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = read(file, profile, head = false)

    override suspend fun extractHead(file: SourceFile, profile: DeviceProfile): List<Piece> = read(file, profile, head = true)

    private suspend fun read(file: SourceFile, profile: DeviceProfile, head: Boolean): List<Piece> {
        currentCoroutineContext().ensureActive()
        val uri = file.uri
        val maxChars = chunkChars(profile.maxTokens)
        return when (file.kind) {
            Kind.PHOTO, Kind.SCREENSHOT -> imageWithText("", image(uri), ocr(uri, -1), maxChars, native.imageLabels(uri))
            Kind.PDF -> pdf(uri, maxChars, if (head) PDF_HEAD_OCR_PAGES else PDF_OCR_PAGES)
            Kind.DOC -> doc(file, uri, maxChars)
            Kind.VIDEO -> video(uri, profile, head)
            else -> emptyList()
        }.also { currentCoroutineContext().ensureActive() }
    }

    override suspend fun later(file: SourceFile, locator: String, profile: DeviceProfile): List<Piece> {
        if (file.kind != Kind.PDF) return super.later(file, locator, profile)
        currentCoroutineContext().ensureActive()
        val page = (Locator.pageOf(locator) ?: return emptyList()) - 1
        return scannedPage(file.uri, page, native.pdfPageText(file.uri, page).orEmpty(), chunkChars(profile.maxTokens))
            .ifEmpty { error("PDF page unavailable") }
    }

    private fun scannedPage(uri: String, i: Int, layer: String, maxChars: Int): List<Piece> = autoreleasepool {
        native.pdfPageJpeg(uri, i, 1024)?.let {
            imageWithText(Locator.page(i + 1), it.toByteArray(), betterPageText(layer, ocr(uri, i)), maxChars)
        }.orEmpty()
    }

    override suspend fun visual(file: SourceFile, locator: String): Part.Image? {
        currentCoroutineContext().ensureActive()
        val bytes = when (file.kind) {
            Kind.PHOTO, Kind.SCREENSHOT -> native.imageJpeg(file.uri, 512)
            Kind.PDF -> native.pdfPageJpeg(file.uri, (Locator.pageOf(locator) ?: return null) - 1, 1024)
            Kind.VIDEO -> native.videoFrameJpeg(file.uri, Locator.msOf(locator) ?: return null, 512)
            else -> null
        }
        return bytes?.let { Part.Image(it.toByteArray()) }
    }

    private fun ocr(uri: String, page: Int): String = native.ocr(uri, page).orEmpty().trim()

    private fun image(uri: String): ByteArray =
        checkNotNull(native.imageJpeg(uri, 512)) { "no image to read yet (an iCloud photo waits for Wi-Fi)" }.toByteArray()

    // A locked PDF reports -1 and leaves the file to its name chunk. One pool per page: the whole file is one
    // coroutine task, so without it every page's renders and PDFKit objects live until the file is done.
    private suspend fun pdf(uri: String, maxChars: Int, ocrNow: Int): List<Piece> = pdfPieces(
        native.pdfPageCount(uri),
        null,
        text = { i -> autoreleasepool { native.pdfPageText(uri, i).orEmpty() } },
        scanned = { i, layer -> scannedPage(uri, i, layer, maxChars) },
        maxChars,
        ocrNow,
    )

    private suspend fun doc(file: SourceFile, uri: String, maxChars: Int): List<Piece> {
        val format = docFormat(file.name, file.mime) ?: return emptyList()
        var cut = false
        val onCut = { cut = true }

        // Swift decodes the bytes, so the extra byte read past the cap shows as a UTF-8 length over it.
        fun capped(text: String?, cap: Int): String = text.orEmpty().also { if (it.encodeToByteArray().size > cap) cut = true }
        fun entry(name: String, cap: Int) = capped(native.zipEntryText(uri, name, cap + 1), cap)

        // The first TEXT_BYTES decoded here, as Android does; a byte past them marks the file cut.
        fun head(): String {
            val bytes = native.readBytes(uri, 0, TEXT_BYTES + 1)?.toByteArray() ?: return ""
            if (bytes.size > TEXT_BYTES) cut = true
            return decodeText(if (cut) bytes.copyOf(TEXT_BYTES) else bytes)
        }
        val text = when (format) {
            DocFormat.DOCX, DocFormat.ODF -> stripXml(entry(format.textEntry!!, 4 * TEXT_BYTES))
            DocFormat.PPTX -> zipText(uri, ::slideOrder, ::entry, onCut)
            DocFormat.XLSX -> xlsxText(zipText(uri, ::xlsxOrder, ::entry, onCut, strip = { it }))
            DocFormat.EPUB -> epub(uri, ::entry, onCut)
            DocFormat.HTML -> stripHtml(head())
            DocFormat.RTF -> stripRtf(head())
            DocFormat.TEXT -> head()
        }
        val pieces = Chunker.chunks(text, maxChars = maxChars).map { Piece("", listOf(Part.Text(it)), it, it) }
        return if (cut) pieces + cutShort() else pieces
    }

    private suspend fun epub(uri: String, entry: (String, Int) -> String, onCut: () -> Unit): String {
        var n = 0
        val chapter = Regex(".*\\.(xhtml|html?)", RegexOption.IGNORE_CASE)
        return zipText(uri, { if (chapter.matches(it)) n++ else null }, entry, onCut, ::stripHtml)
    }

    // Entries whose [order] is non-null are read and concatenated sorted by it.
    private suspend fun zipText(
        uri: String,
        order: (String) -> Int?,
        entry: (String, Int) -> String,
        onCut: () -> Unit,
        strip: (String) -> String = ::stripXml,
    ): String {
        val budget = ZipBudget(onCut = onCut)
        val parts = mutableListOf<String>()
        for ((_, name) in native.zipEntryNames(uri).mapNotNull { name -> order(name)?.let { it to name } }.sortedBy { it.first }) {
            currentCoroutineContext().ensureActive()
            parts += strip(budget.entry { entry(name, it) } ?: break)
        }
        return parts.joinToString("\n")
    }

    private suspend fun video(uri: String, profile: DeviceProfile, head: Boolean): List<Piece> {
        if (profile.videoFrameEverySec <= 0) return emptyList()
        val all = moments(native.videoDurationMs(uri), profile.videoFrameEverySec, 0)
        suspend fun frame(m: Moment): Piece? {
            currentCoroutineContext().ensureActive()
            return autoreleasepool {
                native.videoFrameJpeg(uri, m.frameMs, 512)?.let { Piece(momentLocator(m, false), momentParts(it.toByteArray(), m.frameMs, null), null) }
            }
        }
        if (!head) return all.mapNotNull { frame(it) }
        // One moment now (see headOrder), the others for the deepen pass; one with no frame is dropped, as above.
        val missing = mutableSetOf<Int>()
        for (i in headOrder(all.size)) {
            val piece = frame(all[i])
            if (piece == null) {
                missing += i
                continue
            }
            return all.withIndex().filter { it.index !in missing }.map { (j, m) -> if (j == i) piece else later(momentLocator(m, false)) }
        }
        return emptyList()
    }
}
