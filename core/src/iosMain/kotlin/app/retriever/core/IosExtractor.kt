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
    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
        currentCoroutineContext().ensureActive()
        val uri = file.uri
        val maxChars = chunkChars(profile.maxTokens)
        return when (file.kind) {
            Kind.PHOTO -> listOf(Piece("", listOf(Part.Image(image(uri))), null))
            Kind.SCREENSHOT -> imageWithText("", image(uri), ocr(uri, -1), maxChars)
            Kind.PDF -> pdf(uri, maxChars)
            Kind.DOC -> doc(file, uri, maxChars)
            Kind.VIDEO -> video(uri, profile)
            else -> emptyList()
        }.also { currentCoroutineContext().ensureActive() }
    }

    private fun ocr(uri: String, page: Int): String = native.ocr(uri, page).orEmpty().trim()

    private fun image(uri: String): ByteArray =
        checkNotNull(native.imageJpeg(uri, 512)) { "no image to read yet (an iCloud photo waits for Wi-Fi)" }.toByteArray()

    // A locked PDF reports -1 and leaves the file to its name chunk. One pool per page: the whole file is one
    // coroutine task, so without it every page's renders and PDFKit objects live until the file is done.
    private suspend fun pdf(uri: String, maxChars: Int): List<Piece> = pdfPieces(
        native.pdfPageCount(uri),
        null,
        text = { i -> autoreleasepool { native.pdfPageText(uri, i).orEmpty() } },
        scanned = { i, layer ->
            autoreleasepool {
                native.pdfPageJpeg(uri, i, 1024)?.let {
                    imageWithText(Locator.page(i + 1), it.toByteArray(), betterPageText(layer, ocr(uri, i)), maxChars)
                }.orEmpty()
            }
        },
        maxChars,
    )

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

    private fun video(uri: String, profile: DeviceProfile): List<Piece> = if (profile.videoFrameEverySec <= 0) {
        emptyList()
    } else {
        moments(native.videoDurationMs(uri), profile.videoFrameEverySec, 0).mapNotNull { m ->
            autoreleasepool {
                native.videoFrameJpeg(uri, m.frameMs, 512)?.let { Piece(momentLocator(m, false), momentParts(it.toByteArray(), m.frameMs, null), null) }
            }
        }
    }
}
