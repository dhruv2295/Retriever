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

    // Access

    /** Android: access line (Processing and access screen) */
    val onlyPickedPhotos: String

    /** Android: access line (audio permission) */
    val musicAndRecordings: String

    /** Android: access gap line; flags are what is missing; lines 118-121 build one sentence today (list words, joiners, prefix) */
    fun notCoveredMedia(photos: Boolean, videos: Boolean, music: Boolean): String

    /** Android: access gap line */
    fun notCoveredDocuments(hasFolders: Boolean): String

    /** Android: error under Add a folder */
    val pickFolderOnPhone: String

    /** Android: error under Add a folder; differs from folderOverlaps (iOS) */
    val alreadySearchesFolder: String

    // AccessService

    /** Android: error under Add a folder */
    val couldntKeepFolder: String

    // BrowseScreen

    /** Android: Browse title with all-files access */
    val allFiles: String

    /** both apps: Browse title */
    val filesRetrieverCanAccess: String

    /** both apps: sort subtitle */
    val sortLargestDetail: String

    /** both apps: sort subtitle */
    val sortNameDetail: String

    /** both apps: sort subtitle */
    val sortNewestDetail: String

    /** Android: snackbar; differs from couldntChangePersonCurly (iOS) by apostrophe only */
    val couldntChangePerson: String

    /** both apps: top-bar icon label */
    val back: String

    /** Android: error title; differs from couldntLoadFiles (iOS) */
    val couldntLoadYourFiles: String

    /** Android: error message; differs from wentWrongReadingList (iOS) */
    val wentWrongReadingThem: String

    /** both apps: empty Starred title */
    val keepUsefulFilesClose: String

    /** both apps: empty Starred message */
    val tapStarToFindHere: String

    /** both apps: no-access title */
    val chooseFilesToSearch: String

    /** both apps: no-access message */
    val addPhotosOrFolder: String

    /** Android: button */
    val manageAccess: String

    /** both apps: empty title */
    val nothingHereYet: String

    /** both apps: empty message; Android literal says "phone" = DeviceWords.ANDROID.device */
    fun filesShowUpHere(words: DeviceWords): String

    /** both apps: TalkBack long-click label */
    val moreOptions: String

    /** both apps: inline error at list end */
    val couldntLoadMoreFiles: String

    /** both apps: sort option */
    val sortNewest: String

    /** both apps: sort option */
    val sortLargest: String

    /** both apps: sort option (by name); not the text-field label Words.NAME */
    val sortName: String

    // BrowseViewModel

    /** Android: stored in ui.error; screen seems to show only couldntLoadMoreFiles (unsure it reaches UI) */
    val couldntLoadTheseFiles: String

    // PersonActions

    /** both apps: person action (rename a person) */
    val rename: String

    // DuplicatesScreen

    /** both apps: screen title */
    val duplicates: String

    /** both apps: warning */
    val keepOneCopy: String

    /** Android: error; today raw count and no singular; differs from filesWerentDeleted (iOS) */
    fun filesCouldntBeDeleted(n: Long): String

    /** both apps: button; today raw count */
    fun deleteSelected(n: Long, size: String): String

    /** Android: progress title; differs from lookingForCopies (iOS, no ellipsis) */
    val lookingForCopiesEllipsis: String

    /** both apps: error title */
    val couldntLookForDuplicates: String

    /** both apps: empty title */
    val noDuplicatesFound: String

    /** Android: empty message; today "%,d" (grouped); no singular; differs from noDuplicatesMessage (iOS) */
    fun comparedFiles(n: Long): String

    /** both apps: header */
    fun couldBeFreed(size: String): String

    /** both apps: group header; n is always 2 or more */
    fun copiesEach(n: Long, size: String): String

    /** both apps: screen-reader label of an open icon */
    fun openNamed(name: String): String

    /** Android: TalkBack click label */
    val deselect: String

    /** Android: TalkBack click label */
    val select: String

    /** Android: dialog title; iOS puts the count in the title (deleteFilesAsk) */
    val deleteFilesTitle: String

    /** Android: dialog message; today raw count, no singular */
    fun deleteFilesMessage(n: Long): String

    /** both apps: destructive confirm button */
    val delete: String

    // DuplicatesViewModel

    /** both apps: error message */
    val somethingWentWrongTryAgain: String

    // FileActions

    /** both apps: file action */
    val addToStarred: String

    /** both apps: file action */
    val share: String

    /** both apps: file action */
    val openInAnotherApp: String

    /** both apps: file action / screen title */
    val fileInfo: String

    /** both apps: file action */
    val removeFromStarred: String

    /** both apps: name of the storage root folder */
    val topLevel: String

    /** Android: toast */
    val couldntChangeStar: String

    /** both apps: file action */
    val findSimilar: String

    /** both apps: toast (Android) / alert message (iOS) */
    val couldntFinishThat: String

    /** both apps: dialog title */
    val renameFile: String

    /** both apps: dialog title */
    val deleteThisFile: String

    // FileInfo

    /** both apps: File info subtitle */
    fun kindAndSize(kind: String, size: String): String

    /** both apps: File info row label (where the file is) */
    val locationLabel: String

    /** Android: TalkBack click label */
    val hideFullPath: String

    /** Android: TalkBack click label */
    val showFullPath: String

    /** both apps: disclosure title */
    val locationDetails: String

    /** Android: clipboard label; shown only in some clipboard previews */
    val pathClipLabel: String

    /** both apps: button */
    val copyPath: String

    /** both apps: File info row label */
    val dimensionsLabel: String

    /** both apps: File info value; pixels, raw digits today */
    fun dimensions(width: Int, height: Int): String

    /** both apps: File info row label */
    val durationLabel: String

    /** both apps: File info row label */
    val pagesLabel: String

    /** Android: replaces "primary:" in the full path; keep the trailing slash */
    val internalStorage: String

    // FoldersScreen

    /** both apps: screen title */
    val folders: String

    /** Android: error title; differs from couldntLoadFolders (iOS) */
    val foldersDidntLoad: String

    /** both apps: empty title */
    val noFoldersYet: String

    /** Android: error message */
    val tryAgainInAMoment: String

    /** Android: empty message; differs from foldersAppearFiles (iOS) */
    val foldersAppearPhone: String

    /** both apps: dialog title */
    fun stopSearchingAsk(name: String): String

    /** Android: dialog message (111-112 one sentence); differs from stopSearchingForget (iOS) */
    fun stopSearchingMessage(n: Long, name: String): String

    /** both apps: confirm button */
    val stopSearching: String

    /** Android: folder row subtitle */
    fun filesInParent(n: Long, parent: String): String

    /** Android: TalkBack label */
    fun moreOptionsFor(name: String): String

    /** both apps: menu item / long-click label */
    val stopSearchingThisFolder: String

    // FoldersViewModel

    /** Android: error */
    val couldntLoadYourFolders: String

    /** Android: error */
    val couldntStopSearchingFolder: String

    // HomeScreen

    /** Android: snackbar */
    val onThisDayHidden: String

    /** Android: snackbar action */
    val undo: String

    /** Android: TalkBack click label of the status line */
    val showProcessingDetails: String

    /** both apps: empty Home title */
    val lookingForYourFiles: String

    /** both apps: empty Home title */
    val nothingToSearchYet: String

    /** both apps: empty Home body while looking */
    val takesAMinute: String

    /** Android: empty Home body; differs from addFolderOrPhotos (iOS) */
    val saveAPdfHint: String

    // HomeSections

    /** Android: TalkBack click label on a section header; differs from showsAll (iOS) */
    val showAll: String

    /** Android: TalkBack label of a section header */
    fun sectionSpoken(title: String, subtitle: String): String

    /** Android: TalkBack click label on a person tile */
    val openAction: String

    /** both apps: screen-reader label of a person tile; both apps */
    fun personTileSpoken(name: String, photos: Long): String

    /** Android: TalkBack label of a Browse tile */
    fun browseTileSpoken(label: String, count: String): String

    /** Android: Home section header; differs from onThisDayTitle (iOS, title case) */
    val onThisDay: String

    /** both apps: screen-reader label of an On this day card; count is Words.memoryCount */
    fun onThisDaySpoken(year: Int, count: String, date: String): String

    /** Android: TalkBack click label; differs from showsThem (iOS) */
    val showThem: String

    /** Android: TalkBack long-click label; differs from hideOnThisDayIos */
    val hideOnThisDay: String

    /** both apps: Home section title (the collection) */
    val starredSection: String

    // ReadingPermission

    /** Android: dialog title */
    val notificationPermissionAsk: String

    /** Android: dialog title */
    val processFasterAsk: String

    /** Android: dialog body (98-100 one sentence); "Battery"/"Unrestricted" must match Android's own setting names */
    val processFasterBody: String

    /** Android: dialog button; differs from openSettings (iOS "Open Settings") */
    val openSettingsLowercase: String

    // ModelDownload

    /** Android: DownloadManager notification title */
    val searchModelDownloadTitle: String

    // ModelRepository

    /** Android: download error; UpgradeCard swaps it for Words.DOWNLOAD_FAILED, may not reach UI */
    val downloadStopped: String

    /** Android: download error under the upgrade card */
    val downloadDamaged: String

    /** Android: download error; gb is "%.1f" formatted today */
    fun needsFreeSpace(gb: String): String

    // SearchResults

    /** Android: chip while searching by a photo; name is a file name; "your photo" (SearchViewModel.kt:458) is spliced in today, see likeYourPhoto */
    fun likeFile(name: String): String

    /** both apps: screen-reader label of the photo thumbnail */
    val photoYoureSearchingWith: String

    /** both apps: screen-reader label of the close icon */
    val stopSearchingByPhoto: String

    /** Android: TalkBack label; differs from searchMyExactWords (iOS) */
    val searchWordsAsTyped: String

    /** both apps: note / empty-state message */
    val photoSearchAfterSetup: String

    /** both apps: note over results */
    val matchingExactWordsForNow: String

    /** Android: empty title; scope is starredScope or a lowercase kind plural ("PDFs") */
    fun noMatchesIn(scope: String): String

    /** Android: empty message */
    val nothingLikePhotoThere: String

    /** Android: empty message */
    fun nothingForThere(query: String): String

    /** both apps: button */
    val searchAllFiles: String

    /** both apps: empty title for a photo search */
    val nothingLikeThisYet: String

    /** both apps: empty message */
    val tryAnotherPhoto: String

    /** Android: button */
    val tryFewerWords: String

    /** both apps: kind filter chip (Android) / search scope (iOS): no filter */
    val filterAll: String

    /** both apps: filter chip / search scope: only starred files */
    val filterStarred: String

    /** Android: TalkBack state */
    val expanded: String

    /** Android: TalkBack state */
    val collapsed: String

    /** both apps: disclosure title; today the raw count */
    fun lessLikelyMatches(n: Long): String

    /** both apps: results section header */
    val moreDocuments: String

    /** both apps: results section header */
    val recordings: String

    /** both apps: results section header */
    val documentsAndMore: String

    // SearchScreen

    /** Android: TalkBack label of the photo-search icon; differs from searchByPhoto (iOS) */
    val searchWithPhoto: String

    /** Android: TalkBack label of the clear icon */
    val clearSearch: String

    /** both apps: list header */
    val recentSearches: String

    /** Android: TalkBack label; differs from removeFromRecentSearches (iOS) */
    fun removeFromHistory(query: String): String

    /** Android: TalkBack click label on a recent search; verb */
    val searchAction: String

    /** Android: button under recent searches (iOS says clearRecentSearches there) */
    val clearHistory: String

    // SearchViewModel

    /** Android: today the label "your photo" spliced into likeFile ("Like your photo"); make it its own sentence; differs from likeThisPhoto (iOS) */
    val likeYourPhoto: String

    /** Android: error */
    val couldntOpenPhoto: String

    /** Android: error */
    val thatDidntWork: String

    // SettingsScreen

    /** Android: Settings footer under file types; today Words.KIND_OFF + this tail */
    val includedFilesFooter: String

    /** Android: When to process detail for "When I start it"; differs from onlyWhenYouChooseAnyFile (iOS); app prefixes "<schedule>. " */
    val onlyWhenYouChoose: String

    /** Android: Settings row title */
    val processingNotifications: String

    /** Android: row value: notifications are on */
    val notificationsOn: String

    /** Android: row value: notifications are off */
    val notificationsOff: String

    /** Android: Settings section header */
    val includedFiles: String

    /** both apps: empty list under Folders to skip; both apps */
    val noneExcluded: String

    /** Android: TalkBack label of the Include folder button; differs from includeQuoted (iOS) */
    fun includeNamed(folder: String): String

    /** both apps: button: stop skipping this folder; both apps */
    val includeFolder: String

    /** both apps: Settings section header */
    val privacy: String

    /** both apps: switch title */
    val saveRecentSearches: String

    /** Android: switch detail; differs from savedOnDeviceOnly (iOS) */
    val savedOnDeviceShortcuts: String

    /** both apps: destructive button/row */
    val clearRecentSearches: String

    /** Android: dialog title; differs from clearRecentSearchesAsk (iOS) */
    val clearSearchesAndShortcutsAsk: String

    /** both apps: confirm button that clears recent searches */
    val clear: String

    /** both apps: Settings section header */
    val storage: String

    /** both apps: Settings section header */
    val appearance: String

    /** Android: switch title (Material You); British spelling today */
    val wallpaperColours: String

    /** Android: switch detail */
    val wallpaperColoursOff: String

    /** both apps: Settings section header */
    val about: String

    /** both apps: row label beside the version number */
    val version: String

    /** both apps: row and screen title */
    val openSourceNotices: String

    /** both apps: confirm button of the remove-download dialog (not Words.REMOVE_DOWNLOAD, which says "Remove search download") */
    val removeDownloadConfirm: String

    /** Android: How it works paragraph (literal spans 460-464); one sentence today spliced from two shared sentences */
    fun howItWorksBody(words: DeviceWords): String

    /** Android: How it works paragraph */
    val howItWorksTimes: String

    /** both apps: button/row that re-tunes processing */
    val runSetupCheck: String

    /** Android: row detail while tuning */
    val tuningFewMinutes: String

    /** Android: row detail; message is the raw exception text (English) */
    fun tuneFailedMessage(message: String): String

    /** Android: row detail after tuning */
    val processingIsSetUp: String

    /** Android: row detail */
    val adjustsProcessingSpeed: String

    /** both apps: switch title */
    val useMobileDataForDownloads: String

    /** Android: switch detail; differs from downloadsWaitForWifi (iOS footer) */
    val downloadsWaitForWifiShort: String

    /** both apps: placeholder while notices load */
    val loading: String

    // SettingsViewModel

    /** both apps: snackbar (Android) / red text (iOS); curly apostrophe on both */
    val couldntSaveChange: String

    /** Android: snackbar */
    val searchDownloadRemoved: String

    // Starring

    /** Android: snackbar after starring (past participle, not the collection name) */
    val starAdded: String

    /** Android: snackbar */
    val starRemoved: String

    // StatusScreen

    /** Android: Processing title before status arrives */
    val checkingYourFiles: String

    /** Android: toggle that hides the couldn't-process list */
    val hide: String

    /** Android: toggle that shows the couldn't-process list */
    val reviewFiles: String

    /** Android: battery-exemption hint title */
    val letProcessWhileAway: String

    /** Android: battery-exemption hint body; lines 298-299 are one sentence today */
    fun batteryMayStop(chargingOnly: Boolean): String

    /** Android: row detail under Settings */
    val settingsRowDetail: String

    /** both apps: button when only some photos are allowed */
    val chooseMorePhotos: String

    /** both apps: list header */
    val foldersYouAdded: String

    /** Android: TalkBack label of a folder's remove icon */
    fun removeNamed(name: String): String

    /** Android: button */
    val allowAccessToAllFiles: String

    // Format

    /** Android: lowercase plural noun phrase spliced into noMatchesIn/relaxedNote ("starred PDFs"); reuses shared starredFiles */
    fun starredScope(kind: String?): String

    /** both apps: badge on a result that matched on a page; both apps (iOS Format.badge) */
    fun pageBadge(page: Int): String

    // FileRow

    /** Android: TalkBack click label; differs from opensAtPage (iOS) */
    fun openAtPage(page: Int): String

    /** Android: TalkBack click label, time like 1:05; differs from playsFrom (iOS) */
    fun playFrom(time: String): String

    /** Android: file row subtitle; separators only */
    fun kindSizeDate(kind: String, size: String, date: String): String

    /** Android: TalkBack label of a result row; lines 213-215 are one sentence today built by append */
    fun fileRowSpoken(kind: String, name: String, date: String, position: String?, note: String?): String

    // ReadingFiles

    /** Android: TalkBack long-click label */
    val fileActions: String

    /** both apps: TalkBack/VoiceOver label of a reading-now tile; both apps */
    fun fileSpoken(name: String, kind: String, date: String): String

    // DocScreen

    /** Android: doc viewer empty title (iOS uses strings.notProcessedYet) */
    val notProcessedThisYet: String

    /** Android: message */
    val waitingItsTurn: String

    /** Android: title */
    val noTextFound: String

    /** Android: message */
    val scanOrPictures: String

    /** both apps: error title */
    val couldntOpenFile: String

    /** both apps: error message */
    val maybeMovedOrDeleted: String

    /** both apps: icon label */
    val previousMatch: String

    /** both apps: match counter */
    fun matchOf(at: Int, total: Int): String

    /** Android: match count before stepping; today raw Int and "1 matches" for one (give it a plural) */
    fun matchCount(n: Long): String

    /** both apps: icon label */
    val nextMatch: String

    // Opener

    /** Android: toast */
    val noAppOpensFile: String

    // PdfPageScreen

    /** Android: PDF viewer subtitle; today two spliced literals */
    fun pdfSubtitle(page: Int, count: Int, matchedPage: Int?): String

    /** Android: icon label */
    val closePage: String

    /** both apps: error title */
    val pdfNeedsPassword: String

    /** Android: error message; differs from anotherAppCanAsk (iOS) */
    val pdfFindableByName: String

    /** both apps: error title */
    val folderAccessChanged: String

    /** both apps: error message */
    val chooseFolderAgain: String

    /** both apps: error title */
    val fileNoLongerAvailable: String

    /** both apps: button */
    val goBack: String

    /** Android: error title */
    val couldntOpenPdf: String

    /** Android: TalkBack label of the zoomed page */
    fun pageZoomed(page: Int): String

    /** both apps: page thumbnail label (Android) / PDF badge (iOS) */
    fun pageOf(page: Int, count: Int): String

    /** Android: TalkBack click label */
    val zoomPage: String

    /** Android: TalkBack label of a page; today label + literal */
    fun pageSpoken(label: String, matched: Boolean): String

    /** Android: badge on the matched page */
    val matchedHere: String

    // PhotoScreen

    /** both apps: error title */
    val couldntShowPhoto: String

    // PlayerScreen

    /** both apps: viewer subtitle; time like 1:05 */
    fun matchAt(time: String): String

    /** Android: error title; differs from couldntPlayIos */
    fun couldntPlay(audio: Boolean): String

    /** Android: error message */
    val maybeMovedOrCantPlay: String

    // ViewerQueue

    /** both apps: viewer subtitle "2 of 5"; same text as pairOf but a different meaning */
    fun positionOf(at: Int, count: Int): String

    // ViewerTopBar

    /** both apps: icon label */
    val previousFile: String

    /** both apps: icon label */
    val nextFile: String

    // AppModel+Loop

    /** iOS: red message in Processing/Settings */
    fun keepProcessingFailed(words: DeviceWords): String

    // AppModel+Settings

    /** iOS: message; differs from couldntChangePerson (Android) by its curly apostrophe only */
    val couldntChangePersonCurly: String

    // FolderSource

    /** iOS: fallback name of a folder whose bookmark can't be read */
    val aFolder: String

    // PhotosSource

    /** iOS: stored as the file's folder and shown in rows; translating needs an index migration (unsure) */
    val photosScreenshotsFolder: String

    /** iOS: stored folder path shown in rows (unsure) */
    val photosCameraFolder: String

    /** iOS: stored folder path shown in rows (unsure) */
    val photosVideosFolder: String

    // SearchModel

    /** iOS: lowercase phrase spliced into core's relaxedNote; Android reaches it through starredScope */
    val starredFiles: String

    // Copy

    /** iOS: Home note title / empty title */
    val photosAccessOff: String

    /** iOS: Home note detail */
    val photosOffNoteDetail: String

    /** iOS: Home note title */
    val seesOnlyPickedPhotos: String

    /** iOS: Home note detail */
    val canSearchJustThose: String

    /** iOS: alert after adding a folder */
    val folderAdded: String

    /** iOS: alert; differs from alreadySearchesFolder (Android) */
    fun folderOverlaps(name: String): String

    /** iOS: alert */
    val folderRefused: String

    /** iOS: How it works paragraph in Settings */
    fun backgroundNote(words: DeviceWords): String

    /** iOS: note on Processing */
    val screenKeptOn: String

    /** iOS: what the search understood ("Showing Screenshots · Last week") */
    fun showingLabels(labels: List<String>): String

    /** iOS: button; differs from searchWordsAsTyped (Android) */
    val searchMyExactWords: String

    /** iOS: Lock Screen continued-processing subtitle */
    fun processedSoFar(n: Long): String

    // Format

    /** iOS: VoiceOver hint; differs from openAtPage (Android) */
    fun opensAtPage(page: Int): String

    /** iOS: VoiceOver hint; differs from playFrom (Android) */
    fun playsFrom(time: String): String

    /** iOS: VoiceOver hint; differs from openAction (Android) */
    val opensHint: String

    // BrowseView

    /** iOS: error title; differs from couldntLoadYourFiles (Android) */
    val couldntLoadFiles: String

    /** iOS: error message; differs from wentWrongReadingThem */
    val wentWrongReadingList: String

    /** iOS: Browse row subtitle; separators only */
    fun sizeFolderDate(size: String, folder: String, date: String): String

    /** iOS: picker label */
    val sortBy: String

    /** iOS: toolbar menu label */
    val sortMenu: String

    /** iOS: alert button */
    val ok: String

    // DuplicatesView

    /** iOS: empty message; differs from comparedFiles (Android); "1 MB" has a plain space */
    val noDuplicatesMessage: String

    /** iOS: progress title; differs from lookingForCopiesEllipsis (Android) */
    val lookingForCopies: String

    /** iOS: progress message */
    val thisCanTakeAMinute: String

    /** iOS: alert title (one if/else today); differs from deleteFilesTitle/deleteFilesMessage (Android) */
    fun deleteFilesAsk(n: Long): String

    /** iOS: alert message */
    val cantBeRecovered: String

    /** iOS: explanation under the header */
    val comparesFilesNote: String

    /** iOS: row subtitle */
    fun folderAndDate(folder: String, date: String): String

    /** iOS: VoiceOver hint */
    val selectedToDeleteHint: String

    /** iOS: VoiceOver hint */
    val keptHint: String

    /** iOS: error (one if/else today); differs from filesCouldntBeDeleted (Android) */
    fun filesWerentDeleted(n: Long): String

    // FileActions

    /** iOS: alert message */
    val couldntOpenFolderUnavailable: String

    /** iOS: File info location for a library photo: Apple's Photos app, use its localized name */
    val photosAppName: String

    /** iOS: menu label; differs from moreOptions */
    val moreActions: String

    /** iOS: alert title */
    val couldntFinish: String

    // FoldersView

    /** iOS: section header */
    val foldersWithFiles: String

    /** iOS: retry button (no final period today) */
    val couldntLoadFolderList: String

    /** iOS: swipe action on an added folder */
    val removeFolder: String

    /** iOS: row note */
    val cantOpenFolderAnyMore: String

    /** iOS: section footer; "Files" is Apple's app */
    val alsoSearchesOwnFolder: String

    /** iOS: error title; differs from foldersDidntLoad (Android) */
    val couldntLoadFolders: String

    /** iOS: error message */
    val wentWrongReadingFolders: String

    /** iOS: empty message; differs from foldersAppearPhone (Android) */
    val foldersAppearFiles: String

    /** iOS: dialog message; differs from stopSearchingMessage (Android) */
    fun stopSearchingForget(n: Long, name: String): String

    /** iOS: alert title */
    fun removeFolderAsk(name: String): String

    /** iOS: alert message */
    val removeFolderMessage: String

    // HomeSections

    /** iOS: VoiceOver hint; differs from showAll (Android) */
    val showsAll: String

    /** iOS: VoiceOver hint; differs from showThem (Android) */
    val showsThem: String

    /** iOS: context menu; differs from hideOnThisDay (Android) */
    val hideOnThisDayIos: String

    /** iOS: VoiceOver label of a Home file tile (190-191 one sentence today) */
    fun homeTileSpoken(kind: String, name: String, date: String, starred: Boolean): String

    // HomeView

    /** iOS: empty Home body; differs from saveAPdfHint (Android) */
    val addFolderOrPhotos: String

    /** iOS: button */
    val allowPhotosAccess: String

    /** iOS: button */
    val turnOnPhotosAccess: String

    /** iOS: Home section header (title case); differs from onThisDay (Android) */
    val onThisDayTitle: String

    /** iOS: button */
    val openSettings: String

    /** iOS: note */
    val alsoSearchesFolders: String

    // ReadingFiles

    /** iOS: VoiceOver hint */
    val openFileHint: String

    // ResultViews

    /** iOS: file name of a shared photo, seen by the recipient */
    fun sharedPhotoName(ext: String?): String

    // RootView

    /** iOS: navigation title of the setup flow */
    val setupTitle: String

    // SearchView

    /** iOS: search scope button; must fit five in the bar, keep very short */
    val docsShort: String

    /** iOS: toolbar button label; differs from searchWithPhoto (Android) */
    val searchByPhoto: String

    /** iOS: swipe action on a recent search */
    val removeSearch: String

    /** iOS: context menu; differs from removeFromHistory (Android) */
    val removeFromRecentSearches: String

    /** iOS: error message */
    val somethingWentWrongSearching: String

    /** iOS: under the searching mascot */
    val lookingEllipsis: String

    /** iOS: empty message; scope is the lowercased scope title */
    fun turnOnToSearchScope(scope: String): String

    /** iOS: empty title */
    val noMatchesInPickedPhotos: String

    /** iOS: empty message */
    val onlySeesChosenPhotos: String

    /** iOS: empty title */
    val notQuiteReady: String

    /** iOS: empty message */
    fun nothingLikeThatOn(words: DeviceWords): String

    /** iOS: empty message; scope is a scope title */
    fun nothingInScopeFits(scope: String): String

    /** iOS: empty title */
    fun noStarredMatch(query: String): String

    /** iOS: empty message */
    val onlyStarredShowHere: String

    /** iOS: empty title */
    fun nothingLikePhotoIn(scope: String): String

    /** iOS: empty title */
    fun noMatchesFor(query: String): String

    /** iOS: empty message */
    val tryAllFilesInstead: String

    /** iOS: empty message */
    fun nothingInScopeMatches(scope: String): String

    /** iOS: chip while searching by photo; differs from likeYourPhoto/likeFile (Android) */
    val likeThisPhoto: String

    /** iOS: note over results */
    val photosOffResults: String

    // SettingsView

    /** iOS: disabled button after clearing */
    val cleared: String

    /** iOS: section footer; differs from savedOnDeviceShortcuts (Android) */
    fun savedOnDeviceOnly(words: DeviceWords): String

    /** iOS: dialog title; differs from clearSearchesAndShortcutsAsk (Android) */
    val clearRecentSearchesAsk: String

    /** iOS: How it works paragraph */
    fun privacyBody(words: DeviceWords): String

    /** iOS: How it works paragraph */
    val longFilesBitAtATime: String

    /** iOS: schedule footnote; differs from onlyWhenYouChoose (Android) */
    val onlyWhenYouChooseAnyFile: String

    /** iOS: schedule footnote for Also on battery; "Low Power Mode" is Apple's name (DeviceWords.powerSave); 20 is ReadingPolicy.BATTERY_FLOOR */
    fun alsoOnBatteryIosBody(words: DeviceWords): String

    /** iOS: toggle (before iOS 26) */
    val keepScreenOnToggle: String

    /** iOS: disclosure title */
    fun adjustProcessingFor(words: DeviceWords): String

    /** iOS: disclosure text */
    val runSetupCheckAgain: String

    /** iOS: section footer; today Words.KIND_OFF + this tail */
    val kindsFooterVideos: String

    /** iOS: VoiceOver label; differs from includeNamed (Android) */
    fun includeQuoted(name: String): String

    /** iOS: section footer */
    val doesntLookInThese: String

    /** iOS: section footer */
    val downloadsOnWifiOrData: String

    /** iOS: section footer; differs from downloadsWaitForWifiShort (Android) */
    val downloadsWaitForWifi: String

    /** iOS: fallback when the notices file is missing */
    val noticesUnavailable: String

    // SetupView

    /** iOS: VoiceOver hint on a demo suggestion */
    val searchesForThis: String

    // SplitRoot

    /** iOS: sidebar item (iPad) */
    val searchTab: String

    // StatusView

    /** iOS: section footer */
    val onlySeesPickedPhotos: String

    /** iOS: section footer */
    val photosOffCantSearch: String

    // TextReader

    /** iOS: empty message */
    val notProcessedOpenOriginal: String

    /** iOS: empty title */
    val noTextToShow: String

    /** iOS: empty message */
    val noReadableText: String

    /** iOS: error title */
    val couldntShowText: String

    /** iOS: error message */
    val wentWrongReadingIt: String

    /** iOS: navigation title / icon label */
    val textFromThisFile: String

    /** iOS: note */
    val onlyBeginningIncluded: String

    // Viewer

    /** iOS: PDF badge line (Android uses it inside pdfSubtitle) */
    fun matchOnPage(page: Int): String

    /** iOS: error message; differs from pdfFindableByName (Android) */
    val anotherAppCanAsk: String

    /** iOS: error message */
    val damagedOrUnsupported: String

    /** iOS: error title; differs from couldntPlay (Android says "recording") */
    fun couldntPlayIos(audio: Boolean): String

    /** iOS: error message */
    val iCloudOrCantPlay: String

    /** iOS: VoiceOver label of the photo */
    fun photoSpoken(kind: String, name: String): String

    /** iOS: error message */
    val turnOnToSeePhotos: String

    /** iOS: error message */
    val onlyInICloudNow: String

    /** iOS: progress label */
    val downloadingFromICloud: String
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
