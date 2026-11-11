package app.retriever.core

import app.retriever.core.Words.NBSP

/**
 * Hindi (hi). A machine translation awaiting native review: nothing here has been checked by a native speaker yet.
 * Reviewed by a second model (Codex).
 *
 * Term choices, so a reviewer can check they are used the same way everywhere:
 * - process: प्रोसेस करना (Retriever फ़ाइलें प्रोसेस करता है; फ़ाइल प्रोसेस हुई / हुईं). Never पढ़ना, स्कैन, इंडेक्स.
 * - search by description (feature name): विवरण से खोज. Describing something: "उसके बारे में बताकर".
 * - found by name: नाम से मिली. Found by what's inside: अंदर की सामग्री से मिली.
 * - processed in part: आंशिक रूप से प्रोसेस हुई.
 * - phone: फ़ोन (masculine, like iPhone/iPad, so "आपका फ़ोन" / "आपका iPhone", "इस फ़ोन पर" / "इस iPhone पर").
 * - Battery Saver: बैटरी सेवर (Android). Low Power Mode: निम्न पावर मोड (Apple's Hindi user guide).
 * - Settings: सेटिंग (Android and Apple's Hindi name for the Settings app).
 * - Photos (Apple's app): फ़ोटो. Files (Apple's app): फ़ाइलें ऐप.
 * - starred: स्टार वाली फ़ाइलें (collection), स्टार लगाया गया (snackbar). Actions: स्टार लगाएं / स्टार हटाएं.
 * - People (feature): लोग (“लोग” सुविधा in sentences). Copy (noun): कॉपी / कॉपियाँ. Path: पता.
 * - On this day: आज के दिन. Duplicates: डुप्लिकेट. Access: ऐक्सेस. Delete: मिटाएं. Download: डाउनलोड.
 * - Home: होम. Match: मिलान. Folders to skip: छोड़े जाने वाले फ़ोल्डर.
 * - "you" is the formal आप. Sentences end with "." as Android's Hindi system UI does, not "।".
 * - Wi-Fi, PDF, iPhone, iPad, iCloud, iOS and Retriever stay in Latin script (brief's fixed terms).
 * - Plurals: 0 and 1 take the singular. Verbs and participles agree with the count (हुई / हुईं, है / हैं).
 */
internal object Hindi : Strings {
    /** 0 and 1 take the singular. */
    private fun plural(n: Long, one: String, many: String = one): String = "${formatCount(n)} ${if (n <= 1L) one else many}"

    /** A verb or participle that agrees with a count. */
    private fun agree(n: Long, one: String, many: String): String = if (n <= 1L) one else many

    /** "N फ़ाइलों", the oblique form before में, से, की, को. */
    private fun filesOblique(n: Long): String = plural(n, "फ़ाइल", "फ़ाइलों")

    private fun phone(words: DeviceWords): String = if (words.ios) words.device else "फ़ोन"

    private fun powerSave(words: DeviceWords): String = if (words.ios) "निम्न पावर मोड" else "बैटरी सेवर"

    /** Scope labels before में: inflect known Hindi nouns and preserve other labels. */
    private fun scopeOblique(scope: String): String = when {
        scope.endsWith("फ़ाइलें") -> scope.removeSuffix("फ़ाइलें") + "फ़ाइलों"
        scope.endsWith("दस्तावेज़") -> scope.removeSuffix("दस्तावेज़") + "दस्तावेज़ों"
        else -> scope
    }

    override val sentenceGap = " "

    override val modified = "आखिरी बदलाव"
    override val taken = "ली गई"

    override fun files(n: Long) = plural(n, "फ़ाइल", "फ़ाइलें")

    override fun photos(n: Long) = plural(n, "फ़ोटो")

    override fun videos(n: Long) = plural(n, "वीडियो")

    override fun photosAndVideos(n: Long) = "${formatCount(n)} फ़ोटो और वीडियो"

    override fun readOf(done: Long, total: Long) = "${filesOblique(total)} में से ${formatCount(done)} प्रोसेस ${agree(done, "हुई", "हुईं")}"

    override fun meaningOf(done: Long, total: Long) = "${filesOblique(total)} में से ${formatCount(done)} के लिए विवरण से खोज तैयार है"

    override fun facesOf(done: Long, total: Long) = "${formatCount(total)} फ़ोटो में से ${formatCount(done)} में लोगों की जाँच हुई"

    override val processedInPart = "आंशिक रूप से प्रोसेस हुई"
    override val couldntBeOpened = "खोली नहीं जा सकीं"
    override val stillToProcess = "प्रोसेस होना बाकी"
    override val longFilesStillBeingFinished = "लंबी फ़ाइलें, जिनका काम अभी पूरा हो रहा है"

    override fun largeFiles(n: Long) = "100${NBSP}MB से बड़ी ${files(n)}"

    override fun readCount(read: Long) = "${files(read)} प्रोसेस ${agree(read, "हुई", "हुईं")}"

    override fun partlyReadCount(n: Long) = "${formatCount(n)} आंशिक रूप से प्रोसेस ${agree(n, "हुई", "हुईं")}"

    override fun extent(extent: ReadExtent?) = when (extent) {
        ReadExtent.FIRST_PART -> "आंशिक रूप से प्रोसेस हुई · सिर्फ़ शुरुआती हिस्सा"
        ReadExtent.MEANING_LIMITED -> "आंशिक रूप से प्रोसेस हुई · विवरण से खोज पूरी नहीं हो सकी"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "आंशिक रूप से प्रोसेस हुई · सिर्फ़ शुरुआती हिस्सा, विवरण से खोज के बिना"
        ReadExtent.FIRST_PART_DEEPENING -> "आंशिक रूप से प्रोसेस हुई · अब तक सिर्फ़ शुरुआती हिस्सा"
        ReadExtent.DEEPENING -> "आंशिक रूप से प्रोसेस हुई · विवरण से खोज अभी बाकी है"
        ReadExtent.WHOLE, null -> null
    }

    override val couldntProcessLabel = "प्रोसेस नहीं हो सकी"
    override val notProcessedYet = "अभी प्रोसेस नहीं हुई"
    override val partRestToCome = "आंशिक रूप से प्रोसेस हुई · बाकी हिस्सा बाद में प्रोसेस होगा"
    override val partCouldntFinish = "आंशिक रूप से प्रोसेस हुई · पूरी प्रोसेस नहीं हो सकी"

    override fun kindLabel(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "दस्तावेज़"
        Kind.SCREENSHOT -> "स्क्रीनशॉट"
        Kind.PHOTO -> "फ़ोटो"
        Kind.AUDIO -> "ऑडियो"
        Kind.VIDEO -> "वीडियो"
        Kind.OTHER -> "फ़ाइल"
    }

    override fun kindLabelPlural(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "दस्तावेज़"
        Kind.SCREENSHOT -> "स्क्रीनशॉट"
        Kind.PHOTO -> "फ़ोटो"
        Kind.AUDIO -> "ऑडियो"
        Kind.VIDEO -> "वीडियो"
        Kind.OTHER -> "अन्य फ़ाइलें"
    }

    // Before a postposition (में), so the oblique plural: दस्तावेज़ों, अन्य फ़ाइलों.
    override fun kindsInSentence(kind: Kind) = when (kind) {
        Kind.DOC -> "दस्तावेज़ों"
        Kind.OTHER -> "अन्य फ़ाइलों"
        else -> kindLabelPlural(kind)
    }

    override fun spokenPage(page: Int) = "पेज $page"

    override fun spokenTime(hours: Long, minutes: Long, seconds: Long): String {
        val parts = listOf(
            hours to ("घंटे" to "घंटे"),
            minutes to ("मिनट" to "मिनट"),
            seconds to ("सेकंड" to "सेकंड"),
        ).filter { it.first > 0 }.map { (n, unit) -> plural(n, unit.first, unit.second) }
        return parts.joinToString(" ").ifEmpty { "0 सेकंड" } + " पर"
    }

    override fun textOnlyNote(ramGb: Int, words: DeviceWords) = "इस ${phone(words)} में $ramGb${NBSP}GB मेमोरी है, जो विवरण से खोज के लिए कम है, " +
        "इसलिए Retriever फ़ाइलों को उनके नाम और उनके अंदर लिखे शब्दों से ढूंढता है."

    override fun notReadYet(files: Long) = "${formatCount(files)} बाकी"

    override fun couldntProcess(files: Long) = "${files(files)} प्रोसेस नहीं हो ${agree(files, "सकी", "सकीं")}"

    override val details = "जानकारी"
    override val starting = "शुरू हो रहा है…"
    override val processingNow = "अभी प्रोसेस हो रहा है"
    override val recentlyProcessed = "हाल ही में प्रोसेस हुईं"
    override val processingYourFiles = "आपकी फ़ाइलें प्रोसेस हो रही हैं"
    override val pause = "रोकें"
    override val processTheseKinds = "इस तरह की फ़ाइलें प्रोसेस करें"

    override val continueAction = "जारी रखें"
    override val tryAgain = "फिर कोशिश करें"
    override val download = "डाउनलोड करें"
    override val notNow = "अभी नहीं"
    override val cancel = "रद्द करें"
    override val done = "हो गया"
    override val optional = "ज़रूरी नहीं"

    override fun helloTitle(textOnly: Boolean) = if (textOnly) "फ़ाइलों को उनके अंदर लिखी बातों से ढूंढें" else "कुछ भी ढूंढें, बस उसके बारे में बताकर"

    override val helloBody = "फ़ोटो, स्क्रीनशॉट, दस्तावेज़ और वीडियो, सब एक ही खोज में."
    override val forExample = "उदाहरण के लिए"
    override val helloExamples = listOf(
        Words.HelloExample("मकान मालिक का भेजा Wi-Fi पासवर्ड", "14 मार्च का स्क्रीनशॉट", "लिखा है “Wi-Fi पासवर्ड: …”"),
        Words.HelloExample("सोफ़े पर सोता हुआ कुत्ता", "पिछले रविवार की फ़ोटो", "फ़ोटो में दिख रही चीज़ों से मिली", needsModel = true),
        Words.HelloExample("मेरे किराए का समझौता", "किराए का समझौता 2025.pdf", "पेज 3, हस्ताक्षर वाली कॉपी"),
    )
    override val downloads = "डाउनलोड"

    override fun onDevice(words: DeviceWords) = "आपकी फ़ाइलें आपके ${phone(words)} पर ही प्रोसेस होती हैं"

    override val getStarted = "शुरू करें"

    override fun downloadNote(size: String) = "इसमें Wi-Fi पर एक बार का $size डाउनलोड शामिल है."

    override fun downloadLine(size: String) = "एक बार का $size डाउनलोड, सिर्फ़ Wi-Fi पर"

    override val accessTitle = "चुनें कि Retriever क्या खोज सकता है"
    override val accessBody = "एक या ज़्यादा चुनें. आप बाद में और जोड़ सकते हैं."
    override val allow = "अनुमति दें"
    override val allowAccess = "ऐक्सेस दें"
    override val choose = "चुनें"
    override val chooseMore = "और चुनें"
    override val settings = "सेटिंग"
    override val documents = "दस्तावेज़"
    override val includesScreenshots = "स्क्रीनशॉट भी शामिल हैं"
    override val allowed = "अनुमति है"
    override val somePhotos = "कुछ फ़ोटो चुनी गई हैं"
    override val photosOff = "बंद है. सेटिंग में जाकर चालू करें."
    override val pickFolders = "फ़ाइलें ऐप में फ़ोल्डर चुनें, जैसे डाउनलोड"

    override fun foldersAdded(n: Long) = "${plural(n, "फ़ोल्डर")} ${agree(n, "जोड़ा गया", "जोड़े गए")}"

    override val androidAccessBody = "ताकि आप किसी भी फ़ोटो, दस्तावेज़ या डाउनलोड को उसके बारे में बताकर ढूंढ सकें."
    override val accessScanning = "Retriever अब आपकी फ़ोटो और फ़ाइलों को स्कैन करना शुरू करेगा, ताकि आप उनमें खोज सकें। सब कुछ आपके फ़ोन पर ही रहता है।"
    override val allFilesOff =
        "पूरे फ़ोन में खोजने के लिए “सभी फ़ाइलों को मैनेज करने का ऐक्सेस दें” चालू करें, या चुनें कि क्या शामिल करना है."
    override val includeTitle = "चुनें कि क्या शामिल करना है"
    override val sharedFilesAndFolders = "इस फ़ोन पर मौजूद फ़ाइलें और फ़ोल्डर"
    override val photosAndVideosLabel = "फ़ोटो और वीडियो"
    override val addFolder = "फ़ोल्डर जोड़ें"
    override val nothingSelected = "अभी कोई फ़ाइल नहीं चुनी गई है. शुरू करने के लिए फ़ोटो या कोई फ़ोल्डर चुनें."

    override fun wholeDeviceNote(words: DeviceWords) =
        "Retriever इस ${phone(words)} पर मौजूद फ़ाइलें प्रोसेस करता है, ताकि आप उन्हें उनकी सामग्री से ढूंढ सकें."

    override val firstLook = "आपकी हाल की फ़ाइलों पर पहली नज़र"

    override fun firstSearchHint(state: DescriptionState) =
        if (state == DescriptionState.READY) "किसी फ़ोटो या फ़ाइल के बारे में बताएं" else "नाम या शब्दों से खोजें"

    override fun descriptionLine(state: DescriptionState, percent: Int?) = when (state) {
        DescriptionState.DOWNLOADING -> "विवरण से खोज डाउनलोड हो रही है" + (percent?.let { " · $it%" } ?: "")
        DescriptionState.WAITING_FOR_WIFI -> "विवरण से खोज डाउनलोड करने के लिए Wi-Fi का इंतज़ार है."
        DescriptionState.PREPARING -> "विवरण से खोज तैयार हो रही है"
        DescriptionState.FAILED -> "विवरण से खोज का डाउनलोड रुक गया."
        DescriptionState.NO_SPACE -> "विवरण से खोज डाउनलोड करने के लिए काफ़ी जगह खाली नहीं है."
        DescriptionState.READY, DescriptionState.NOT_STARTED, DescriptionState.UNAVAILABLE -> null
    }

    override val descriptionReady = "विवरण से खोज तैयार है"
    override val descriptionReadyBody = "अब अपनी हाल की फ़ाइलों के बारे में बताकर उन्हें ढूंढें."

    override val homeIntroTitle = "Retriever अपने-आप काम करता रहता है"
    override val homeIntroBody = "आगे यह होगा."
    override val gotIt = "ठीक है"
    override val newFiles = "नई फ़ोटो और फ़ाइलें"
    override val newFilesBody = "अपने-आप जुड़ जाती हैं. आपको कुछ नहीं करना है."
    override val notifyWhenSearchable = "सब कुछ खोजने लायक होने पर मुझे बताएं"
    override val caughtUpNoticeTitle = "अब सब कुछ खोजा जा सकता है"

    override fun caughtUpNotice(files: Long, textOnly: Boolean) =
        (if (files <= 1L) "Retriever ने कुल ${files(files)} प्रोसेस कर ली है. " else "Retriever ने सभी ${files(files)} प्रोसेस कर ली हैं. ") +
            if (textOnly) "इन्हें नाम या अंदर लिखे शब्दों से ढूंढें." else "कुछ भी ढूंढने के लिए बस उसके बारे में बताएं."

    override fun recentFiles(n: Long) = when (n) {
        0L -> "आपकी हाल की फ़ाइलें"
        1L -> "आपकी सबसे हाल की फ़ाइल"
        else -> "आपकी हाल की ${formatCount(n)} फ़ाइलें"
    }

    override fun otherFiles(rest: Long?) = when (rest) {
        null -> "आपकी बाकी फ़ाइलें"
        1L -> "बाकी बची एक फ़ाइल"
        else -> "बाकी ${files(rest)}"
    }

    override fun recentFilesBody(state: DescriptionState) = when (state) {
        DescriptionState.READY -> "इनके बारे में बताकर इन्हें ढूंढें."

        DescriptionState.UNAVAILABLE -> "इन्हें नाम या शब्दों से ढूंढें. विवरण से खोज के लिए ज़्यादा मेमोरी वाला फ़ोन चाहिए."

        DescriptionState.WAITING_FOR_WIFI -> "अभी इन्हें नाम या शब्दों से ढूंढें. एक बार का डाउनलोड पूरा करने के लिए Wi-Fi का इंतज़ार है."

        DescriptionState.FAILED -> "अभी इन्हें नाम या शब्दों से ढूंढें. डाउनलोड रुक गया."

        DescriptionState.NO_SPACE -> "अभी इन्हें नाम या शब्दों से ढूंढें. विवरण से खोज डाउनलोड करने के लिए काफ़ी जगह खाली नहीं है."

        // Not "one-time": after a chip build fails on its first run, this is the second download.
        DescriptionState.NOT_STARTED -> "अभी इन्हें नाम या शब्दों से ढूंढें. विवरण से खोज के लिए डाउनलोड ज़रूरी है."

        DescriptionState.DOWNLOADING, DescriptionState.PREPARING ->
            "अभी इन्हें नाम या शब्दों से ढूंढें. सेटअप पूरा होते ही विवरण से खोज भी मिल जाएगी."
    }

    override fun recentFilesAction(state: DescriptionState, size: String) = when (state) {
        DescriptionState.WAITING_FOR_WIFI -> "मोबाइल डेटा इस्तेमाल करें ($size)"
        DescriptionState.FAILED -> tryAgain
        DescriptionState.NOT_STARTED -> "$size डाउनलोड करें"
        else -> null
    }

    override val demoPicking = "आपकी हाल की फ़ाइलें चुनी जा रही हैं…"
    override val demoHint = "हाल की किसी फ़ोटो, स्क्रीनशॉट या दस्तावेज़ का कोई ऐसा शब्द लिखें जो आपको याद हो."

    override fun demoSearchScope(done: Long, finished: Boolean, words: DeviceWords) = when {
        finished ->
            "आपके ${phone(words)} पर कोई भी फ़ाइल उसके नाम से ढूंढता है, और ${plural(done, "डेमो फ़ाइल", "डेमो फ़ाइलों")} की सामग्री में भी खोजता है."

        // The first frame: nothing processed yet, so no "0 demo files".
        done == 0L -> "आपके ${phone(words)} पर कोई भी फ़ाइल उसके नाम से ढूंढता है."

        else -> "आपके ${phone(words)} पर कोई भी फ़ाइल उसके नाम से ढूंढता है. " +
            "अब तक प्रोसेस की गई ${plural(done, "डेमो फ़ाइल", "डेमो फ़ाइलों")} की सामग्री में भी खोजता है."
    }

    override fun seeAll(files: Long) = if (files == 1L) "फ़ाइल देखें" else "${files(files)} देखें"

    override val read = "अंदर की सामग्री से मिली"
    override val foundByName = "नाम से मिली"
    override val foundBy = "कैसे मिली"

    override fun kindRead(read: Long, planned: Long, kind: Kind): String {
        val feminine = kind == Kind.PHOTO || kind == Kind.OTHER
        val verb = if (feminine) agree(read, "हुई", "हुईं") else agree(read, "हुआ", "हुए")
        return "${kindLabelPlural(kind)}: ${formatCount(planned)} में से ${formatCount(read)} प्रोसेस $verb"
    }

    override val tryLabel = "आज़माएं:"

    override val startProcessing = "प्रोसेस शुरू करें"

    override fun notificationsAsk(demoPending: Boolean) =
        "काम के दौरान आपकी स्क्रीन के ऊपर एक छोटी-सी सूचना दिखेगी, ताकि आप देख सकें कि कितना काम हुआ या उसे रोक सकें. " +
            "कोई आवाज़ नहीं, कोई दूसरा अलर्ट नहीं." +
            if (demoPending) " डेमो पूरा होने पर हम आपको बता भी सकते हैं." else ""

    override val progressChannel = "कितना प्रोसेस हुआ"
    override val progressChannelBody = "दिखाता है कि कितना प्रोसेस हुआ, और डेमो पूरा होने पर एक बार बताता है."
    override val demoDoneNoticeTitle = "डेमो पूरा हो गया"

    override fun demoDoneNotice(done: Long, planned: Long) = "${readOf(done, planned)}. उन्हें खोजने के लिए Retriever खोलें."

    override val searchField = "आप क्या ढूंढ रहे हैं?"
    override val nameMatches = "नाम से मिलान"
    override val textMatches = "शब्दों से मिलान"
    override val searchingContents = "फ़ाइलों के अंदर खोजा जा रहा है…"

    override fun copies(n: Int) = plural(n.toLong(), "कॉपी", "कॉपियाँ")

    override fun openCopyIn(folder: String) = "$folder में मौजूद कॉपी खोलें"

    override val noMatches = "कुछ नहीं मिला"

    override fun noMatchesDetail(byMeaning: Boolean) =
        (if (byMeaning) "बताएं कि उसमें क्या है, या पूछें कि उसमें क्या लिखा है." else "उसमें मौजूद या उसके नाम का कोई शब्द आज़माएं.") +
            " कब का है, यह भी जोड़ें, जैसे “पिछले हफ़्ते”."

    override val moreWaiting = "Retriever अभी आपकी फ़ाइलें प्रोसेस कर रहा है, इसलिए थोड़ी देर बाद फिर से खोजें."
    override val searchFailed = "खोज पूरी नहीं हो सकी"
    override val showingNameMatches = "नाम से मिलान दिखाए जा रहे हैं"
    override val searchingWithoutModel = "नाम और पहले से प्रोसेस हुए टेक्स्ट में खोजा जा रहा है"

    override fun matches(labels: List<String>) = "मिलान: " + labels.joinToString(", ")

    private val monthNames = listOf("जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर")
    private val weekdayNames = listOf("सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार")

    override fun dateLabel(date: DateFilter) = when (date) {
        DateFilter.Today -> "आज"

        DateFilter.Yesterday -> "कल"

        is DateFilter.DaysAgo -> "${formatCount(date.days.toLong())} दिन पहले"

        is DateFilter.Period -> (if (date.last) "पिछले " else "इस ") + when (date.unit) {
            PeriodUnit.WEEK -> "हफ़्ते"
            PeriodUnit.MONTH -> "महीने"
            PeriodUnit.YEAR -> "साल"
        }

        is DateFilter.Weekday -> when (date.which) {
            Which.LAST -> "पिछले "
            Which.THIS -> "इस "
            Which.BARE -> ""
        } + weekdayNames[date.day]

        is DateFilter.Month -> monthNames[date.month - 1] + (date.year?.let { " $it" } ?: "")

        is DateFilter.Day -> "${date.day} ${monthNames[date.month - 1]}" + (date.year?.let { " $it" } ?: "")

        is DateFilter.Year -> "${date.year}"
    }

    /** The date as it leads a sentence: "आज", "पिछले हफ़्ते", "सोमवार को", "2024 में". */
    private fun inDate(date: DateFilter): String = when (date) {
        DateFilter.Today, DateFilter.Yesterday, is DateFilter.DaysAgo, is DateFilter.Period -> dateLabel(date)
        is DateFilter.Weekday, is DateFilter.Day -> "${dateLabel(date)} को"
        else -> "${dateLabel(date)} में"
    }

    override fun noneFound(kinds: List<Kind>, people: List<String>, date: DateFilter?): String {
        val names = people.joinToString(" और ")
        val what = if (kinds.isEmpty()) {
            if (names.isEmpty()) "कुछ नहीं मिला" else "$names से जुड़ी कोई फ़ाइल नहीं मिली"
        } else {
            kinds.joinToString(" और ") { kind ->
                val feminine = kind == Kind.PHOTO || kind == Kind.PDF || kind == Kind.OTHER
                val of = if (names.isEmpty()) "" else "$names ${if (feminine) "की" else "का"} "
                "${of}कोई ${kindLabel(kind)} नहीं ${if (feminine) "मिली" else "मिला"}"
            }
        }
        return listOf(date?.let(::inDate).orEmpty(), what).filter { it.isNotEmpty() }.joinToString(" ")
    }

    override fun relaxedNote(missing: String, scope: String?) = "$missing, इसलिए यहाँ " +
        if (scope == null) "सब कुछ दिखाया जा रहा है." else "${scopeOblique(scope)} में मौजूद सब कुछ दिखाया जा रहा है."

    /** की before feminine फ़ोटो and PDF, के before the masculine plurals (स्क्रीनशॉट, वीडियो, दस्तावेज़). */
    override fun filterQuery(kind: Kind, date: DateFilter) =
        "${dateLabel(date)} ${if (kind == Kind.PHOTO || kind == Kind.PDF) "की" else "के"} ${kindLabelPlural(kind)}"

    override val downloadForDescription = "विवरण से खोज के लिए डाउनलोड करें"

    override val readingStatus = "प्रोसेसिंग"
    override val recentlyAdded = "हाल ही में जोड़ी गईं"
    override val moreOnPhone = "ब्राउज़ करें"
    override val allFolders = "सभी फ़ोल्डर"

    override val howItWorks = "Retriever कैसे काम करता है और आपकी निजता"
    override val whenToRead = "कब प्रोसेस करना है"
    override val whatItCanSee = "Retriever क्या देख सकता है"
    override val foldersToSkip = "छोड़े जाने वाले फ़ोल्डर"
    override val kindOff =
        "बंद है: Retriever इन्हें अब भी नाम से ढूंढता है, लेकिन इनके अंदर की सामग्री भूल जाता है. आपकी फ़ाइलों में कोई बदलाव नहीं होता."
    override val showOnThisDay = "आज के दिन दिखाएं (पिछले सालों में इसी तारीख की फ़ोटो)"

    override val people = "लोग"
    override val recognizePeople = "लोगों को पहचानें"
    override val peopleOfferTitle = "लोगों की फ़ोटो ढूंढें"
    override val peopleOfferBody =
        "Retriever आपकी फ़ोटो में मौजूद चेहरों के ग्रुप बना सकता है, ताकि आप किसी को नाम दें और उसे नाम से ढूंढ सकें. " +
            "चेहरे इसी फ़ोन पर प्रोसेस होते हैं और कभी इससे बाहर नहीं जाते."
    override val turnOn = "चालू करें"
    override val recognizePeopleDetail =
        "आपकी फ़ोटो में मौजूद चेहरों के ग्रुप बनाता है, ताकि आप लोगों को नाम दे सकें और उन्हें नाम से ढूंढ सकें. " +
            "चेहरे इसी फ़ोन पर प्रोसेस होते हैं और उनका कभी बैकअप नहीं होता."
    override val forgetPeopleTitle = "सभी चेहरे और नाम भूल जाएं?"
    override val forgetPeopleBody = "Retriever चेहरों के बनाए गए ग्रुप और आपके दिए नाम भूल जाएगा. आपकी फ़ोटो में कोई बदलाव नहीं होता."
    override val forget = "भूल जाएं"
    override val notThisPerson = "यह व्यक्ति नहीं"
    override val addAName = "नाम जोड़ें"
    override val name = "नाम"
    override val save = "सेव करें"
    override val samePersonAs = "वही व्यक्ति, जो…"
    override val samePersonAsk = "क्या यह एक ही व्यक्ति है?"
    override val samePersonYes = "एक ही व्यक्ति"
    override val samePersonNo = "अलग-अलग हैं"
    override val samePersonPrevious = "पिछली जोड़ी"
    override val samePersonNext = "अगली जोड़ी"

    override fun pairOf(at: Int, of: Int) = "$of में से $at"

    override fun facesToGo(left: Long) = "${photos(left)} में लोगों की जाँच बाकी है"

    override val retrieversData = "Retriever का डेटा"
    override val temporaryFiles = "अस्थायी फ़ाइलें"

    override fun keptOn(size: String, words: DeviceWords) = "$size · इस ${phone(words)} पर सेव है"

    override val searchByMeaning = "विवरण से खोज"
    override val removeDownload = "खोज का डाउनलोड हटाएं"
    override val removeDownloadAsk = "खोज का डाउनलोड हटाएं?"
    override val namesStillWork = "फ़ाइलों के नाम और पहले से प्रोसेस हुए शब्दों से खोज काम करती रहेगी."

    override fun downloaded(size: String) = "डाउनलोड हो गया · $size"

    override fun removeDownloadMessage(size: String?) =
        (if (size != null) "इससे $size जगह खाली होगी. " else "") + "$namesStillWork विवरण से खोज के लिए फिर से डाउनलोड करना होगा."

    override val freeUpSpace = "जगह खाली करें"
    override val largeFilesTitle = "बड़ी फ़ाइलें"
    override val findDuplicates = "डुप्लिकेट फ़ाइलें ढूंढें"
    override val neverDeletes = "Retriever आपसे पूछे बिना कभी कुछ नहीं मिटाता."

    override fun possibleCopies(n: Long) = "${files(n)} शायद किसी फ़ाइल की कॉपी ${agree(n, "है", "हैं")}"

    override fun deleteFrom(words: DeviceWords) = "${phone(words)} से मिटाएं"

    override fun deleteConfirm(words: DeviceWords) = "इससे फ़ाइल आपके ${phone(words)} से मिट जाएगी."

    override val upgradeTitle = "फ़ाइलों के बारे में बताकर उन्हें ढूंढें"
    override val waitingForWifi = "डाउनलोड के लिए Wi-Fi का इंतज़ार है"
    override val useMobileData = "मोबाइल डेटा इस्तेमाल करें"
    override val waitForWifi = "Wi-Fi का इंतज़ार करें"
    override val checkingDownload = "डाउनलोड की जांच हो रही है…"
    override val downloadFailed = "डाउनलोड बीच में रुक गया. अपना कनेक्शन देखें और फिर से कोशिश करें."
    override val noSpace = "डाउनलोड के लिए काफ़ी जगह खाली नहीं है"
    override val tuneLeaving = "आप अभी नाम से खोज सकते हैं. आपके लौटने पर सेटअप फिर से शुरू होगा."

    override fun upgradeBody(size: String, words: DeviceWords) = "Wi-Fi पर $size का एक डाउनलोड. " +
        "फिर ‘समुद्र किनारे सूर्यास्त’ जैसी खोज से फ़ोटो मिल जाती हैं, भले ही उनमें ये शब्द न हों. सब कुछ इसी ${phone(words)} पर रहता है."

    override fun downloadProgress(done: String, total: String, percent: Int) = "$total में से $done · $percent%"

    override fun mobileDataAsk(size: String) = "मोबाइल डेटा से $size डाउनलोड करें?"

    override fun freeUp(size: String) = "जारी रखने के लिए $size जगह खाली करें"

    override fun tuneTitle(words: DeviceWords) = "इस ${phone(words)} के लिए प्रोसेसिंग सेट की जा रही है"

    override fun tuneBody(words: DeviceWords) = "करीब एक मिनट. इस ${phone(words)} के लिए सही प्रोसेसिंग स्पीड ढूंढी जा रही है."

    override fun tuneFailed(words: DeviceWords) = "इस ${phone(words)} के लिए प्रोसेसिंग सेट नहीं हो सकी."

    override fun addingMeaning(done: Long, total: Long) = "विवरण से खोज जोड़ी जा रही है · ${filesOblique(total)} में से ${formatCount(done)}"

    override val meaningCatchingUp =
        "विवरण से खोज अब काम करती है. पहले प्रोसेस हुई फ़ाइलों के लिए भी यह सुविधा Retriever के काम जारी रखने पर जुड़ती जाएगी."

    override fun scheduleTitle(schedule: ReadingSchedule) = when (schedule) {
        ReadingSchedule.CHARGING -> "चार्जिंग के दौरान"
        ReadingSchedule.BATTERY -> "बैटरी पर भी"
        ReadingSchedule.MANUAL -> "जब मैं शुरू करूं"
    }

    override fun statusAction(action: StatusAction) = when (action) {
        StatusAction.CONTINUE -> continueAction
        StatusAction.ALLOW_ACCESS -> allowAccess
        StatusAction.STOP -> "बंद करें"
        StatusAction.RESUME -> "जारी रखें"
        StatusAction.DISMISS -> "हटाएं"
        StatusAction.TRY_AGAIN -> tryAgain
        StatusAction.START -> startProcessing
        StatusAction.PAUSE -> pause
    }

    override fun stopCopy(stop: StopReason, words: DeviceWords) = when (stop) {
        StopReason.PAUSED -> "रुका हुआ है"
        StopReason.POWER_SAVE -> "रुका हुआ · ${powerSave(words)} बंद होने पर जारी होगा"
        StopReason.HOT -> "रुका हुआ · आपका ${phone(words)} ठंडा होने पर जारी होगा"
        StopReason.LOW_BATTERY, StopReason.UNPLUGGED -> "रुका हुआ · चार्ज पर लगाने पर जारी होगा"
        StopReason.CHARGING_UP -> "रुका हुआ · बैटरी में थोड़ा और चार्ज होने पर जारी होगा"
        StopReason.MANUAL -> "सिर्फ़ तब प्रोसेस होगा जब आप शुरू करेंगे"
        StopReason.DISK_FULL -> "रुका है, आपके ${phone(words)} पर जगह नहीं है"
        StopReason.DONE, StopReason.BUDGET -> "थोड़ा ब्रेक ले रहा है"
    }

    override val noAccessTitle = "Retriever आपकी फ़ाइलें नहीं देख पा रहा है"

    override fun noAccessDetail(words: DeviceWords) = "जो फ़ाइलें पहले से प्रोसेस हो चुकी हैं, उनमें खोज अब भी काम करती है." +
        if (words.ios) " इससे आपके ${phone(words)} पर सेटिंग ऐप खुलेगा." else ""

    override val finishSetupTitle = "सेटअप पूरा करें"
    override val finishSetupDetail = "चुनें कि Retriever किन फ़ाइलों में खोज सकता है."
    override val downloadPausedTitle = "डाउनलोड रुका है"

    override fun downloadPausedDetail(words: DeviceWords, size: String) =
        "Wi-Fi से जुड़ने पर यह जारी रहेगा. अगर आपका ${phone(words)} भरा हुआ है, तो इसके लिए $size खाली जगह चाहिए."

    override fun downloadingTitle(percent: Int?) = "विवरण से खोज डाउनलोड हो रही है" + (percent?.let { " · $it%" } ?: "")

    override val downloadingDetail = "आप Retriever इस्तेमाल करते रह सकते हैं."

    override val addingDescriptionTitle = "विवरण से खोज जोड़ी जा रही है"
    override val allFilesProcessedTitle = "सभी फ़ाइलें प्रोसेस हो गईं"
    override val gettingReadyTitle = "तैयारी हो रही है…"
    override val notNowTitle = "अभी फ़ाइलें सिर्फ़ नाम से मिलेंगी"

    override val sampleTitle = "डेमो फ़ाइलें प्रोसेस हो रही हैं"
    override val lookingForFacesTitle = "आपकी फ़ोटो में लोगों को ढूंढा जा रहा है"
    override val deepeningTitle = "कुछ लंबी फ़ाइलों का बाकी हिस्सा प्रोसेस हो रहा है"
    override val lookingTitle = "नई फ़ाइलें देखी जा रही हैं…"
    override val caughtUpTitle = "सब हो गया. Retriever ने जो कुछ प्रोसेस हो सकता था, सब कर लिया"

    override val processingReadyDetail = "आपकी प्रगति सेव है। अभी शुरू कर सकते हैं, या अनुकूल स्थिति होने पर प्रोसेसिंग अपने-आप जारी होगी।"
    override val processingAdaptiveDetail = "फ़ोन सुचारू रूप से चलता रहे, इसलिए प्रोसेसिंग अपनी गति बदलती है।"
    override val processingBatteryOverrideDetail = "प्रोसेसिंग बैटरी ${ReadingPolicy.RESUMED_BATTERY_FLOOR}% तक रहने पर चलती है।"

    override val processingKeepPaused = "रुका रहने दें"
    override val processingSearchAvailableDetail = "अभी खोज सकते हैं. फ़ाइलों के नाम और पहले से प्रोसेस किया गया कॉन्टेंट खोजा जा सकता है."
    override val processingPlugInDetail =
        "प्रोसेसिंग जारी रखने के लिए फ़ोन चार्ज पर लगाएँ। Retriever बैटरी ${ReadingPolicy.RESUMED_BATTERY_FLOOR}% तक रहने पर प्रोसेस करता है।"
    override val processingTapToContinue = "जारी रखने के लिए टैप करें"

    override val automaticChargingPreferenceDetail = "अपने आप चार्जिंग का इंतज़ार करता है. फिर शुरू करें चुनकर इस बार बैटरी पर प्रोसेसिंग शुरू कर सकते हैं."

    override val automaticProcessingPreferenceTitle = "अपने-आप प्रोसेसिंग"
    override val automaticProcessingPreferenceDetail =
        "बैटरी ${ReadingPolicy.BATTERY_FLOOR}% से कम होने पर अपने आप होने वाली प्रोसेसिंग रुक जाती है। आपकी शुरू की या फिर से चालू की " +
            "गई प्रोसेसिंग ${ReadingPolicy.RESUMED_BATTERY_FLOOR}% तक चलती है।"

    override val pausedDetail = "खोज अब भी काम करती है."

    override fun powerSaveDetail(words: DeviceWords) = "${powerSave(words)} बंद होने पर जारी रहेगा."

    override val hotDetail = "अपने-आप जारी रहेगा."
    override val lowBatteryDetail = "चार्जर लगाएं, Retriever जारी रखेगा."
    override val chargingUpDetail = "बैटरी थोड़ी और चार्ज होते ही प्रोसेसिंग शुरू होगी."

    override fun chargingDetail(words: DeviceWords) = "आपके ${phone(words)} के चार्ज होते समय Retriever काम जारी रखेगा."

    override val manualDetail = "आप कोई भी फ़ाइल उसके नाम से ढूंढ सकते हैं."

    override val sampleDetail = "करीब एक मिनट. न कुछ बदला जाता है, न कहीं भेजा जाता है."

    override fun meaningDetail(words: DeviceWords) = "ताकि आप चीज़ों के मतलब से और अपनी फ़ोटो में दिख रही चीज़ों से खोज सकें. सिर्फ़ इसी ${phone(words)} पर."

    override val peopleDownloadDetail = "“लोग” सुविधा के लिए एक बार का डाउनलोड. आपकी सभी फ़ाइलें खोजी जा सकती हैं."
    override val facesDetail = "एक वैकल्पिक अतिरिक्त चरण. आपकी सभी फ़ाइलें खोजी जा सकती हैं, और कुछ भी दोबारा प्रोसेस नहीं होता."

    override fun facesWaitingDetail(words: DeviceWords) =
        "जब आपका ${phone(words)} अनुमति देता है, तब Retriever लोगों को ढूंढता है. आपकी सभी फ़ाइलें खोजी जा सकती हैं."

    override val deepeningDetail = "आप इन्हें इनके शुरुआती हिस्से से ढूंढ सकते हैं."
    override val limitedDetail = "कुछ लंबी फ़ाइलें आंशिक रूप से ही प्रोसेस हुईं. आप उन्हें उनके शुरुआती हिस्से से ढूंढ सकते हैं."
    override val readingMeaningDetail = "साथ में विवरण से खोज भी जोड़ी जा रही है."

    override fun readingDetail(words: DeviceWords) = if (words.ios) {
        "खोज पूरे समय काम करती है. Retriever खुला रहने पर, और कभी-कभी iOS की अनुमति मिलने पर, आपकी फ़ाइलें प्रोसेस करता है."
    } else {
        "आप Retriever बंद कर सकते हैं; यह काम जारी रखेगा. खोज पूरे समय काम करती है."
    }

    override fun waitingLongFilesDetail(words: DeviceWords) =
        "जब आपका ${phone(words)} अनुमति देता है, तब Retriever कुछ लंबी फ़ाइलों का बाकी हिस्सा प्रोसेस करता है. खोज अभी काम करती है."

    override fun waitingMeaningDetail(words: DeviceWords) = "जब आपका ${phone(words)} अनुमति देता है, तब Retriever विवरण से खोज जोड़ता है. खोज अभी काम करती है."

    override val diskFullDetail = "थोड़ी जगह खाली करें, Retriever जारी रखेगा."

    override fun waitingDetail(words: DeviceWords) = "Retriever कब जारी रखेगा, यह आपका ${phone(words)} तय करता है. कुछ भी खोता नहीं है."

    override val caughtUpDetail = "यह नई फ़ाइलें अपने-आप प्रोसेस करता है."

    override val gettingReadersDetail = "Google Play सेवाओं से एक बार का डाउनलोड. नाम से खोज अभी काम करती है."

    override val peopleWaitsForWifi = "“लोग” सुविधा डाउनलोड करने के लिए Wi-Fi का इंतज़ार है."

    override fun deepeningLead(left: Long) = "${notReadYet(left)}."

    override fun longFilesWait(n: Long) = "सभी फ़ाइलें प्रोसेस हो गईं. ${plural(n, "लंबी फ़ाइल", "लंबी फ़ाइलों")} का बाकी हिस्सा इंतज़ार में है."

    override val allFilesProcessedLead = "सभी फ़ाइलें प्रोसेस हो गईं."

    override val foundByOther = "इसका नाम. Retriever अभी इस तरह की फ़ाइल प्रोसेस नहीं कर सकता."
    override val foundByBeginningRestPending = "नाम और शुरुआती हिस्सा. बाकी हिस्सा अभी प्रोसेस नहीं हुआ है."
    override val foundByPartCouldntFinish = "नाम और अंदर की सामग्री का कुछ हिस्सा. Retriever इसे पूरा प्रोसेस नहीं कर सका."
    override val foundByBeginning = "नाम और शुरुआती हिस्सा"
    override val foundByInsideMeaningLimited = "नाम और अंदर की सामग्री. विवरण से खोज पूरी नहीं हो सकी."
    override val foundByInsideDeepening = "नाम और अंदर की सामग्री. विवरण से खोज अभी बाकी है."
    override val foundByInside = "नाम और अंदर की सामग्री"
    override val foundByReadingOff = "इसका नाम. इस तरह की फ़ाइल के लिए प्रोसेसिंग बंद है."

    override fun foundBySkipped(tries: Long) = "इसका नाम. ${plural(tries, "बार")} कोशिश करने के बाद भी Retriever इसे प्रोसेस नहीं कर सका."

    override val foundByNothingInside = "इसका नाम. इसके अंदर का कुछ भी प्रोसेस नहीं हो सका."
    override val foundByNotYet = "इसका नाम. अभी प्रोसेस नहीं हुई."

    override val processingFailed = "आपकी फ़ाइलें प्रोसेस करते समय कुछ गड़बड़ हो गई. Retriever फिर से कोशिश करेगा."

    // FileActions
    override val fileNameField = "नाम"

    // Notifications
    override fun notificationBody(count: String?, detail: String?, read: Long) = listOfNotNull(count, detail).joinToString(". ").ifEmpty { readCount(read) }

    // SearchResults
    override fun understoodChip(labels: List<String>) = labels.joinToString(" · ")

    override fun noMatchesMessage(byMeaning: Boolean, waiting: Boolean, wordsOnly: Boolean) =
        listOfNotNull(noMatchesDetail(byMeaning), moreWaiting.takeIf { waiting }, searchingWithoutModel.takeIf { wordsOnly }).joinToString(" ")

    override fun reasonLine(parts: List<String>) = parts.joinToString(" · ")

    // SearchScreen
    override val appSettings = "सेटिंग"

    // SettingsScreen
    override fun scheduleSummary(title: String, body: String) = "$title. $body"

    override val sizePending = "…"

    // FirstRunStages
    override fun folderNames(names: List<String>) = names.joinToString(" · ")

    // AppModel+Setup
    override fun kindsReadSpoken(parts: List<String>) = parts.joinToString(", ")

    // SearchModel
    override val scopePhotos = "फ़ोटो"

    override val scopeDocuments = "दस्तावेज़"

    // HomeView
    override fun titleDetailSpoken(title: String, detail: String?) = listOfNotNull(title, detail).joinToString(", ")

    // ResultViews
    override fun resultRowSpoken(kind: String, name: String, date: String, position: String?, extent: String?, reason: String?) =
        listOfNotNull(kind, name, date, position, extent, reason).joinToString(", ")

    // Access
    override val onlyPickedPhotos = "सिर्फ़ आपकी चुनी हुई फ़ोटो"

    override val musicAndRecordings = "संगीत और रिकॉर्डिंग"

    override fun notCoveredMedia(photos: Boolean, videos: Boolean, music: Boolean) = listOfNotNull(
        "फ़ोटो".takeIf { photos },
        "वीडियो".takeIf { videos },
        "संगीत".takeIf { music },
    ).let { m ->
        "शामिल नहीं: " +
            if (m.size == 1) m[0] else m.dropLast(1).joinToString(", ") + " और " + m.last()
    }

    override fun notCoveredDocuments(hasFolders: Boolean) =
        if (hasFolders) "शामिल नहीं: आपके जोड़े गए फ़ोल्डरों के बाहर के दस्तावेज़ और डाउनलोड" else "शामिल नहीं: दस्तावेज़ और डाउनलोड"

    override val pickFolderOnPhone = "इस फ़ोन के स्टोरेज में कोई फ़ोल्डर चुनें."

    override val alreadySearchesFolder = "Retriever उस फ़ोल्डर में पहले से खोजता है."

    // AccessService
    override val couldntKeepFolder = "उस फ़ोल्डर का ऐक्सेस बना नहीं रह सका. उसे फिर से चुनकर देखें."

    // BrowseScreen
    override val allFiles = "सभी फ़ाइलें"

    override val filesRetrieverCanAccess = "वे फ़ाइलें जिन तक Retriever पहुंच सकता है"

    override val sortLargestDetail = "सबसे बड़ी पहले"

    override val sortNameDetail = "नाम से, A से Z"

    override val sortNewestDetail = "सबसे नई पहले · बदलाव की तारीख से"

    override val couldntChangePerson = "इस व्यक्ति में बदलाव नहीं हो सका. फिर से कोशिश करें."

    override val back = "वापस जाएं"
    override val home = "होम"
    override val fullScreen = "फ़ुल स्क्रीन"
    override val showBesideList = "सूची के साथ दिखाएं"
    override val searchYourFiles = "अपनी फ़ाइलें खोजें"

    override val couldntLoadYourFiles = "आपकी फ़ाइलें लोड नहीं हो सकीं"

    override val wentWrongReadingThem = "उन्हें लोड करते समय कुछ गड़बड़ हो गई. एक बार फिर कोशिश करें."

    override val keepUsefulFilesClose = "काम की फ़ाइलें पास रखें"

    override val tapStarToFindHere = "किसी फ़ाइल पर स्टार टैप करें, ताकि वह यहां मिले."

    override val chooseFilesToSearch = "खोजने के लिए फ़ाइलें चुनें"

    override val addPhotosOrFolder = "फ़ोटो या कोई फ़ोल्डर जोड़ें."

    override val manageAccess = "ऐक्सेस मैनेज करें"

    override val nothingHereYet = "यहां अभी कुछ नहीं है"

    override fun filesShowUpHere(words: DeviceWords) = "Retriever को आपके ${phone(words)} पर फ़ाइलें मिलते ही वे यहां दिखेंगी."

    override val moreOptions = "ज़्यादा विकल्प"

    override val couldntLoadMoreFiles = "और फ़ाइलें लोड नहीं हो सकीं"

    override val sortNewest = "सबसे नई"

    override val sortLargest = "सबसे बड़ी"

    override val sortName = "नाम"

    // BrowseViewModel
    override val couldntLoadTheseFiles = "ये फ़ाइलें लोड नहीं हो सकीं."

    // PersonActions
    override val rename = "नाम बदलें"

    // DuplicatesScreen
    override val duplicates = "डुप्लिकेट"

    override val keepOneCopy = "हर फ़ाइल की कम से कम एक कॉपी रखें"

    override fun filesCouldntBeDeleted(n: Long) = "${files(n)} मिटाई नहीं जा ${agree(n, "सकी", "सकीं")}"

    override fun deleteSelected(n: Long, size: String) = "${plural(n, "चुनी गई फ़ाइल", "चुनी गई फ़ाइलें")} मिटाएं ($size)"

    override val lookingForCopiesEllipsis = "कॉपियाँ ढूंढी जा रही हैं…"

    override val couldntLookForDuplicates = "डुप्लिकेट नहीं ढूंढे जा सके"

    override val noDuplicatesFound = "कोई डुप्लिकेट नहीं मिला"

    override fun comparedFiles(n: Long) = "Retriever ने ${filesOblique(n)} की तुलना की."

    override fun couldBeFreed(size: String) = "$size जगह खाली हो सकती है"

    override fun copiesEach(n: Long, size: String) = "${plural(n, "कॉपी", "कॉपियाँ")} · हर कॉपी: $size"

    override fun openNamed(name: String) = "$name खोलें"

    override val deselect = "चुनाव हटाएं"

    override val select = "चुनें"

    override val deleteFilesTitle = "फ़ाइलें मिटाएं?"

    override fun deleteFilesMessage(n: Long) = "${files(n)} मिटाएं? मिटाने के बाद वापस नहीं ला सकेंगे."

    override val delete = "मिटाएं"

    // DuplicatesViewModel
    override val somethingWentWrongTryAgain = "कुछ गड़बड़ हो गई. फिर से कोशिश करें."

    // FileActions
    override val addToStarred = "स्टार लगाएं"

    override val share = "शेयर करें"

    override val openInAnotherApp = "दूसरे ऐप में खोलें"

    override val fileInfo = "फ़ाइल की जानकारी"

    override val removeFromStarred = "स्टार हटाएं"

    override val topLevel = "मुख्य फ़ोल्डर"

    override val couldntChangeStar = "स्टार नहीं बदला जा सका. फिर से कोशिश करें."

    override val findSimilar = "मिलती-जुलती ढूंढें"

    override val couldntFinishThat = "यह पूरा नहीं हो सका. हो सकता है फ़ाइल कहीं और चली गई हो या ऐक्सेस बदल गया हो."

    override val renameFile = "फ़ाइल का नाम बदलें"

    override val deleteThisFile = "यह फ़ाइल मिटाएं?"

    // FileInfo
    override fun kindAndSize(kind: String, size: String) = "$kind · $size"

    override val locationLabel = "जगह"

    override val hideFullPath = "पूरा पता छिपाएं"

    override val showFullPath = "पूरा पता दिखाएं"

    override val locationDetails = "जगह की जानकारी"

    override val pathClipLabel = "पता"

    override val copyPath = "पता कॉपी करें"

    override val dimensionsLabel = "चौड़ाई और ऊंचाई"

    override fun dimensions(width: Int, height: Int) = "$width × $height"

    override val durationLabel = "अवधि"

    override val pagesLabel = "पेज"

    override val internalStorage = "इंटरनल स्टोरेज/"

    // FoldersScreen
    override val folders = "फ़ोल्डर"

    override val foldersDidntLoad = "फ़ोल्डर लोड नहीं हुए"

    override val noFoldersYet = "अभी कोई फ़ोल्डर नहीं है"

    override val tryAgainInAMoment = "थोड़ी देर में फिर से कोशिश करें."

    override val foldersAppearPhone = "Retriever को आपके फ़ोन पर फ़ोल्डर मिलते ही वे यहां दिखेंगे."

    override fun stopSearchingAsk(name: String) = "$name में खोजना बंद करें?"

    override fun stopSearchingMessage(n: Long, name: String) = "$name में मौजूद ${files(n)} अभी खोज से हटा दी ${agree(n, "जाएगी", "जाएंगी")}, " +
        "और वापस लाने के लिए सभी फ़ाइलें फिर से प्रोसेस करनी होंगी. आप होम पर सबसे ऊपर दिख रही स्थिति पर टैप करके यह फ़ोल्डर वापस ला सकते हैं."

    override val stopSearching = "खोजना बंद करें"

    override fun filesInParent(n: Long, parent: String?) = if (parent == null) files(n) else "${files(n)} · $parent"

    override fun moreOptionsFor(name: String) = "$name के लिए ज़्यादा विकल्प"

    override val stopSearchingThisFolder = "इस फ़ोल्डर में खोजना बंद करें"

    // FoldersViewModel
    override val couldntLoadYourFolders = "आपके फ़ोल्डर लोड नहीं हो सके. थोड़ी देर में फिर से कोशिश करें."

    override val couldntStopSearchingFolder = "उस फ़ोल्डर में खोजना बंद नहीं हो सका. थोड़ी देर में फिर से कोशिश करें."

    // HomeScreen
    override val onThisDayHidden = "“आज के दिन” छिपाया गया"

    override val undo = "पहले जैसा करें"

    override val showProcessingDetails = "प्रोसेसिंग की जानकारी दिखाएं"

    override val lookingForYourFiles = "आपकी फ़ाइलें ढूंढी जा रही हैं"

    override val nothingToSearchYet = "अभी खोजने के लिए कुछ नहीं है"

    override val takesAMinute = "इसमें एक मिनट लगता है. फ़ाइलें मिलते ही आप नाम से खोज सकते हैं."

    override val saveAPdfHint = "कोई PDF सेव करें, स्क्रीनशॉट या फ़ोटो लें. वह फ़ाइल यहां दिखेगी."

    // HomeSections
    override val showAll = "सभी दिखाएं"

    override fun sectionSpoken(title: String, subtitle: String) = "$title, $subtitle"

    override val openAction = "खोलें"

    override fun personTileSpoken(name: String, photos: Long) = "$name, ${photos(photos)}"

    override fun browseTileSpoken(label: String, count: String) = "$label, $count"

    override val onThisDay = "आज के दिन"

    override fun onThisDaySpoken(year: Int, count: String, date: String) = "$year, तारीख: $date, $count"

    override val showThem = "उन्हें दिखाएं"

    override val hideOnThisDay = "“आज के दिन” छिपाएं"

    override val starredSection = "स्टार वाली फ़ाइलें"

    // ReadingPermission
    override val notificationPermissionAsk = "Retriever के फ़ाइलें प्रोसेस करते समय सूचना दिखाएं?"

    // ModelDownload
    override val searchModelDownloadTitle = "Retriever के लिए खोज का डाउनलोड"

    // ModelRepository
    override val downloadStopped = "डाउनलोड रुक गया. अपना कनेक्शन देखें और फिर से कोशिश करें."

    override val downloadDamaged = "डाउनलोड खराब हो गया. फिर से कोशिश करें."

    override fun needsFreeSpace(gb: String) = "$gb GB खाली जगह चाहिए. थोड़ी जगह खाली करें, फिर से कोशिश करें."

    // SearchResults
    override fun likeFile(name: String) = "$name जैसी"

    override val photoYoureSearchingWith = "वह फ़ोटो जिससे आप खोज रहे हैं"

    override val stopSearchingByPhoto = "फ़ोटो से खोजना बंद करें"

    override val searchWordsAsTyped = "इसके बजाय, लिखे गए शब्द ही खोजें"

    override val photoSearchAfterSetup = "सेटअप पूरा होने पर फ़ोटो से खोज काम करेगी."

    override val matchingExactWordsForNow = "अभी हूबहू शब्दों से मिलान हो रहा है. विवरण से खोज के नतीजे जल्द ही वापस आएंगे."

    override fun noMatchesIn(scope: String) = "${scopeOblique(scope)} में कोई मिलान नहीं"

    override val nothingLikePhotoThere = "वहां इस फ़ोटो जैसा कुछ नहीं है."

    override fun nothingForThere(query: String) = "वहां “$query” के लिए कुछ नहीं मिला."

    override val searchAllFiles = "सभी फ़ाइलों में खोजें"

    override val nothingLikeThisYet = "अभी ऐसा कुछ नहीं है"

    override val tryAnotherPhoto = "कोई दूसरी फ़ोटो आज़माएं."

    override val tryFewerWords = "कम शब्द आज़माएं"

    override val filterAll = "सभी"

    override val filterStarred = "स्टार वाली"

    override val expanded = "खुला हुआ"

    override val collapsed = "सिमटा हुआ"

    override fun lessLikelyMatches(n: Long) = "कम मिलते-जुलते नतीजे (${formatCount(n)})"

    override val moreDocuments = "और दस्तावेज़"

    override val recordings = "रिकॉर्डिंग"

    override val documentsAndMore = "दस्तावेज़ और अन्य फ़ाइलें"

    // SearchScreen
    override val searchWithPhoto = "फ़ोटो से खोजें"

    override val clearSearch = "खोज मिटाएं"

    override val recentSearches = "हाल की खोजें"

    override fun removeFromHistory(query: String) = "इतिहास से $query हटाएं"

    override val searchAction = "खोजें"

    override val clearHistory = "इतिहास मिटाएं"

    // SearchViewModel
    override val likeYourPhoto = "आपकी फ़ोटो जैसी"

    override val couldntOpenPhoto = "यह फ़ोटो खुल नहीं सकी. कोई दूसरी चुनें."

    override val thatDidntWork = "ऐसा नहीं हो सका. फिर से कोशिश करें."

    // SettingsScreen
    override val includedFilesFooter = "$kindOff छोड़ा गया फ़ोल्डर खोज में शामिल नहीं होता."

    override val onlyWhenYouChoose = "सिर्फ़ जब आप प्रोसेसिंग शुरू करें या फिर से चालू करें।"

    override val showProcessingProgress = "प्रोसेसिंग की प्रगति दिखाएं"

    override val processingProgressNotice = "Retriever जब आपकी फ़ाइलें प्रोसेस करे, तब प्रगति देखने के लिए बिना आवाज़ वाली एक सूचना चालू करें."

    override val notificationsOn = "चालू"

    override val includedFiles = "शामिल फ़ाइलें"

    override val noneExcluded = "कोई नहीं"

    override fun includeNamed(folder: String) = "$folder शामिल करें"

    override val includeFolder = "फ़ोल्डर शामिल करें"

    override val privacy = "निजता"

    override val saveRecentSearches = "हाल की खोजें सेव करें"

    override val savedOnDeviceShortcuts = "इसी फ़ोन पर सेव होती हैं. खोज के शॉर्टकट के लिए भी इस्तेमाल होती हैं."

    override val clearRecentSearches = "हाल की खोजें मिटाएं"

    override val clearSearchesAndShortcutsAsk = "हाल की खोजें और शॉर्टकट मिटाएं?"

    override val clear = "मिटाएं"

    override val storage = "स्टोरेज"

    override val appearance = "दिखावट"

    override val wallpaperColours = "वॉलपेपर के रंग"

    override val wallpaperColoursOff = "बंद होने पर Retriever का सुनहरा नारंगी रंग इस्तेमाल होता है."

    override val about = "ऐप के बारे में"

    override val version = "वर्शन"

    override val openSourceNotices = "ओपन सोर्स नोटिस"

    override val removeDownloadConfirm = "डाउनलोड हटाएं"

    override fun howItWorksBody(words: DeviceWords) = "${wholeDeviceNote(words)} ${onDevice(words)}. " +
        "खोज और लिखे हुए शब्द पहचानने की सुविधाओं के लिए इंटरनेट से डाउनलोड करना पड़ सकता है. " +
        "क्लाउड पर रखी फ़ाइलें खोलने के लिए उन्हें रखने वाला ऐप भी इंटरनेट इस्तेमाल कर सकता है."

    override val howItWorksTimes = "यह आपकी फ़ाइलें उन समयों पर प्रोसेस करता है जो आप “$automaticProcessingPreferenceTitle” में चुनते हैं. " +
        "लंबी फ़ाइलें थोड़ा-थोड़ा करके प्रोसेस होती हैं: पहले आप उन्हें उनके शुरुआती हिस्से से ढूंढ सकते हैं."

    override val runSetupCheck = "सेटअप जांचें"

    override val tuningFewMinutes = "सेट हो रहा है… ज़्यादा से ज़्यादा कुछ मिनट"

    override fun tuneFailedMessage(message: String) = "सेटअप नहीं हो सका: $message"

    override val processingIsSetUp = "प्रोसेसिंग सेट हो गई है"

    override val adjustsProcessingSpeed = "इस फ़ोन के हिसाब से प्रोसेसिंग की स्पीड सेट करता है."

    override val useMobileDataForDownloads = "डाउनलोड के लिए मोबाइल डेटा इस्तेमाल करें"

    override val downloadsWaitForWifiShort = "बंद: डाउनलोड Wi-Fi का इंतज़ार करते हैं."

    override val loading = "लोड हो रहा है…"

    // SettingsViewModel
    override val couldntSaveChange = "यह बदलाव सेव नहीं हो सका. फिर से कोशिश करें."

    override val searchDownloadRemoved = "खोज का डाउनलोड हटा दिया गया"

    // Starring
    override val starAdded = "स्टार लगाया गया"

    override val starRemoved = "स्टार हटाया गया"

    // StatusScreen
    override val checkingYourFiles = "आपकी फ़ाइलें देखी जा रही हैं…"

    override val hide = "छिपाएं"

    override val reviewFiles = "फ़ाइलें देखें"

    override val letProcessWhileAway = "जब आप Retriever इस्तेमाल न कर रहे हों, तब भी उसे फ़ाइलें प्रोसेस करने दें"

    override fun batteryMayStop(chargingOnly: Boolean) = "बैटरी बचाने के लिए आपका फ़ोन इसे रोक सकता है." + sentenceGap +
        if (chargingOnly) automaticChargingPreferenceDetail else automaticProcessingPreferenceDetail

    override val settingsRowDetail = "क्या प्रोसेस करना है, स्टोरेज और अन्य विकल्प"

    override val chooseMorePhotos = "और फ़ोटो चुनें"

    override val foldersYouAdded = "आपके जोड़े गए फ़ोल्डर"

    override fun removeNamed(name: String) = "$name हटाएं"

    override val allowAccessToAllFiles = "सभी फ़ाइलों का ऐक्सेस दें"

    // Format
    override fun starredScope(kind: String?) = kind?.let {
        val feminine = it == kindsInSentence(Kind.PHOTO) || it == kindsInSentence(Kind.OTHER)
        if (feminine) "स्टार वाली $it" else "स्टार वाले $it"
    } ?: starredFiles

    override fun pageBadge(page: Int) = "पेज $page"

    // FileRow
    override fun openAtPage(page: Int) = "पेज $page पर खोलें"

    override fun playFrom(time: String) = "$time से चलाएं"

    override fun kindSizeDate(kind: String, size: String, date: String) = "$kind · $size · $date"

    override fun fileRowSpoken(kind: String, name: String, date: String, position: String?, note: String?) =
        "$kind, $name, $date" + (position?.let { ", $it" } ?: "") + (note?.let { ", $it" } ?: "")

    // ReadingFiles
    override val fileActions = "फ़ाइल के विकल्प"

    override fun fileSpoken(name: String, kind: String, date: String) = "$name, $kind, $date"

    // DocScreen
    override val notProcessedThisYet = "Retriever ने इसे अभी प्रोसेस नहीं किया है"

    override val waitingItsTurn = "यह दूसरी नई फ़ाइलों के साथ अपनी बारी का इंतज़ार कर रही है. इसे अभी देखने के लिए किसी दूसरे ऐप में खोलें."

    override val noTextFound = "Retriever को इस फ़ाइल में कोई टेक्स्ट नहीं मिला"

    override val scanOrPictures = "हो सकता है यह स्कैन की गई फ़ाइल हो या इसमें सिर्फ़ तस्वीरें हों. इसे देखने के लिए किसी दूसरे ऐप में खोलें."

    override val couldntOpenFile = "यह फ़ाइल खुल नहीं सकी"

    override val maybeMovedOrDeleted = "हो सकता है इसे कहीं और ले जाया गया हो या मिटा दिया गया हो."

    override val previousMatch = "पिछला मिलान"

    override fun matchOf(at: Int, total: Int) = "मिलान $at (कुल $total)"

    override fun matchCount(n: Long) = plural(n, "मिलान")

    override val nextMatch = "अगला मिलान"

    // Opener
    override val noAppOpensFile = "इस फ़ोन पर कोई भी ऐप यह फ़ाइल नहीं खोल सकता"

    // PdfPageScreen
    override fun pdfSubtitle(page: Int, count: Int, matchedPage: Int?) = (matchedPage?.let { "${matchOnPage(it)} · " } ?: "") + pageOf(page, count)

    override val closePage = "पेज बंद करें"

    override val pdfNeedsPassword = "इस PDF के लिए पासवर्ड चाहिए"

    override val pdfFindableByName = "Retriever इसे अब भी नाम से ढूंढ सकता है. आप इसे किसी दूसरे ऐप में खोल सकते हैं."

    override val folderAccessChanged = "इस फ़ोल्डर का ऐक्सेस बदल गया है"

    override val chooseFolderAgain = "यह फ़ाइल खोलने के लिए फ़ोल्डर फिर से चुनें."

    override val fileNoLongerAvailable = "यह फ़ाइल अब उपलब्ध नहीं है"

    override val goBack = "वापस जाएं"

    override val couldntOpenPdf = "यह PDF खुल नहीं सका"

    override fun pageZoomed(page: Int) = "पेज $page, ज़ूम किया गया"

    override fun pageOf(page: Int, count: Int) = "पेज $page (कुल $count)"

    override val zoomPage = "पेज ज़ूम करें"

    override fun pageSpoken(label: String, matched: Boolean) = label + if (matched) ", यहां आपकी खोज का मिलान हुआ" else ""

    override val matchedHere = "यहां मिलान हुआ"

    // PhotoScreen
    override val couldntShowPhoto = "यह फ़ोटो दिखाई नहीं जा सकी"

    // PlayerScreen
    override fun matchAt(time: String) = "$time पर मिलान"

    override fun couldntPlay(audio: Boolean) = if (audio) "यह रिकॉर्डिंग चल नहीं सकी" else "यह वीडियो चल नहीं सका"

    override val maybeMovedOrCantPlay = "हो सकता है इसे कहीं और ले जाया गया हो या मिटा दिया गया हो, या यह फ़ोन इसे नहीं चला सकता."

    // ViewerQueue
    override fun positionOf(at: Int, count: Int) = "$count में से $at"

    // ViewerTopBar
    override val previousFile = "पिछली फ़ाइल"

    override val nextFile = "अगली फ़ाइल"

    // AppModel+Loop
    override fun keepProcessingFailed(words: DeviceWords) = "जब आप Retriever इस्तेमाल नहीं कर रहे थे, तब iOS प्रोसेसिंग जारी नहीं रख सका. " +
        "इसे खुला रखें, या अपने ${phone(words)} के चार्ज होते समय इसे फ़ाइलें प्रोसेस करने दें."

    // AppModel+Settings
    override val couldntChangePersonCurly = "इस व्यक्ति में बदलाव नहीं हो सका. फिर से कोशिश करें."

    // FolderSource
    override val aFolder = "एक फ़ोल्डर"

    // SearchModel
    override val starredFiles = "स्टार वाली फ़ाइलें"

    // Copy
    override val photosAccessOff = "फ़ोटो का ऐक्सेस बंद है"

    override val photosOffNoteDetail = "पहले से प्रोसेस हुई फ़ाइलों में खोज अब भी काम करती है. अपनी फ़ोटो और स्क्रीनशॉट भी खोजने के लिए इसे चालू करें."

    override val seesOnlyPickedPhotos = "Retriever सिर्फ़ आपकी चुनी हुई फ़ोटो देखता है"

    override val canSearchJustThose = "यह सिर्फ़ उन्हीं में खोज सकता है."

    override val folderAdded = "फ़ोल्डर जोड़ दिया गया. Retriever इसके अंदर की फ़ाइलें प्रोसेस करेगा."

    override fun folderOverlaps(name: String) = "इस फ़ोल्डर की कुछ फ़ाइलें “$name” में भी हैं, जिसमें Retriever पहले से खोजता है."

    override val folderRefused = "iOS ने Retriever को वह फ़ोल्डर रखने नहीं दिया. कोई दूसरा फ़ोल्डर आज़माएं."

    override fun backgroundNote(words: DeviceWords) =
        "Retriever खुला रहने पर आपकी फ़ाइलें प्रोसेस करता है, और iOS अनुमति दे तो कभी-कभी तब भी, जब आप इसे इस्तेमाल नहीं कर रहे होते. " +
            "बैकग्राउंड में अपने-आप होने वाली प्रोसेसिंग आपके ${phone(
                words,
            )} के चार्ज होने का इंतज़ार करती है. प्रोसेसिंग शुरू करने या फिर शुरू करने में थोड़ी बैटरी लगती है."

    override val screenKeptOn = "जब Retriever आपकी फ़ाइलें प्रोसेस करता है, तब आपकी स्क्रीन चालू रहती है. आप इसे सेटिंग में बंद कर सकते हैं."

    override fun showingLabels(labels: List<String>) = "दिखाया जा रहा है: " + labels.joinToString(" · ")

    override val searchMyExactWords = "मेरे हूबहू शब्द खोजें"

    override fun processedSoFar(n: Long) = "अब तक ${readCount(n)}"

    // Format
    override fun opensAtPage(page: Int) = "फ़ाइल पेज $page पर खुलेगी"

    override fun playsFrom(time: String) = "शुरुआत $time से होगी"

    override val opensHint = "फ़ाइल खुलेगी"

    // BrowseView
    override val couldntLoadFiles = "फ़ाइलें लोड नहीं हो सकीं"

    override val wentWrongReadingList = "सूची लोड करते समय कुछ गड़बड़ हो गई."

    override fun sizeFolderDate(size: String, folder: String, date: String) = "$size · $folder · $date"

    override val sortBy = "इस क्रम में लगाएं"

    override val sortMenu = "क्रम"

    override val ok = "ठीक है"

    // DuplicatesView
    override val noDuplicatesMessage =
        "Retriever ने आपके जोड़े गए फ़ोल्डरों की फ़ाइलों की तुलना की. 1 MB से बड़ी कोई कॉपी नहीं मिली. फ़ोटो की जांच नहीं होती."

    override val lookingForCopies = "कॉपियाँ ढूंढी जा रही हैं"

    override val thisCanTakeAMinute = "इसमें एक मिनट लग सकता है."

    override fun deleteFilesAsk(n: Long) = if (n == 1L) "1 फ़ाइल मिटाएं?" else "${files(n)} मिटाएं?"

    override val cantBeRecovered = "इन्हें वापस नहीं लाया जा सकता."

    override val comparesFilesNote = "आपके जोड़े गए फ़ोल्डरों की फ़ाइलों की तुलना करता है, फ़ोटो की नहीं. हर फ़ाइल की सबसे पुरानी कॉपी रखी जाती है. " +
        "क्या हटेगा, यह बदलने के लिए किसी कॉपी पर टैप करें."

    override fun folderAndDate(folder: String, date: String) = "$folder · $date"

    override val selectedToDeleteHint = "मिटाने के लिए चुनी गई. रखने के लिए दो बार टैप करें."

    override val keptHint = "रखी गई. मिटाने के लिए चुनने को दो बार टैप करें."

    override fun filesWerentDeleted(n: Long) = if (n == 1L) "1 फ़ाइल नहीं मिटाई गई" else "${files(n)} नहीं मिटाई ${agree(n, "गई", "गईं")}"

    // FileActions
    override val couldntOpenFolderUnavailable = "यह फ़ाइल खुल नहीं सकी. हो सकता है इसका फ़ोल्डर उपलब्ध न हो."

    override val photosAppName = "फ़ोटो"

    override val moreActions = "और विकल्प"

    override val couldntFinish = "पूरा नहीं हो सका"

    // FoldersView
    override val foldersWithFiles = "फ़ाइलों वाले फ़ोल्डर"

    override val couldntLoadFolderList = "फ़ोल्डरों की सूची लोड नहीं हो सकी. फिर से कोशिश करें"

    override val removeFolder = "हटाएं"

    override val cantOpenFolderAnyMore = "Retriever अब यह फ़ोल्डर नहीं खोल सकता. इसे हटाएं, फिर दोबारा जोड़ें."

    override val alsoSearchesOwnFolder = "Retriever फ़ाइलें ऐप में अपने फ़ोल्डर में भी खोजता है. यहां से कोई फ़ोल्डर हटाने पर वह कभी मिटता नहीं है."

    override val couldntLoadFolders = "फ़ोल्डर लोड नहीं हो सके"

    override val wentWrongReadingFolders = "आपके फ़ोल्डर लोड करते समय कुछ गड़बड़ हो गई."

    override val foldersAppearFiles = "Retriever को आपकी फ़ाइलों के फ़ोल्डर मिलते ही वे यहां दिखेंगे."

    override fun stopSearchingForget(n: Long, name: String) = "Retriever $name में मौजूद ${files(n)} भूल जाएगा. आप इसे यहां से वापस ला सकते हैं."

    override fun removeFolderAsk(name: String) = "$name हटाएं?"

    override val removeFolderMessage =
        "Retriever इसमें खोजना बंद कर देगा और वहां जो प्रोसेस किया था, उसे भूल जाएगा. फ़ोल्डर और उसकी फ़ाइलें जहां हैं, वहीं रहेंगी."

    // HomeSections
    override val showsAll = "सभी दिखाता है"

    override val showsThem = "उन्हें दिखाता है"

    override val hideOnThisDayIos = "“आज के दिन” छिपाएं"

    override fun homeTileSpoken(kind: String, name: String, date: String, starred: Boolean) = "$kind, $name, $date" + if (starred) ", स्टार लगा है" else ""

    // HomeView
    override val addFolderOrPhotos = "कोई फ़ोल्डर जोड़ें, या Retriever को अपनी फ़ोटो देखने दें, और यह उनके अंदर की सामग्री ढूंढ लेगा."

    override val allowPhotosAccess = "फ़ोटो का ऐक्सेस दें"

    override val turnOnPhotosAccess = "फ़ोटो का ऐक्सेस चालू करें"

    override val onThisDayTitle = "आज के दिन"

    override val openSettings = "सेटिंग खोलें"

    override val alsoSearchesFolders = "Retriever आपके जोड़े गए फ़ोल्डरों में भी खोजता है."

    // ReadingFiles
    override val openFileHint = "फ़ाइल खोलें"

    // ResultViews
    override fun sharedPhotoName(ext: String?) = "फ़ोटो.${ext ?: "jpg"}"

    // RootView
    override val setupTitle = "सेटअप"

    // SearchView
    override val docsShort = "दस्तावेज़"

    override val searchByPhoto = "फ़ोटो से खोजें"

    override val removeSearch = "हटाएं"

    override val removeFromRecentSearches = "हाल की खोजों से हटाएं"

    override val somethingWentWrongSearching = "खोजते समय कुछ गड़बड़ हो गई."

    override val lookingEllipsis = "खोज जारी है…"

    override fun turnOnToSearchScope(scope: String) = "सेटिंग में इसे चालू करें, ताकि Retriever ${scopeOblique(scope)} में भी खोज सके."

    override val noMatchesInPickedPhotos = "आपकी चुनी हुई फ़ोटो में कोई मिलान नहीं"

    override val onlySeesChosenPhotos = "Retriever सिर्फ़ आपकी चुनी हुई फ़ोटो देख सकता है. ज़्यादा फ़ोटो में खोजने के लिए और फ़ोटो चुनें."

    override val notQuiteReady = "अभी पूरी तरह तैयार नहीं"

    override fun nothingLikeThatOn(words: DeviceWords) = "इस ${phone(words)} पर अभी ऐसा कुछ नहीं है."

    override fun nothingInScopeFits(scope: String) = "${scopeOblique(scope)} में ऐसा कुछ नहीं है. अपनी सभी फ़ाइलों में खोजें."

    override fun noStarredMatch(query: String) = "स्टार वाली कोई फ़ाइल “$query” से मेल नहीं खाती"

    override val onlyStarredShowHere = "यहां सिर्फ़ वे फ़ाइलें दिखती हैं जिन पर आपने स्टार लगाया है. अपनी सभी फ़ाइलों में खोजें."

    override fun nothingLikePhotoIn(scope: String) = "${scopeOblique(scope)} में इस फ़ोटो जैसा कुछ नहीं"

    override fun noMatchesFor(query: String) = "“$query” के लिए कोई मिलान नहीं"

    override val tryAllFilesInstead = "इसके बजाय अपनी सभी फ़ाइलों में खोजें."

    override fun nothingInScopeMatches(scope: String) = "${scopeOblique(scope)} में कोई मिलान नहीं. अपनी सभी फ़ाइलों में खोजें."

    override val likeThisPhoto = "इस फ़ोटो जैसी"

    override val photosOffResults = "फ़ोटो का ऐक्सेस बंद है, इसलिए इन नतीजों में आपकी फ़ोटो शामिल नहीं हैं."

    // SettingsView
    override val cleared = "मिटा दी गईं"

    override fun savedOnDeviceOnly(words: DeviceWords) = "सिर्फ़ इस ${phone(words)} पर सेव होती हैं. कहीं कुछ नहीं भेजा जाता."

    override val clearRecentSearchesAsk = "हाल की खोजें मिटाएं?"

    override fun privacyBody(words: DeviceWords) =
        "Retriever आपकी फ़ाइलें इसी ${phone(words)} पर प्रोसेस करता है. आपकी खोजें, स्टार और फ़ाइलों के अंदर जो मिलता है, सब यहीं रहता है. " +
            "विवरण से खोज डाउनलोड करने और iCloud में रखी फ़ाइलें खोलने के लिए इंटरनेट इस्तेमाल हो सकता है."

    override val longFilesBitAtATime =
        "लंबी फ़ाइलें थोड़ा-थोड़ा करके प्रोसेस होती हैं: पहले आप उन्हें उनके शुरुआती हिस्से से ढूंढ सकते हैं, और बाकी हिस्सा बाद में आता है."

    override val onlyWhenYouChooseAnyFile = "सिर्फ़ जब आप प्रोसेसिंग शुरू करें या फिर से चालू करें। आप किसी भी फ़ाइल को उसके नाम से हमेशा खोज सकते हैं।"

    override val keepScreenOnToggle = "प्रोसेसिंग के दौरान स्क्रीन चालू रखें"

    override fun adjustProcessingFor(words: DeviceWords) = "इस ${phone(words)} के लिए प्रोसेसिंग सेट करें"

    override val runSetupCheckAgain = "सेटअप जांच फिर से चलाएं."

    override val kindsFooterVideos = "$kindOff वीडियो: सिर्फ़ तस्वीरें, आवाज़ नहीं."

    override fun includeQuoted(name: String) = "“$name” शामिल करें"

    override val doesntLookInThese = "Retriever इनमें खोज नहीं करता. आपकी फ़ाइलें जहां हैं, वहीं रहती हैं."

    override val downloadsOnWifiOrData = "डाउनलोड Wi-Fi या मोबाइल डेटा पर होते हैं."

    override val downloadsWaitForWifi = "डाउनलोड Wi-Fi का इंतज़ार करते हैं, ताकि आपका डेटा खर्च न हो."

    override val noticesUnavailable = "Retriever ओपन सोर्स सॉफ़्टवेयर पर बना है. इसके लाइसेंस अभी नहीं दिखाए जा सके."

    // SetupView
    override val searchesForThis = "इसे खोजता है"

    // SplitRoot
    override val searchTab = "खोजें"

    // StatusView
    override val onlySeesPickedPhotos = "Retriever सिर्फ़ आपकी चुनी हुई फ़ोटो देख सकता है."

    override val photosOffCantSearch = "फ़ोटो का ऐक्सेस बंद है, इसलिए Retriever आपकी फ़ोटो में नहीं खोज सकता."

    // TextReader
    override val notProcessedOpenOriginal = "Retriever ने यह फ़ाइल अभी प्रोसेस नहीं की है. आप इसके बजाय असली फ़ाइल खोल सकते हैं."

    override val noTextToShow = "दिखाने के लिए कोई टेक्स्ट नहीं"

    override val noReadableText = "Retriever को इस फ़ाइल में पढ़ने लायक कोई टेक्स्ट नहीं मिला."

    override val couldntShowText = "टेक्स्ट नहीं दिखाया जा सका"

    override val wentWrongReadingIt = "इसे लोड करते समय कुछ गड़बड़ हो गई. थोड़ी देर में फिर से कोशिश करें."

    override val textFromThisFile = "इस फ़ाइल का टेक्स्ट"

    override val onlyBeginningIncluded = "इस फ़ाइल का सिर्फ़ शुरुआती हिस्सा शामिल है."

    // Viewer
    override fun matchOnPage(page: Int) = "पेज $page पर मिलान"

    override val anotherAppCanAsk = "कोई दूसरा ऐप इसका पासवर्ड पूछ सकता है."

    override val damagedOrUnsupported = "हो सकता है यह खराब हो, या ऐसे फ़ॉर्मैट में हो जिसे Retriever नहीं दिखा सकता."

    override fun couldntPlayIos(audio: Boolean) = if (audio) "यह ऑडियो चल नहीं सका" else "यह वीडियो चल नहीं सका"

    override val iCloudOrCantPlay = "हो सकता है यह सिर्फ़ iCloud में हो और कनेक्शन न हो, या ऐसे फ़ॉर्मैट में हो जिसे Retriever नहीं चला सकता."

    override fun photoSpoken(kind: String, name: String) = "$kind, $name"

    override val turnOnToSeePhotos = "अपनी फ़ोटो यहां देखने के लिए सेटिंग में इसे चालू करें."

    override val onlyInICloudNow = "हो सकता है यह सिर्फ़ iCloud में हो, और अभी कनेक्शन न हो."

    override val downloadingFromICloud = "iCloud से डाउनलोड हो रहा है"
}
