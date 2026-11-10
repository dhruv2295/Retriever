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

    override fun facesOf(done: Long, total: Long) = "${formatCount(done)} of ${photos(total)} looked at"

    override val processedInPart = "Processed in part"
    override val couldntBeOpened = "Couldn’t be opened"
    override val stillToProcess = "Still to process"
    override val longFilesStillBeingFinished = "Long files still being finished"

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

    override fun spokenPage(page: Int) = "page $page"

    override fun spokenTime(hours: Long, minutes: Long, seconds: Long): String {
        val parts = listOf(hours to "hour", minutes to "minute", seconds to "second").filter { it.first > 0 }.map { (n, unit) -> plural(n, unit) }
        return "at " + parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    override fun textOnlyNote(ramGb: Int, words: DeviceWords) =
        "This ${words.device} has $ramGb${NBSP}GB of memory, too little for search by description, so Retriever finds files by their names and the words inside them."

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
    override val readFor15 = "Process for 15 minutes"
    override val optional = "Optional"

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

    override val homeIntroTitle = "Retriever keeps going on its own"
    override val homeIntroBody = "Here’s what happens next."
    override val gotIt = "Got it"
    override val newFiles = "New photos and files"
    override val newFilesBody = "Picked up automatically. You don’t need to do anything."
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

    override fun otherFilesBody(words: DeviceWords) =
        "When your ${words.device} is charging and you’re not using it. With lots of files, this can take a few nights."

    override fun otherFilesOnBatteryBody(words: DeviceWords) =
        "When you’re not using your ${words.device}: on battery down to ${ReadingPolicy.BATTERY_FLOOR}%, and while charging. " +
            "With lots of files, this can take a few days."

    override fun onBatteryBody(words: DeviceWords) =
        "Also on battery down to ${ReadingPolicy.BATTERY_FLOOR}%, so the rest of your charge is left for your day. " +
            "It stops when ${words.powerSave} is on or your ${words.device} is warm."

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

    override val chooseWhen = "Choose when to process"
    override val tryLabel = "Try:"

    override fun chooseTitle(words: DeviceWords) = "Now, the rest of your ${words.device}"

    override fun chooseBody(rest: Long?) = when (rest) {
        null -> "The rest of your files aren’t processed yet. Until they are, you can find them by name, but not by what’s inside."
        1L -> "1 file isn’t processed yet. Until it is, you can find it by name, but not by what’s inside."
        else -> "${files(rest)} aren’t processed yet. Until they are, you can find them by name, but not by what’s inside."
    }

    override fun choosePace(words: DeviceWords) =
        "Processing them takes hours, so Retriever does a little at a time. You can use your ${words.device} and Retriever the whole time."

    override val recommended = "Recommended"
    override val read15Now = "While charging, and 15 minutes now"
    override val notNowBody = "Only names can be found until you choose. You can choose any time from Home."
    override val startProcessing = "Start processing"
    override val goToHome = "Go to Home"

    override fun whileChargingBody(words: DeviceWords) =
        "When your ${words.device} is charging and you’re not using it, usually overnight. You can pause any time."

    override fun chargingAndNowBody(words: DeviceWords) =
        "Starts right away and uses some battery, then carries on while charging." + if (words.ios) " Keep Retriever open." else ""

    override fun readNowBody(words: DeviceWords) =
        "Uses some battery. Stops early if your ${words.device} gets warm. Then carries on while charging." + if (words.ios) " Keep Retriever open." else ""

    override fun notificationsAsk(demoPending: Boolean) =
        "One small notice at the top of your screen while it works, so you can see how far it’s got or pause it. No sounds, no other alerts." +
            if (demoPending) " We can also let you know when the demo is done." else ""

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

    override fun facesToGo(left: Long) = "${photos(left)} to look at"

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
        StatusAction.CHOOSE_WHEN -> chooseWhen
        StatusAction.READ_FOR_15 -> readFor15
        StatusAction.STOP -> "Stop"
        StatusAction.RESUME -> "Resume"
        StatusAction.DISMISS -> "Dismiss"
        StatusAction.TRY_AGAIN -> tryAgain
    }

    override fun stopCopy(stop: StopReason, words: DeviceWords) = when (stop) {
        StopReason.PAUSED -> "Paused"
        StopReason.POWER_SAVE -> "Paused while ${words.powerSave} is on"
        StopReason.HOT -> "Paused to let your ${words.device} cool down"
        StopReason.LOW_BATTERY -> "Paused, battery is low"
        StopReason.CHARGING_UP -> "Charging up first"
        StopReason.BUSY -> "Waiting until you put your ${words.device} down"
        StopReason.UNPLUGGED -> "Waits until you charge"
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
    override val countingTitle = "Counting your files…"
    override val notNowTitle = "Only names can be found for now"

    override fun readNowTitle(minutesLeft: Int) = "Processing for 15 minutes · $minutesLeft min left"

    override val sampleTitle = "Processing the demo files"
    override val gettingPeopleReadyTitle = "Getting People ready"
    override val lookingForFacesTitle = "Looking for faces in your photos"
    override val deepeningTitle = "Processing the rest of some long files"
    override val chargingReadTitle = "Processing while you charge"
    override val lookingTitle = "Checking for new files…"
    override val caughtUpTitle = "All done. Retriever has processed everything it can"
    override val limitedTitle = "All done. A few long files were processed only in part"

    override val pausedDetail = "Search still works."

    override fun powerSaveDetail(words: DeviceWords) = "Carries on when ${words.powerSave} is off."

    override val hotDetail = "Carries on by itself."
    override val lowBatteryDetail = "Plug in and Retriever carries on."
    override val chargingUpDetail = "Processing starts once your battery has a bit more charge."
    override val busyDetail = "Or open Retriever to carry on now."

    override fun chargingDetail(words: DeviceWords) = "Retriever carries on when your ${words.device} is charging."

    override val manualDetail = "You can find any file by its name."

    override fun readNowDetail(words: DeviceWords) = "Uses some battery. " + if (words.ios) "Keep Retriever open." else "Stops on its own."

    override val sampleDetail = "About a minute. Nothing is changed or sent."

    override fun meaningDetail(words: DeviceWords) = "So you can search by what things mean, and by what’s in your photos. On this ${words.device} only."

    override val peopleDownloadDetail = "A one-time download for People. Search works the whole time."
    override val facesDetail = "All your files are processed. Search works the whole time."
    override val deepeningDetail = "You can find them by their beginning."
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

    override val caughtUpDetail = "It processes new files by itself."

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
}
