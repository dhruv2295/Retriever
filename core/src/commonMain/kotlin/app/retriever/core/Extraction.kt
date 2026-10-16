package app.retriever.core

/** Text rules shared by the Android and iOS extractors. */
const val PDF_PAGES = 30
const val TEXT_BYTES = 300 * 1024
const val MIN_TEXT_CHARS = 40
const val MIN_AUDIO_WINDOW_SEC = 2

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
