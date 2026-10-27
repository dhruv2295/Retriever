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
 * "your files", never "library"; "found by name", never "name only" (the one exception is [breakdown]'s "by name only",
 * as demo-flow.md words it); Retriever "processes" files, never reads, analyzes,
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

    /**
     * Every count line, by the one count rule ([ReadingProgress.processed], [TasteProgress.done]): "30 of 25,793 files
     * processed". The bar beside it is exactly [done] / [total].
     */
    fun readOf(done: Long, total: Long): String = "${formatCount(done)} of ${files(total)} processed"

    /** The meaning pass's count (ReadingPhase.MEANING): "1,938 of 3,142 files have search by description". */
    fun meaningOf(done: Long, total: Long): String = "${formatCount(done)} of ${files(total)} ${if (total == 1L) "has" else "have"} search by description"

    /**
     * Processing's Details: what the count's processed files are, then what is left. "535 found by what’s inside · 597 by
     * name only · 4 in part · 25,125 to go"; in part and couldn't open only when there are some.
     */
    fun breakdown(progress: ReadingProgress): String = listOfNotNull(
        "${formatCount(progress.read)} found by what’s inside",
        "${formatCount(progress.nameOnly)} by name only",
        progress.partlyRead.takeIf { it > 0 }?.let { "${formatCount(it)} in part" },
        progress.couldntRead.takeIf { it > 0 }?.let { "${formatCount(it)} couldn’t be opened" },
        notReadYet(progress.waiting),
    ).joinToString(" · ")

    fun largeFiles(n: Long): String = "${files(n)} over 100${NBSP}MB"

    /** Processing's counts; a file processed in part counts as processed on Home. */
    fun readCount(read: Long, partlyRead: Long = 0): String =
        "${files(read)} processed" + if (partlyRead > 0) " · ${formatCount(partlyRead)} processed in part" else ""

    /** Said under a file that was not read whole; null when it was. */
    fun extent(extent: ReadExtent?): String? = when (extent) {
        ReadExtent.FIRST_PART -> "Processed in part · only its beginning"
        ReadExtent.MEANING_LIMITED -> "Processed in part · couldn’t finish search by description"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "Processed in part · only its beginning, without search by description"
        ReadExtent.FIRST_PART_DEEPENING -> "Processed in part · only its beginning so far"
        ReadExtent.DEEPENING -> "Processed in part · search by description still to come"
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

    /** Where a match is, as a screen reader says it: "page 12", "at 1 minute 5 seconds"; null for no place. */
    fun spokenPosition(locator: String): String? {
        Locator.pageOf(locator)?.let { return "page $it" }
        val total = (Locator.msOf(locator) ?: return null) / 1000
        val parts = listOf(total / 3600 to "hour", total % 3600 / 60 to "minute", total % 60 to "second")
            .filter { it.first > 0 }
            .map { (n, unit) -> plural(n, unit) }
        return "at " + parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    fun textOnlyNote(ramGb: Double, words: DeviceWords): String =
        "This ${words.device} has ${ramGb.roundToInt()}${NBSP}GB of memory, too little for search by description, so Retriever finds files by their names and the words inside them."

    /** Processing only; never on Home. */
    fun notReadYet(files: Long): String = "${formatCount(files)} to go"

    fun couldntProcess(files: Long): String = "Couldn’t process ${files(files)}"

    // The Processing screen's own labels, the same on both apps.
    const val DETAILS = "Details"
    const val STARTING = "Getting started…"
    const val PROCESSING_NOW = "Processing now"
    const val RECENTLY_PROCESSED = "Recently processed"
    const val PROCESSING_YOUR_FILES = "Processing your files"

    /** Processing's second button, only where [ReadingStatus.offersPause] says: never beside a session's Stop. */
    const val PAUSE = "Pause"

    /** Settings' header over the per-kind switches. */
    const val PROCESS_THESE_KINDS = "Process these file types"

    // Shared actions.

    const val CONTINUE = "Continue"
    const val TRY_AGAIN = "Try again"
    const val DOWNLOAD = "Download"
    const val NOT_NOW = "Not now"
    const val CANCEL = "Cancel"

    /** Closes a sheet or dialog; never a button that starts work. */
    const val DONE = "Done"
    const val READ_FOR_15 = "Process for 15 minutes"

    /** Marks the search-by-meaning row at the end of Home. */
    const val OPTIONAL = "Optional"

    // Hello. [textOnly]: a phone too small for search by description promises only what it can do.
    fun helloTitle(textOnly: Boolean): String = if (textOnly) "Find files by what’s inside them" else "Find anything by describing it"
    const val HELLO_BODY = "Photos, screenshots, documents and videos, in one search."

    /** Over Hello's looping example, so it reads as an illustration, not the person's files. */
    const val FOR_EXAMPLE = "For example"

    /** Hello's illustrated examples: what is typed, then the card that answers it. [needsModel]: found only by description. */
    data class HelloExample(val query: String, val title: String, val detail: String, val needsModel: Boolean = false)

    val HELLO_EXAMPLES = listOf(
        HelloExample("wifi password the landlord sent", "Screenshot from 14 March", "Says “Wi-Fi password: …”"),
        HelloExample("dog asleep on the sofa", "Photo from last Sunday", "Found by what’s in it", needsModel = true),
        HelloExample("my rent agreement", "Lease 2025.pdf", "Page 3, the signed copy"),
    )

    /** The examples this phone can keep: a phone too small for the model shows only those words alone find. */
    fun helloExamples(textOnly: Boolean): List<HelloExample> = HELLO_EXAMPLES.filter { !textOnly || !it.needsModel }

    /** Android's access screen's third tile. */
    const val DOWNLOADS = "Downloads"

    /** The privacy line above Hello's and the access screen's button. */
    fun onDevice(words: DeviceWords): String = "Everything happens on your ${words.device}"
    const val GET_STARTED = "Get started"

    /** Under Get started on a phone that will download search by description; [size]: the platform's format. */
    fun downloadNote(size: String): String = "Includes a one-time ${keepTogether(size)} download, on Wi-Fi."

    // The access screen.
    const val ACCESS_TITLE = "Choose what Retriever can search"
    const val ACCESS_BODY = "Pick one or more. You can add more later."
    const val ALLOW = "Allow"
    const val ALLOW_ACCESS = "Allow access"
    const val CHOOSE = "Choose"
    const val CHOOSE_MORE = "Choose more"
    const val SETTINGS = "Settings"
    const val DOCUMENTS = "Documents"
    const val INCLUDES_SCREENSHOTS = "Includes screenshots"
    const val ALLOWED = "Allowed"
    const val SOME_PHOTOS = "Some photos selected"
    const val PHOTOS_OFF = "Turned off. Turn on in Settings."
    const val PICK_FOLDERS = "Pick folders in Files, like Downloads"

    fun foldersAdded(n: Long): String = "${plural(n, "folder")} added"

    /** Android's access screen, under the title. */
    const val ANDROID_ACCESS_BODY = "So you can find any photo, document or download by describing it."

    // Access, before this version's access screen.
    const val ALL_FILES_OFF = "Turn on “Allow access to manage all files” to search your whole phone, or choose what to include."
    const val INCLUDE_TITLE = "Choose what to include"

    /** Android's whole-phone access, as Reading status names it (iOS names its photos and folders). */
    const val SHARED_FILES_AND_FOLDERS = "Files and folders on this phone"
    const val PHOTOS_AND_VIDEOS = "Photos and videos"
    const val ADD_FOLDER = "Add a folder"
    const val NOTHING_SELECTED = "No files selected yet. Choose photos or a folder to begin."

    fun wholeDeviceNote(words: DeviceWords): String = "Retriever looks inside the files on this ${words.device} so you can find them by what’s in them."

    // The first search: processing and search on one screen.

    /** The first search's title: it describes, never orders. */
    const val FIRST_LOOK = "A first look at your recent files"

    /** The search field's hint: words only until search by description is ready, so no one describes too early. */
    fun firstSearchHint(state: DescriptionState): String = if (state == DescriptionState.READY) "Describe a photo or file" else "Search by name or words"

    /**
     * The quiet line under the count while search by description gets ready; null when ready, not on this phone, or
     * not started. [percent]: the download's, when known.
     */
    fun descriptionLine(state: DescriptionState, percent: Int?): String? = when (state) {
        DescriptionState.DOWNLOADING -> "Downloading search by description" + (percent?.let { " · $it%" } ?: "")
        DescriptionState.WAITING_FOR_WIFI -> "Waiting for Wi-Fi to download search by description."
        DescriptionState.PREPARING -> "Preparing search by description"
        DescriptionState.FAILED -> "The download of search by description stopped."
        DescriptionState.NO_SPACE -> "Not enough free space to download search by description."
        DescriptionState.READY, DescriptionState.NOT_STARTED, DescriptionState.UNAVAILABLE -> null
    }

    const val DESCRIPTION_READY = "Search by description is ready"
    const val DESCRIPTION_READY_BODY = "Now describe your recent files to find them."

    // Home's one-time card after setup.
    const val HOME_INTRO_TITLE = "Retriever keeps going on its own"
    const val HOME_INTRO_BODY = "Here’s what happens next."
    const val GOT_IT = "Got it"
    const val NEW_FILES = "New photos and files"
    const val NEW_FILES_BODY = "Picked up automatically. You don’t need to do anything."
    const val NOTIFY_WHEN_SEARCHABLE = "Notify me when everything’s searchable"

    /** The one notice [NOTIFY_WHEN_SEARCHABLE] asked for ([Retriever.claimCaughtUpNotice]). */
    const val CAUGHT_UP_NOTICE_TITLE = "Everything is searchable"

    fun caughtUpNotice(files: Long, textOnly: Boolean): String = "Retriever has processed all ${files(files)}. " +
        if (textOnly) "Find any of them by name or the words inside." else "Describe anything to find it."

    /** [n]: the demo's planned files; 0 when Continue came before they were picked. */
    fun recentFiles(n: Long): String = when (n) {
        0L -> "Your recent files"
        1L -> "Your most recent file"
        else -> "Your ${formatCount(n)} recent files"
    }

    /** [rest]: [ReadingProgress.rest]; null until counted. */
    fun otherFiles(rest: Long?): String = when (rest) {
        null -> "The rest of your files"
        1L -> "The other file"
        else -> "The other ${files(rest)}"
    }

    fun otherFilesBody(words: DeviceWords): String =
        "When your ${words.device} is charging and you’re not using it. With lots of files, this can take a few nights."

    /** What the recent files can do now; promises no time. */
    fun recentFilesBody(state: DescriptionState): String = when (state) {
        DescriptionState.READY -> "Find them by describing them."

        DescriptionState.UNAVAILABLE -> "Find them by name or words. Search by description needs a phone with more memory."

        DescriptionState.WAITING_FOR_WIFI -> "Find them by name or words now. Waiting for Wi-Fi to finish a one-time download."

        DescriptionState.FAILED -> "Find them by name or words now. The download stopped."

        DescriptionState.NO_SPACE -> "Find them by name or words now. There isn’t enough free space to download search by description."

        DescriptionState.NOT_STARTED -> "Find them by name or words now. Search by description needs a one-time download."

        DescriptionState.DOWNLOADING, DescriptionState.PREPARING ->
            "Find them by name or words now. Search by description arrives when setup finishes."
    }

    /** The card's action under the recent files, null when there is nothing to do; [size]: the download's size. */
    fun recentFilesAction(state: DescriptionState, size: String): String? = when (state) {
        DescriptionState.WAITING_FOR_WIFI -> "Use mobile data (${keepTogether(size)})"
        DescriptionState.FAILED -> TRY_AGAIN
        DescriptionState.NOT_STARTED -> "Download ${keepTogether(size)}"
        else -> null
    }

    /** The count line while the files are picked (under a second). */
    const val DEMO_PICKING = "Picking your recent files…"

    /** The chips' slot until the demo has three suggestions. */
    const val DEMO_HINT = "Type a word you remember from a recent photo, screenshot or document."

    /**
     * Above the demo's results: names cover the whole phone, words inside only the demo's [done] files. [finished]: the
     * demo is done ([TasteProgress.pending] is 0).
     */
    fun demoSearchScope(done: Long, finished: Boolean, words: DeviceWords): String = when {
        finished -> "Finds any file on your ${words.device} by its name, and looks inside the ${plural(done, "demo file")}."

        // The first frame: nothing processed yet, so no "0 demo files".
        done == 0L -> "Finds any file on your ${words.device} by its name."

        else -> "Finds any file on your ${words.device} by its name. Looks inside the ${plural(done, "demo file")} processed so far."
    }

    /** Opens the list of the demo's files, each with its note. */
    fun seeAll(files: Long): String = if (files == 1L) "See the file" else "See the ${files(files)}"

    /** A demo file whose content is all searchable. */
    const val READ = "Found by what’s inside"
    const val FOUND_BY_NAME = "Found by name"

    /** File info's row for [readingLabel]. */
    const val FOUND_BY = "Found by"

    /** For a screen reader, never on screen: "12 of 40 photos processed". */
    fun kindRead(read: Long, planned: Long, kind: Kind): String =
        "${formatCount(read)} of ${formatCount(planned)} ${kindLabelPlural(kind).let { if (kind == Kind.PDF) it else it.lowercase() }} processed"

    const val CHOOSE_WHEN = "Choose when to process"

    /** Labels words from the person's own files wherever they are offered (the demo's chips, Home's search with no history). */
    const val TRY_LABEL = "Try:"

    // When to process the rest: after the demo, and from Home's status line.

    fun chooseTitle(words: DeviceWords): String = "Now, the rest of your ${words.device}"

    /** [rest]: [ReadingProgress.rest]; null until the phone's files are all counted, so no number that still grows. */
    fun chooseBody(rest: Long?): String = when (rest) {
        null -> "The rest of your files aren’t processed yet. Until they are, you can find them by name, but not by what’s inside."
        1L -> "1 file isn’t processed yet. Until it is, you can find it by name, but not by what’s inside."
        else -> "${files(rest)} aren’t processed yet. Until they are, you can find them by name, but not by what’s inside."
    }

    /** No figure: the estimate is not calibrated yet ([ESTIMATE_CALIBRATED]). */
    fun choosePace(words: DeviceWords): String =
        "Processing them takes hours, so Retriever does a little at a time. You can use your ${words.device} and Retriever the whole time."

    /** Beside the first option, While charging. */
    const val RECOMMENDED = "Recommended"
    const val READ_15_NOW = "While charging, and 15 minutes now"
    const val NOT_NOW_BODY = "Only names can be found until you choose. You can choose any time from Home."

    /** The choice's button: it starts a long job, so never "Done"; [GO_TO_HOME] when Not now is picked. */
    const val START_PROCESSING = "Start processing"
    const val GO_TO_HOME = "Go to Home"

    /** While charging, on the choice and in Settings' When to process. */
    fun whileChargingBody(words: DeviceWords): String =
        "When your ${words.device} is charging and you’re not using it, usually overnight. You can pause any time."

    /** [READ_15_NOW]'s line on the choice. */
    fun chargingAndNowBody(words: DeviceWords): String =
        "Starts right away and uses some battery, then carries on while charging." + if (words.ios) " Keep Retriever open." else ""

    /** Settings' Process for 15 minutes. */
    fun readNowBody(words: DeviceWords): String = "Uses some battery. Stops early if your ${words.device} gets warm. Then carries on while charging." +
        if (words.ios) " Keep Retriever open." else ""

    /**
     * The notification ask's body (Android). The demo's line only while the demo runs ([SetupInputs.demoRunning]) with files
     * still to process: once it is stopped, skipped or decided there is no demo message to promise.
     */
    fun notificationsAsk(demoRunning: Boolean, demo: TasteProgress): String =
        "One small notice at the top of your screen while it works, so you can see how far it’s got or pause it. No sounds, no other alerts." +
            if (demoRunning && demo.pending > 0) " We can also let you know when the demo is done." else ""

    // The notification channel and the one demo-done notice (Android).
    const val PROGRESS_CHANNEL = "Processing progress"
    const val PROGRESS_CHANNEL_BODY = "Shows how far processing has got, and once when the demo is done."
    const val DEMO_DONE_NOTICE_TITLE = "The demo is done"

    fun demoDoneNotice(done: Long, planned: Long): String = "${readOf(done, planned)}. Open Retriever to search them."

    // Search.
    const val SEARCH_FIELD = "What are you looking for?"
    const val NAME_MATCHES = "Name matches"
    const val TEXT_MATCHES = "Text matches"
    const val SEARCHING_CONTENTS = "Searching file contents…"

    /** A result that stands for exact copies ([SearchEngine.Hit.copies]): "2 copies". */
    fun copies(n: Int): String = plural(n.toLong(), "copy", "copies")

    /** The result menu's way to the other copy, by its folder's own name: "Open the copy in Downloads". */
    fun openCopyIn(folder: String): String = "Open the copy in $folder"
    const val NO_MATCHES = "Nothing found"
    const val NO_MATCHES_DETAIL = "Try a word from the file’s name."

    /** Said after [NO_MATCHES_DETAIL] only while files are still to be processed. */
    const val MORE_WAITING = "Retriever is still processing your files, so search again later."
    const val SEARCH_FAILED = "Search couldn’t finish"
    const val SHOWING_NAME_MATCHES = "Showing name matches"
    const val SEARCHING_WITHOUT_MODEL = "Searching names and text already processed"

    /** Results this few, without the model, earn the one-line upgrade hint. */
    const val FEW_RESULTS = 3

    /** No model to search by description: a phone too small for it, or the optional download not in place and tuned. */
    fun wordsOnly(textOnly: Boolean, modelMissing: Boolean, setUp: Boolean): Boolean = textOnly || modelMissing || !setUp

    fun matches(labels: List<String>): String = "Matches: " + labels.joinToString(", ")

    /** The one-line hint under few results while the upgrade is on offer; null otherwise. */
    fun upgradeHint(results: Int, upgrade: UpgradeStage): String? =
        "Download for search by description".takeIf { upgrade == UpgradeStage.OFFER && results < FEW_RESULTS }

    // Home, in order under the status line: Starred, Recently added, On this day, Browse.

    /** Processing's screen title; on Home the status line itself is the way there. */
    const val READING_STATUS = "Processing"
    const val RECENTLY_ADDED = "Recently added"
    const val MORE_ON_PHONE = "Browse"
    const val ALL_FOLDERS = "All folders"

    /** Folder rows in More on your phone before All folders; the folder list has the rest. */
    const val MORE_FOLDERS = 3

    // Settings.
    const val HOW_IT_WORKS = "How Retriever works and your privacy"
    const val WHEN_TO_READ = "When to process"
    const val WHAT_IT_CAN_SEE = "What Retriever can see"
    const val FOLDERS_TO_SKIP = "Folders to skip"
    const val KIND_OFF = "Turned off: Retriever still finds these by name, but forgets what’s inside them. Your files aren’t touched."
    const val SHOW_ON_THIS_DAY = "Show On this day (photos from this date in past years)"

    // People (docs/features/people.md): off until the person turns it on.
    const val PEOPLE = "People"
    const val RECOGNIZE_PEOPLE = "Recognize people"
    const val RECOGNIZE_PEOPLE_DETAIL =
        "Groups the faces in your photos so you can name people and find them by name. Processed on this phone, never backed up."
    const val FORGET_PEOPLE_TITLE = "Forget every face and name?"
    const val FORGET_PEOPLE_BODY = "Retriever forgets the faces it grouped and the names you gave. Your photos aren’t touched."
    const val FORGET = "Forget"
    const val NOT_THIS_PERSON = "Not this person"
    const val ADD_A_NAME = "Add a name"
    const val NAME = "Name"
    const val SAVE = "Save"
    const val SAME_PERSON_AS = "Same person as…"

    // Home's question about two people who may be one (Faces.kt, ASK_SAME_PERSON).
    const val SAME_PERSON_ASK = "Same person?"
    const val SAME_PERSON_YES = "Same person"
    const val SAME_PERSON_NO = "Not the same"
    const val SAME_PERSON_PREVIOUS = "Previous pair"
    const val SAME_PERSON_NEXT = "Next pair"

    /** Which of Home's same-person pairs shows, when there is more than one: "2 of 5". */
    fun pairOf(at: Int, of: Int): String = "$at of $of"

    /** The person's screen title and the rename dialog's: their name, or the ask for one. */
    fun personTitle(name: String?): String = name ?: ADD_A_NAME

    /** Home's People header line while photos still wait to be looked at. */
    fun lookingForFaces(left: Long): String = "Looking for faces · ${formatCount(left)} to go"

    /** Settings → Storage: a title, then its size and where it is kept. */
    const val RETRIEVERS_DATA = "Retriever’s data"
    const val TEMPORARY_FILES = "Temporary files"

    fun keptOn(size: String, words: DeviceWords): String = "${keepTogether(size)} · kept on this ${words.device}"

    /** Settings → Search by description, once downloaded: what it costs and how to give the space back. */
    const val SEARCH_BY_MEANING = "Search by description"
    const val REMOVE_DOWNLOAD = "Remove search download"
    const val REMOVE_DOWNLOAD_ASK = "Remove the search download?"
    const val NAMES_STILL_WORK = "File names and text already processed still work."

    fun downloaded(size: String): String = "Downloaded · ${keepTogether(size)}"

    /** The confirmation for removing the download; [size] is what is on the device, null when it can't be read. */
    fun removeDownloadMessage(size: String?): String =
        (if (size != null) "This frees ${keepTogether(size)}. " else "") + "$NAMES_STILL_WORK Search by description needs the download again."

    /** Home's last section: large files and possible copies. Nothing is deleted from either list without asking. */
    const val FREE_UP_SPACE = "Free up space"
    const val LARGE_FILES = "Large files"
    const val FIND_DUPLICATES = "Find duplicate files"
    const val NEVER_DELETES = "Retriever never deletes anything without asking you first."

    fun largeFilesDetail(n: Long, size: String): String = "${largeFiles(n)} · ${keepTogether(size)}"

    /** Files sharing a size with another: copies are possible, not yet found. */
    fun possibleCopies(n: Long): String = "${files(n)} might be copies"

    // A file's menu.
    fun deleteFrom(words: DeviceWords): String = "Delete from ${words.device}"

    fun deleteConfirm(words: DeviceWords): String = "This deletes the file from your ${words.device}."

    // The meaning upgrade.
    const val UPGRADE_TITLE = "Find files by describing them"
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

    /** The meaning pass: files read so far that have search by description, of all files read. */
    fun addingMeaning(progress: ReadingProgress): String =
        "Adding search by description · ${formatCount(progress.contentRead - progress.meaningWaiting)} of ${files(progress.contentRead)}"
}
