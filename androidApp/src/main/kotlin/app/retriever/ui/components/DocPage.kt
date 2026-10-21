package app.retriever.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import app.retriever.core.FileRules
import app.retriever.core.SourceFile
import app.retriever.core.android.readCapped
import app.retriever.core.decodeText
import app.retriever.core.stripHtml
import app.retriever.core.stripRtf
import app.retriever.core.stripXml
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.RoundingMode
import java.util.zip.ZipInputStream

/** How a page lays out its rows: wrapped prose, unwrapped monospace lines, a sheet's cells, or a slide's title and lines. */
internal enum class Look { PROSE, CODE, GRID, SLIDE }

/** What a document's head gives its tile: the picture the file carries of itself, or its first rows laid out as a page. */
internal sealed interface Head {
    class Picture(val bytes: ByteArray) : Head

    /** One cell per row except [Look.GRID]; a slide's first row is its title. */
    class Page(val look: Look, val rows: List<List<String>>) : Head
}

/**
 * A document's tile drawn from its head, never from the index (which keeps flattened chunks): Office and OpenDocument
 * files' own thumbnail when they carry one, else a white page with the first lines, cells or slide, as Quick Look draws
 * one on iOS. Every read is capped and anything odd gives null, so the tile keeps its type card.
 */
internal object DocPage {
    /** Inflated bytes one zip pass may read in all: a big media folder ahead of the text ends the pass, not the phone's time. */
    private const val ZIP_BUDGET = 4 shl 20

    /** More for a workbook until its shared strings are read: tens of MB of sheets inflate in a few hundred ms. */
    private const val XLSX_ALLOWANCE = 28 shl 20
    private const val ZIP_ENTRIES = 1000
    private const val ENTRY_HEAD = 256 shl 10
    private const val PICTURE_BYTES = 1 shl 20
    private const val TEXT_HEAD = 16 shl 10
    private const val MAX_ROWS = 40
    private const val MAX_COLS = 8

    private val PICTURES = setOf("docProps/thumbnail.jpeg", "docProps/thumbnail.jpg", "docProps/thumbnail.png", "Thumbnails/thumbnail.png")
    private val TEXTS = setOf("word/document.xml", "ppt/slides/slide1.xml", "xl/worksheets/sheet1.xml", "xl/sharedStrings.xml", "content.xml")
    private val PROSE = setOf("txt", "md", "markdown", "rtf", "html", "htm")
    private val GRID = setOf("csv", "tsv")

    /**
     * Markup's prolog: the XML declaration and xml-* instructions, comments, DOCTYPE (with its subset), and the blanks between.
     * Only `<?xml` instructions, so a PHP file's `<?php … ?>` code stays.
     */
    private val PROLOG =
        Regex(
            """\A(?:[ \t]*\r?\n|<\?xml.*?\?>|<!--.*?-->|<!DOCTYPE(?:[^>\[]|\[.*?])*>)*""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

    /** A terminal colour or cursor sequence, or a lone escape. */
    private val ANSI = Regex("\u001B(\\[[0-9;?]*[A-Za-z])?")

    /** Zip formats: without the zip signature (encrypted, or not what the name says) they have no head to show. */
    private val CONTAINERS = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "epub")

    /** Called on [Thumbnails.load]'s IO dispatcher; null when the file gives nothing worth drawing. */
    fun render(context: Context, file: SourceFile, n: Int): Bitmap? =
        context.contentResolver.openInputStream(file.uri.toUri())?.use { read(it, file.name, file.mime) }?.let { draw(it, n) }

    fun read(input: InputStream, name: String, mime: String): Head? {
        val head = BufferedInputStream(input)
        head.mark(4)
        val magic = ByteArray(4).also { if (head.read(it) != 4) return null }
        head.reset()
        if (magic.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))) return zip(head)
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = mime.lowercase()
        val known = ext in FileRules.DOC_TEXT_EXTENSIONS || ext in PROSE || ext in GRID
        val text = ext !in CONTAINERS && (known || m.startsWith("text/") || m == "application/json")
        if (!text) return null
        val read = head.readCapped(TEXT_HEAD) {}
        // decodeText reads any byte as Windows-1252, so a binary file renamed .txt shows as control characters: no text instead.
        // Escape (a log's colours) and form feed are a text file's own; neither is drawn.
        if (read.isEmpty() || read.any { it.isISOControl() && it !in "\t\n\r\u000C\u001B" }) return null
        val raw = read.replace(ANSI, "").replace('\u000C', '\n')
        return when {
            ext in GRID || m == "text/csv" || m == "text/tab-separated-values" -> page(Look.GRID, delimited(raw, if (ext == "tsv" || "tab" in m) '\t' else ','))
            ext == "html" || ext == "htm" || m == "text/html" -> prose(stripHtml(htmlBody(raw)))
            ext == "rtf" || "rtf" in m -> prose(stripRtf(raw))
            ext in PROSE || ext.isEmpty() -> prose(raw)
            else -> page(Look.CODE, raw.removePrefix("\uFEFF").replace(PROLOG, "").lines().take(MAX_ROWS).map { listOf(it.replace("\t", "    ")) })
        }
    }

    /**
     * One pass over the zip, keeping the head of each entry worth having and stopping at an embedded thumbnail.
     * ponytail: streamed, so a thumbnail stored after the budget is missed and the drawn page shows, and a workbook whose
     * sheets inflate past the allowance before its shared strings shows blank labels; a seekable ZipFile over the file
     * descriptor if a cold-Try trace says it matters.
     */
    private fun zip(input: InputStream): Head? {
        val parts = mutableMapOf<String, String>()
        var left = ZIP_BUDGET
        // Excel stores the sheets before xl/sharedStrings.xml, and a stream reaches it only by inflating them in full.
        var stringsDue = false
        runCatching {
            ZipInputStream(input).use { zip ->
                val buf = ByteArray(8192)

                // Every byte of every entry is read here, kept or not: nextEntry would otherwise inflate the rest uncounted.
                // Until the shared strings are read, the entries ahead of them leave their head's worth of the budget.
                fun entry(keep: Int, floor: Int): ByteArray {
                    val out = ByteArrayOutputStream()
                    while (left > floor) {
                        val read = zip.read(buf, 0, minOf(buf.size, left - floor))
                        if (read < 0) break
                        left -= read
                        if (out.size() < keep) out.write(buf, 0, minOf(read, keep - out.size()))
                    }
                    return out.toByteArray()
                }
                repeat(ZIP_ENTRIES) {
                    val name = zip.nextEntry?.name ?: return@use
                    if (name.startsWith("xl/") && !stringsDue && "xl/sharedStrings.xml" !in parts) {
                        stringsDue = true
                        left += XLSX_ALLOWANCE
                    }
                    val floor = if (stringsDue && name != "xl/sharedStrings.xml") ENTRY_HEAD else 0
                    when (name) {
                        // A picture cut by a cap would not decode, nor would one with no readable header: only a whole,
                        // decodable one counts, else the pass goes on to the text.
                        in PICTURES -> entry(PICTURE_BYTES, floor)
                            .takeIf { left > floor && it.size < PICTURE_BYTES && bounds(it) != null }
                            ?.let { return Head.Picture(it) }

                        in TEXTS -> parts[name] = decodeText(entry(ENTRY_HEAD, floor))

                        else -> entry(0, floor)
                    }
                    if (name == "xl/sharedStrings.xml") stringsDue = false
                    if (left <= floor) return@use
                }
            }
        }
        parts["word/document.xml"]?.let { return prose(docx(it)) }
        parts["ppt/slides/slide1.xml"]?.let { return page(Look.SLIDE, lines(stripXml(cutTag(it)))) }
        parts["xl/worksheets/sheet1.xml"]?.let { return page(Look.GRID, xlsx(it, parts["xl/sharedStrings.xml"].orEmpty())) }
        val content = parts["content.xml"]?.let(::cutTag) ?: return null
        return when {
            "<office:spreadsheet" in content -> page(Look.GRID, ods(from(content, "<office:spreadsheet")))
            "<office:presentation" in content -> page(Look.SLIDE, lines(stripXml(odp(content))))
            else -> prose(stripXml(from(content, "<office:body")))
        }
    }

    /** [text] from [tag] on, the tag kept whole so stripping removes it; all of it without one. */
    private fun from(text: String, tag: String) = text.indexOf(tag, ignoreCase = true).let { if (it < 0) text else text.substring(it) }

    private fun page(look: Look, rows: List<List<String>>): Head.Page? = rows.takeIf { r ->
        r.any { row -> row.any { it.isNotBlank() } }
    }?.let { Head.Page(look, it) }

    private fun prose(text: String) = page(Look.PROSE, lines(text))

    private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_ROWS).map { listOf(it) }

    /** A head cut inside a tag would show the tag's half: drop it. */
    private fun cutTag(xml: String) = if (xml.lastIndexOf('<') > xml.lastIndexOf('>')) xml.substring(0, xml.lastIndexOf('<')) else xml

    private val FIELD_CODE = Regex("<w:(instrText|delText)\\b[^>]*>[\\s\\S]*?</w:\\1>")

    /** Field codes (TOC, PAGE) and deleted revisions are markup's text, not the page's. */
    private fun docx(xml: String) = stripXml(from(cutTag(xml), "<w:body").replace(FIELD_CODE, ""))

    private val OPEN_RAW = Regex("<(style|script)\\b(?![\\s\\S]*</\\1\\s*>)[\\s\\S]*", RegexOption.IGNORE_CASE)

    /** The body, without a style or script block the cap cut open (its CSS would read as prose). */
    private fun htmlBody(html: String) = cutTag(from(html, "<body").replace(OPEN_RAW, ""))

    private val SI = Regex("<si\\b[\\s\\S]*?</si>")
    private val ROW = Regex("<row\\b[^>]*?(?:/>|>([\\s\\S]*?)</row>)")
    private val CELL = Regex("<c\\b([^>]*?)(?:/>|>([\\s\\S]*?)</c>)")
    private val REF = Regex("\\br=\"([A-Z]+)\\d+\"")
    private val TYPE = Regex("\\bt=\"(\\w+)\"")
    private val V = Regex("<v>([\\s\\S]*?)</v>")
    private val IS = Regex("<is>([\\s\\S]*?)</is>")

    /** Cells at their own columns (core's xlsxText drops empty ones, which would shift a sparse row left). */
    private fun xlsx(sheet: String, strings: String): List<List<String>> {
        val shared = SI.findAll(strings).map { stripXml(it.value).trim() }.toList()
        return grid(
            ROW.findAll(sheet).take(MAX_ROWS).map { row ->
                var next = 0
                CELL.findAll(row.groupValues[1]).mapNotNull { cell ->
                    val col = REF.find(cell.groupValues[1])?.groupValues?.get(1)?.fold(0) { a, c -> a * 26 + (c - 'A' + 1) }?.minus(1) ?: next
                    next = col + 1
                    if (col >= MAX_COLS) return@mapNotNull null
                    val v = V.find(cell.groupValues[2])?.groupValues?.get(1)?.let { stripXml(it).trim() }
                    col to when (TYPE.find(cell.groupValues[1])?.groupValues?.get(1)) {
                        "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                        "inlineStr" -> IS.find(cell.groupValues[2])?.groupValues?.get(1)?.let { stripXml(it).trim() }
                        "str", "b", "e" -> v
                        else -> v?.let(::number)
                    }.orEmpty()
                }.toMap()
            }.toList(),
        )
    }

    /** A stored double as a sheet shows it: 0.30000000000000004 as 0.3. */
    private fun number(v: String) =
        v.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)?.let { if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString() } ?: v

    private val ODS_ROW = Regex("<table:table-row\\b([^>]*?)(?:/>|>([\\s\\S]*?)</table:table-row>)")
    private val ODS_CELL = Regex("<table:(?:covered-)?table-cell\\b([^>]*?)(?:/>|>([\\s\\S]*?)</table:(?:covered-)?table-cell>)")
    private val REPEAT = Regex("number-(?:columns|rows)-repeated=\"(\\d+)\"")

    private fun ods(body: String): List<List<String>> {
        val rows = mutableListOf<Map<Int, String>>()
        for (row in ODS_ROW.findAll(body.substringBefore("</table:table>"))) {
            var col = 0
            val cells = mutableMapOf<Int, String>()
            for (cell in ODS_CELL.findAll(row.groupValues[2])) {
                val text = stripXml(cell.groupValues[2]).trim()
                repeat(repeats(cell.groupValues[1], MAX_COLS)) { if (col < MAX_COLS) cells[col++] = text }
            }
            repeat(repeats(row.groupValues[1], MAX_ROWS)) { rows += cells }
            if (rows.size >= MAX_ROWS) break
        }
        return grid(rows.take(MAX_ROWS))
    }

    private fun repeats(attrs: String, cap: Int) = (REPEAT.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1).coerceIn(1, cap)

    private val NOTES = Regex("<presentation:notes\\b[\\s\\S]*?</presentation:notes>")

    /** The first slide, without its speaker notes. */
    private fun odp(content: String) = from(from(content, "<office:presentation"), "<draw:page").substringBefore("</draw:page>").replace(NOTES, "")

    /** Rows of column → text as a rectangle, trailing empty rows and columns dropped. */
    private fun grid(rows: List<Map<Int, String>>): List<List<String>> {
        val width = rows.maxOfOrNull { r -> r.filterValues { it.isNotEmpty() }.keys.maxOrNull()?.plus(1) ?: 0 } ?: 0
        return rows.map { r -> List(width) { r[it].orEmpty() } }.dropLastWhile { r -> r.all { it.isEmpty() } }
    }

    /** CSV or TSV rows, quotes honoured; a quoted line break ends the row (a tile has no room for it anyway). */
    private fun delimited(text: String, sep: Char): List<List<String>> = text.lines().filter { it.isNotBlank() }.take(MAX_ROWS).map { line ->
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i++]
            when {
                c == '"' && quoted && line.getOrNull(i) == '"' -> cell.append(c).also { i++ }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> cells += cell.toString().trim().also { cell.clear() }
                else -> cell.append(c)
            }
        }
        (cells + cell.toString().trim()).take(MAX_COLS)
    }

    // --- Drawing ---

    private val INK = 0xFF202124.toInt()
    private const val RULE = 0x33000000
    private const val EDGE = 0x29000000

    /**
     * An n x n bitmap, transparent around a white page (white in both themes, like paper), so the tile's own colour
     * shows around it. The page stays within the middle 74% of the width: Home's 3:4 card crops a square's sides.
     */
    fun draw(head: Head, n: Int): Bitmap? {
        val picture = (head as? Head.Picture)?.let { picture(it.bytes, n) ?: return null }
        val look = (head as? Head.Page)?.look
        val aspect = when {
            picture != null -> (picture.width.toFloat() / picture.height).coerceIn(0.5f, 2f)
            look == Look.SLIDE -> 16f / 9
            else -> 1 / 1.414f
        }
        val w = minOf(n * 0.74f, n * 0.9f * aspect)
        val h = w / aspect
        val page = RectF((n - w) / 2, (n - h) / 2, (n + w) / 2, (n + h) / 2)
        val bitmap = createBitmap(n, n)
        val canvas = Canvas(bitmap)
        canvas.drawRect(page, Paint().apply { color = android.graphics.Color.WHITE })
        if (picture != null) {
            canvas.drawBitmap(picture, null, page, Paint(Paint.FILTER_BITMAP_FLAG))
            picture.recycle()
        } else {
            canvas.withClip(page) { text(this, head as Head.Page, page, n) }
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = EDGE
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, n / 256f)
        }
        canvas.drawRect(page, edge)
        return bitmap
    }

    /** The picture's size from its header alone; null when it has none an image decoder reads. */
    private fun bounds(bytes: ByteArray): BitmapFactory.Options? = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
        .takeIf { it.outWidth > 0 && it.outHeight > 0 }

    private fun picture(bytes: ByteArray, n: Int): Bitmap? {
        val bounds = bounds(bytes) ?: return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= n) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Fewer, larger lines on a smaller tile: about 9 lines on a 256 px page and 13 on 512, so the first words read at
     * Try's grid and the page reads as a page on the pile.
     */
    private fun text(canvas: Canvas, head: Head.Page, page: RectF, n: Int) {
        val lines = 5 + n / 64
        val margin = page.width() * 0.09f
        val size = page.height() / (lines * 1.35f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = size
            typeface = if (head.look == Look.CODE) Typeface.MONOSPACE else Typeface.SANS_SERIF
        }
        val left = page.left + margin
        val width = (page.width() - 2 * margin).toInt().coerceAtLeast(1)
        val room = page.height() - 2 * margin
        when (head.look) {
            Look.PROSE -> canvas.withTranslation(left, page.top + margin) { layout(head.rows.joinToString("\n") { it.first() }, paint, width, room).draw(this) }

            // Code keeps its lines whole: cut at the page's edge, never wrapped.
            Look.CODE -> canvas.withClip(left, page.top + margin, left + width, page.top + margin + room) {
                val step = paint.fontSpacing
                head.rows.take((room / step).toInt()).forEachIndexed { i, row ->
                    drawText(row.first(), left, page.top + margin - paint.ascent() + i * step, paint)
                }
            }

            Look.SLIDE -> {
                val pad = page.height() * 0.1f
                val title = TextPaint(paint).apply {
                    textSize = page.height() * 0.16f
                    typeface = Typeface.DEFAULT_BOLD
                }
                val inner = (page.width() - 2 * pad).toInt().coerceAtLeast(1)
                val top = layout(head.rows.first().first(), title, inner, page.height() * 0.3f)
                val body = layout(
                    head.rows.drop(1).joinToString("\n") { it.first() },
                    TextPaint(paint).apply { textSize = page.height() * 0.09f },
                    inner,
                    page.height() - 2 * pad - top.height - pad / 2,
                )
                canvas.withTranslation(page.left + pad, page.top + pad) {
                    top.draw(this)
                    translate(0f, top.height + pad / 2)
                    body.draw(this)
                }
            }

            Look.GRID -> grid(canvas, head.rows, page, paint.apply { textSize = size * 0.8f }, n)
        }
    }

    /** As many whole lines of [text] as fit [height], the last ellipsized. */
    private fun layout(text: String, paint: TextPaint, width: Int, height: Float): StaticLayout {
        val one = paint.fontMetrics.let { it.descent - it.ascent }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setMaxLines((height / one).toInt().coerceAtLeast(1))
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
    }

    /** Equal columns with ruled cells, like a sheet's gridlines, as many as fit at a readable size. */
    private fun grid(canvas: Canvas, rows: List<List<String>>, page: RectF, paint: TextPaint, n: Int) {
        val cols = minOf(rows.maxOf { it.size }, 2 + n / 192).coerceAtLeast(1)
        val rowH = paint.textSize * 1.6f
        val colW = page.width() / cols
        val rule = Paint().apply {
            color = RULE
            strokeWidth = maxOf(1f, n / 384f)
        }
        val pad = paint.textSize * 0.3f
        val shown = minOf(rows.size, (page.height() / rowH).toInt())
        for (r in 0..shown) canvas.drawLine(page.left, page.top + r * rowH, page.right, page.top + r * rowH, rule)
        for (c in 1 until cols) canvas.drawLine(page.left + c * colW, page.top, page.left + c * colW, page.top + shown * rowH, rule)
        val baseline = (rowH - paint.fontMetrics.let { it.descent + it.ascent }) / 2
        for (r in 0 until shown) {
            for (c in 0 until cols) {
                val cell = rows[r].getOrNull(c).orEmpty()
                if (cell.isEmpty()) continue
                val fits = paint.breakText(cell, true, colW - 2 * pad, null)
                canvas.drawText(cell, 0, fits, page.left + c * colW + pad, page.top + r * rowH + baseline, paint)
            }
        }
    }
}
