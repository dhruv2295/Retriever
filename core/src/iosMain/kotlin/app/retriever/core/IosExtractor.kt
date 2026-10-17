package app.retriever.core

import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * The same pieces and locators as AndroidExtractor, over Swift's file access. A missing JPEG (an iCloud-only
 * asset) drops that piece; the file's name stays searchable.
 */
class IosExtractor(private val native: NativeExtractor) : Extractor {
    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = withContext(Dispatchers.IO) {
        val uri = file.uri
        val maxChars = chunkChars(profile.maxTokens)
        when (file.kind) {
            Kind.PHOTO -> listOfNotNull(native.imageJpeg(uri, 512)?.let { Piece("", listOf(Part.Image(it.toByteArray())), null) })
            Kind.SCREENSHOT -> native.imageJpeg(uri, 512)?.let { imageWithText("", it.toByteArray(), ocr(uri, -1), maxChars) }.orEmpty()
            Kind.PDF -> pdf(uri, maxChars)
            Kind.DOC -> doc(file, uri, maxChars)
            Kind.VIDEO -> video(uri, profile)
            else -> emptyList()
        }
    }

    private fun ocr(uri: String, page: Int): String = native.ocr(uri, page).orEmpty().trim()

    /** OCR text is stored once: in its text pieces, or on the image piece when it is too short to chunk. */
    private fun imageWithText(locator: String, jpeg: ByteArray, ocr: String, maxChars: Int): List<Piece> {
        val text = textPieces(locator, ocr, maxChars)
        val keyword = if (text.isEmpty()) ocr.ifBlank { null } else null
        return listOf(Piece(locator, listOf(Part.Image(jpeg)), keyword)) + text
    }

    // A locked PDF reports -1 and leaves the file to its name chunk. One pool per page: the whole file is one
    // coroutine task, so without it every page's renders and PDFKit objects live until the file is done.
    private fun pdf(uri: String, maxChars: Int): List<Piece> =
        (0 until minOf(native.pdfPageCount(uri), PDF_PAGES)).flatMap { i -> autoreleasepool { pdfPage(uri, i, maxChars) } }

    private fun pdfPage(uri: String, i: Int, maxChars: Int): List<Piece> {
        val locator = "p. ${i + 1}"
        val text = native.pdfPageText(uri, i).orEmpty()
        return if (text.trim().length >= MIN_TEXT_CHARS) {
            textPieces(locator, text, maxChars)
        } else {
            native.pdfPageJpeg(uri, i, 1024)?.let { imageWithText(locator, it.toByteArray(), ocr(uri, i), maxChars) }.orEmpty()
        }
    }

    private fun doc(file: SourceFile, uri: String, maxChars: Int): List<Piece> {
        val format = docFormat(file.name, file.mime) ?: return emptyList()
        val text = when (format) {
            DocFormat.DOCX, DocFormat.XLSX, DocFormat.ODF -> stripXml(native.zipEntryText(uri, format.textEntry!!, 4 * TEXT_BYTES).orEmpty())
            DocFormat.PPTX -> zipText(uri, ::slideOrder)
            DocFormat.EPUB -> epub(uri)
            DocFormat.HTML -> stripHtml(native.textHead(uri, TEXT_BYTES).orEmpty())
            DocFormat.TEXT -> native.textHead(uri, TEXT_BYTES).orEmpty()
        }
        return Chunker.chunks(text, maxChars = maxChars).map { Piece("", listOf(Part.Text(it)), it) }
    }

    private fun epub(uri: String): String {
        var n = 0
        val chapter = Regex(".*\\.(xhtml|html?)", RegexOption.IGNORE_CASE)
        return zipText(uri, { if (chapter.matches(it)) n++ else null }, ::stripHtml)
    }

    // Entries whose [order] is non-null are read and concatenated sorted by it.
    private fun zipText(uri: String, order: (String) -> Int?, strip: (String) -> String = ::stripXml): String =
        native.zipEntryNames(uri)
            .mapNotNull { name -> order(name)?.let { it to name } }
            .sortedBy { it.first }
            .joinToString("\n") { strip(native.zipEntryText(uri, it.second, 4 * TEXT_BYTES).orEmpty()) }

    private fun video(uri: String, profile: DeviceProfile): List<Piece> =
        if (profile.videoFrameEverySec <= 0) emptyList() else moments(native.videoDurationMs(uri), profile.videoFrameEverySec, 0).mapNotNull { m ->
            autoreleasepool {
                native.videoFrameJpeg(uri, m.frameMs, 512)?.let { Piece(momentLocator(m, false), momentParts(it.toByteArray(), m.frameMs, null), null) }
            }
        }
}
