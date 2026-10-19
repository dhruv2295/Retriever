package app.retriever.core

import kotlin.math.roundToInt

/** The words that name the device and its settings; each app passes its own, so the shared sentences stay one table. */
data class DeviceWords(val device: String, val powerSave: String, val os: String) {
    companion object {
        val ANDROID = DeviceWords("phone", "Battery Saver", "Android")

        /** [device] is "iPhone" or "iPad". */
        fun ios(device: String) = DeviceWords(device, "Low Power Mode", "iOS")
    }
}

/** A count in the person's locale, grouped ("1,200"): the platform's number formatter. */
expect fun formatCount(n: Long): String

/** The sentences both apps show around numbers, dates and files. Sizes and dates stay with each platform's formatter. */
object Words {
    /** Keeps a number on the line of its unit. */
    const val NBSP = ' '

    /** The one date-label convention: a capture date is "Taken", anything else "Modified". */
    const val MODIFIED = "Modified"
    const val TAKEN = "Taken"

    /** A platform-formatted size ("200.5 kB") that never wraps between number and unit. */
    fun keepTogether(size: String): String = size.replace(' ', NBSP)

    fun plural(n: Long, one: String, many: String = "${one}s"): String = "${formatCount(n)} ${if (n == 1L) one else many}"

    fun files(n: Long): String = plural(n, "file")

    fun readCount(read: Long, partlyRead: Long = 0): String = "${files(read)} read" + if (partlyRead > 0) " · ${formatCount(partlyRead)} partly read" else ""

    /** Said under a file that was not read whole; null when it was. */
    fun extent(extent: ReadExtent?): String? = when (extent) {
        ReadExtent.FIRST_PART -> "Partly read · only the beginning is included"
        ReadExtent.MEANING_LIMITED -> "Partly read · couldn’t finish meaning search"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "Partly read · only the beginning is included; couldn’t finish meaning search"
        ReadExtent.FIRST_PART_DEEPENING -> "Partly read · only the beginning is included; more meaning search to come"
        ReadExtent.DEEPENING -> "Partly read · more meaning search to come"
        else -> null
    }

    /**
     * How much of a listed file is findable; null when all of it is. A kind outside [inside] (Retriever.readInside, which
     * never holds OTHER) is Name only, as the reading counts say.
     */
    fun coverage(file: Store.StoredFile, inside: Set<Kind>): String? = when {
        file.file.kind !in inside || (!file.contentRead && file.state == FileState.INDEXED) -> "Name only"
        !file.contentRead && file.state == FileState.SKIPPED -> "Couldn’t read"
        !file.contentRead -> "Waiting to read"
        file.state == FileState.PENDING -> "Partly read · waiting to read more"
        file.state == FileState.SKIPPED -> "Partly read · couldn’t finish reading"
        else -> extent(file.extent ?: if (file.partial) ReadExtent.FIRST_PART else null)
    }

    fun kindLabel(kind: Kind): String = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "Document"
        Kind.SCREENSHOT -> "Screenshot"
        Kind.PHOTO -> "Photo"
        Kind.AUDIO -> "Audio"
        Kind.VIDEO -> "Video"
        Kind.OTHER -> "File"
    }

    fun kindLabelPlural(kind: Kind): String = when (kind) {
        Kind.PDF -> "PDFs"
        Kind.DOC -> "Documents"
        Kind.SCREENSHOT -> "Screenshots"
        Kind.PHOTO -> "Photos"
        Kind.AUDIO -> "Audio"
        Kind.VIDEO -> "Videos"
        Kind.OTHER -> "Other files"
    }

    /**
     * A section of [kinds] under its title: "Name only" when none of them is read inside on this device, "{N} waiting to
     * read" over the readable ones while any wait, otherwise nothing. Documents (PDF + doc) follows the same rule.
     */
    fun kindProgress(kinds: List<Kind>, inside: Set<Kind>, pending: Map<Kind, Long>): String? {
        val read = kinds.filter { it in inside && it != Kind.OTHER }
        if (read.isEmpty()) return "Name only"
        val left = read.sumOf { pending[it] ?: 0L }
        return if (left > 0) "${formatCount(left)} waiting to read" else null
    }

    /** Where a match is, as a screen reader says it: "page 12", "at 1 minute 5 seconds"; null for no place. */
    fun spokenPosition(locator: String): String? {
        Locator.pageOf(locator)?.let { return "page $it" }
        val total = (Locator.msOf(locator) ?: return null) / 1000
        val parts = listOf(total / 3600 to "hour", total % 3600 / 60 to "minute", total % 60 to "second")
            .filter { it.first > 0 }
            .map { (n, unit) -> plural(n, unit) }
        return "at " + parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    /** The try-it results header: it names where the search looks, not how good the matches are. */
    fun closestIn(files: Long): String = "Searching inside ${formatCount(files)} sample files"

    const val STILL_READING = "Reading more sample files"

    /** The rest of the library, before anyone chose to read it. No hours: the estimate is not calibrated yet. */
    fun moreFiles(files: Long?): String =
        files?.let { "${formatCount(it)} more files can be read. You can already find them by name." } ?: "You can already find your files by name."

    fun textOnlyNote(ramGb: Double, words: DeviceWords): String =
        "This ${words.device} has ${ramGb.roundToInt()}${NBSP}GB of memory, too little to understand photos or meaning, so Retriever finds files by their names and the words inside them."
}
