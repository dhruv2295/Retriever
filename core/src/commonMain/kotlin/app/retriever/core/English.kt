package app.retriever.core

import app.retriever.core.Words.NBSP

/** The source language; every other [Strings] translates these. */
internal object English : Strings {
    private fun plural(n: Long, one: String, many: String = "${one}s"): String = "${formatCount(n)} ${if (n == 1L) one else many}"

    override val sentenceGap = " "

    override val modified = "Modified"
    override val taken = "Taken"

    override fun files(n: Long) = plural(n, "file")

    override fun photos(n: Long) = plural(n, "photo")

    override fun videos(n: Long) = plural(n, "video")

    override fun photosAndVideos(n: Long) = "${formatCount(n)} photos and videos"

    override fun readOf(done: Long, total: Long) = "${formatCount(done)} of ${files(total)} processed"

    override fun meaningOf(done: Long, total: Long) = "${formatCount(done)} of ${files(total)} ${if (total == 1L) "has" else "have"} search by description"

    override fun facesOf(done: Long, total: Long) = "${formatCount(done)} of ${photos(total)} checked for people"

    override val processedInPart = "Processed in part"
    override val couldntBeOpened = "Couldn’t be opened"
    override val stillToProcess = "Still to process"
    override val longFilesStillBeingFinished = "Long files still being finished"
    override val checkedForPeople = "Checked for people"
    override val stillToCheckForPeople = "Still to check for people"

    override fun largeFiles(n: Long) = "${files(n)} over 100${NBSP}MB"

    override fun readCount(read: Long) = "${files(read)} processed"

    override fun partlyReadCount(n: Long) = "${formatCount(n)} processed in part"

    override fun extent(extent: ReadExtent?) = when (extent) {
        ReadExtent.FIRST_PART -> "Processed in part · only its beginning"
        ReadExtent.MEANING_LIMITED -> "Processed in part · couldn’t finish search by description"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "Processed in part · only its beginning, without search by description"
        ReadExtent.FIRST_PART_DEEPENING -> "Processed in part · only its beginning so far"
        ReadExtent.DEEPENING -> "Processed in part · search by description still to come"
        ReadExtent.WHOLE, null -> null
    }

    override val couldntProcessLabel = "Couldn’t process"
    override val notProcessedYet = "Not processed yet"
    override val partRestToCome = "Processed in part · the rest is still to come"
    override val partCouldntFinish = "Processed in part · couldn’t finish"

    override fun kindLabel(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "Document"
        Kind.SCREENSHOT -> "Screenshot"
        Kind.PHOTO -> "Photo"
        Kind.AUDIO -> "Audio"
        Kind.VIDEO -> "Video"
        Kind.OTHER -> "File"
    }

    override fun kindLabelPlural(kind: Kind) = when (kind) {
        Kind.PDF -> "PDFs"
        Kind.DOC -> "Documents"
        Kind.SCREENSHOT -> "Screenshots"
        Kind.PHOTO -> "Photos"
        Kind.AUDIO -> "Audio"
        Kind.VIDEO -> "Videos"
        Kind.OTHER -> "Other files"
    }

    override fun kindsInSentence(kind: Kind) = kindLabelPlural(kind).let { if (it.length > 1 && it[1].isUpperCase()) it else it.lowercase() }

    override fun spokenPage(page: Int) = "page $page"

    override fun spokenTime(hours: Long, minutes: Long, seconds: Long): String {
        val parts = listOf(hours to "hour", minutes to "minute", seconds to "second").filter { it.first > 0 }.map { (n, unit) -> plural(n, unit) }
        return "at " + parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    override fun textOnlyNote(ramGb: Int, words: DeviceWords) =
        "This ${words.device} has $ramGb${NBSP}GB of memory, too little for search by description, so Retriever finds files by their names and the " +
            "words inside them."

    override fun notReadYet(files: Long) = "${formatCount(files)} to go"

    override fun couldntProcess(files: Long) = "Couldn’t process ${files(files)}"

    override val details = "Details"
    override val starting = "Getting started…"
    override val processingNow = "Processing now"
    override val recentlyProcessed = "Recently processed"
    override val processingYourFiles = "Processing your files"
    override val pause = "Pause"
    override val processTheseKinds = "Process these file types"

    override val continueAction = "Continue"
    override val tryAgain = "Try again"
    override val download = "Download"
    override val notNow = "Not now"
    override val cancel = "Cancel"
    override val done = "Done"

    override fun helloTitle(textOnly: Boolean) = if (textOnly) "Find files by what’s inside them" else "Find anything by describing it"

    override val helloBody = "Photos, screenshots, documents and videos, in one search."
    override val forExample = "For example"
    override val helloExamples = listOf(
        Words.HelloExample("wifi password the landlord sent", "Screenshot from 14 March", "Says “Wi-Fi password: …”"),
        Words.HelloExample("dog asleep on the sofa", "Photo from last Sunday", "Found by what’s in it", needsModel = true),
        Words.HelloExample("my rent agreement", "Lease 2025.pdf", "Page 3, the signed copy"),
    )
    override val downloads = "Downloads"

    override fun onDevice(words: DeviceWords) = "Your files are processed on your ${words.device}"

    override val getStarted = "Get started"

    override fun downloadNote(size: String) = "Includes a one-time $size download, on Wi-Fi."

    override fun downloadLine(size: String) = "One-time $size download, on Wi-Fi only"

    override val accessTitle = "Choose what Retriever can search"
    override val accessBody = "Pick one or more. You can add more later."
    override val allow = "Allow"
    override val allowAccess = "Allow access"
    override val choose = "Choose"
    override val chooseMore = "Choose more"
    override val settings = "Settings"
    override val documents = "Documents"
    override val includesScreenshots = "Includes screenshots"
    override val allowed = "Allowed"
    override val somePhotos = "Some photos selected"
    override val photosOff = "Turned off. Turn on in Settings."
    override val pickFolders = "Pick folders in Files, like Downloads"

    override fun foldersAdded(n: Long) = "${plural(n, "folder")} added"

    override val androidAccessBody = "So you can find any photo, document or download by describing it."
    override val accessScanning = "Retriever starts scanning your photos and files now, so you can search them."
    override val startScanning = "Start scanning"
    override val allFilesOff = "Turn on “Allow access to manage all files” to search your whole phone, or choose what to include."
    override val includeTitle = "Choose what to include"
    override val sharedFilesAndFolders = "Files and folders on this phone"
    override val photosAndVideosLabel = "Photos and videos"
    override val addFolder = "Add a folder"
    override val nothingSelected = "No files selected yet. Choose photos or a folder to begin."

    override fun wholeDeviceNote(words: DeviceWords) = "Retriever looks inside the files on this ${words.device} so you can find them by what’s in them."

    override val firstLook = "A first look at your recent files"

    override fun firstSearchHint(state: DescriptionState) = if (state == DescriptionState.READY) "Describe a photo or file" else "Search by name or words"

    override fun descriptionLine(state: DescriptionState, percent: Int?) = when (state) {
        DescriptionState.DOWNLOADING -> "Downloading search by description" + (percent?.let { " · $it%" } ?: "")
        DescriptionState.WAITING_FOR_WIFI -> "Waiting for Wi-Fi to download search by description."
        DescriptionState.PREPARING -> "Preparing search by description"
        DescriptionState.FAILED -> "The download of search by description stopped."
        DescriptionState.NO_SPACE -> "Not enough free space to download search by description."
        DescriptionState.READY, DescriptionState.NOT_STARTED, DescriptionState.UNAVAILABLE -> null
    }

    override val descriptionReady = "Search by description is ready"
    override val descriptionReadyBody = "Now describe your recent files to find them."

    override val homeIntroTitle = "What happens next"
    override val homeIntroBody = "Your scan keeps going while you use other apps, with a notification you can pause it from."
    override val gotIt = "Got it"
    override val newFiles = "New photos and files"
    override val newFilesBody = "New files are processed at the times you choose in Settings."
    override val notifyWhenSearchable = "Notify me when everything’s searchable"
    override val caughtUpNoticeTitle = "Everything is searchable"

    override fun caughtUpNotice(files: Long, textOnly: Boolean) = "Retriever has processed all ${files(files)}. " +
        if (textOnly) "Find any of them by name or the words inside." else "Describe anything to find it."

    override fun recentFiles(n: Long) = when (n) {
        0L -> "Your recent files"
        1L -> "Your most recent file"
        else -> "Your ${formatCount(n)} recent files"
    }

    override fun otherFiles(rest: Long?) = when (rest) {
        null -> "The rest of your files"
        1L -> "The other file"
        else -> "The other ${files(rest)}"
    }

    override fun recentFilesBody(state: DescriptionState) = when (state) {
        DescriptionState.READY -> "Find them by describing them."

        DescriptionState.UNAVAILABLE -> "Find them by name or words. Search by description needs a phone with more memory."

        DescriptionState.WAITING_FOR_WIFI -> "Find them by name or words now. Waiting for Wi-Fi to finish a one-time download."

        DescriptionState.FAILED -> "Find them by name or words now. The download stopped."

        DescriptionState.NO_SPACE -> "Find them by name or words now. There isn’t enough free space to download search by description."

        // Not "one-time": after a chip build fails on its first run, this is the second download.
        DescriptionState.NOT_STARTED -> "Find them by name or words now. Search by description needs a download."

        DescriptionState.DOWNLOADING, DescriptionState.PREPARING ->
            "Find them by name or words now. Search by description arrives when setup finishes."
    }

    override fun recentFilesAction(state: DescriptionState, size: String) = when (state) {
        DescriptionState.WAITING_FOR_WIFI -> "Use mobile data ($size)"
        DescriptionState.FAILED -> tryAgain
        DescriptionState.NOT_STARTED -> "Download $size"
        else -> null
    }

    override val demoPicking = "Picking your recent files…"
    override val demoHint = "Type a word you remember from a recent photo, screenshot or document."

    override fun demoSearchScope(done: Long, finished: Boolean, words: DeviceWords) = when {
        finished -> "Finds any file on your ${words.device} by its name, and looks inside the ${plural(done, "demo file")}."

        // The first frame: nothing processed yet, so no "0 demo files".
        done == 0L -> "Finds any file on your ${words.device} by its name."

        else -> "Finds any file on your ${words.device} by its name. Looks inside the ${plural(done, "demo file")} processed so far."
    }

    override fun seeAll(files: Long) = if (files == 1L) "See the file" else "See the ${files(files)}"

    override val read = "Found by what’s inside"
    override val foundByName = "Found by name"
    override val foundBy = "Found by"

    override fun kindRead(read: Long, planned: Long, kind: Kind) =
        "${formatCount(read)} of ${formatCount(planned)} ${kindLabelPlural(kind).let { if (kind == Kind.PDF) it else it.lowercase() }} processed"

    override val tryLabel = "Try:"

    override val startProcessing = "Start processing"

    override val progressChannel = "Processing progress"
    override val progressChannelBody = "Shows how far processing has got, and once when the demo is done."
    override val demoDoneNoticeTitle = "The demo is done"

    override fun demoDoneNotice(done: Long, planned: Long) = "${readOf(done, planned)}. Open Retriever to search them."

    override val searchField = "What are you looking for?"
    override val nameMatches = "Name matches"
    override val textMatches = "Text matches"
    override val searchingContents = "Searching file contents…"

    override fun copies(n: Int) = plural(n.toLong(), "copy", "copies")

    override fun openCopyIn(folder: String) = "Open the copy in $folder"

    override val noMatches = "Nothing found"

    override fun noMatchesDetail(byMeaning: Boolean) =
        (if (byMeaning) "Describe what’s in it, or ask what it says." else "Try a word it contains or from its name.") + " Add when, like “last week”."

    override val moreWaiting = "Retriever is still processing your files, so search again later."
    override val searchFailed = "Search couldn’t finish"
    override val showingNameMatches = "Showing name matches"
    override val searchingWithoutModel = "Searching names and text already processed"

    override fun matches(labels: List<String>) = "Matches: " + labels.joinToString(", ")

    private val monthNames = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )
    private val weekdayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    override fun dateLabel(date: DateFilter) = when (date) {
        DateFilter.Today -> "today"

        DateFilter.Yesterday -> "yesterday"

        is DateFilter.DaysAgo -> "${plural(date.days.toLong(), "day")} ago"

        is DateFilter.Period -> (if (date.last) "last " else "this ") + date.unit.name.lowercase()

        is DateFilter.Weekday -> when (date.which) {
            Which.LAST -> "last "
            Which.THIS -> "this "
            Which.BARE -> ""
        } + weekdayNames[date.day]

        is DateFilter.Month -> monthNames[date.month - 1] + (date.year?.let { " $it" } ?: "")

        is DateFilter.Day -> "${date.day} ${monthNames[date.month - 1].take(3)}" + (date.year?.let { " $it" } ?: "")

        is DateFilter.Year -> "${date.year}"
    }

    override fun noneFound(kinds: List<Kind>, people: List<String>, date: DateFilter?): String {
        val what = kinds.joinToString(" and ", transform = ::kindsInSentence)
        val of = if (people.isEmpty()) "" else " of " + people.joinToString(" and ")
        val from = date?.let { " from ${dateLabel(it)}" }.orEmpty()
        return if (what.isEmpty()) "Nothing$of$from" else "No $what$of$from"
    }

    override fun relaxedNote(missing: String, scope: String?) = "$missing, so here " + if (scope == null) "is everything." else "are all your $scope."

    override fun filterQuery(kind: Kind, date: DateFilter) = "${kindsInSentence(kind).lowercase()} from ${dateLabel(date)}"

    override val downloadForDescription = "Download for search by description"

    override val readingStatus = "Processing"
    override val recentlyAdded = "Recently added"
    override val moreOnPhone = "Browse"
    override val allFolders = "All folders"

    override val howItWorks = "How Retriever works and your privacy"
    override val whenToRead = "When to process"
    override val whatItCanSee = "What Retriever can see"
    override val foldersToSkip = "Folders to skip"
    override val kindOff = "Turned off: Retriever still finds these by name, but forgets what’s inside them. Your files aren’t touched."
    override val showOnThisDay = "Show On this day (photos from this date in past years)"

    override val people = "People"
    override val recognizePeople = "Recognize people"
    override val peopleOfferTitle = "Find photos of people"
    override val peopleOfferBody =
        "Retriever can group the faces in your photos, so you can name someone and find them by name. Faces are read on this phone and never leave it."
    override val turnOn = "Turn on"
    override fun wholeDeviceTitle(words: DeviceWords) = "Search your whole ${words.device}"
    override val wholeDeviceBody = "Also finds files in Downloads, WhatsApp and your other apps’ folders."
    override val allowAllFiles = "Allow access to all files"
    override val changeWhatsIncluded = "Change what’s included"
    override val allFilesChoice = "All files"
    override val recommended = "Recommended"
    override val allFilesChoiceBody = "Photos, documents, downloads and files other apps keep. The best results."
    override val orOnly = "Or only"
    override fun peopleChoiceBody(words: DeviceWords) = "Groups faces so you can search by name. Stays on this ${words.device}."
    override fun peopleRowBody(words: DeviceWords) = "Find photos by who’s in them. Faces are grouped on this ${words.device}, after your files."
    override val recognizePeopleDetail =
        "Groups the faces in your photos so you can name people and find them by name. Processed on this phone, never backed up."
    override val forgetPeopleTitle = "Forget every face and name?"
    override val forgetPeopleBody = "Retriever forgets the faces it grouped and the names you gave. Your photos aren’t touched."
    override val forget = "Forget"
    override val notThisPerson = "Not this person"
    override val addAName = "Add a name"
    override val name = "Name"
    override val save = "Save"
    override val samePersonAs = "Same person as…"
    override val samePersonAsk = "Same person?"
    override val samePersonYes = "Same person"
    override val samePersonNo = "Not the same"
    override val samePersonPrevious = "Previous pair"
    override val samePersonNext = "Next pair"

    override fun pairOf(at: Int, of: Int) = "$at of $of"

    override fun facesToGo(left: Long) = "${photos(left)} left to check for people"

    override val retrieversData = "Retriever’s data"
    override val temporaryFiles = "Temporary files"

    override fun keptOn(size: String, words: DeviceWords) = "$size · kept on this ${words.device}"

    override val searchByMeaning = "Search by description"
    override val removeDownload = "Remove search download"
    override val removeDownloadAsk = "Remove the search download?"
    override val namesStillWork = "File names and text already processed still work."

    override fun downloaded(size: String) = "Downloaded · $size"

    override fun removeDownloadMessage(size: String?) =
        (if (size != null) "This frees $size. " else "") + "$namesStillWork Search by description needs the download again."

    override val freeUpSpace = "Free up space"
    override val largeFilesTitle = "Large files"
    override val findDuplicates = "Find duplicate files"
    override val neverDeletes = "Retriever never deletes anything without asking you first."

    override fun possibleCopies(n: Long) = "${files(n)} might be copies"

    override fun deleteFrom(words: DeviceWords) = "Delete from ${words.device}"

    override fun deleteConfirm(words: DeviceWords) = "This deletes the file from your ${words.device}."

    override val upgradeTitle = "Find files by describing them"
    override val waitingForWifi = "Waiting for Wi-Fi to download"
    override val useMobileData = "Use mobile data"
    override val waitForWifi = "Wait for Wi-Fi"
    override val checkingDownload = "Checking the download…"
    override val downloadFailed = "Download interrupted. Check your connection and try again."
    override val noSpace = "Not enough free space to download"
    override val tuneLeaving = "You can search names now. Setting up resumes when you return."

    override fun upgradeBody(size: String, words: DeviceWords) = "One download of $size on Wi-Fi. " +
        "Then a search like ‘sunset at the beach’ finds photos even without those words. Everything stays on this ${words.device}."

    override fun downloadProgress(done: String, total: String, percent: Int) = "$done of $total · $percent%"

    override fun mobileDataAsk(size: String) = "Download $size using mobile data?"

    override fun freeUp(size: String) = "Free up $size to continue"

    override fun tuneTitle(words: DeviceWords) = "Setting up processing for this ${words.device}"

    override fun tuneBody(words: DeviceWords) = "About a minute. Finding a processing speed that suits this ${words.device}."

    override fun tuneFailed(words: DeviceWords) = "Couldn’t finish setting up processing for this ${words.device}."

    override fun addingMeaning(done: Long, total: Long) = "Adding search by description · ${formatCount(done)} of ${files(total)}"

    override val meaningCatchingUp = "Search by description works now. Files processed before it was ready get it as Retriever carries on."

    override fun scheduleTitle(schedule: ReadingSchedule) = when (schedule) {
        ReadingSchedule.CHARGING -> "While charging"
        ReadingSchedule.BATTERY -> "Also on battery"
        ReadingSchedule.MANUAL -> "When I start it"
    }

    override fun statusAction(action: StatusAction) = when (action) {
        StatusAction.CONTINUE -> continueAction
        StatusAction.ALLOW_ACCESS -> allowAccess
        StatusAction.STOP -> "Stop"
        StatusAction.RESUME -> "Resume"
        StatusAction.DISMISS -> "Dismiss"
        StatusAction.TRY_AGAIN -> tryAgain
        StatusAction.START -> startProcessing
        StatusAction.PAUSE -> pause
    }

    override fun stopCopy(stop: StopReason, words: DeviceWords) = when (stop) {
        StopReason.PAUSED -> "Paused"
        StopReason.POWER_SAVE -> "Paused · continues when ${words.powerSave} is off"
        StopReason.HOT -> "Paused · continues when your ${words.device} cools down"
        StopReason.LOW_BATTERY, StopReason.UNPLUGGED -> "Paused · continues when you plug in"
        StopReason.CHARGING_UP -> "Paused · continues when the battery has a bit more charge"
        StopReason.MANUAL -> "Processing only when you start it"
        StopReason.DISK_FULL -> "Paused, your ${words.device} is full"
        StopReason.DONE, StopReason.BUDGET -> "Taking a short break"
    }

    override val noAccessTitle = "Retriever can’t see your files"

    override fun noAccessDetail(words: DeviceWords) =
        "Search still works on the files it has already processed." + if (words.ios) " This opens your ${words.device}’s Settings." else ""

    override val finishSetupTitle = "Finish setting up"
    override val finishSetupDetail = "Choose what Retriever can look through."
    override val downloadPausedTitle = "Download paused"

    override fun downloadPausedDetail(words: DeviceWords, size: String) =
        "It carries on when you’re on Wi-Fi. If your ${words.device} is full, it needs $size free."

    override fun downloadingTitle(percent: Int?) = "Downloading search by description" + (percent?.let { " · $it%" } ?: "")

    override val downloadingDetail = "You can keep using Retriever."

    override val addingDescriptionTitle = "Adding search by description"
    override val allFilesProcessedTitle = "All files processed"
    override val gettingReadyTitle = "Getting ready…"
    override val notNowTitle = "Only names can be found for now"

    override val lookingForFacesTitle = "Finding people in your photos"
    override val deepeningTitle = "Processing the rest of some long files"
    override val lookingTitle = "Checking for new files…"
    override val caughtUpTitle = "All done. Retriever has processed everything it can"

    override val processingReadyDetail = "Your progress is saved. You can start now, or processing will continue when conditions allow."
    override val keepGoingInBackground = "Keep going in the background"
    override val processingAdaptiveDetail = "Processing adapts to keep your phone responsive."
    override val processingBatteryOverrideDetail = "Processing runs on battery down to ${ReadingPolicy.RESUMED_BATTERY_FLOOR}%."

    override val processingKeepPaused = "Keep paused"
    override val processingSearchAvailableDetail =
        "Search is available now. File names and content already processed remain searchable."
    override val processingPlugInDetail =
        "Plug in to keep processing. Retriever processes on battery down to ${ReadingPolicy.RESUMED_BATTERY_FLOOR}%."
    override val processingTapToContinue = "Tap to continue"

    override val automaticChargingPreferenceDetail = "Between scans, Retriever waits for charging. Resume lets you process on battery this time."

    override val automaticProcessingPreferenceTitle = "When to process"
    override val automaticProcessingPreferenceDetail =
        "Between scans, processing on battery stops below ${ReadingPolicy.BATTERY_FLOOR}%. A scan you start or resume runs down to " +
            "${ReadingPolicy.RESUMED_BATTERY_FLOOR}%."

    override val pausedDetail = "Search still works."

    override fun powerSaveDetail(words: DeviceWords) = "Carries on when ${words.powerSave} is off."

    override val hotDetail = "Carries on by itself."
    override val lowBatteryDetail = "Plug in and Retriever carries on."
    override val chargingUpDetail = "Processing starts once your battery has a bit more charge."

    override fun chargingDetail(words: DeviceWords) = "Retriever carries on when your ${words.device} is charging."

    override val manualDetail = "You can find any file by its name."

    override fun meaningDetail(words: DeviceWords) = "So you can search by what things mean, and by what’s in your photos. On this ${words.device} only."

    override val peopleDownloadDetail = "A one-time download for People. Your files are all searchable."
    override val facesDetail = "An optional extra step. Your files are all searchable, and nothing is processed again."

    override fun facesWaitingDetail(words: DeviceWords) = "Retriever finds people when your ${words.device} allows. Your files are all searchable."

    override val deepeningDetail = "You can find them by their beginning."
    override val limitedDetail = "A few long files were processed only in part. You can find them by their beginning."
    override val readingMeaningDetail = "Also adding search by description."

    override fun readingDetail(words: DeviceWords) = if (words.ios) {
        "Search works the whole time. Retriever processes your files while it’s open, and sometimes when iOS allows."
    } else {
        "You can close Retriever; it carries on. Search works the whole time."
    }

    override fun waitingLongFilesDetail(words: DeviceWords) =
        "Retriever processes the rest of some long files when your ${words.device} allows. Search works now."

    override fun waitingMeaningDetail(words: DeviceWords) = "Retriever adds search by description when your ${words.device} allows. Search works now."

    override val diskFullDetail = "Free up some space and Retriever carries on."

    override fun waitingDetail(words: DeviceWords) = "Your ${words.device} decides when Retriever carries on. Nothing is lost."

    override val caughtUpDetail = "New files are processed at the times you choose in Settings."

    override val gettingReadersDetail = "A one-time download from Google Play services. Search by name works now."

    override val peopleWaitsForWifi = "People's download waits for Wi-Fi."

    override fun deepeningLead(left: Long) = "${notReadYet(left)}."

    override fun longFilesWait(n: Long) = "All files processed. The rest of ${plural(n, "long file")} waits."

    override val allFilesProcessedLead = "All files processed."

    override val foundByOther = "Its name. Retriever can’t look inside this kind of file yet."
    override val foundByBeginningRestPending = "Name and its beginning. The rest is not processed yet."
    override val foundByPartCouldntFinish = "Name and part of what’s inside. Retriever couldn’t finish processing it."
    override val foundByBeginning = "Name and its beginning"
    override val foundByInsideMeaningLimited = "Name and what’s inside. Search by description couldn’t finish."
    override val foundByInsideDeepening = "Name and what’s inside. Search by description is still to come."
    override val foundByInside = "Name and what’s inside"
    override val foundByReadingOff = "Its name. Processing is off for this kind of file."

    override fun foundBySkipped(tries: Long) = "Its name. Retriever couldn’t process it after ${plural(tries, "try", "tries")}."

    override val foundByNothingInside = "Its name. Nothing inside could be processed."
    override val foundByNotYet = "Its name. Not processed yet."

    override val processingFailed = "Something went wrong processing your files. Retriever will try again."

    // FileActions
    override val fileNameField = "Name"

    // Notifications
    override fun notificationBody(count: String?, detail: String?, read: Long) = listOfNotNull(count, detail).joinToString(". ").ifEmpty { readCount(read) }

    // SearchResults
    override fun understoodChip(labels: List<String>) = labels.joinToString(" · ")

    override fun noMatchesMessage(byMeaning: Boolean, waiting: Boolean, wordsOnly: Boolean) =
        listOfNotNull(noMatchesDetail(byMeaning), moreWaiting.takeIf { waiting }, searchingWithoutModel.takeIf { wordsOnly }).joinToString(" ")

    override fun reasonLine(parts: List<String>) = parts.joinToString(" · ")

    // SearchScreen
    override val appSettings = "Settings"

    // SettingsScreen
    override fun scheduleSummary(title: String, body: String) = "$title. $body"

    override val sizePending = "…"

    // FirstRunStages
    override fun folderNames(names: List<String>) = names.joinToString(" · ")

    // AppModel+Setup
    override fun kindsReadSpoken(parts: List<String>) = parts.joinToString(", ")

    // SearchModel
    override val scopePhotos = "Photos"

    override val scopeDocuments = "Documents"

    // HomeView
    override fun titleDetailSpoken(title: String, detail: String?) = listOfNotNull(title, detail).joinToString(", ")

    // ResultViews
    override fun resultRowSpoken(kind: String, name: String, date: String, position: String?, extent: String?, reason: String?) =
        listOfNotNull(kind, name, date, position, extent, reason).joinToString(", ")

    // Access
    override val onlyPickedPhotos = "Only the photos you picked"

    override val musicAndRecordings = "Music and recordings"

    override fun notCoveredMedia(photos: Boolean, videos: Boolean, music: Boolean) = listOfNotNull(
        "photos".takeIf {
            photos
        },
        "videos".takeIf { videos },
        "music".takeIf { music },
    ).let { m ->
        "Not covered: " +
            if (m.size == 1) m[0] else m.dropLast(1).joinToString(", ") + " and " + m.last()
    }

    override fun notCoveredDocuments(hasFolders: Boolean) =
        if (hasFolders) "Not covered: documents and downloads outside the folders you added" else "Not covered: documents and downloads"

    override val pickFolderOnPhone = "Pick a folder on this phone's storage."

    override val alreadySearchesFolder = "Retriever already searches that folder."

    /** "photos, videos and 2 folders" */
    private fun granted(photos: Boolean, videos: Boolean, music: Boolean, folders: Int) = listOfNotNull(
        "photos".takeIf { photos },
        "videos".takeIf { videos },
        "music".takeIf { music },
        plural(folders.toLong(), "folder").takeIf { folders > 0 },
    ).let { m -> if (m.size <= 1) m.firstOrNull().orEmpty() else m.dropLast(1).joinToString(", ") + " and " + m.last() }

    override fun canSeeOnly(photos: Boolean, videos: Boolean, music: Boolean, folders: Int) =
        "Retriever can see your ${granted(photos, videos, music, folders)}."

    override fun seesPartOf(photos: Boolean, videos: Boolean, music: Boolean, folders: Int, words: DeviceWords) =
        "${granted(photos, videos, music, folders).replaceFirstChar(Char::uppercase)}, not your whole ${words.device}"

    override fun seesWholeDevice(words: DeviceWords) = "Your whole ${words.device}"

    override fun searchedOnly(photos: Boolean, videos: Boolean, music: Boolean, folders: Int) =
        "Searched your ${granted(photos, videos, music, folders)} only. Downloads and files in other apps weren’t included."

    // AccessService
    override val couldntKeepFolder = "Couldn't keep access to that folder. Try picking it again."

    // BrowseScreen
    override val allFiles = "All files"

    override val filesRetrieverCanAccess = "Files Retriever can access"

    override val sortLargestDetail = "Largest first"

    override val sortNameDetail = "By name, A to Z"

    override val sortNewestDetail = "Newest first · by date modified"

    override val couldntChangePerson = "Couldn't change this person. Try again."

    override val back = "Back"
    override val home = "Home"
    override val fullScreen = "Full screen"
    override val showBesideList = "Show beside the list"
    override val searchYourFiles = "Search Your Files"

    override val couldntLoadYourFiles = "Couldn't load your files"

    override val wentWrongReadingThem = "Something went wrong reading them. Give it another go."

    override val keepUsefulFilesClose = "Keep useful files close"

    override val tapStarToFindHere = "Tap the star on a file to find it here."

    override val chooseFilesToSearch = "Choose files to search"

    override val addPhotosOrFolder = "Add photos or a folder."

    override val manageAccess = "Manage access"

    override val nothingHereYet = "Nothing here yet"

    override fun filesShowUpHere(words: DeviceWords) = "Files show up here once Retriever finds them on your ${words.device}."

    override val moreOptions = "More options"

    override val couldntLoadMoreFiles = "Couldn't load more files"

    override val sortNewest = "Newest"

    override val sortLargest = "Largest"

    override val sortName = "Name"

    // BrowseViewModel
    override val couldntLoadTheseFiles = "Couldn't load these files."

    // PersonActions
    override val rename = "Rename"

    // DuplicatesScreen
    override val duplicates = "Duplicates"

    override val keepOneCopy = "Keep at least one copy of each file"

    override fun filesCouldntBeDeleted(n: Long) = "${formatCount(n)} files couldn't be deleted"

    override fun deleteSelected(n: Long, size: String) = "Delete ${formatCount(n)} selected ($size)"

    override val lookingForCopiesEllipsis = "Looking for copies…"

    override val couldntLookForDuplicates = "Couldn't look for duplicates"

    override val noDuplicatesFound = "No duplicates found"

    override fun comparedFiles(n: Long) = "Retriever compared ${formatCount(n)} files."

    override fun couldBeFreed(size: String) = "$size could be freed"

    override fun copiesEach(n: Long, size: String) = "${formatCount(n)} copies · $size each"

    override fun openNamed(name: String) = "Open $name"

    override val deselect = "Deselect"

    override val select = "Select"

    override val deleteFilesTitle = "Delete files?"

    override fun deleteFilesMessage(n: Long) = "Delete ${formatCount(n)} files? This can't be undone."

    override val delete = "Delete"

    // DuplicatesViewModel
    override val somethingWentWrongTryAgain = "Something went wrong. Try again."

    // FileActions
    override val addToStarred = "Add to Starred"

    override val share = "Share"

    override val openInAnotherApp = "Open in another app"

    override val fileInfo = "File info"

    override val removeFromStarred = "Remove from Starred"

    override val topLevel = "Top level"

    override val couldntChangeStar = "Couldn't change the star. Try again."

    override val findSimilar = "Find similar"

    override val couldntFinishThat = "Couldn't finish that. The file may have moved or access may have changed."

    override val renameFile = "Rename file"

    override val deleteThisFile = "Delete this file?"

    // FileInfo
    override fun kindAndSize(kind: String, size: String) = "$kind · $size"

    override val locationLabel = "Location"

    override val hideFullPath = "Hide full path"

    override val showFullPath = "Show full path"

    override val locationDetails = "Location details"

    override val pathClipLabel = "Path"

    override val copyPath = "Copy path"

    override val dimensionsLabel = "Dimensions"

    override fun dimensions(width: Int, height: Int) = "$width × $height"

    override val durationLabel = "Duration"

    override val pagesLabel = "Pages"

    override val internalStorage = "Internal storage/"

    // FoldersScreen
    override val folders = "Folders"

    override val foldersDidntLoad = "Folders didn't load"

    override val noFoldersYet = "No folders yet"

    override val tryAgainInAMoment = "Try again in a moment."

    override val foldersAppearPhone = "Folders appear once Retriever has looked through your phone."

    override fun stopSearchingAsk(name: String) = "Stop searching $name?"

    override fun stopSearchingMessage(n: Long, name: String) =
        "The ${files(n)} in $name are removed from search now, and bringing them back means processing them all again. " +
            "You can bring it back from the status line at the top of Home."

    override val stopSearching = "Stop searching"

    override fun filesInParent(n: Long, parent: String?) = if (parent == null) files(n) else "${files(n)} · $parent"

    override fun moreOptionsFor(name: String) = "More options for $name"

    override val stopSearchingThisFolder = "Stop searching this folder"

    // FoldersViewModel
    override val couldntLoadYourFolders = "Couldn't load your folders. Try again in a moment."

    override val couldntStopSearchingFolder = "Couldn't stop searching that folder. Try again in a moment."

    // HomeScreen
    override val onThisDayHidden = "On this day hidden"

    override val undo = "Undo"

    override val showProcessingDetails = "Show processing details"

    override val lookingForYourFiles = "Looking for your files"

    override val nothingToSearchYet = "Nothing to search yet"

    override val takesAMinute = "This takes a minute. You can search by name as soon as they turn up."

    override val saveAPdfHint = "Save a PDF, take a screenshot or a photo, and it'll show up here."

    // HomeSections
    override val showAll = "Show all"

    override fun sectionSpoken(title: String, subtitle: String) = "$title, $subtitle"

    override val openAction = "Open"

    override fun personTileSpoken(name: String, photos: Long) = "$name, ${photos(photos)}"

    override fun browseTileSpoken(label: String, count: String) = "$label, $count"

    override val onThisDay = "On this day"

    override fun onThisDaySpoken(year: Int, count: String, date: String) = "$year, $count from $date"

    override val showThem = "Show them"

    override val hideOnThisDay = "Hide On this day"

    override val starredSection = "Starred"

    // ModelDownload
    override val searchModelDownloadTitle = "Retriever search model"

    // ModelRepository
    override val downloadStopped = "The download stopped. Check your connection and try again."

    override val downloadDamaged = "The download was damaged. Try again."

    override fun needsFreeSpace(gb: String) = "Needs $gb GB free. Free up some space, then try again."

    // SearchResults
    override fun likeFile(name: String) = "Like $name"

    override val photoYoureSearchingWith = "The photo you're searching with"

    override val stopSearchingByPhoto = "Stop searching by photo"

    override val searchWordsAsTyped = "Search the words as typed instead"

    override val photoSearchAfterSetup = "Search by photo works once setup finishes."

    override val matchingExactWordsForNow = "Matching exact words for now. Results by description will be back shortly."

    override fun noMatchesIn(scope: String) = "No matches in your $scope"

    override val nothingLikePhotoThere = "Nothing like this photo there."

    override fun nothingForThere(query: String) = "Nothing for “$query” there."

    override val searchAllFiles = "Search all files"

    override val nothingLikeThisYet = "Nothing like this yet"

    override val tryAnotherPhoto = "Try another photo."

    override val tryFewerWords = "Try fewer words"

    override val filterAll = "All"

    override val filterStarred = "Starred"

    override val expanded = "Expanded"

    override val collapsed = "Collapsed"

    override fun lessLikelyMatches(n: Long) = "Less likely matches (${formatCount(n)})"

    override val moreDocuments = "More documents"

    override val recordings = "Recordings"

    override val documentsAndMore = "Documents and more"

    // SearchScreen
    override val searchWithPhoto = "Search with a photo"

    override val clearSearch = "Clear search"

    override val recentSearches = "Recent searches"

    override fun removeFromHistory(query: String) = "Remove $query from history"

    override val searchAction = "Search"

    override val clearHistory = "Clear history"

    // SearchViewModel
    override val likeYourPhoto = "Like your photo"

    override val couldntOpenPhoto = "Couldn't open this photo. Choose another one."

    override val thatDidntWork = "That didn't work. Try again."

    // SettingsScreen
    override val includedFilesFooter = "$kindOff A folder to skip is left out of search."

    override val onlyWhenYouChoose = "Only when you start or resume processing."

    override val showProcessingProgress = "Show processing progress"

    override val processingProgressNotice = "Turn on one silent notification to see progress while Retriever processes your files."

    override val notificationsOn = "On"

    override val includedFiles = "Included files"

    override val noneExcluded = "None"

    override fun includeNamed(folder: String) = "Include $folder"

    override val includeFolder = "Include folder"

    override val privacy = "Privacy"

    override val saveRecentSearches = "Save recent searches"

    override val savedOnDeviceShortcuts = "Saved on this device. Also used for search shortcuts."

    override val clearRecentSearches = "Clear recent searches"

    override val clearSearchesAndShortcutsAsk = "Clear recent searches and shortcuts?"

    override val clear = "Clear"

    override val storage = "Storage"

    override val appearance = "Appearance"

    override val wallpaperColours = "Wallpaper colours"

    override val wallpaperColoursOff = "Off uses Retriever's warm amber."

    override val about = "About"

    override val version = "Version"

    override val openSourceNotices = "Open-source notices"

    override val removeDownloadConfirm = "Remove download"

    override fun howItWorksBody(words: DeviceWords) = "${wholeDeviceNote(
        words,
    )} ${onDevice(
        words,
    )}. Search and text-recognition models may need an internet download. Your file provider may also use the internet to open cloud files."

    override val howItWorksTimes =
        "It processes your files at the times you choose under $automaticProcessingPreferenceTitle. Long files are processed a bit at a time: you " +
            "can find them by their beginning first."

    override val runSetupCheck = "Run setup check"

    override val tuningFewMinutes = "Tuning… a few minutes at most"

    override fun tuneFailedMessage(message: String) = "Tune failed: $message"

    override val processingIsSetUp = "Processing is set up"

    override val adjustsProcessingSpeed = "Adjusts processing speed to this phone."

    override val useMobileDataForDownloads = "Use mobile data for downloads"

    override val downloadsWaitForWifiShort = "Off: downloads wait for Wi-Fi."

    override val loading = "Loading…"

    // SettingsViewModel
    override val couldntSaveChange = "Couldn’t save this change. Try again."

    override val searchDownloadRemoved = "Search download removed"

    // Starring
    override val starAdded = "Starred"

    override val starRemoved = "Star removed"

    // StatusScreen
    override val checkingYourFiles = "Checking your files…"

    override val hide = "Hide"

    override val reviewFiles = "Review files"

    override val letProcessWhileAway = "Let Retriever process files while you’re not using it"

    override fun batteryMayStop(chargingOnly: Boolean) =
        "Your phone may stop it to save battery." + sentenceGap + if (chargingOnly) automaticChargingPreferenceDetail else automaticProcessingPreferenceDetail

    override val settingsRowDetail = "What to process, storage and more"

    override val chooseMorePhotos = "Choose more photos"

    override val foldersYouAdded = "Folders you added"

    override fun removeNamed(name: String) = "Remove $name"

    override val allowAccessToAllFiles = "Allow access to all files"

    // Format
    override fun starredScope(kind: String?) = kind?.let { "starred $it" } ?: starredFiles

    override fun pageBadge(page: Int) = "Page $page"

    // FileRow
    override fun openAtPage(page: Int) = "Open at page $page"

    override fun playFrom(time: String) = "Play from $time"

    override fun kindSizeDate(kind: String, size: String, date: String) = "$kind · $size · $date"

    override fun fileRowSpoken(kind: String, name: String, date: String, position: String?, note: String?) =
        "$kind, $name, $date" + (position?.let { ", $it" } ?: "") + (note?.let { ", $it" } ?: "")

    // ReadingFiles
    override val fileActions = "File actions"

    override fun fileSpoken(name: String, kind: String, date: String) = "$name, $kind, $date"

    // DocScreen
    override val notProcessedThisYet = "Retriever hasn't processed this yet"

    override val waitingItsTurn = "It's waiting its turn with the other new files. Open it in another app to see it now."

    override val noTextFound = "Retriever couldn't find text in this file"

    override val scanOrPictures = "It may be a scan or hold only pictures. Open it in another app to see it."

    override val couldntOpenFile = "Couldn't open this file"

    override val maybeMovedOrDeleted = "It may have been moved or deleted."

    override val previousMatch = "Previous match"

    override fun matchOf(at: Int, total: Int) = "Match $at of $total"

    override fun matchCount(n: Long) = "${formatCount(n)} matches"

    override val nextMatch = "Next match"

    // Opener
    override val noAppOpensFile = "No app on this phone opens this file"

    // PdfPageScreen
    override fun pdfSubtitle(page: Int, count: Int, matchedPage: Int?) = (matchedPage?.let { "${matchOnPage(it)} · " } ?: "") + pageOf(page, count)

    override val closePage = "Close page"

    override val pdfNeedsPassword = "This PDF needs a password"

    override val pdfFindableByName = "Retriever can still find it by name. You can open it in another app."

    override val folderAccessChanged = "Access to this folder has changed"

    override val chooseFolderAgain = "Choose the folder again to open this file."

    override val fileNoLongerAvailable = "This file is no longer available"

    override val goBack = "Go back"

    override val couldntOpenPdf = "Couldn't open this PDF"

    override fun pageZoomed(page: Int) = "Page $page, zoomed"

    override fun pageOf(page: Int, count: Int) = "Page $page of $count"

    override val zoomPage = "Zoom page"

    override fun pageSpoken(label: String, matched: Boolean) = label + if (matched) ", where your search matched" else ""

    override val matchedHere = "Matched here"

    // PhotoScreen
    override val couldntShowPhoto = "Couldn't show this photo"

    // PlayerScreen
    override fun matchAt(time: String) = "Match at $time"

    override fun couldntPlay(audio: Boolean) = "Couldn't play this ${if (audio) "recording" else "video"}"

    override val maybeMovedOrCantPlay = "It may have been moved or deleted, or this phone can't play it."

    // ViewerQueue
    override fun positionOf(at: Int, count: Int) = "$at of $count"

    // ViewerTopBar
    override val previousFile = "Previous file"

    override val nextFile = "Next file"

    // AppModel+Loop
    override fun keepProcessingFailed(words: DeviceWords) =
        "iOS couldn’t keep processing while you weren’t using Retriever. Keep it open, or let it process your files while your ${words.device} charges."

    // AppModel+Settings
    override val couldntChangePersonCurly = "Couldn’t change this person. Try again."

    // FolderSource
    override val aFolder = "A folder"

    // SearchModel
    override val starredFiles = "starred files"

    // Copy
    override val photosAccessOff = "Photos access is off"

    override val photosOffNoteDetail = "Search still works on files already processed. Turn it on to search your photos and screenshots too."

    override val seesOnlyPickedPhotos = "Retriever sees only the photos you picked"

    override val canSearchJustThose = "It can search just those."

    override val folderAdded = "Folder added. Retriever will process what's inside."

    override fun folderOverlaps(name: String) = "That folder overlaps “$name”, which Retriever already searches."

    override val folderRefused = "iOS wouldn't let Retriever keep that folder. Try another one."

    override fun backgroundNote(words: DeviceWords) =
        "Retriever processes your files while it’s open, and sometimes while you’re not using it, when iOS allows. " +
            "Automatic background processing waits until " +
            "your ${words.device} is charging. Starting or resuming processing uses some battery."

    override val screenKeptOn = "Your screen stays on while Retriever processes your files. You can turn this off in Settings."

    override fun showingLabels(labels: List<String>) = "Showing " + labels.joinToString(" · ")

    override val searchMyExactWords = "Search my exact words"

    override fun processedSoFar(n: Long) = "${readCount(n)} so far"

    // Format
    override fun opensAtPage(page: Int) = "Opens at page $page"

    override fun playsFrom(time: String) = "Plays from $time"

    override val opensHint = "Opens"

    // BrowseView
    override val couldntLoadFiles = "Couldn't load files"

    override val wentWrongReadingList = "Something went wrong reading the list."

    override fun sizeFolderDate(size: String, folder: String, date: String) = "$size · $folder · $date"

    override val sortBy = "Sort by"

    override val sortMenu = "Sort"

    override val ok = "OK"

    // DuplicatesView
    override val noDuplicatesMessage = "Retriever checked the files in your added folders and found no copies bigger than 1 MB. Photos aren't checked."

    override val lookingForCopies = "Looking for copies"

    override val thisCanTakeAMinute = "This can take a minute."

    override fun deleteFilesAsk(n: Long) = if (n == 1L) "Delete 1 file?" else "Delete ${formatCount(n)} files?"

    override val cantBeRecovered = "They can't be recovered."

    override val comparesFilesNote = "Compares files in the folders you added, not photos. The oldest copy of each file is kept. Tap a copy to change " +
        "what goes."

    override fun folderAndDate(folder: String, date: String) = "$folder · $date"

    override val selectedToDeleteHint = "Selected to delete. Double-tap to keep it."

    override val keptHint = "Kept. Double-tap to select it for deleting."

    override fun filesWerentDeleted(n: Long) = if (n == 1L) "1 file wasn't deleted" else "${formatCount(n)} files weren't deleted"

    // FileActions
    override val couldntOpenFolderUnavailable = "Couldn't open this file. Its folder may be unavailable."

    override val photosAppName = "Photos"

    override val moreActions = "More actions"

    override val couldntFinish = "Couldn't finish"

    // FoldersView
    override val foldersWithFiles = "Folders with files"

    override val couldntLoadFolderList = "Couldn't load the folder list. Try again"

    override val removeFolder = "Remove"

    override val cantOpenFolderAnyMore = "Retriever can't open this folder any more. Remove it, then add it again."

    override val alsoSearchesOwnFolder = "Retriever also searches its own folder in the Files app. Removing a folder here never deletes it."

    override val couldntLoadFolders = "Couldn't load folders"

    override val wentWrongReadingFolders = "Something went wrong reading your folders."

    override val foldersAppearFiles = "Folders appear once Retriever has looked through your files."

    override fun stopSearchingForget(n: Long, name: String) = "Retriever will forget the ${files(n)} in $name. You can bring it back here."

    override fun removeFolderAsk(name: String) = "Remove $name?"

    override val removeFolderMessage = "Retriever will stop searching it and forget what it processed there. The folder and its files stay where they are."

    // HomeSections
    override val showsAll = "Shows all"

    override val showsThem = "Shows them"

    override val hideOnThisDayIos = "Hide On This Day"

    override fun homeTileSpoken(kind: String, name: String, date: String, starred: Boolean) = "$kind, $name, $date" + if (starred) ", starred" else ""

    // HomeView
    override val addFolderOrPhotos = "Add a folder, or let Retriever see your photos, and it'll find what's inside them."

    override val allowPhotosAccess = "Allow Photos access"

    override val turnOnPhotosAccess = "Turn on Photos access"

    override val onThisDayTitle = "On This Day"

    override val openSettings = "Open Settings"

    override val alsoSearchesFolders = "Retriever also searches folders you add."

    // ReadingFiles
    override val openFileHint = "Open file"

    // ResultViews
    override fun sharedPhotoName(ext: String?) = "Photo.${ext ?: "jpg"}"

    // RootView
    override val setupTitle = "Setup"

    // SearchView
    override val docsShort = "Docs"

    override val searchByPhoto = "Search by photo"

    override val removeSearch = "Remove"

    override val removeFromRecentSearches = "Remove from recent searches"

    override val somethingWentWrongSearching = "Something went wrong searching."

    override val lookingEllipsis = "Looking…"

    override fun turnOnToSearchScope(scope: String) = "Turn it on in Settings, and Retriever can search your $scope too."

    override val noMatchesInPickedPhotos = "No matches in the photos you picked"

    override val onlySeesChosenPhotos = "Retriever can only see the photos you chose. Pick more to search more of them."

    override val notQuiteReady = "Not quite ready"

    override fun nothingLikeThatOn(words: DeviceWords) = "Nothing like that on this ${words.device} yet."

    override fun nothingInScopeFits(scope: String) = "Nothing in $scope fits. Try all your files."

    override fun noStarredMatch(query: String) = "No starred file matches “$query”"

    override val onlyStarredShowHere = "Only files you've starred show here. Try all your files."

    override fun nothingLikePhotoIn(scope: String) = "Nothing like this photo in $scope"

    override fun noMatchesFor(query: String) = "No matches for “$query”"

    override val tryAllFilesInstead = "Try all your files instead."

    override fun nothingInScopeMatches(scope: String) = "Nothing in $scope matches. Try all your files."

    override val likeThisPhoto = "Like this photo"

    override val photosOffResults = "Photos access is off, so these results leave out your photos."

    // SettingsView
    override val cleared = "Cleared"

    override fun savedOnDeviceOnly(words: DeviceWords) = "Saved on this ${words.device} only. Nothing is sent anywhere."

    override val clearRecentSearchesAsk = "Clear recent searches?"

    override fun privacyBody(words: DeviceWords) =
        "Retriever processes your files on this ${words.device}. Your searches, stars and what it finds inside stay here. Downloading search by " +
            "description and opening files stored in iCloud may use the internet."

    override val longFilesBitAtATime = "Long files are processed a bit at a time: you can find them by their beginning first, and the rest follows."

    override val onlyWhenYouChooseAnyFile = "Only when you start or resume processing. You can always find any file by its name."

    override val keepScreenOnToggle = "Keep the screen on while processing"

    override fun adjustProcessingFor(words: DeviceWords) = "Adjust processing for this ${words.device}"

    override val runSetupCheckAgain = "Run the setup check again."

    override val kindsFooterVideos = "$kindOff Videos: pictures only, no sound."

    override val photosLibrary = "Photos library"

    override val foldersFromFiles = "Folders from Files"

    override val addFoldersFooter = "PDFs and documents in folders you add from the Files app become searchable."

    override val searchDocumentsToo = "Search documents too"

    override val searchDocumentsTooBody =
        "Add folders from the Files app, like Downloads or iCloud Drive, and Retriever searches the PDFs and documents in them."

    override val downloadsOnWifiOrData = "Downloads on Wi-Fi or mobile data."

    override val downloadsWaitForWifi = "Downloads wait for Wi-Fi, so they won't use your data."

    override val noticesUnavailable = "Retriever is built on open-source software. Its licences couldn't be shown just now."

    // SetupView
    override val searchesForThis = "Searches for this"

    // SplitRoot
    override val searchTab = "Search"

    // StatusView
    override val onlySeesPickedPhotos = "Retriever can only see the photos you picked."

    override val photosOffCantSearch = "Photos access is off, so Retriever can't search your photos."

    // TextReader
    override val notProcessedOpenOriginal = "Retriever hasn't processed this file yet. You can open the original instead."

    override val noTextToShow = "No text to show"

    override val noReadableText = "Retriever found no readable text in this file."

    override val couldntShowText = "Couldn't show the text"

    override val wentWrongReadingIt = "Something went wrong reading it. Try again in a moment."

    override val textFromThisFile = "Text from this file"

    override val onlyBeginningIncluded = "Only the beginning of this file is included."

    // Viewer
    override fun matchOnPage(page: Int) = "Match on page $page"

    override val anotherAppCanAsk = "Another app can ask for it."

    override val damagedOrUnsupported = "It may be damaged, or in a form Retriever can't show."

    override fun couldntPlayIos(audio: Boolean) = if (audio) "Couldn't play this audio" else "Couldn't play this video"

    override val iCloudOrCantPlay = "It may be only in iCloud and there's no connection, or in a form Retriever can't play."

    override fun photoSpoken(kind: String, name: String) = "$kind, $name"

    override val turnOnToSeePhotos = "Turn it on in Settings to see your photos here."

    override val onlyInICloudNow = "It may be only in iCloud, and there's no connection right now."

    override val downloadingFromICloud = "Downloading from iCloud"
}
