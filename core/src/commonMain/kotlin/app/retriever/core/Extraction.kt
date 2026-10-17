package app.retriever.core

/** Text rules shared by the Android and iOS extractors. */
/** Pages read through their text layer: cheap, and the head-first embed keeps the model cost per file fixed. */
const val PDF_TEXT_PAGES = 1000

/** Pages with no text layer, each a render plus OCR (about a second apiece); later ones are skipped. */
const val PDF_OCR_PAGES = 30

/** Whole text and HTML files up to this; a zip entry (docx, pptx, epub chapter) gets 4x. */
const val TEXT_BYTES = 2 * 1024 * 1024
const val MIN_TEXT_CHARS = 40
const val MIN_AUDIO_WINDOW_SEC = 2

/** Locator of the [cutShort] marker; it has no parts, so draftChunks never stores it. */
const val CUT_SHORT = "\u0000cut-short"

/** Appended by an extractor that stopped at one of the caps above, so the file is recorded as read in part. */
fun cutShort() = Piece(CUT_SHORT, emptyList(), null)

private val TAG = Regex("<[^>]+>")
private val BLANKS = Regex("[ \t]+")
private val SLIDE = Regex("ppt/slides/slide(\\d+)\\.xml")
private val SCRIPT_STYLE = Regex("<(script|style)\\b[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
private val HTML_BREAK = Regex("</(p|div|li|tr|h[1-6])\\s*>|<br\\s*/?>", RegexOption.IGNORE_CASE)

/** 800 characters at a 512-token window; the embedder's InputTooLong split stays the backstop. */
fun chunkChars(maxTokens: Int): Int = 800 * maxTokens / 512

/** OOXML (docx/pptx/xlsx) and ODF content.xml: paragraph ends become newlines, tags go, entities decode. */
fun stripXml(xml: String): String = xml
    .replace("</w:p>", "\n").replace("</a:p>", "\n").replace("</si>", "\n")
    .replace("</text:p>", "\n").replace("</text:h>", "\n")
    .replace(TAG, "")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
    .replace("&amp;", "&")
    .replace(BLANKS, " ")

fun stripHtml(html: String): String = stripXml(
    html.replace(SCRIPT_STYLE, "")
        .replace(HTML_BREAK, "\n")
        .replace("&nbsp;", " ").replace("&#39;", "'"),
)

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
        if (fallback > 0) fallback-- else if (!skip) out.append(s)
    }
    while (i < rtf.length) {
        val c = rtf[i++]
        when (c) {
            '{' -> {
                groups += skip to uc
                fallback = 0
            }
            '}' -> {
                groups.removeLastOrNull()?.let { (s, u) -> skip = s; uc = u }
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
                        '\r', '\n' -> { fallback = 0; if (!skip) out.append('\n') }
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
                    word == "bin" && param != null -> i += param
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
    if (text.indices.any { text[it].isMark() && (it == 0 || text[it - 1].isWhitespace() || text[it - 1].isISOControl() || text[it - 1].isMissingGlyph()) }) return true
    return SPLIT_MATRA.containsMatchIn(text)
}

/** After OCR of a page whose text layer was thin or broken: the longer clean one, else the longer of the two. */
fun betterPageText(layer: String, ocr: String): String =
    listOf(ocr, layer).maxWith(compareBy({ it.isNotBlank() && !looksBroken(it) }, { it.trim().length }))

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

/** Zip entry holding a format's text, for the formats read from a single entry. */
val DocFormat.textEntry: String? get() = when (this) {
    DocFormat.DOCX -> "word/document.xml"
    DocFormat.XLSX -> "xl/sharedStrings.xml"
    DocFormat.ODF -> "content.xml"
    else -> null
}

/** Text chunks for a page or image; empty when there is too little text to be worth embedding. */
fun textPieces(locator: String, text: String, maxChars: Int): List<Piece> =
    if (text.trim().length < MIN_TEXT_CHARS) emptyList()
    else Chunker.chunks(text, maxChars = maxChars).map { Piece(locator, listOf(Part.Text(it)), it) }

/** Consecutive [seconds]-long windows as (start second, samples); a tail shorter than [minSeconds] is dropped. */
fun audioWindows(pcm: ShortArray, seconds: Int, minSeconds: Int = MIN_AUDIO_WINDOW_SEC): List<Pair<Int, ShortArray>> {
    val size = seconds * AUDIO_SAMPLE_RATE
    return (pcm.indices step size).mapIndexedNotNull { i, start ->
        val end = minOf(start + size, pcm.size)
        if (end - start >= minSeconds * AUDIO_SAMPLE_RATE) i * seconds to pcm.copyOfRange(start, end) else null
    }
}
