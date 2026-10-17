@file:OptIn(BetaInteropApi::class)

package app.retriever.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The same pieces and locators as AndroidExtractor, over Swift's file access. A photo with no image to read (an
 * iCloud-only asset off Wi-Fi) fails, so it is tried again on a later batch and, after three tries, is listed as
 * skipped with a way to retry, rather than counted as read by its name alone.
 */
class IosExtractor(private val native: NativeExtractor) : Extractor {
    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = withContext(Dispatchers.IO) {
        val uri = file.uri
        val maxChars = chunkChars(profile.maxTokens)
        when (file.kind) {
            Kind.PHOTO -> listOf(Piece("", listOf(Part.Image(image(uri))), null))
            Kind.SCREENSHOT -> imageWithText("", image(uri), ocr(uri, -1), maxChars)
            Kind.PDF -> pdf(uri, maxChars)
            Kind.DOC -> doc(file, uri, maxChars)
            Kind.VIDEO -> video(uri, profile)
            else -> emptyList()
        }
    }

    private fun ocr(uri: String, page: Int): String = native.ocr(uri, page).orEmpty().trim()

    private fun image(uri: String): ByteArray =
        checkNotNull(native.imageJpeg(uri, 512)) { "no image to read yet (an iCloud photo waits for Wi-Fi)" }.toByteArray()

    /** OCR text is stored once: in its text pieces, or on the image piece when it is too short to chunk. */
    private fun imageWithText(locator: String, jpeg: ByteArray, ocr: String, maxChars: Int): List<Piece> {
        val text = textPieces(locator, ocr, maxChars)
        val keyword = if (text.isEmpty()) ocr.ifBlank { null } else null
        return listOf(Piece(locator, listOf(Part.Image(jpeg)), keyword)) + text
    }

    // A locked PDF reports -1 and leaves the file to its name chunk. One pool per page: the whole file is one
    // coroutine task, so without it every page's renders and PDFKit objects live until the file is done.
    private suspend fun pdf(uri: String, maxChars: Int): List<Piece> {
        val pageCount = native.pdfPageCount(uri)
        val pieces = mutableListOf<Piece>()
        var ocrPages = 0
        var skipped = false
        for (i in 0 until minOf(pageCount, PDF_TEXT_PAGES)) {
            currentCoroutineContext().ensureActive()
            autoreleasepool {
                val locator = "p. ${i + 1}"
                val text = native.pdfPageText(uri, i).orEmpty()
                val hasText = text.trim().length >= MIN_TEXT_CHARS
                pieces += when {
                    hasText && !looksBroken(text) -> textPieces(locator, text, maxChars)
                    ocrPages < PDF_OCR_PAGES -> {
                        ocrPages++
                        native.pdfPageJpeg(uri, i, 1024)?.let { imageWithText(locator, it.toByteArray(), betterPageText(text, ocr(uri, i)), maxChars) }.orEmpty()
                    }
                    else -> {
                        skipped = true
                        if (hasText) textPieces(locator, text, maxChars) else emptyList()
                    }
                }
            }
        }
        if (pageCount > PDF_TEXT_PAGES || skipped) pieces += cutShort()
        return pieces
    }

    private suspend fun doc(file: SourceFile, uri: String, maxChars: Int): List<Piece> {
        val format = docFormat(file.name, file.mime) ?: return emptyList()
        var cut = false
        val onCut = { cut = true }
        // Swift decodes the bytes, so the extra byte read past the cap shows as a UTF-8 length over it.
        fun capped(text: String?, cap: Int): String = text.orEmpty().also { if (it.encodeToByteArray().size > cap) cut = true }
        fun entry(name: String, cap: Int) = capped(native.zipEntryText(uri, name, cap + 1), cap)
        val text = when (format) {
            DocFormat.DOCX, DocFormat.ODF -> stripXml(entry(format.textEntry!!, 4 * TEXT_BYTES))
            DocFormat.PPTX -> zipText(uri, ::slideOrder, ::entry, onCut)
            DocFormat.XLSX -> xlsxText(zipText(uri, ::xlsxOrder, ::entry, onCut, strip = { it }))
            DocFormat.EPUB -> epub(uri, ::entry, onCut)
            DocFormat.HTML -> stripHtml(capped(native.textHead(uri, TEXT_BYTES + 1), TEXT_BYTES))
            DocFormat.RTF -> stripRtf(capped(native.textHead(uri, TEXT_BYTES + 1), TEXT_BYTES))
            DocFormat.TEXT -> capped(native.textHead(uri, TEXT_BYTES + 1), TEXT_BYTES)
        }
        val pieces = Chunker.chunks(text, maxChars = maxChars).map { Piece("", listOf(Part.Text(it)), it) }
        return if (cut) pieces + cutShort() else pieces
    }

    private suspend fun epub(uri: String, entry: (String, Int) -> String, onCut: () -> Unit): String {
        var n = 0
        val chapter = Regex(".*\\.(xhtml|html?)", RegexOption.IGNORE_CASE)
        return zipText(uri, { if (chapter.matches(it)) n++ else null }, entry, onCut, ::stripHtml)
    }

    // Entries whose [order] is non-null are read and concatenated sorted by it.
    private suspend fun zipText(uri: String, order: (String) -> Int?, entry: (String, Int) -> String, onCut: () -> Unit, strip: (String) -> String = ::stripXml): String {
        val budget = ZipBudget(onCut = onCut)
        val parts = mutableListOf<String>()
        for ((_, name) in native.zipEntryNames(uri).mapNotNull { name -> order(name)?.let { it to name } }.sortedBy { it.first }) {
            currentCoroutineContext().ensureActive()
            parts += strip(budget.entry { entry(name, it) } ?: break)
        }
        return parts.joinToString("\n")
    }

    private fun video(uri: String, profile: DeviceProfile): List<Piece> =
        if (profile.videoFrameEverySec <= 0) emptyList() else moments(native.videoDurationMs(uri), profile.videoFrameEverySec, 0).mapNotNull { m ->
            autoreleasepool {
                native.videoFrameJpeg(uri, m.frameMs, 512)?.let { Piece(momentLocator(m, false), momentParts(it.toByteArray(), m.frameMs, null), null) }
            }
        }
}
