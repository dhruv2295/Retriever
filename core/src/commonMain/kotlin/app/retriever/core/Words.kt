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

    fun largeFiles(n: Long): String = "${files(n)} over 100${NBSP}MB"

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
     * read" over the readable ones while any are [scheduled] (pending also holds files whose content is already read),
     * otherwise nothing. Documents (PDF + doc) follows the same rule.
     */
    fun kindProgress(kinds: List<Kind>, inside: Set<Kind>, pending: Map<Kind, Long>, scheduled: Long): String? {
        val read = kinds.filter { it in inside && it != Kind.OTHER }
        if (read.isEmpty()) return "Name only"
        val left = read.sumOf { pending[it] ?: 0L }
        return if (scheduled > 0 && left > 0) waitingToRead(left) else null
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
    fun closestIn(files: Long): String = "Closest in these ${formatCount(files)} files"

    fun stillReading(files: Long): String = "Still reading ${formatCount(files)} more"

    /** The rest of the library, before anyone chose to read it. No hours: the estimate is not calibrated yet. */
    fun moreFiles(files: Long?): String =
        files?.let { "${formatCount(it)} more files can be read. You can already find them by name." } ?: "You can already find your files by name."

    fun textOnlyNote(ramGb: Double, words: DeviceWords): String =
        "This ${words.device} has ${ramGb.roundToInt()}${NBSP}GB of memory, too little to understand photos or meaning, so Retriever finds files by their names and the words inside them."

    fun waitingToRead(files: Long): String = "${formatCount(files)} waiting to read"

    // Shared actions.
    const val GO_TO_HOME = "Go to Home"
    const val CONTINUE = "Continue"
    const val TRY_AGAIN = "Try again"
    const val DOWNLOAD = "Download"
    const val NOT_NOW = "Not now"
    const val CANCEL = "Cancel"

    // Welcome. No download note: reading needs none.
    const val WELCOME_TITLE = "Find files by what’s inside"
    const val SEARCH_BY_NAME = "Search by name"

    fun welcomeBody(words: DeviceWords): String = "Remember what it says or shows, even if you’ve forgotten the name. Reading happens on this ${words.device}."

    // Access, asked on the welcome.
    const val CHOOSE_ACCESS = "Choose what Retriever can search"
    const val SEARCH_WHOLE_PHONE = "Search your whole phone"
    const val WHOLE_PHONE_READY = "Retriever can search your whole phone."
    const val ALL_FILES_OFF = "Turn on “Allow access to manage all files” to search your whole phone, or choose what to include."
    const val CHOOSE_INSTEAD = "Choose what to include instead"
    const val INCLUDE_TITLE = "Choose what to include"
    const val SEARCH_ALL_PHOTOS = "Search all your photos"
    const val ADD_FOLDERS_FROM_FILES = "Add folders from Files"
    const val SHARED_FILES_AND_FOLDERS = "Shared files and folders"
    const val PHOTOS_AND_VIDEOS = "Photos and videos"
    const val ADD_FOLDER = "Add a folder"
    const val SELECTED_PHOTOS = "Selected photos"
    const val ALL_PHOTOS = "All photos"
    const val CHOOSE_MORE_PHOTOS = "Choose more photos"
    const val ONLY_SELECTED_PHOTOS = "Only the photos you selected are included."
    const val NOTHING_SELECTED = "No files selected yet. Choose photos or a folder to begin."

    fun wholeDeviceNote(words: DeviceWords): String =
        "Retriever reads the files on this ${words.device} to find them by what’s inside. Nothing leaves your ${words.device}."

    fun iosAccessNote(words: DeviceWords): String =
        "iOS lets Retriever read your photos and the folders you pick in Files. Nothing leaves your ${words.device}."

    // The sample.
    const val SAMPLE_TITLE = "Try it on your own files"

    /** The sample's count line before its plan exists. */
    const val SAMPLE_PICKING = "Picking a few of your files…"
    const val READING_NOW = "Reading now"
    const val TRY_SEARCH = "Try search"

    /** A sample file whose content is all searchable. */
    const val READ = "Read"

    fun seeAll(files: Long): String = "See all ${files(files)}"

    /** "12 of 40 files read". */
    fun readOf(read: Long, planned: Long): String = "${formatCount(read)} of ${files(planned)} read"

    /**
     * The sample's muted line while it reads, null otherwise (paused or done), so no stale time stays on screen: the time
     * left once measured ([TasteProgress.secondsLeft]), then whether search can be tried yet ([TasteProgress.readyToTry]).
     */
    fun sampleNote(reading: Boolean, readyToTry: Boolean, secondsLeft: Int?): String? {
        if (!reading) return null
        val time = secondsLeft?.let {
            val minutes = (it / 60.0).roundToInt()
            when {
                it < 60 -> "Less than a minute left."
                minutes == 1 -> "About a minute left."
                else -> "About $minutes minutes left."
            }
        }
        return when {
            !readyToTry -> listOfNotNull(time, "You can try search once a few files are read.").joinToString(" ")
            time == null -> "You can try search now. Retriever keeps reading the rest."
            else -> "$time You can try search now."
        }
    }

    /** For a screen reader, never on screen: "12 of 40 photos read". */
    fun kindRead(read: Long, planned: Long, kind: Kind): String =
        "${formatCount(read)} of ${formatCount(planned)} ${kindLabelPlural(kind).let { if (kind == Kind.PDF) it else it.lowercase() }} read"

    // Try.
    const val TRY_TITLE = "Find a file you know"
    const val TRY_BODY = "Type something you remember from it, like a name, a place or an amount, or tap a suggestion."
    const val TRY_WITHOUT_SUGGESTIONS = "Open one of these files, then type something you remember from it."
    const val FILES_TO_TRY = "Files you can try"
    const val CHOOSE_WHEN = "Choose when to read more"

    /** Try's scope: names cover the whole library, words inside only the [read] files ([TasteProgress.read]). */
    fun searchingTry(read: Long): String = "Searching every file’s name, and inside the ${files(read)} read so far."

    /**
     * The notification ask. The sample's line only while an undecided [sample] still has files to read and is not ready
     * to try: once it is, its one message is already claimed, so the line would promise a message that never comes.
     */
    fun notificationsAsk(decided: Boolean, sample: TasteProgress): String = "See progress and pause a reading session." +
        if (!decided && !sample.readyToTry && sample.pending > 0) " We can also let you know when your sample is ready." else ""

    // Search.
    const val SEARCH_FIELD = "Search your files"
    const val NAME_SEARCH_HINT = "You can search every file by name"
    const val NAME_MATCHES = "Name matches"
    const val TEXT_MATCHES = "Text matches"
    const val RELATED = "Related to your search"
    const val WORDS_ONLY = "Words only"
    const val SEARCHING_CONTENTS = "Searching file contents…"
    const val NO_MATCHES = "No matches yet"
    const val NO_MATCHES_DETAIL = "Try another description or a file name."
    const val MORE_WAITING = "More files are waiting to be read."
    const val SEARCH_FAILED = "Search couldn’t finish"
    const val SHOWING_NAME_MATCHES = "Showing name matches"
    const val SEARCHING_WITHOUT_MODEL = "Searching names and text already read"

    /** Results this few, without the model, earn the one-line upgrade hint. */
    const val FEW_RESULTS = 3

    /** No model to search by meaning: a phone too small for it, or the optional download not in place and tuned. */
    fun wordsOnly(textOnly: Boolean, modelMissing: Boolean, setUp: Boolean): Boolean = textOnly || modelMissing || !setUp

    fun matches(labels: List<String>): String = "Matches: " + labels.joinToString(", ")

    fun searchHint(textOnly: Boolean): String =
        if (textOnly) "Try a file name or words from a document." else "Try a file name, words from a document, or something in a photo."

    /** The one-line hint under few results while the upgrade is on offer; null otherwise. */
    fun upgradeHint(results: Int, upgrade: UpgradeStage): String? =
        "Download for meaning search".takeIf { upgrade == UpgradeStage.OFFER && results < FEW_RESULTS }

    // Home and More on your phone.
    const val YOUR_FILES = "Your files"
    const val READING_STATUS = "Reading status"
    const val MORE_ON_PHONE = "More on your phone"
    const val MORE_ON_PHONE_BODY = "Files Retriever can access"
    const val ALL_FOLDERS = "All folders"

    /** Folder rows in More on your phone before All folders; the folder list has the rest. */
    const val MORE_FOLDERS = 3

    /** Home's documents section: PDFs and documents together only under a header that names both. */
    fun documentsTitle(pdfs: Long, docs: Long): String = when {
        pdfs > 0 && docs > 0 -> "Documents and PDFs"
        pdfs > 0 -> kindLabelPlural(Kind.PDF)
        else -> kindLabelPlural(Kind.DOC)
    }

    fun inLibrary(files: Long): String = "${files(files)} in your library"

    /** "120 files · 640 MB", "At least" when some sizes are unknown, "Size unavailable" when none is known. [knownSize]: the platform's format of [FileTotals.knownBytes]. */
    fun countAndSize(totals: FileTotals, knownSize: String): String = files(totals.files) + " · " + when {
        totals.unknownSizes == 0L -> keepTogether(knownSize)
        totals.unknownSizes < totals.files -> "At least ${keepTogether(knownSize)}"
        else -> "Size unavailable"
    }

    fun sizeUnavailableFor(files: Long): String = "Size unavailable for ${files(files)}"

    // The meaning upgrade.
    const val UPGRADE_TITLE = "Search by meaning and what’s in your photos"
    const val WAITING_FOR_WIFI = "Waiting for Wi-Fi to download"
    const val USE_MOBILE_DATA = "Use mobile data"
    const val WAIT_FOR_WIFI = "Wait for Wi-Fi"
    const val CHECKING_DOWNLOAD = "Checking the download…"
    const val DOWNLOAD_FAILED = "Download interrupted. Check your connection and try again."
    const val NO_SPACE = "Not enough free space to download"
    const val TUNE_LEAVING = "You can search names now. Reading setup resumes when you return."

    /** [size]: the platform's format of [Retriever.modelDownloadBytes]. */
    fun upgradeBody(size: String, words: DeviceWords): String = "One download of ${keepTogether(size)} on Wi-Fi. " +
        "Then a search like ‘sunset at the beach’ finds photos even without those words. Everything stays on this ${words.device}."

    fun downloadProgress(done: String, total: String, percent: Int): String = "${keepTogether(done)} of ${keepTogether(total)} · $percent%"

    fun mobileDataAsk(size: String): String = "Download ${keepTogether(size)} using mobile data?"

    fun freeUp(size: String): String = "Free up ${keepTogether(size)} to continue"

    fun tuneTitle(words: DeviceWords): String = "Setting up reading for this ${words.device}"

    fun tuneBody(words: DeviceWords): String = "About a minute. Finding a reading speed that suits this ${words.device}."

    fun tuneFailed(words: DeviceWords): String = "Couldn’t finish setting up reading for this ${words.device}."

    /** A download bar as a screen reader says it; [percent]: the platform's percent format. */
    fun downloading(percent: String): String = "Downloading, $percent"

    /** The meaning pass: files read so far that have search by meaning, of all files read. */
    fun addingMeaning(progress: ReadingProgress): String =
        "Adding search by meaning · ${formatCount(progress.contentRead - progress.meaningWaiting)} of ${files(progress.contentRead)}"
}
