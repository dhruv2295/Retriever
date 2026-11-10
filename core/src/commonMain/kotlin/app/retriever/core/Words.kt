package app.retriever.core

import kotlin.concurrent.Volatile
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
 *
 * Each sentence comes from the current [language]'s [Strings] when it is asked for, never earlier, so a language change
 * shows at once; the choices between sentences stay here, once for every language.
 */
object Words {
    /** Set by each app from its resolved resources, at start and when the device's language changes. */
    @Volatile
    var language: Language = Language.EN

    val strings: Strings get() = language.strings

    /** Keeps a number on the line of its unit. */
    const val NBSP = ' '

    /** The one date-label convention: a capture date is "Taken", anything else "Modified". */
    val MODIFIED: String get() = strings.modified
    val TAKEN: String get() = strings.taken

    /** A platform-formatted size ("200.5 kB") that never wraps between number and unit. */
    fun keepTogether(size: String): String = size.replace(' ', NBSP)

    fun files(n: Long): String = strings.files(n)

    fun photos(n: Long): String = strings.photos(n)

    /** "8 photos", "1 video", "5 photos and videos". */
    fun memoryCount(photos: Long, videos: Long): String = when {
        videos == 0L -> strings.photos(photos)
        photos == 0L -> strings.videos(videos)
        else -> strings.photosAndVideos(photos + videos)
    }

    /**
     * Every count line, by the one count rule ([ReadingProgress.processed], [TasteProgress.done]): "30 of 25,793 files
     * processed". The bar beside it is exactly [done] / [total].
     */
    fun readOf(done: Long, total: Long): String = strings.readOf(done, total)

    /**
     * The meaning pass's count (ReadingPhase.MEANING), "1,938 of 3,142 files have search by description". It shows only once
     * every file is read ([meaningCounted]), so no count of every file stands beside it.
     */
    fun meaningOf(done: Long, total: Long): String = strings.meaningOf(done, total)

    /** The look for faces' count (ReadingPhase.FACES): "2,046 of 16,705 photos looked at". */
    fun facesOf(done: Long, total: Long): String = strings.facesOf(done, total)

    /** One row of Processing's Details: a label and its count, drawn side by side. */
    data class DetailRow(val label: String, val count: String)

    /**
     * Processing's Details: what the count's processed files are, then what is left. In part, couldn't open and the long
     * files still being finished only when there are some.
     */
    fun breakdown(progress: ReadingProgress): List<DetailRow> = listOfNotNull(
        DetailRow(READ, formatCount(progress.read)),
        DetailRow(FOUND_BY_NAME, formatCount(progress.nameOnly)),
        progress.partlyRead.takeIf { it > 0 }?.let { DetailRow(strings.processedInPart, formatCount(it)) },
        progress.couldntRead.takeIf { it > 0 }?.let { DetailRow(strings.couldntBeOpened, formatCount(it)) },
        DetailRow(strings.stillToProcess, formatCount(progress.waiting)),
        progress.laterPasses.takeIf { it > 0 }?.let { DetailRow(strings.longFilesStillBeingFinished, formatCount(it)) },
    )

    fun largeFiles(n: Long): String = strings.largeFiles(n)

    /** Processing's counts; a file processed in part counts as processed on Home. */
    fun readCount(read: Long, partlyRead: Long = 0): String = strings.readCount(read) + if (partlyRead > 0) " · ${strings.partlyReadCount(partlyRead)}" else ""

    /** Said under a file that was not read whole; null when it was. */
    fun extent(extent: ReadExtent?): String? = strings.extent(extent)

    /**
     * How a listed file can be found; null when by everything in it. A kind outside [inside] (Retriever.readInside, which
     * never holds OTHER) is found by name, as the reading counts say.
     */
    fun coverage(file: Store.StoredFile, inside: Set<Kind>): String? = when {
        file.file.kind !in inside || (!file.contentRead && file.state == FileState.INDEXED) -> FOUND_BY_NAME
        !file.contentRead && file.state == FileState.SKIPPED -> strings.couldntProcessLabel
        !file.contentRead -> strings.notProcessedYet
        file.state == FileState.PENDING -> strings.partRestToCome
        file.state == FileState.SKIPPED -> strings.partCouldntFinish
        else -> extent(file.extent ?: if (file.partial) ReadExtent.FIRST_PART else null)
    }

    fun kindLabel(kind: Kind): String = strings.kindLabel(kind)

    fun kindLabelPlural(kind: Kind): String = strings.kindLabelPlural(kind)

    /** Where a match is, as a screen reader says it: "page 12", "at 1 minute 5 seconds"; null for no place. */
    fun spokenPosition(locator: String): String? {
        Locator.pageOf(locator)?.let { return strings.spokenPage(it) }
        val total = (Locator.msOf(locator) ?: return null) / 1000
        return strings.spokenTime(total / 3600, total % 3600 / 60, total % 60)
    }

    fun textOnlyNote(ramGb: Double, words: DeviceWords): String = strings.textOnlyNote(ramGb.roundToInt(), words)

    /** Processing only; never on Home. */
    fun notReadYet(files: Long): String = strings.notReadYet(files)

    fun couldntProcess(files: Long): String = strings.couldntProcess(files)

    // The Processing screen's own labels, the same on both apps.
    val DETAILS: String get() = strings.details
    val STARTING: String get() = strings.starting
    val PROCESSING_NOW: String get() = strings.processingNow
    val RECENTLY_PROCESSED: String get() = strings.recentlyProcessed
    val PROCESSING_YOUR_FILES: String get() = strings.processingYourFiles

    /** Processing's second button, only where [ReadingStatus.offersPause] says: never beside a session's Stop. */
    val PAUSE: String get() = strings.pause

    /** Settings' header over the per-kind switches. */
    val PROCESS_THESE_KINDS: String get() = strings.processTheseKinds

    // Shared actions.

    val CONTINUE: String get() = strings.continueAction
    val TRY_AGAIN: String get() = strings.tryAgain
    val DOWNLOAD: String get() = strings.download
    val NOT_NOW: String get() = strings.notNow
    val CANCEL: String get() = strings.cancel

    /** Closes a sheet or dialog; never a button that starts work. */
    val DONE: String get() = strings.done
    val READ_FOR_15: String get() = strings.readFor15

    /** Marks the search-by-meaning row at the end of Home. */
    val OPTIONAL: String get() = strings.optional

    // Hello. [textOnly]: a phone too small for search by description promises only what it can do.
    fun helloTitle(textOnly: Boolean): String = strings.helloTitle(textOnly)
    val HELLO_BODY: String get() = strings.helloBody

    /** Over Hello's looping example, so it reads as an illustration, not the person's files. */
    val FOR_EXAMPLE: String get() = strings.forExample

    /** Hello's illustrated examples: what is typed, then the card that answers it. [needsModel]: found only by description. */
    data class HelloExample(val query: String, val title: String, val detail: String, val needsModel: Boolean = false)

    /** In the same order in every language: each app draws its own picture for each place. */
    val HELLO_EXAMPLES: List<HelloExample> get() = strings.helloExamples

    /** The examples this phone can keep: a phone too small for the model shows only those words alone find. */
    fun helloExamples(textOnly: Boolean): List<HelloExample> = HELLO_EXAMPLES.filter { !textOnly || !it.needsModel }

    /** Android's access screen's third tile. */
    val DOWNLOADS: String get() = strings.downloads

    /** The privacy line above Hello's and the access screen's button. */
    fun onDevice(words: DeviceWords): String = strings.onDevice(words)
    val GET_STARTED: String get() = strings.getStarted

    /** Under Get started on a phone that will download search by description; [size]: the platform's format. */
    fun downloadNote(size: String): String = strings.downloadNote(keepTogether(size))

    // The access screen.
    val ACCESS_TITLE: String get() = strings.accessTitle
    val ACCESS_BODY: String get() = strings.accessBody
    val ALLOW: String get() = strings.allow
    val ALLOW_ACCESS: String get() = strings.allowAccess
    val CHOOSE: String get() = strings.choose
    val CHOOSE_MORE: String get() = strings.chooseMore
    val SETTINGS: String get() = strings.settings
    val DOCUMENTS: String get() = strings.documents
    val INCLUDES_SCREENSHOTS: String get() = strings.includesScreenshots
    val ALLOWED: String get() = strings.allowed
    val SOME_PHOTOS: String get() = strings.somePhotos
    val PHOTOS_OFF: String get() = strings.photosOff
    val PICK_FOLDERS: String get() = strings.pickFolders

    fun foldersAdded(n: Long): String = strings.foldersAdded(n)

    /** Android's access screen, under the title. */
    val ANDROID_ACCESS_BODY: String get() = strings.androidAccessBody

    // Access, before this version's access screen.
    val ALL_FILES_OFF: String get() = strings.allFilesOff
    val INCLUDE_TITLE: String get() = strings.includeTitle

    /** Android's whole-phone access, as Reading status names it (iOS names its photos and folders). */
    val SHARED_FILES_AND_FOLDERS: String get() = strings.sharedFilesAndFolders
    val PHOTOS_AND_VIDEOS: String get() = strings.photosAndVideosLabel
    val ADD_FOLDER: String get() = strings.addFolder
    val NOTHING_SELECTED: String get() = strings.nothingSelected

    fun wholeDeviceNote(words: DeviceWords): String = strings.wholeDeviceNote(words)

    // The first search: processing and search on one screen.

    /** The first search's title: it describes, never orders. */
    val FIRST_LOOK: String get() = strings.firstLook

    /** The search field's hint: words only until search by description is ready, so no one describes too early. */
    fun firstSearchHint(state: DescriptionState): String = strings.firstSearchHint(state)

    /**
     * The quiet line under the count while search by description gets ready; null when ready, not on this phone, or
     * not started. [percent]: the download's, when known.
     */
    fun descriptionLine(state: DescriptionState, percent: Int?): String? = strings.descriptionLine(state, percent)

    val DESCRIPTION_READY: String get() = strings.descriptionReady
    val DESCRIPTION_READY_BODY: String get() = strings.descriptionReadyBody

    // Home's one-time card after setup.
    val HOME_INTRO_TITLE: String get() = strings.homeIntroTitle
    val HOME_INTRO_BODY: String get() = strings.homeIntroBody
    val GOT_IT: String get() = strings.gotIt
    val NEW_FILES: String get() = strings.newFiles
    val NEW_FILES_BODY: String get() = strings.newFilesBody
    val NOTIFY_WHEN_SEARCHABLE: String get() = strings.notifyWhenSearchable

    /** The one notice [NOTIFY_WHEN_SEARCHABLE] asked for ([Retriever.claimCaughtUpNotice]). */
    val CAUGHT_UP_NOTICE_TITLE: String get() = strings.caughtUpNoticeTitle

    fun caughtUpNotice(files: Long, textOnly: Boolean): String = strings.caughtUpNotice(files, textOnly)

    /** [n]: the demo's planned files; 0 when Continue came before they were picked. */
    fun recentFiles(n: Long): String = strings.recentFiles(n)

    /** [rest]: [ReadingProgress.rest]; null until counted. */
    fun otherFiles(rest: Long?): String = strings.otherFiles(rest)

    fun otherFilesBody(words: DeviceWords): String = strings.otherFilesBody(words)

    /** [otherFilesBody] for Also on battery ([ReadingSchedule.BATTERY]). */
    fun otherFilesOnBatteryBody(words: DeviceWords): String = strings.otherFilesOnBatteryBody(words)

    /** Settings' line for Also on battery. */
    fun onBatteryBody(words: DeviceWords): String = strings.onBatteryBody(words)

    /** What the recent files can do now; promises no time. */
    fun recentFilesBody(state: DescriptionState): String = strings.recentFilesBody(state)

    /** The card's action under the recent files, null when there is nothing to do; [size]: the download's size. */
    fun recentFilesAction(state: DescriptionState, size: String): String? = strings.recentFilesAction(state, keepTogether(size))

    /** The count line while the files are picked (under a second). */
    val DEMO_PICKING: String get() = strings.demoPicking

    /** The chips' slot until the demo has three suggestions. */
    val DEMO_HINT: String get() = strings.demoHint

    /**
     * Above the demo's results: names cover the whole phone, words inside only the demo's [done] files. [finished]: the
     * demo is done ([TasteProgress.pending] is 0).
     */
    fun demoSearchScope(done: Long, finished: Boolean, words: DeviceWords): String = strings.demoSearchScope(done, finished, words)

    /** Opens the list of the demo's files, each with its note. */
    fun seeAll(files: Long): String = strings.seeAll(files)

    /** A demo file whose content is all searchable. */
    val READ: String get() = strings.read
    val FOUND_BY_NAME: String get() = strings.foundByName

    /** File info's row for [readingLabel]. */
    val FOUND_BY: String get() = strings.foundBy

    /** For a screen reader, never on screen: "12 of 40 photos processed". */
    fun kindRead(read: Long, planned: Long, kind: Kind): String = strings.kindRead(read, planned, kind)

    val CHOOSE_WHEN: String get() = strings.chooseWhen

    /** Labels words from the person's own files wherever they are offered (the demo's chips, Home's search with no history). */
    val TRY_LABEL: String get() = strings.tryLabel

    // When to process the rest: after the demo, and from Home's status line.

    fun chooseTitle(words: DeviceWords): String = strings.chooseTitle(words)

    /** [rest]: [ReadingProgress.rest]; null until the phone's files are all counted, so no number that still grows. */
    fun chooseBody(rest: Long?): String = strings.chooseBody(rest)

    /** No figure: the estimate is not calibrated yet ([ESTIMATE_CALIBRATED]). */
    fun choosePace(words: DeviceWords): String = strings.choosePace(words)

    /** Beside the first option, While charging. */
    val RECOMMENDED: String get() = strings.recommended
    val READ_15_NOW: String get() = strings.read15Now
    val NOT_NOW_BODY: String get() = strings.notNowBody

    /** The choice's button: it starts a long job, so never "Done"; [GO_TO_HOME] when Not now is picked. */
    val START_PROCESSING: String get() = strings.startProcessing
    val GO_TO_HOME: String get() = strings.goToHome

    /** While charging, on the choice and in Settings' When to process. */
    fun whileChargingBody(words: DeviceWords): String = strings.whileChargingBody(words)

    /** [READ_15_NOW]'s line on the choice. */
    fun chargingAndNowBody(words: DeviceWords): String = strings.chargingAndNowBody(words)

    /** Settings' Process for 15 minutes. */
    fun readNowBody(words: DeviceWords): String = strings.readNowBody(words)

    /**
     * The notification ask's body (Android). The demo's line only while the demo runs ([SetupInputs.demoRunning]) with files
     * still to process: once it is stopped, skipped or decided there is no demo message to promise.
     */
    fun notificationsAsk(demoRunning: Boolean, demo: TasteProgress): String = strings.notificationsAsk(demoRunning && demo.pending > 0)

    // The notification channel and the one demo-done notice (Android).
    val PROGRESS_CHANNEL: String get() = strings.progressChannel
    val PROGRESS_CHANNEL_BODY: String get() = strings.progressChannelBody
    val DEMO_DONE_NOTICE_TITLE: String get() = strings.demoDoneNoticeTitle

    fun demoDoneNotice(done: Long, planned: Long): String = strings.demoDoneNotice(done, planned)

    // Search.
    val SEARCH_FIELD: String get() = strings.searchField
    val NAME_MATCHES: String get() = strings.nameMatches
    val TEXT_MATCHES: String get() = strings.textMatches
    val SEARCHING_CONTENTS: String get() = strings.searchingContents

    /** A result that stands for exact copies ([SearchEngine.Hit.copies]): "2 copies". */
    fun copies(n: Int): String = strings.copies(n)

    /** The result menu's way to the other copy, by its folder's own name: "Open the copy in Downloads". */
    fun openCopyIn(folder: String): String = strings.openCopyIn(folder)
    val NO_MATCHES: String get() = strings.noMatches

    /** How to ask again: by what a file shows or says when meaning search is on ([byMeaning]), else by its words; and when. */
    fun noMatchesDetail(byMeaning: Boolean): String = strings.noMatchesDetail(byMeaning)

    /** Said after [noMatchesDetail] only while files are still to be processed. */
    val MORE_WAITING: String get() = strings.moreWaiting
    val SEARCH_FAILED: String get() = strings.searchFailed
    val SHOWING_NAME_MATCHES: String get() = strings.showingNameMatches
    val SEARCHING_WITHOUT_MODEL: String get() = strings.searchingWithoutModel

    /** Results this few, without the model, earn the one-line upgrade hint. */
    const val FEW_RESULTS = 3

    /** No model to search by description: a phone too small for it, or the optional download not in place and tuned. */
    fun wordsOnly(textOnly: Boolean, modelMissing: Boolean, setUp: Boolean): Boolean = textOnly || modelMissing || !setUp

    fun matches(labels: List<String>): String = strings.matches(labels)

    /** The one-line hint under few results while the upgrade is on offer; null otherwise. */
    fun upgradeHint(results: Int, upgrade: UpgradeStage): String? =
        strings.downloadForDescription.takeIf { upgrade == UpgradeStage.OFFER && results < FEW_RESULTS }

    // Home, in order under the status line: Starred, Recently added, On this day, Browse.

    /** Processing's screen title; on Home the status line itself is the way there. */
    val READING_STATUS: String get() = strings.readingStatus
    val RECENTLY_ADDED: String get() = strings.recentlyAdded
    val MORE_ON_PHONE: String get() = strings.moreOnPhone
    val ALL_FOLDERS: String get() = strings.allFolders

    /** Folder rows in More on your phone before All folders; the folder list has the rest. */
    const val MORE_FOLDERS = 3

    // Settings.
    val HOW_IT_WORKS: String get() = strings.howItWorks
    val WHEN_TO_READ: String get() = strings.whenToRead
    val WHAT_IT_CAN_SEE: String get() = strings.whatItCanSee
    val FOLDERS_TO_SKIP: String get() = strings.foldersToSkip
    val KIND_OFF: String get() = strings.kindOff
    val SHOW_ON_THIS_DAY: String get() = strings.showOnThisDay

    // People (README.md): off until the person turns it on.
    val PEOPLE: String get() = strings.people
    val RECOGNIZE_PEOPLE: String get() = strings.recognizePeople

    /** Home's offer while People is off: the same consent as Settings' switch, said where People will appear. */
    val PEOPLE_OFFER_TITLE: String get() = strings.peopleOfferTitle
    val PEOPLE_OFFER_BODY: String get() = strings.peopleOfferBody
    val TURN_ON: String get() = strings.turnOn
    val RECOGNIZE_PEOPLE_DETAIL: String get() = strings.recognizePeopleDetail
    val FORGET_PEOPLE_TITLE: String get() = strings.forgetPeopleTitle
    val FORGET_PEOPLE_BODY: String get() = strings.forgetPeopleBody
    val FORGET: String get() = strings.forget
    val NOT_THIS_PERSON: String get() = strings.notThisPerson
    val ADD_A_NAME: String get() = strings.addAName
    val NAME: String get() = strings.name
    val SAVE: String get() = strings.save
    val SAME_PERSON_AS: String get() = strings.samePersonAs

    // Home's question about two people who may be one (Faces.kt, ASK_SAME_PERSON).
    val SAME_PERSON_ASK: String get() = strings.samePersonAsk
    val SAME_PERSON_YES: String get() = strings.samePersonYes
    val SAME_PERSON_NO: String get() = strings.samePersonNo
    val SAME_PERSON_PREVIOUS: String get() = strings.samePersonPrevious
    val SAME_PERSON_NEXT: String get() = strings.samePersonNext

    /** Which of Home's same-person pairs shows, when there is more than one: "2 of 5". */
    fun pairOf(at: Int, of: Int): String = strings.pairOf(at, of)

    /** The person's screen title and the rename dialog's: their name, or the ask for one. */
    fun personTitle(name: String?): String = name ?: ADD_A_NAME

    /** Home's People header line while photos wait: true whether the look runs or waits for the files, which go first. */
    fun facesToGo(left: Long): String = strings.facesToGo(left)

    /** Settings → Storage: a title, then its size and where it is kept. */
    val RETRIEVERS_DATA: String get() = strings.retrieversData
    val TEMPORARY_FILES: String get() = strings.temporaryFiles

    fun keptOn(size: String, words: DeviceWords): String = strings.keptOn(keepTogether(size), words)

    /** Settings → Search by description, once downloaded: what it costs and how to give the space back. */
    val SEARCH_BY_MEANING: String get() = strings.searchByMeaning
    val REMOVE_DOWNLOAD: String get() = strings.removeDownload
    val REMOVE_DOWNLOAD_ASK: String get() = strings.removeDownloadAsk
    val NAMES_STILL_WORK: String get() = strings.namesStillWork

    fun downloaded(size: String): String = strings.downloaded(keepTogether(size))

    /** The confirmation for removing the download; [size] is what is on the device, null when it can't be read. */
    fun removeDownloadMessage(size: String?): String = strings.removeDownloadMessage(size?.let(::keepTogether))

    /** Home's last section: large files and possible copies. Nothing is deleted from either list without asking. */
    val FREE_UP_SPACE: String get() = strings.freeUpSpace
    val LARGE_FILES: String get() = strings.largeFilesTitle
    val FIND_DUPLICATES: String get() = strings.findDuplicates
    val NEVER_DELETES: String get() = strings.neverDeletes

    fun largeFilesDetail(n: Long, size: String): String = "${largeFiles(n)} · ${keepTogether(size)}"

    /** Files sharing a size with another: copies are possible, not yet found. */
    fun possibleCopies(n: Long): String = strings.possibleCopies(n)

    // A file's menu.
    fun deleteFrom(words: DeviceWords): String = strings.deleteFrom(words)

    fun deleteConfirm(words: DeviceWords): String = strings.deleteConfirm(words)

    // The meaning upgrade.
    val UPGRADE_TITLE: String get() = strings.upgradeTitle
    val WAITING_FOR_WIFI: String get() = strings.waitingForWifi
    val USE_MOBILE_DATA: String get() = strings.useMobileData
    val WAIT_FOR_WIFI: String get() = strings.waitForWifi
    val CHECKING_DOWNLOAD: String get() = strings.checkingDownload
    val DOWNLOAD_FAILED: String get() = strings.downloadFailed
    val NO_SPACE: String get() = strings.noSpace
    val TUNE_LEAVING: String get() = strings.tuneLeaving

    /** [size]: the platform's format of [Retriever.modelDownloadBytes]. */
    fun upgradeBody(size: String, words: DeviceWords): String = strings.upgradeBody(keepTogether(size), words)

    fun downloadProgress(done: String, total: String, percent: Int): String = strings.downloadProgress(keepTogether(done), keepTogether(total), percent)

    fun mobileDataAsk(size: String): String = strings.mobileDataAsk(keepTogether(size))

    fun freeUp(size: String): String = strings.freeUp(keepTogether(size))

    fun tuneTitle(words: DeviceWords): String = strings.tuneTitle(words)

    fun tuneBody(words: DeviceWords): String = strings.tuneBody(words)

    fun tuneFailed(words: DeviceWords): String = strings.tuneFailed(words)

    /**
     * The meaning pass in Settings. While first reads remain the status line counts those, and a second count here didn't
     * add up with it (19.7k of 28k processed beside 3,609 to go): only what is happening, with no bar ([meaningCounted]).
     * Once they are done, the status line's own count.
     */
    fun addingMeaning(progress: ReadingProgress): String = if (meaningCounted(progress)) {
        strings.addingMeaning(progress.contentRead - progress.meaningWaiting, progress.contentRead)
    } else {
        MEANING_CATCHING_UP
    }

    /** The status line's own rule for counting the meaning pass (ReadingPhase.MEANING): every first read is done. */
    fun meaningCounted(progress: ReadingProgress): Boolean = progress.scheduled == 0L

    val MEANING_CATCHING_UP: String get() = strings.meaningCatchingUp
}
