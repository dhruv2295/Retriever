package app.retriever.core

import kotlin.math.roundToInt

/** The words that name the device and its settings; each app passes its own, so the shared sentences stay one table. */
data class DeviceWords(val device: String, val powerSave: String, val os: String) {
    /** iOS reads only while Retriever is open (or the system grants a background turn), and opens Settings for access. */
    val ios: Boolean get() = os == "iOS"

    companion object {
        val ANDROID = DeviceWords("phone", "Battery Saver", "Android")

        /** [device] is "iPhone" or "iPad". */
        fun ios(device: String) = DeviceWords(device, "Low Power Mode", "iOS")
    }
}

/** A count in the person's locale, grouped ("1,200"): the platform's number formatter. */
expect fun formatCount(n: Long): String

/**
 * The sentences both apps show around numbers, dates and files, in plain words a first-time user knows: "your phone" or
 * "your files", never "library"; "found by name", never "name only"; Retriever "processes" files, never reads, analyzes,
 * indexes, learns or scans them; "processed in part", never "partly read"; "15 minutes", never "session"; nothing about
 * a "schedule". Sizes and dates stay with each platform's formatter.
 */
object Words {
    /** Keeps a number on the line of its unit. */
    const val NBSP = '\u00A0'

    /** The one date-label convention: a capture date is "Taken", anything else "Modified". */
    const val MODIFIED = "Modified"
    const val TAKEN = "Taken"

    /** A platform-formatted size ("200.5 kB") that never wraps between number and unit. */
    fun keepTogether(size: String): String = size.replace(' ', NBSP)

    fun plural(n: Long, one: String, many: String = "${one}s"): String = "${formatCount(n)} ${if (n == 1L) one else many}"

    fun files(n: Long): String = plural(n, "file")

    fun largeFiles(n: Long): String = "${files(n)} over 100${NBSP}MB"

    /** Processing's counts; a file processed in part counts as processed on Home. */
    fun readCount(read: Long, partlyRead: Long = 0): String =
        "${files(read)} processed" + if (partlyRead > 0) " · ${formatCount(partlyRead)} processed in part" else ""

    /** Said under a file that was not read whole; null when it was. */
    fun extent(extent: ReadExtent?): String? = when (extent) {
        ReadExtent.FIRST_PART -> "Processed in part · only its beginning"
        ReadExtent.MEANING_LIMITED -> "Processed in part · couldn’t finish search by meaning"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "Processed in part · only its beginning, without search by meaning"
        ReadExtent.FIRST_PART_DEEPENING -> "Processed in part · only its beginning so far"
        ReadExtent.DEEPENING -> "Processed in part · search by meaning still to come"
        else -> null
    }

    /**
     * How a listed file can be found; null when by everything in it. A kind outside [inside] (Retriever.readInside, which
     * never holds OTHER) is found by name, as the reading counts say.
     */
    fun coverage(file: Store.StoredFile, inside: Set<Kind>): String? = when {
        file.file.kind !in inside || (!file.contentRead && file.state == FileState.INDEXED) -> FOUND_BY_NAME
        !file.contentRead && file.state == FileState.SKIPPED -> "Couldn’t process"
        !file.contentRead -> "Not processed yet"
        file.state == FileState.PENDING -> "Processed in part · the rest is still to come"
        file.state == FileState.SKIPPED -> "Processed in part · couldn’t finish"
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
     * Where [kinds] stand, for Reading status and Settings, never Home: [FOUND_BY_NAME] when none of them is read inside
     * on this device, "{N} to go" over the readable ones while any are [scheduled] (pending also holds files whose
     * content is already read), otherwise nothing. Documents (PDF + doc) follows the same rule.
     */
    fun kindProgress(kinds: List<Kind>, inside: Set<Kind>, pending: Map<Kind, Long>, scheduled: Long): String? {
        val read = kinds.filter { it in inside && it != Kind.OTHER }
        if (read.isEmpty()) return FOUND_BY_NAME
        val left = read.sumOf { pending[it] ?: 0L }
        return if (scheduled > 0 && left > 0) notReadYet(left) else null
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

    fun stillReading(files: Long): String = "Still processing ${formatCount(files)} more"

    fun textOnlyNote(ramGb: Double, words: DeviceWords): String =
        "This ${words.device} has ${ramGb.roundToInt()}${NBSP}GB of memory, too little to understand photos or meaning, so Retriever finds files by their names and the words inside them."

    /** Processing only; never on Home. */
    fun notReadYet(files: Long): String = "${formatCount(files)} to go"

    fun couldntProcess(files: Long): String = "Couldn’t process ${files(files)}"

    // The Processing screen's own labels, the same on both apps.
    const val DETAILS = "Details"
    const val STARTING = "Getting started…"
    const val PROCESSING_NOW = "Processing now"
    const val RECENTLY_PROCESSED = "Recently processed"
    const val PROCESSING_YOUR_FILES = "Processing your files"

    /** Settings' header over the per-kind switches. */
    const val PROCESS_THESE_KINDS = "Process these file types"

    // Shared actions.

    /** Setup's way out before it ends (Try, Choose): Home, with the sample still reading. */
    const val GO_TO_HOME = "Skip for now"
    const val CONTINUE = "Continue"
    const val TRY_AGAIN = "Try again"
    const val DOWNLOAD = "Download"
    const val NOT_NOW = "Not now"
    const val CANCEL = "Cancel"
    const val DONE = "Done"
    const val READ_FOR_15 = "Process for 15 minutes"

    /** Marks the search-by-meaning row at the end of Home. */
    const val OPTIONAL = "Optional"

    // Welcome. No download note: reading needs none.
    const val WELCOME_TITLE = "Find files by what’s inside"

    /** The welcome's way past the sample: it skips the try, not the search. */
    const val SEARCH_BY_NAME = "Skip the try"

    /** One line above the welcome's buttons: what follows the access choice. */
    const val NEXT_TRY = "Next, a one-minute try: Retriever processes a few of your recent files so you can search them right away."

    /** The welcome's primary button once access is in place. */
    const val START_THE_TRY = "Start the one-minute try"
    const val WELCOME_BODY = "Remember what it says or shows, even if you’ve forgotten the name."

    /** The welcome's privacy promise, on its own line and larger than the body. */
    fun nothingSent(words: DeviceWords): String = "Nothing leaves your ${words.device}. No account, nothing sent anywhere."

    // Access, asked on the welcome.
    const val SEARCH_WHOLE_PHONE = "Search your whole phone"
    const val WHOLE_PHONE_READY = "Retriever can look through the files on this phone. You can change this in Settings."
    const val ALL_FILES_OFF = "Turn on “Allow access to manage all files” to search your whole phone, or choose what to include."
    const val CHOOSE_INSTEAD = "Choose what to include instead"
    const val INCLUDE_TITLE = "Choose what to include"
    const val SEARCH_ALL_PHOTOS = "Search all your photos"
    const val ADD_FOLDERS_FROM_FILES = "Add folders from Files"

    /** Android's whole-phone access, as Reading status names it (iOS names its photos and folders). */
    const val SHARED_FILES_AND_FOLDERS = "Files and folders on this phone"
    const val PHOTOS_AND_VIDEOS = "Photos and videos"
    const val ADD_FOLDER = "Add a folder"
    const val SELECTED_PHOTOS = "Selected photos"
    const val ALL_PHOTOS = "All photos"
    const val CHOOSE_MORE_PHOTOS = "Choose more photos"
    const val ONLY_SELECTED_PHOTOS = "Only the photos you selected are included."
    const val NOTHING_SELECTED = "No files selected yet. Choose photos or a folder to begin."

    fun wholeDeviceNote(words: DeviceWords): String = "Retriever looks inside the files on this ${words.device} so you can find them by what’s in them."

    fun iosAccessNote(words: DeviceWords): String = "iOS lets Retriever see your photos and the folders you pick in Files."

    // The sample.
    const val SAMPLE_TITLE = "Try it on your own files"

    /** The sample's count line before its plan exists. */
    const val SAMPLE_PICKING = "Trying a few of your recent files to show you how it works. About a minute. Nothing is changed or sent."

    /** The sample's muted line once it has read everything it planned. */
    const val SAMPLE_DONE = "All done. Now try finding something."
    const val TRY_SEARCH = "Try search"

    /** A sample file whose content is all searchable. */
    const val READ = "Found by what’s inside"
    const val FOUND_BY_NAME = "Found by name"

    /** File info's row for [readingLabel]. */
    const val FOUND_BY = "Found by"

    /** No total while [pending] files are left, since the plan can still shrink (124 must never become 86 on screen). */
    fun seeAll(files: Long, pending: Long): String = if (pending > 0) "See the files" else "See all ${files(files)}"

    /**
     * The sample's count: "79 files processed" while [pending] files are left, since the plan can still shrink and "of 124"
     * would become "of 86"; "84 of 86 files processed" once it stops changing.
     */
    fun readOf(read: Long, planned: Long, pending: Long = 0): String =
        if (pending > 0) readCount(read) else "${formatCount(read)} of ${files(planned)} processed"

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
            !readyToTry -> listOfNotNull(time, "You can try search once a few files are processed.").joinToString(" ")
            time == null -> "Ready to try. Search for a word you remember from a recent file."
            else -> "$time Ready to try."
        }
    }

    /** For a screen reader, never on screen: "12 of 40 photos processed". */
    fun kindRead(read: Long, planned: Long, kind: Kind): String =
        "${formatCount(read)} of ${formatCount(planned)} ${kindLabelPlural(kind).let { if (kind == Kind.PDF) it else it.lowercase() }} processed"

    // Try.
    const val TRY_TITLE = "Find a file you know"
    const val TRY_BODY = "Type something you remember from it, like a name, a place or an amount, or tap a suggestion."
    const val TRY_WITHOUT_SUGGESTIONS = "Open one of these files, then type something you remember from it."
    const val FILES_TO_TRY = "Files you can try"
    const val CHOOSE_WHEN = "Choose when to process"

    /** Labels words from the person's own files wherever they are offered (Home's search suggestions with no history). */
    const val TRY_LABEL = "Try:"

    /** Try's scope: names cover the whole library, words inside only the [read] files ([TasteProgress.read]). */
    fun searchingTry(read: Long): String = "Searching every file’s name, and inside the ${files(read)} processed so far."

    // Choose when to process, after Try and from Home's status line.
    const val CHOOSE_TITLE = "When should Retriever process your files?"
    const val MORE_FILES = "You can already find every file by its name. To find them by what’s inside, Retriever needs to process them."

    /** No hours or nights: the estimate is not calibrated yet ([ESTIMATE_CALIBRATED]). */
    const val CHOOSE_PACE = "It processes a bit each time, so it can take a while. You can use Retriever the whole time."
    const val READ_15_NOW = "Process for 15 minutes now"
    const val NOT_NOW_BODY = "You can choose later in Settings."

    fun whileChargingBody(words: DeviceWords): String =
        "Best choice. Only while your ${words.device} is charging and you’re not using it. You can pause any time."

    fun readNowBody(words: DeviceWords): String = "Uses some battery. Stops early if your ${words.device} gets warm. Then carries on while charging." +
        if (words.ios) " Keep Retriever open." else ""

    /**
     * The notification ask's body (Android). The sample's line only while an undecided [sample] still has files to read
     * and is not ready to try: once it is, its one message is already claimed, so the line would promise a message that
     * never comes.
     */
    fun notificationsAsk(decided: Boolean, sample: TasteProgress): String =
        "One small notice at the top of your screen while it works, so you can see how far it’s got or pause it. No sounds, no other alerts." +
            if (!decided && !sample.readyToTry && sample.pending > 0) " We can also let you know when your sample is ready." else ""

    // Search.
    const val SEARCH_FIELD = "What are you looking for?"
    const val NAME_MATCHES = "Name matches"
    const val TEXT_MATCHES = "Text matches"
    const val RELATED = "Related to your search"
    const val SEARCHING_CONTENTS = "Searching file contents…"
    const val NO_MATCHES = "Nothing found"
    const val NO_MATCHES_DETAIL = "Try a word from the file’s name."

    /** Said after [NO_MATCHES_DETAIL] only while files are still to be processed. */
    const val MORE_WAITING = "Retriever is still processing your files, so search again later."
    const val SEARCH_FAILED = "Search couldn’t finish"
    const val SHOWING_NAME_MATCHES = "Showing name matches"
    const val SEARCHING_WITHOUT_MODEL = "Searching names and text already processed"

    /** Results this few, without the model, earn the one-line upgrade hint. */
    const val FEW_RESULTS = 3

    /** No model to search by meaning: a phone too small for it, or the optional download not in place and tuned. */
    fun wordsOnly(textOnly: Boolean, modelMissing: Boolean, setUp: Boolean): Boolean = textOnly || modelMissing || !setUp

    fun matches(labels: List<String>): String = "Matches: " + labels.joinToString(", ")

    /** The one-line hint under few results while the upgrade is on offer; null otherwise. */
    fun upgradeHint(results: Int, upgrade: UpgradeStage): String? =
        "Download for meaning search".takeIf { upgrade == UpgradeStage.OFFER && results < FEW_RESULTS }

    // Home, in order under the status line: Starred, Recently added, On this day, Browse.

    /** Processing's screen title; on Home the status line itself is the way there. */
    const val READING_STATUS = "Processing"
    const val RECENTLY_ADDED = "Recently added"
    const val MORE_ON_PHONE = "Browse"
    const val ALL_FOLDERS = "All folders"

    /** Folder rows in More on your phone before All folders; the folder list has the rest. */
    const val MORE_FOLDERS = 3

    /** Home's documents section: PDFs and documents together only under a header that names both. */
    fun documentsTitle(pdfs: Long, docs: Long): String = when {
        pdfs > 0 && docs > 0 -> "Documents and PDFs"
        pdfs > 0 -> kindLabelPlural(Kind.PDF)
        else -> kindLabelPlural(Kind.DOC)
    }

    /** "120 files · 640 MB", "At least" when some sizes are unknown, "Size unavailable" when none is known. [knownSize]: the platform's format of [FileTotals.knownBytes]. */
    fun countAndSize(totals: FileTotals, knownSize: String): String = files(totals.files) + " · " + when {
        totals.unknownSizes == 0L -> keepTogether(knownSize)
        totals.unknownSizes < totals.files -> "At least ${keepTogether(knownSize)}"
        else -> "Size unavailable"
    }

    // Settings.
    const val HOW_IT_WORKS = "How Retriever works and your privacy"
    const val WHEN_TO_READ = "When to process"
    const val WHAT_IT_CAN_SEE = "What Retriever can see"
    const val FOLDERS_TO_SKIP = "Folders to skip"
    const val KIND_OFF = "Turned off: Retriever still finds these by name, but forgets what’s inside them. Your files aren’t touched."
    const val SHOW_ON_THIS_DAY = "Show On this day (photos from this date in past years)"

    /** Settings → Storage, where large files and duplicates live (never Home). */
    const val FREE_UP_SPACE = "Free up space"
    const val FIND_DUPLICATES = "Find duplicate files"
    const val NEVER_DELETES = "Retriever never deletes anything without asking you first."

    fun whatItHasRead(words: DeviceWords): String = "What Retriever has processed · stays on this ${words.device}"

    // A file's menu.
    fun deleteFrom(words: DeviceWords): String = "Delete from ${words.device}"

    fun deleteConfirm(words: DeviceWords): String = "This deletes the file from your ${words.device}."

    // The meaning upgrade.
    const val UPGRADE_TITLE = "Search by meaning and what’s in your photos"
    const val WAITING_FOR_WIFI = "Waiting for Wi-Fi to download"
    const val USE_MOBILE_DATA = "Use mobile data"
    const val WAIT_FOR_WIFI = "Wait for Wi-Fi"
    const val CHECKING_DOWNLOAD = "Checking the download…"
    const val DOWNLOAD_FAILED = "Download interrupted. Check your connection and try again."
    const val NO_SPACE = "Not enough free space to download"
    const val TUNE_LEAVING = "You can search names now. Setting up resumes when you return."

    /** [size]: the platform's format of [Retriever.modelDownloadBytes]. */
    fun upgradeBody(size: String, words: DeviceWords): String = "One download of ${keepTogether(size)} on Wi-Fi. " +
        "Then a search like ‘sunset at the beach’ finds photos even without those words. Everything stays on this ${words.device}."

    fun downloadProgress(done: String, total: String, percent: Int): String = "${keepTogether(done)} of ${keepTogether(total)} · $percent%"

    fun mobileDataAsk(size: String): String = "Download ${keepTogether(size)} using mobile data?"

    fun freeUp(size: String): String = "Free up ${keepTogether(size)} to continue"

    fun tuneTitle(words: DeviceWords): String = "Setting up processing for this ${words.device}"

    fun tuneBody(words: DeviceWords): String = "About a minute. Finding a processing speed that suits this ${words.device}."

    fun tuneFailed(words: DeviceWords): String = "Couldn’t finish setting up processing for this ${words.device}."

    /** A download bar as a screen reader says it; [percent]: the platform's percent format. */
    fun downloading(percent: String): String = "Downloading, $percent"

    /** The meaning pass: files read so far that have search by meaning, of all files read. */
    fun addingMeaning(progress: ReadingProgress): String =
        "Adding search by meaning · ${formatCount(progress.contentRead - progress.meaningWaiting)} of ${files(progress.contentRead)}"
}
