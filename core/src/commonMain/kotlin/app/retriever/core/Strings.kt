package app.retriever.core

/**
 * One language's sentences: every shared word both apps show, one implementation per language ([English] is the source),
 * so the compiler holds every translation complete and each language owns its plurals and word order. [Words] is the
 * one way in: it holds the logic (which sentence, when) and asks the current [Language] for the words. Sizes arrive
 * already kept together ([Words.keepTogether]); counts arrive as numbers, for the language's plural, and are written
 * with [formatCount]. The pattern is Lyricist's (adrielcafe/lyricist, MIT): strings as typed Kotlin, one object per locale.
 */
interface Strings {
    /** Between two sentences of one line: a space, or nothing where sentences end with their own mark (Japanese). */
    val sentenceGap: String

    val modified: String
    val taken: String

    fun files(n: Long): String

    fun photos(n: Long): String

    fun videos(n: Long): String

    /** "5 photos and videos": a count of both together. */
    fun photosAndVideos(n: Long): String

    fun readOf(done: Long, total: Long): String

    fun meaningOf(done: Long, total: Long): String

    fun facesOf(done: Long, total: Long): String

    val processedInPart: String
    val couldntBeOpened: String
    val stillToProcess: String
    val longFilesStillBeingFinished: String

    fun largeFiles(n: Long): String

    /** "1,200 files processed". */
    fun readCount(read: Long): String

    /** "3 processed in part", after [readCount]. */
    fun partlyReadCount(n: Long): String

    fun extent(extent: ReadExtent?): String?

    val couldntProcessLabel: String
    val notProcessedYet: String
    val partRestToCome: String
    val partCouldntFinish: String

    fun kindLabel(kind: Kind): String

    fun kindLabelPlural(kind: Kind): String

    fun spokenPage(page: Int): String

    /** A time in a file as a screen reader says it; each part 0 when absent, all 0 at the very start. */
    fun spokenTime(hours: Long, minutes: Long, seconds: Long): String

    fun textOnlyNote(ramGb: Int, words: DeviceWords): String

    fun notReadYet(files: Long): String

    fun couldntProcess(files: Long): String

    val details: String
    val starting: String
    val processingNow: String
    val recentlyProcessed: String
    val processingYourFiles: String
    val pause: String
    val processTheseKinds: String

    val continueAction: String
    val tryAgain: String
    val download: String
    val notNow: String
    val cancel: String
    val done: String
    val readFor15: String
    val optional: String

    fun helloTitle(textOnly: Boolean): String
    val helloBody: String
    val forExample: String
    val helloExamples: List<Words.HelloExample>
    val downloads: String

    fun onDevice(words: DeviceWords): String
    val getStarted: String

    fun downloadNote(size: String): String

    val accessTitle: String
    val accessBody: String
    val allow: String
    val allowAccess: String
    val choose: String
    val chooseMore: String
    val settings: String
    val documents: String
    val includesScreenshots: String
    val allowed: String
    val somePhotos: String
    val photosOff: String
    val pickFolders: String

    fun foldersAdded(n: Long): String
    val androidAccessBody: String
    val allFilesOff: String
    val includeTitle: String
    val sharedFilesAndFolders: String
    val photosAndVideosLabel: String
    val addFolder: String
    val nothingSelected: String

    fun wholeDeviceNote(words: DeviceWords): String
    val firstLook: String

    fun firstSearchHint(state: DescriptionState): String

    fun descriptionLine(state: DescriptionState, percent: Int?): String?
    val descriptionReady: String
    val descriptionReadyBody: String

    val homeIntroTitle: String
    val homeIntroBody: String
    val gotIt: String
    val newFiles: String
    val newFilesBody: String
    val notifyWhenSearchable: String
    val caughtUpNoticeTitle: String

    fun caughtUpNotice(files: Long, textOnly: Boolean): String

    fun recentFiles(n: Long): String

    fun otherFiles(rest: Long?): String

    fun otherFilesBody(words: DeviceWords): String

    fun otherFilesOnBatteryBody(words: DeviceWords): String

    fun onBatteryBody(words: DeviceWords): String

    fun recentFilesBody(state: DescriptionState): String

    fun recentFilesAction(state: DescriptionState, size: String): String?
    val demoPicking: String
    val demoHint: String

    fun demoSearchScope(done: Long, finished: Boolean, words: DeviceWords): String

    fun seeAll(files: Long): String
    val read: String
    val foundByName: String
    val foundBy: String

    fun kindRead(read: Long, planned: Long, kind: Kind): String
    val chooseWhen: String
    val tryLabel: String

    fun chooseTitle(words: DeviceWords): String

    fun chooseBody(rest: Long?): String

    fun choosePace(words: DeviceWords): String
    val recommended: String
    val read15Now: String
    val notNowBody: String
    val startProcessing: String
    val goToHome: String

    fun whileChargingBody(words: DeviceWords): String

    fun chargingAndNowBody(words: DeviceWords): String

    fun readNowBody(words: DeviceWords): String

    /** [demoPending]: the demo still runs with files to process, so its done notice can be promised. */
    fun notificationsAsk(demoPending: Boolean): String
    val progressChannel: String
    val progressChannelBody: String
    val demoDoneNoticeTitle: String

    fun demoDoneNotice(done: Long, planned: Long): String

    val searchField: String
    val nameMatches: String
    val textMatches: String
    val searchingContents: String

    fun copies(n: Int): String

    fun openCopyIn(folder: String): String
    val noMatches: String

    fun noMatchesDetail(byMeaning: Boolean): String
    val moreWaiting: String
    val searchFailed: String
    val showingNameMatches: String
    val searchingWithoutModel: String

    fun matches(labels: List<String>): String
    val downloadForDescription: String

    val readingStatus: String
    val recentlyAdded: String
    val moreOnPhone: String
    val allFolders: String

    val howItWorks: String
    val whenToRead: String
    val whatItCanSee: String
    val foldersToSkip: String
    val kindOff: String
    val showOnThisDay: String

    val people: String
    val recognizePeople: String
    val peopleOfferTitle: String
    val peopleOfferBody: String
    val turnOn: String
    val recognizePeopleDetail: String
    val forgetPeopleTitle: String
    val forgetPeopleBody: String
    val forget: String
    val notThisPerson: String
    val addAName: String
    val name: String
    val save: String
    val samePersonAs: String
    val samePersonAsk: String
    val samePersonYes: String
    val samePersonNo: String
    val samePersonPrevious: String
    val samePersonNext: String

    fun pairOf(at: Int, of: Int): String

    fun facesToGo(left: Long): String

    val retrieversData: String
    val temporaryFiles: String

    fun keptOn(size: String, words: DeviceWords): String

    val searchByMeaning: String
    val removeDownload: String
    val removeDownloadAsk: String
    val namesStillWork: String

    fun downloaded(size: String): String

    fun removeDownloadMessage(size: String?): String

    val freeUpSpace: String
    val largeFilesTitle: String
    val findDuplicates: String
    val neverDeletes: String

    fun possibleCopies(n: Long): String

    fun deleteFrom(words: DeviceWords): String

    fun deleteConfirm(words: DeviceWords): String

    val upgradeTitle: String
    val waitingForWifi: String
    val useMobileData: String
    val waitForWifi: String
    val checkingDownload: String
    val downloadFailed: String
    val noSpace: String
    val tuneLeaving: String

    fun upgradeBody(size: String, words: DeviceWords): String

    fun downloadProgress(done: String, total: String, percent: Int): String

    fun mobileDataAsk(size: String): String

    fun freeUp(size: String): String

    fun tuneTitle(words: DeviceWords): String

    fun tuneBody(words: DeviceWords): String

    fun tuneFailed(words: DeviceWords): String

    fun addingMeaning(done: Long, total: Long): String
    val meaningCatchingUp: String

    // The status line (ReadingPolicy).

    fun scheduleTitle(schedule: ReadingSchedule): String

    fun statusAction(action: StatusAction): String

    fun stopCopy(stop: StopReason, words: DeviceWords): String

    val noAccessTitle: String

    fun noAccessDetail(words: DeviceWords): String
    val finishSetupTitle: String
    val finishSetupDetail: String
    val downloadPausedTitle: String

    fun downloadPausedDetail(words: DeviceWords, size: String): String

    fun downloadingTitle(percent: Int?): String
    val downloadingDetail: String

    val addingDescriptionTitle: String
    val allFilesProcessedTitle: String
    val countingTitle: String
    val notNowTitle: String

    fun readNowTitle(minutesLeft: Int): String
    val sampleTitle: String
    val gettingPeopleReadyTitle: String
    val lookingForFacesTitle: String
    val deepeningTitle: String
    val chargingReadTitle: String
    val lookingTitle: String
    val caughtUpTitle: String
    val limitedTitle: String

    val pausedDetail: String

    fun powerSaveDetail(words: DeviceWords): String
    val hotDetail: String
    val lowBatteryDetail: String
    val chargingUpDetail: String
    val busyDetail: String

    fun chargingDetail(words: DeviceWords): String
    val manualDetail: String

    fun readNowDetail(words: DeviceWords): String
    val sampleDetail: String

    fun meaningDetail(words: DeviceWords): String
    val peopleDownloadDetail: String
    val facesDetail: String
    val deepeningDetail: String
    val readingMeaningDetail: String

    fun readingDetail(words: DeviceWords): String

    fun waitingLongFilesDetail(words: DeviceWords): String

    fun waitingMeaningDetail(words: DeviceWords): String
    val diskFullDetail: String

    fun waitingDetail(words: DeviceWords): String
    val caughtUpDetail: String

    val peopleWaitsForWifi: String

    fun deepeningLead(left: Long): String

    fun longFilesWait(n: Long): String
    val allFilesProcessedLead: String

    // File info's Found by (readingLabel).
    val foundByOther: String
    val foundByBeginningRestPending: String
    val foundByPartCouldntFinish: String
    val foundByBeginning: String
    val foundByInsideMeaningLimited: String
    val foundByInsideDeepening: String
    val foundByInside: String
    val foundByReadingOff: String

    fun foundBySkipped(tries: Long): String
    val foundByNothingInside: String
    val foundByNotYet: String

    val processingFailed: String
}

/**
 * The languages Retriever speaks, by the tag each platform resolves its own resources to: Android's `language` string in
 * values-xx, iOS's first preferred localization. Anything else is English.
 */
enum class Language(val tag: String) {
    EN("en"),
    ;

    val strings: Strings get() = when (this) {
        EN -> English
    }

    companion object {
        fun of(tag: String?): Language = entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: EN
    }
}
