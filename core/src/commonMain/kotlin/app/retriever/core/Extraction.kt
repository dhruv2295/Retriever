package app.retriever.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

// Text rules shared by the Android and iOS extractors.
/** Pages read through their text layer: cheap, and the head-first embed keeps the model cost per file fixed. */
const val PDF_TEXT_PAGES = 1000

/** Pages with no text layer, each a render plus OCR (about a second apiece); later ones are skipped. */
const val PDF_OCR_PAGES = 30

/** Whole text and HTML files up to this; a zip entry (docx, pptx, epub chapter) gets 4x. */
const val TEXT_BYTES = 2 * 1024 * 1024
const val MIN_TEXT_CHARS = 40
const val MIN_AUDIO_WINDOW_SEC = 2

/** The longest side of an image or page bitmap read for OCR, and the width a scanned PDF page is rendered at. */
const val MAX_PAGE_PX = 4096
const val OCR_WIDTH = 1600
const val OCR_SHORT_PX = 1080

/** [w] x [h] scaled down (never up) to fit [maxShort] on the shorter side and [maxLong] on the longer. */
fun fitSize(w: Int, h: Int, maxShort: Int, maxLong: Int): Pair<Int, Int> {
    val scale = minOf(1.0, maxShort.toDouble() / minOf(w, h), maxLong.toDouble() / maxOf(w, h))
    return if (scale >= 1.0) w to h else maxOf(1, (w * scale).roundToInt()) to maxOf(1, (h * scale).roundToInt())
}

/**
 * The size to OCR an image at: ML Kit wants about 16 px per character, which a 1080 px short side gives for screen
 * text, and the long side stays under [MAX_PAGE_PX] so a very tall screenshot does not decode to a huge bitmap.
 */
fun ocrTargetSize(w: Int, h: Int): Pair<Int, Int> = fitSize(w, h, OCR_SHORT_PX, MAX_PAGE_PX)

/**
 * One running byte budget (4 x [TEXT_BYTES]) for all the entries of a zip (epub chapters, pptx slides), so a zip
 * bomb of many in-cap entries cannot add up to gigabytes. [onCut] runs once, when an entry is wanted past the budget.
 */
class ZipBudget(private var left: Int = 4 * TEXT_BYTES, private val onCut: () -> Unit) {
    /**
     * The text of the next entry, read through [read] with the bytes still left less [reserve] (kept for a later
     * entry that must fit); null once nothing is left to read with.
     */
    fun entry(reserve: Int = 0, read: (cap: Int) -> String): String? {
        if (left - reserve <= 0) {
            onCut()
            return null
        }
        return read(left - reserve).also { left -= it.encodeToByteArray().size }
    }
}

/** Locator of the [cutShort] marker; it has no parts, so draftChunks never stores it. */
const val CUT_SHORT = "\u0000cut-short"

/** Appended by an extractor that stopped at one of the caps above, so the file is recorded as read in part. */
fun cutShort() = Piece(CUT_SHORT, emptyList(), null)

private val TAG = Regex("<[^>]+>")
private val BLANKS = Regex("[ \t]+")
private val SLIDE = Regex("ppt/slides/slide(\\d+)\\.xml")
private val SCRIPT_STYLE = Regex("<(script|style)\\b[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
private val XML_SPACE = Regex("<(w:tab|w:br|a:br|text:tab|text:line-break|text:s)\\b[^>]*>")
private val NUM_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
private val HTML_ENTITIES = mapOf(
    "&nbsp;" to " ", "&copy;" to "©", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
    "&lsquo;" to "‘", "&rsquo;" to "’", "&ldquo;" to "“", "&rdquo;" to "”",
)
private val HTML_BREAK = Regex("</(p|div|li|tr|td|section|blockquote|h[1-6])\\s*>|<(br|hr)\\s*/?>", RegexOption.IGNORE_CASE)

/** 800 characters at a 512-token window; the embedder's InputTooLong split stays the backstop. */
fun chunkChars(maxTokens: Int): Int = 800 * maxTokens / 512

/** OOXML (docx/pptx/xlsx) and ODF content.xml: paragraph ends become newlines, tags go, entities decode. */
fun stripXml(xml: String): String = xml
    .replace("</w:p>", "\n").replace("</a:p>", "\n").replace("</si>", "\n")
    .replace("</text:p>", "\n").replace("</text:h>", "\n")
    .replace(XML_SPACE) { if (it.groupValues[1].let { n -> n.endsWith("tab") || n == "text:s" }) " " else "\n" }
    .replace(TAG, "")
    .replace(NUM_ENTITY) {
        val d = it.groupValues[1]
        val cp = if (d[0] == 'x') d.drop(1).toIntOrNull(16) else d.toIntOrNull()
        when {
            cp == null || cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> ""
            cp <= 0xFFFF -> cp.toChar().toString()
            else -> (cp - 0x10000).let { c -> "${(0xD800 + (c shr 10)).toChar()}${(0xDC00 + (c and 0x3FF)).toChar()}" }
        }
    }
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
    .replace("&amp;", "&")
    .replace(BLANKS, " ")

fun stripHtml(html: String): String = stripXml(
    html.replace(SCRIPT_STYLE, "")
        .replace(HTML_BREAK, "\n")
        .let { h -> HTML_ENTITIES.entries.fold(h) { acc, (k, v) -> acc.replace(k, v) } },
)

private val XLSX_PART = Regex("xl/worksheets/sheet(\\d+)\\.xml")
private val SI = Regex("<si\\b[\\s\\S]*?</si>")
private val ROW = Regex("<row\\b[^>]*>([\\s\\S]*?)</row>")
private val CELL = Regex("<c\\b([^>]*?)(?:/>|>([\\s\\S]*?)</c>)")
private val CELL_TYPE = Regex("\\bt=\"(\\w+)\"")
private val CELL_V = Regex("<v>([\\s\\S]*?)</v>")
private val CELL_IS = Regex("<is>([\\s\\S]*?)</is>")

/** Order of the xlsx entries [xlsxText] reads: the shared strings first, then the sheets by number; null for the rest. */
fun xlsxOrder(entryName: String): Int? = if (entryName == "xl/sharedStrings.xml") 0 else XLSX_PART.matchEntire(entryName)?.groupValues?.get(1)?.toInt()?.plus(1)

/**
 * The text of an xlsx read as the raw XML of sharedStrings.xml followed by the sheets (see [xlsxOrder]): one line
 * per row, cells tab-separated; shared strings resolved, inline strings and numbers kept. With no sheet, the shared strings alone.
 */
fun xlsxText(raw: String): String {
    val at = raw.indexOf("<worksheet")
    if (at < 0) return stripXml(raw)
    val shared = SI.findAll(raw.substring(0, at)).map { stripXml(it.value).trim() }.toList()
    return ROW.findAll(raw.substring(at)).mapNotNull { row ->
        CELL.findAll(row.groupValues[1]).mapNotNull { cell ->
            val body = cell.groupValues[2]
            val v = CELL_V.find(body)?.groupValues?.get(1)?.let { stripXml(it).trim() }
            when (CELL_TYPE.find(cell.groupValues[1])?.groupValues?.get(1)) {
                "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                "inlineStr" -> CELL_IS.find(body)?.groupValues?.get(1)?.let { stripXml(it).trim() }
                else -> v
            }?.takeIf { it.isNotEmpty() }
        }.joinToString("\t").ifEmpty { null }
    }.joinToString("\n")
}

/** pptx slide number for ordering, null for every other entry. */
fun slideOrder(entryName: String): Int? = SLIDE.matchEntire(entryName)?.groupValues?.get(1)?.toInt()

// Windows-1252 0x80-0x9F; the rest of the code page is Latin-1, which maps 1:1 to Unicode.
private const val CP1252_HIGH = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ"

private val RTF_SKIPPED = setOf(
    "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
    "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
)

private val RTF_CHARS = mapOf(
    "par" to "\n", "line" to "\n", "sect" to "\n", "page" to "\n", "row" to "\n", "tab" to "\t", "cell" to "\t",
    "emdash" to "—", "endash" to "–", "bullet" to "•", "lquote" to "‘", "rquote" to "’", "ldblquote" to "“", "rdblquote" to "”",
)

/**
 * The text of an RTF document, after the RTF 1.9.1 specification: skipped and `{\*` destinations drop their
 * group, `\'hh` is Windows-1252, `\uN` drops the next `\ucN` fallback characters. A head cut mid-file
 * (unbalanced braces, a half control word) reads up to the cut.
 */
fun stripRtf(rtf: String): String {
    val out = StringBuilder()
    val groups = ArrayList<Pair<Boolean, Int>>() // the enclosing groups' (skip, uc)
    var skip = false
    var uc = 1
    var fallback = 0 // fallback characters still to drop after a \uN
    var i = 0
    fun text(s: String) {
        if (fallback > 0) {
            fallback--
        } else if (!skip) {
            out.append(s)
        }
    }
    while (i < rtf.length) {
        val c = rtf[i++]
        when (c) {
            '{' -> {
                groups += skip to uc
                fallback = 0
            }

            '}' -> {
                groups.removeLastOrNull()?.let { (s, u) ->
                    skip = s
                    uc = u
                }
                fallback = 0
            }

            '\r', '\n' -> {}

            '\\' -> {
                if (i >= rtf.length) break
                val next = rtf[i]
                if (!next.isAsciiLetter()) {
                    i++
                    when (next) {
                        '\'' -> {
                            val code = rtf.substring(i, minOf(i + 2, rtf.length)).toIntOrNull(16)
                            i += 2
                            if (code != null) text((if (code in 0x80..0x9F) CP1252_HIGH[code - 0x80] else code.toChar()).toString())
                        }

                        '*' -> skip = true

                        '~' -> text(" ")

                        '_' -> text("-")

                        '\\', '{', '}' -> text(next.toString())

                        '\r', '\n' -> {
                            fallback = 0
                            if (!skip) out.append('\n')
                        }

                        else -> fallback = 0 // \- optional hyphen, \| and the rest: nothing to show
                    }
                    continue
                }
                val start = i
                while (i < rtf.length && rtf[i].isAsciiLetter()) i++
                val word = rtf.substring(start, i)
                val numStart = i
                if (i < rtf.length && rtf[i] == '-') i++
                while (i < rtf.length && rtf[i].isDigit()) i++
                val param = rtf.substring(numStart, i).toIntOrNull()
                if (i < rtf.length && rtf[i] == ' ') i++
                when {
                    word == "u" && param != null -> {
                        fallback = 0
                        text((if (param < 0) param + 65536 else param).toChar().toString())
                        fallback = uc
                    }

                    word == "uc" && param != null -> uc = param

                    word == "bin" && param != null -> if (param > 0) i = minOf(i + param, rtf.length)

                    // a negative count would rewind the cursor for ever
                    word in RTF_SKIPPED -> skip = true

                    else -> {
                        fallback = 0
                        RTF_CHARS[word]?.let { if (!skip) out.append(it) }
                    }
                }
            }

            else -> text(c.toString())
        }
    }
    return out.toString()
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

// ponytail: Devanagari only; Unicode has no script-neutral signal for a conjunct split at its vowel sign, add a rule per script if another shows up.
private val SPLIT_MATRA = Regex("(^|\\s)[\\u0915-\\u0939\\u0958-\\u095F][\\u0941\\u0943]\\s+[\\u0900-\\u097F]")

private fun Char.isMissingGlyph() = code == 0 || code == 0xFFFD || code in 0xE000..0xF8FF

private fun Char.isMark() = category == CharCategory.NON_SPACING_MARK || category == CharCategory.COMBINING_SPACING_MARK

/**
 * True when a PDF text layer came out garbled, so the page is worth OCR: missing-glyph characters (NUL,
 * U+FFFD, private use), a combining mark with no base before it (line break, control character or start of
 * text), or a Devanagari conjunct split at its vowel sign ("कु मार" for "कुमार"), which is how Type 3 fonts
 * from Chrome's PDF export read back.
 */
fun looksBroken(text: String): Boolean {
    val missing = text.count { it.isMissingGlyph() }
    if (missing > 0 && missing * 100 >= text.length) return true
    if (text.indices.any {
            text[it].isMark() && (it == 0 || text[it - 1].isWhitespace() || text[it - 1].isISOControl() || text[it - 1].isMissingGlyph())
        }
    ) {
        return true
    }
    return SPLIT_MATRA.containsMatchIn(text)
}

/** After OCR of a page whose text layer was thin or broken: the longer clean one, else the longer of the two. */
fun betterPageText(layer: String, ocr: String): String = listOf(ocr, layer).maxWith(compareBy({ it.isNotBlank() && !looksBroken(it) }, { it.trim().length }))

enum class DocFormat { DOCX, PPTX, XLSX, ODF, EPUB, HTML, RTF, TEXT }

/** How a document's text is read, by extension or MIME type; null when it has no readable text. */
fun docFormat(name: String, mime: String): DocFormat? {
    val ext = name.substringAfterLast('.', "").lowercase()
    val m = mime.lowercase()
    return when {
        ext == "docx" || "wordprocessingml" in m -> DocFormat.DOCX
        ext == "pptx" || "presentationml" in m -> DocFormat.PPTX
        ext == "xlsx" || "spreadsheetml" in m -> DocFormat.XLSX
        ext in setOf("odt", "ods", "odp") || "opendocument" in m -> DocFormat.ODF
        ext == "epub" || m == "application/epub+zip" -> DocFormat.EPUB
        ext == "html" || ext == "htm" || m == "text/html" -> DocFormat.HTML
        ext == "rtf" || "rtf" in m -> DocFormat.RTF
        ext in FileRules.DOC_TEXT_EXTENSIONS || m.startsWith("text/") || m == "application/json" -> DocFormat.TEXT
        else -> null
    }
}

/** Zip entry holding a format's text, for the formats read from a single entry (xlsx reads several: [xlsxOrder]). */
val DocFormat.textEntry: String? get() = when (this) {
    DocFormat.DOCX -> "word/document.xml"
    DocFormat.ODF -> "content.xml"
    else -> null
}

/** OCR stays in text chunks, independently of the image's vector and classifier labels. */
fun imageWithText(locator: String, jpeg: ByteArray, ocr: String, maxChars: Int, labels: String? = null): List<Piece> =
    listOf(Piece(locator, listOf(Part.Image(jpeg)), null, labels = labels)) +
        Chunker.chunks(ocr, maxChars = maxChars).map { Piece(locator, listOf(Part.Text(it)), it, it) }

/**
 * A PDF's pieces: each page by its text layer ([text]), else rendered and read by OCR ([scanned], given the page
 * index and its layer text) up to [PDF_OCR_PAGES], then skipped and the file marked [cutShort]. A file known to have
 * no text layer ([hasTextLayer] false), or not known (null) with no text on any page so far, stops opening pages once
 * the OCR cap is spent: they have nothing left to give. A page that throws is left out.
 */
suspend fun pdfPieces(
    pageCount: Int,
    hasTextLayer: Boolean?,
    text: (Int) -> String,
    scanned: suspend (Int, String) -> List<Piece>,
    maxChars: Int,
): List<Piece> {
    val pieces = mutableListOf<Piece>()
    var ocrPages = 0
    var sawText = false
    var skipped = false
    for (i in 0 until minOf(pageCount, PDF_TEXT_PAGES)) {
        currentCoroutineContext().ensureActive()
        if (ocrPages >= PDF_OCR_PAGES && (hasTextLayer == false || (hasTextLayer == null && !sawText))) {
            skipped = true
            break
        }
        val locator = Locator.page(i + 1)
        try {
            val layer = text(i)
            val hasText = layer.trim().length >= MIN_TEXT_CHARS
            sawText = sawText || hasText
            pieces += when {
                hasText && !looksBroken(layer) -> textPieces(locator, layer, maxChars)

                ocrPages < PDF_OCR_PAGES -> {
                    ocrPages++
                    scanned(i, layer)
                }

                else -> {
                    skipped = true
                    if (hasText) textPieces(locator, layer, maxChars) else emptyList()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }
    if (pageCount > PDF_TEXT_PAGES || skipped) pieces += cutShort()
    return pieces
}

fun textPieces(locator: String, text: String, maxChars: Int): List<Piece> = if (text.trim().length < MIN_TEXT_CHARS) {
    emptyList()
} else {
    Chunker.chunks(text, maxChars = maxChars).map { Piece(locator, listOf(Part.Text(it)), it, it) }
}

/** Consecutive [seconds]-long windows as (start second, samples); a tail shorter than [minSeconds] is dropped. */
fun audioWindows(pcm: ShortArray, seconds: Int, minSeconds: Int = MIN_AUDIO_WINDOW_SEC): List<Pair<Int, ShortArray>> {
    val size = seconds * AUDIO_SAMPLE_RATE
    return (pcm.indices step size).mapIndexedNotNull { i, start ->
        val end = minOf(start + size, pcm.size)
        if (end - start >= minSeconds * AUDIO_SAMPLE_RATE) i * seconds to pcm.copyOfRange(start, end) else null
    }
}
