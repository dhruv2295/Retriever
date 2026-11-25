package app.retriever.core

import app.retriever.core.Words.NBSP

/**
 * German. A machine translation awaiting native review; nothing here has been checked by a native speaker yet.
 * Reviewed by a second model (Codex).
 *
 * Glossary, for the reviewer to hold consistent:
 * - process → verarbeiten (Verarbeitung); never lesen, analysieren, indexieren, scannen.
 * - search by description → Suche per Beschreibung (feature name, always this phrase).
 * - found by name → über den Namen gefunden; found by what's inside → über den Inhalt gefunden.
 * - processed in part → teilweise verarbeitet.
 * - audio file → Audiodatei; duplicates → doppelte Dateien; copy → Kopie.
 * - phone → Handy (das Handy, like das iPhone/das iPad, so one article serves all three: dein, dieses, deinem).
 * - Battery Saver → Energiesparmodus (Android); Low Power Mode → Stromsparmodus (iOS). Both der.
 * - "Allow access to manage all files" → „Zugriff zum Verwalten aller Dateien zulassen“ (Android's setting).
 * - Starred → Markiert (the collection), star → markieren / Markierung.
 * - People → Personen; On this day → An diesem Tag; Home → Startseite; Browse → Durchsuchen.
 * - While charging → Beim Laden; Also on battery → Auch im Akkubetrieb.
 * - WLAN, as Android and iOS name Wi-Fi in German, including in example queries.
 * - Retriever is "es" (das Programm) wherever a pronoun refers back to it.
 * - du throughout; buttons are infinitives ("Herunterladen"), iOS VoiceOver hints are third person ("Öffnet").
 * - Plural: 1 singular, everything else plural. Percent as "50 %" with a non-breaking space.
 */
internal object German : Strings {
    private fun plural(n: Long, one: String, many: String): String = "${formatCount(n)} ${if (n == 1L) one else many}"

    /** The device noun: das Handy, das iPhone, das iPad; all neuter, so the articles agree for every device. */
    private fun device(words: DeviceWords): String = if (words.ios) words.device else "Handy"

    /** Both names are masculine: der Stromsparmodus, der Energiesparmodus. */
    private fun powerSave(words: DeviceWords): String = if (words.ios) "Stromsparmodus" else "Energiesparmodus"

    private fun withPercent(p: Int): String = "$p$NBSP%"

    override val sentenceGap = " "

    override val modified = "Geändert"
    override val taken = "Aufgenommen"

    override fun files(n: Long) = plural(n, "Datei", "Dateien")

    override fun photos(n: Long) = plural(n, "Foto", "Fotos")

    override fun videos(n: Long) = plural(n, "Video", "Videos")

    override fun photosAndVideos(n: Long) = "${formatCount(n)} Fotos und Videos"

    override fun readOf(done: Long, total: Long) = "${formatCount(done)} von ${files(total)} verarbeitet"

    override fun meaningOf(done: Long, total: Long) = "${formatCount(done)} von ${files(total)} bereit für die Suche per Beschreibung"

    override fun facesOf(done: Long, total: Long) = "${formatCount(done)} von ${photos(total)} auf Personen geprüft"

    override val processedInPart = "Teilweise verarbeitet"
    override val couldntBeOpened = "Öffnen fehlgeschlagen"
    override val stillToProcess = "Noch zu verarbeiten"
    override val longFilesStillBeingFinished = "Lange Dateien noch in Verarbeitung"
    override val checkedForPeople = "Auf Personen geprüft"
    override val stillToCheckForPeople = "Noch auf Personen zu prüfen"

    override fun largeFiles(n: Long) = "${files(n)} über 100${NBSP}MB"

    override fun readCount(read: Long) = "${files(read)} verarbeitet"

    override fun partlyReadCount(n: Long) = "${formatCount(n)} teilweise verarbeitet"

    override fun extent(extent: ReadExtent?) = when (extent) {
        ReadExtent.FIRST_PART -> "Teilweise verarbeitet · nur der Anfang"
        ReadExtent.MEANING_LIMITED -> "Teilweise verarbeitet · Suche per Beschreibung nicht abgeschlossen"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "Teilweise verarbeitet · nur der Anfang, ohne Suche per Beschreibung"
        ReadExtent.FIRST_PART_DEEPENING -> "Teilweise verarbeitet · bisher nur der Anfang"
        ReadExtent.DEEPENING -> "Teilweise verarbeitet · Suche per Beschreibung folgt noch"
        ReadExtent.WHOLE, null -> null
    }

    override val couldntProcessLabel = "Verarbeitung fehlgeschlagen"
    override val notProcessedYet = "Noch nicht verarbeitet"
    override val partRestToCome = "Teilweise verarbeitet · der Rest folgt noch"
    override val partCouldntFinish = "Teilweise verarbeitet · nicht abgeschlossen"

    override fun kindLabel(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "Dokument"
        Kind.SCREENSHOT -> "Screenshot"
        Kind.PHOTO -> "Foto"
        Kind.AUDIO -> "Audiodatei"
        Kind.VIDEO -> "Video"
        Kind.OTHER -> "Datei"
    }

    override fun kindLabelPlural(kind: Kind) = when (kind) {
        Kind.PDF -> "PDFs"
        Kind.DOC -> "Dokumente"
        Kind.SCREENSHOT -> "Screenshots"
        Kind.PHOTO -> "Fotos"
        Kind.AUDIO -> "Audiodateien"
        Kind.VIDEO -> "Videos"
        Kind.OTHER -> "Andere Dateien"
    }

    override fun kindsInSentence(kind: Kind) = if (kind == Kind.OTHER) "andere Dateien" else kindLabelPlural(kind)

    override fun spokenPage(page: Int) = "Seite $page"

    override fun spokenTime(hours: Long, minutes: Long, seconds: Long): String {
        val parts = listOf(
            Triple(hours, "Stunde", "Stunden"),
            Triple(minutes, "Minute", "Minuten"),
            Triple(seconds, "Sekunde", "Sekunden"),
        ).filter { it.first > 0 }.map { (n, one, many) -> plural(n, one, many) }
        return "bei " + parts.joinToString(" ").ifEmpty { "0 Sekunden" }
    }

    override fun textOnlyNote(ramGb: Int, words: DeviceWords) =
        "Dieses ${device(words)} hat $ramGb${NBSP}GB Arbeitsspeicher, zu wenig für die Suche per Beschreibung. " +
            "Darum findet Retriever Dateien über ihre Namen und die Wörter darin."

    override fun notReadYet(files: Long) = "${formatCount(files)} ausstehend"

    override fun couldntProcess(files: Long) =
        if (files == 1L) "${files(files)} konnte nicht verarbeitet werden" else "${files(files)} konnten nicht verarbeitet werden"

    override val details = "Details"
    override val starting = "Wird gestartet…"
    override val processingNow = "Wird gerade verarbeitet"
    override val recentlyProcessed = "Zuletzt verarbeitet"
    override val processingYourFiles = "Deine Dateien werden verarbeitet"
    override val pause = "Pausieren"
    override val processTheseKinds = "Diese Dateitypen verarbeiten"

    override val continueAction = "Weiter"
    override val tryAgain = "Erneut versuchen"
    override val download = "Herunterladen"
    override val notNow = "Nicht jetzt"
    override val cancel = "Abbrechen"
    override val done = "Fertig"

    override fun helloTitle(textOnly: Boolean) = if (textOnly) "Finde Dateien anhand ihres Inhalts" else "Finde alles, indem du es beschreibst"

    override val helloBody = "Fotos, Screenshots, Dokumente und Videos in einer Suche."
    override val forExample = "Zum Beispiel"
    override val helloExamples = listOf(
        Words.HelloExample("WLAN-Passwort vom Vermieter", "Screenshot vom 14. März", "Zeigt „WLAN-Passwort: …“"),
        Words.HelloExample("Hund schläft auf dem Sofa", "Foto vom letzten Sonntag", "Über den Inhalt gefunden", needsModel = true),
        Words.HelloExample("mein Mietvertrag", "Mietvertrag 2025.pdf", "Seite 3, die unterschriebene Fassung"),
    )
    override val downloads = "Downloads"

    override fun onDevice(words: DeviceWords) = "Deine Dateien werden auf deinem ${device(words)} verarbeitet"

    override val getStarted = "Starten"

    override fun downloadNote(size: String) = "Enthält einen einmaligen Download von $size über WLAN."

    override fun downloadLine(size: String) = "Einmaliger Download von $size, nur über WLAN"

    override val accessTitle = "Wähle, was Retriever durchsuchen darf"
    override val accessBody = "Wähle eine oder mehrere Optionen. Du kannst später mehr hinzufügen."
    override val allow = "Zulassen"
    override val allowAccess = "Zugriff erlauben"
    override val choose = "Auswählen"
    override val chooseMore = "Mehr auswählen"
    override val settings = "Einstellungen"
    override val documents = "Dokumente"
    override val includesScreenshots = "Inklusive Screenshots"
    override val allowed = "Erlaubt"
    override val somePhotos = "Einige Fotos ausgewählt"
    override val photosOff = "Deaktiviert. In den Einstellungen aktivieren."
    override val pickFolders = "Wähle Ordner in „Dateien“ aus, etwa Downloads"

    override fun foldersAdded(n: Long) = "${plural(n, "Ordner", "Ordner")} hinzugefügt"

    override val androidAccessBody = "So findest du Fotos, Dokumente und Downloads, indem du sie beschreibst."
    override val accessScanning =
        "Retriever beginnt jetzt, deine Fotos und Dateien zu verarbeiten, damit du sie durchsuchen kannst."
    override val startScanning = "Verarbeitung starten"
    override val allFilesOff =
        "Aktiviere „Zugriff zum Verwalten aller Dateien zulassen“, um dein ganzes Handy zu durchsuchen, oder wähle aus, was dabei sein soll."
    override val includeTitle = "Wähle aus, was dabei sein soll"
    override val sharedFilesAndFolders = "Dateien und Ordner auf diesem Handy"
    override val photosAndVideosLabel = "Fotos und Videos"
    override val addFolder = "Ordner hinzufügen"
    override val nothingSelected = "Noch keine Dateien ausgewählt. Wähle Fotos oder einen Ordner aus, um zu beginnen."

    override fun wholeDeviceNote(words: DeviceWords) = "Retriever verarbeitet die Dateien auf diesem ${device(words)}, damit du sie über ihren Inhalt findest."

    override val firstLook = "Ein erster Blick auf deine neuesten Dateien"

    override fun firstSearchHint(state: DescriptionState) =
        if (state == DescriptionState.READY) "Beschreibe ein Foto oder eine Datei" else "Nach Namen oder Wörtern suchen"

    override fun descriptionLine(state: DescriptionState, percent: Int?) = when (state) {
        DescriptionState.DOWNLOADING -> "Suche per Beschreibung wird heruntergeladen" + (percent?.let { " · ${withPercent(it)}" } ?: "")
        DescriptionState.WAITING_FOR_WIFI -> "Der Download der Suche per Beschreibung wartet auf WLAN."
        DescriptionState.PREPARING -> "Suche per Beschreibung wird vorbereitet"
        DescriptionState.FAILED -> "Der Download der Suche per Beschreibung wurde unterbrochen."
        DescriptionState.NO_SPACE -> "Nicht genug freier Speicher für den Download der Suche per Beschreibung."
        DescriptionState.READY, DescriptionState.NOT_STARTED, DescriptionState.UNAVAILABLE -> null
    }

    override val descriptionReady = "Die Suche per Beschreibung ist bereit"
    override val descriptionReadyBody = "Beschreibe jetzt deine neuesten Dateien, um sie zu finden."

    override val homeIntroTitle = "Wie es weitergeht"
    override val homeIntroBody = "Deine Verarbeitung läuft weiter, während du andere Apps nutzt. In der Benachrichtigung kannst du sie pausieren."
    override val gotIt = "Verstanden"
    override val newFiles = "Neue Fotos und Dateien"
    override val newFilesBody = "Neue Dateien werden zu den Zeiten verarbeitet, die du in den Einstellungen wählst."
    override val notifyWhenSearchable = "Benachrichtige mich, wenn alles durchsuchbar ist"
    override val caughtUpNoticeTitle = "Alles ist durchsuchbar"

    override fun caughtUpNotice(files: Long, textOnly: Boolean) =
        (if (files == 1L) "Retriever hat ${files(files)} verarbeitet. " else "Retriever hat alle ${files(files)} verarbeitet. ") +
            if (textOnly) "Finde sie über den Namen oder die Wörter darin." else "Beschreibe etwas, um es zu finden."

    override fun recentFiles(n: Long) = when (n) {
        0L -> "Deine neuesten Dateien"
        1L -> "Deine neueste Datei"
        else -> "Deine ${formatCount(n)} neuesten Dateien"
    }

    override fun otherFiles(rest: Long?) = when (rest) {
        null -> "Der Rest deiner Dateien"
        1L -> "Die andere Datei"
        else -> "Die anderen ${files(rest)}"
    }

    override fun readTime(span: ReadSpan, nights: Int, words: DeviceWords): String {
        val time = when (span) {
            ReadSpan.UNDER_AN_HOUR -> "weniger als eine Stunde"
            ReadSpan.A_FEW_HOURS -> "einige Stunden"
            ReadSpan.A_NIGHT -> "etwa eine Nacht"
            ReadSpan.NIGHTS -> "etwa ${plural(nights.toLong(), "Nacht", "Nächte")}"
        }
        val where = when {
            words.ios -> " mit geöffnetem Retriever am Ladegerät, im Hintergrund länger"
            span >= ReadSpan.A_NIGHT -> " am Ladegerät"
            else -> ""
        }
        return "Alle zu verarbeiten dauert $time$where."
    }

    override fun onceOnDevice(words: DeviceWords) = "Einmalig, auf deinem ${device(words)}"

    override fun onceOnDeviceBody(words: DeviceWords) = "Retriever verarbeitet jede Datei nur einmal, hier auf deinem ${device(words)}, " +
        "und deine Dateien verlassen es nie. Dein ${device(words)} kann dabei warm werden; wird es zu heiß, pausiert Retriever."

    override fun recentFilesBody(state: DescriptionState) = when (state) {
        DescriptionState.READY -> "Finde sie, indem du sie beschreibst."

        DescriptionState.UNAVAILABLE -> "Finde sie über den Namen oder Wörter im Inhalt. Die Suche per Beschreibung braucht ein Gerät mit mehr Arbeitsspeicher."

        DescriptionState.WAITING_FOR_WIFI -> "Finde sie jetzt über den Namen oder Wörter im Inhalt. Ein einmaliger Download wartet auf WLAN."

        DescriptionState.FAILED -> "Finde sie jetzt über den Namen oder Wörter im Inhalt. Der Download wurde unterbrochen."

        DescriptionState.NO_SPACE ->
            "Finde sie jetzt über den Namen oder Wörter im Inhalt. Es ist nicht genug Speicher frei, um die Suche per Beschreibung herunterzuladen."

        // Not "one-time": after a chip build fails on its first run, this is the second download.
        DescriptionState.NOT_STARTED -> "Finde sie jetzt über den Namen oder Wörter im Inhalt. Die Suche per Beschreibung braucht einen Download."

        DescriptionState.DOWNLOADING, DescriptionState.PREPARING ->
            "Finde sie jetzt über den Namen oder Wörter im Inhalt. Die Suche per Beschreibung ist bereit, sobald die Einrichtung fertig ist."
    }

    override fun recentFilesAction(state: DescriptionState, size: String) = when (state) {
        DescriptionState.WAITING_FOR_WIFI -> "Mobile Daten nutzen ($size)"
        DescriptionState.FAILED -> tryAgain
        DescriptionState.NOT_STARTED -> "$size herunterladen"
        else -> null
    }

    override val demoPicking = "Deine neuesten Dateien werden ausgewählt…"
    override val demoHint = "Gib ein Wort ein, das du auf einem deiner neuesten Fotos oder Screenshots oder in einem Dokument gesehen hast."

    override fun demoSearchScope(done: Long, finished: Boolean, words: DeviceWords) = when {
        finished ->
            "Findet jede Datei auf deinem ${device(words)} über ihren Namen und die ${plural(done, "Demodatei", "Demodateien")} auch über ihren Inhalt."

        // The first frame: nothing processed yet, so no "0 Demodateien".
        done == 0L -> "Findet jede Datei auf deinem ${device(words)} über ihren Namen."

        else -> "Findet jede Datei auf deinem ${device(words)} über ihren Namen. Die ${formatCount(done)} " +
            if (done ==
                1L
            ) {
                "bisher verarbeitete Demodatei findest du auch über ihren Inhalt."
            } else {
                "bisher verarbeiteten Demodateien findest du auch über ihren Inhalt."
            }
    }

    override fun seeAll(files: Long) = if (files == 1L) "Datei ansehen" else "Alle ${files(files)} ansehen"

    override val read = "Über den Inhalt gefunden"
    override val foundByName = "Über den Namen gefunden"
    override val foundBy = "Gefunden über"

    // "von" takes the dative plural, and German keeps its nouns capitalised, so the count's noun is its own word here.
    override fun kindRead(read: Long, planned: Long, kind: Kind) = "${formatCount(read)} von ${formatCount(planned)} " +
        when (kind) {
            Kind.PDF -> if (planned == 1L) "PDF" else "PDFs"
            Kind.DOC -> if (planned == 1L) "Dokument" else "Dokumenten"
            Kind.SCREENSHOT -> if (planned == 1L) "Screenshot" else "Screenshots"
            Kind.PHOTO -> if (planned == 1L) "Foto" else "Fotos"
            Kind.AUDIO -> if (planned == 1L) "Audiodatei" else "Audiodateien"
            Kind.VIDEO -> if (planned == 1L) "Video" else "Videos"
            Kind.OTHER -> if (planned == 1L) "anderer Datei" else "anderen Dateien"
        } + " verarbeitet"

    override val tryLabel = "Probier:"

    override val startProcessing = "Verarbeitung starten"

    override val progressChannel = "Verarbeitungsfortschritt"
    override val progressChannelBody = "Zeigt den Fortschritt der Verarbeitung und meldet einmal, wenn die Demo fertig ist."
    override val demoDoneNoticeTitle = "Die Demo ist fertig"

    override fun demoDoneNotice(done: Long, planned: Long) = "${readOf(done, planned)}. Öffne Retriever, um sie zu durchsuchen."

    override val searchField = "Wonach suchst du?"
    override val nameMatches = "Treffer im Namen"
    override val textMatches = "Treffer im Text"
    override val searchingContents = "Dateiinhalte werden durchsucht…"

    override fun copies(n: Int) = plural(n.toLong(), "Kopie", "Kopien")

    override fun openCopyIn(folder: String) = "Kopie in „$folder“ öffnen"

    override val noMatches = "Nichts gefunden"

    override fun noMatchesDetail(byMeaning: Boolean) =
        (if (byMeaning) "Beschreibe den Inhalt oder frag, was darin steht." else "Probier ein Wort aus dem Inhalt oder dem Namen.") +
            " Ergänze eine Zeitangabe, etwa „letzte Woche“."

    override val moreWaiting = "Retriever verarbeitet deine Dateien noch, also such später noch einmal."
    override val searchFailed = "Die Suche konnte nicht abgeschlossen werden"
    override val showingNameMatches = "Treffer im Namen werden angezeigt"
    override val searchingWithoutModel = "Retriever sucht in Dateinamen und bereits verarbeitetem Text."

    override fun matches(labels: List<String>) = "Treffer: " + labels.joinToString(", ")

    private val monthNames = listOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember",
    )
    private val weekdayNames = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

    override fun dateLabel(date: DateFilter) = when (date) {
        DateFilter.Today -> "heute"

        DateFilter.Yesterday -> "gestern"

        is DateFilter.DaysAgo -> if (date.days == 1) "vor 1 Tag" else "vor ${formatCount(date.days.toLong())} Tagen"

        is DateFilter.Period -> when (date.unit) {
            PeriodUnit.WEEK -> if (date.last) "letzte Woche" else "diese Woche"
            PeriodUnit.MONTH -> if (date.last) "letzten Monat" else "diesen Monat"
            PeriodUnit.YEAR -> if (date.last) "letztes Jahr" else "dieses Jahr"
        }

        is DateFilter.Weekday -> when (date.which) {
            Which.LAST -> "letzten ${weekdayNames[date.day]}"
            Which.THIS -> "diesen ${weekdayNames[date.day]}"
            Which.BARE -> weekdayNames[date.day]
        }

        is DateFilter.Month -> monthNames[date.month - 1] + (date.year?.let { " $it" } ?: "")

        is DateFilter.Day -> "${date.day}. ${monthNames[date.month - 1]}" + (date.year?.let { " $it" } ?: "")

        is DateFilter.Year -> "${date.year}"
    }

    /** "von" with the date in the dative: "von letzter Woche", "vom letzten Monat", "aus 2024". */
    private fun fromDate(date: DateFilter): String = when (date) {
        is DateFilter.Period -> when (date.unit) {
            PeriodUnit.WEEK -> if (date.last) "von letzter Woche" else "von dieser Woche"
            PeriodUnit.MONTH -> if (date.last) "vom letzten Monat" else "von diesem Monat"
            PeriodUnit.YEAR -> if (date.last) "vom letzten Jahr" else "von diesem Jahr"
        }

        is DateFilter.Weekday -> when (date.which) {
            Which.LAST -> "vom letzten ${weekdayNames[date.day]}"
            Which.THIS -> "von diesem ${weekdayNames[date.day]}"
            Which.BARE -> "vom ${weekdayNames[date.day]}"
        }

        is DateFilter.Month, is DateFilter.Day -> "vom ${dateLabel(date)}"

        is DateFilter.Year -> "aus ${date.year}"

        else -> "von ${dateLabel(date)}"
    }

    override fun noneFound(kinds: List<Kind>, people: List<String>, date: DateFilter?): String {
        val what = kinds.joinToString(" und ", transform = ::kindsInSentence)
        val of = if (people.isEmpty()) "" else " von " + people.joinToString(" und ")
        val from = date?.let { " ${fromDate(it)}" }.orEmpty()
        return if (what.isEmpty()) "Nichts$of$from" else "Keine $what$of$from"
    }

    override fun relaxedNote(missing: String, scope: String?): String {
        val inflected = scope?.replaceFirst("markierte ", "markierten ")?.replaceFirst("andere Dateien", "anderen Dateien")
        return "$missing, deshalb siehst du hier " + if (scope == null) "alles." else "alle $inflected."
    }

    override fun filterQuery(kind: Kind, date: DateFilter) = "${kindsInSentence(kind)} ${fromDate(date)}"

    override val downloadForDescription = "Suche per Beschreibung laden"

    override val readingStatus = "Verarbeitung"
    override val recentlyAdded = "Zuletzt hinzugefügt"
    override val moreOnPhone = "Durchsuchen"
    override val allFolders = "Alle Ordner"

    override val howItWorks = "Retriever: Funktion und Datenschutz"
    override val whenToRead = "Wann verarbeitet wird"
    override val whatItCanSee = "Was Retriever sehen kann"
    override val foldersToSkip = "Ausgelassene Ordner"
    override val kindOff = "Deaktiviert: Retriever findet diese weiterhin über den Namen, vergisst aber ihren Inhalt. Deine Dateien bleiben unverändert."
    override val showOnThisDay = "„An diesem Tag“ anzeigen (Fotos von diesem Datum aus früheren Jahren)"

    override val people = "Personen"
    override val recognizePeople = "Personen erkennen"
    override val peopleOfferTitle = "Fotos von Personen finden"
    override val peopleOfferBody =
        "Retriever kann die Gesichter in deinen Fotos gruppieren, damit du jemandem einen Namen geben und die Person über den Namen finden kannst. " +
            "Gesichter werden auf diesem Gerät verarbeitet und verlassen es nie."
    override val turnOn = "Aktivieren"
    override fun wholeDeviceTitle(words: DeviceWords) = "Dein ganzes ${device(words)} durchsuchen"
    override val wholeDeviceBody = "Findet auch Dateien in Downloads, WhatsApp und den Ordnern deiner anderen Apps."
    override val allowAllFiles = "Zugriff auf alle Dateien erlauben"
    override val changeWhatsIncluded = "Auswahl ändern"
    override val allFilesChoice = "Alle Dateien"
    override val recommended = "Empfohlen"
    override val allFilesChoiceBody = "Fotos, Dokumente, Downloads und Dateien anderer Apps. Die besten Ergebnisse."
    override val orOnly = "Oder nur"
    override fun peopleChoiceBody(words: DeviceWords) = "Gruppiert Gesichter, damit du nach Namen suchen kannst. Bleibt auf diesem ${device(words)}."
    override fun peopleRowBody(words: DeviceWords) =
        "Finde Fotos danach, wer darauf ist. Gesichter werden auf diesem ${device(words)} gruppiert, nach deinen Dateien."
    override val recognizePeopleDetail =
        "Gruppiert die Gesichter in deinen Fotos, damit du Personen benennen und über ihren Namen finden kannst. Die Gesichter werden auf diesem " +
            "Gerät verarbeitet und nie in einer Datensicherung gespeichert."
    override val forgetPeopleTitle = "Alle Gesichter und Namen vergessen?"
    override val forgetPeopleBody = "Retriever vergisst die gruppierten Gesichter und die Namen, die du vergeben hast. Deine Fotos bleiben unverändert."
    override val forget = "Vergessen"
    override val notThisPerson = "Nicht diese Person"
    override val addAName = "Namen hinzufügen"
    override val name = "Name"
    override val save = "Speichern"
    override val samePersonAs = "Dieselbe Person wie…"
    override val samePersonAsk = "Dieselbe Person?"
    override val samePersonYes = "Dieselbe Person"
    override val samePersonNo = "Nicht dieselbe"
    override val samePersonPrevious = "Vorheriges Paar"
    override val samePersonNext = "Nächstes Paar"

    override fun pairOf(at: Int, of: Int) = "$at von $of"

    override fun facesToGo(left: Long) = "${photos(left)} noch auf Personen zu prüfen"

    override val retrieversData = "Daten von Retriever"
    override val temporaryFiles = "Temporäre Dateien"

    override fun keptOn(size: String, words: DeviceWords) = "$size · auf diesem ${device(words)} gespeichert"

    override val searchByMeaning = "Suche per Beschreibung"
    override val removeDownload = "Such-Download entfernen"
    override val removeDownloadAsk = "Such-Download entfernen?"
    override val namesStillWork = "Die Suche über Dateinamen und bereits verarbeiteten Text funktioniert weiterhin."

    override fun downloaded(size: String) = "Heruntergeladen · $size"

    override fun removeDownloadMessage(size: String?) =
        (if (size != null) "Dadurch werden $size frei. " else "") + "$namesStillWork Für die Suche per Beschreibung ist der Download dann wieder nötig."

    override val freeUpSpace = "Speicher freigeben"
    override val largeFilesTitle = "Große Dateien"
    override val findDuplicates = "Doppelte Dateien finden"
    override val neverDeletes = "Retriever löscht nie etwas, ohne dich vorher zu fragen."

    override fun possibleCopies(n: Long) = if (n == 1L) "${files(n)} könnte eine Kopie sein" else "${files(n)} könnten Kopien sein"

    override fun deleteFrom(words: DeviceWords) = "Vom ${device(words)} löschen"

    override fun deleteConfirm(words: DeviceWords) = "Dadurch wird die Datei von deinem ${device(words)} gelöscht."

    override val upgradeTitle = "Dateien finden, indem du sie beschreibst"
    override val waitingForWifi = "Download wartet auf WLAN"
    override val useMobileData = "Mobile Daten nutzen"
    override val waitForWifi = "Auf WLAN warten"
    override val checkingDownload = "Download wird geprüft…"
    override val downloadFailed = "Download unterbrochen. Prüfe deine Verbindung und versuch es noch einmal."
    override val noSpace = "Nicht genug freier Speicher für den Download"
    override val tuneLeaving = "Du kannst jetzt nach Namen suchen. Die Einrichtung geht weiter, wenn du zurückkommst."

    override fun upgradeBody(size: String, words: DeviceWords) = "Einmaliger Download von $size über WLAN. " +
        "Danach findet eine Suche wie „Sonnenuntergang am Strand“ Fotos auch ohne diese Wörter. Alles bleibt auf diesem ${device(words)}."

    override fun downloadProgress(done: String, total: String, percent: Int) = "$done von $total · ${withPercent(percent)}"

    override fun mobileDataAsk(size: String) = "$size über mobile Daten herunterladen?"

    override fun freeUp(size: String) = "Gib $size frei, um fortzufahren"

    override fun tuneTitle(words: DeviceWords) = "Verarbeitung wird für dieses ${device(words)} eingerichtet"

    override fun tuneBody(words: DeviceWords) = "Etwa eine Minute. Retriever sucht ein Verarbeitungstempo, das zu diesem ${device(words)} passt."

    override fun tuneFailed(words: DeviceWords) = "Die Einrichtung der Verarbeitung für dieses ${device(words)} konnte nicht abgeschlossen werden."

    override fun addingMeaning(done: Long, total: Long) = "Suche per Beschreibung wird ergänzt · ${formatCount(done)} von ${files(total)}"

    override val meaningCatchingUp =
        "Die Suche per Beschreibung funktioniert jetzt. Retriever ergänzt sie nach und nach auch für zuvor verarbeitete Dateien."

    override fun scheduleTitle(schedule: ReadingSchedule) = when (schedule) {
        ReadingSchedule.CHARGING -> "Beim Laden"
        ReadingSchedule.BATTERY -> "Auch im Akkubetrieb"
        ReadingSchedule.MANUAL -> "Wenn ich es starte"
    }

    override fun statusAction(action: StatusAction) = when (action) {
        StatusAction.CONTINUE -> continueAction
        StatusAction.ALLOW_ACCESS -> allowAccess
        StatusAction.STOP -> "Stoppen"
        StatusAction.RESUME -> "Fortsetzen"
        StatusAction.DISMISS -> "Schließen"
        StatusAction.TRY_AGAIN -> tryAgain
        StatusAction.START -> startProcessing
        StatusAction.PAUSE -> pause
    }

    override fun stopCopy(stop: StopReason, words: DeviceWords) = when (stop) {
        StopReason.PAUSED -> "Pausiert"
        StopReason.POWER_SAVE -> "Pausiert · geht weiter, wenn der ${powerSave(words)} aus ist"
        StopReason.HOT -> "Pausiert · geht weiter, wenn dein ${device(words)} abgekühlt ist"
        StopReason.LOW_BATTERY, StopReason.UNPLUGGED -> "Pausiert · geht weiter, wenn du dein ${device(words)} anschließt"
        StopReason.CHARGING_UP -> "Pausiert · geht weiter, wenn der Akku etwas mehr geladen ist"
        StopReason.MANUAL -> "Verarbeitet nur, wenn du die Verarbeitung startest"
        StopReason.DISK_FULL -> "Pausiert, dein ${device(words)} ist voll"
        StopReason.DONE, StopReason.BUDGET -> "Kurze Pause"
    }

    override val noAccessTitle = "Retriever kann deine Dateien nicht sehen"

    override fun noAccessDetail(words: DeviceWords) =
        "Die Suche funktioniert weiter für bereits verarbeitete Dateien." + if (words.ios) " Das öffnet die Einstellungen auf deinem ${device(words)}." else ""

    override val finishSetupTitle = "Einrichtung abschließen"
    override val finishSetupDetail = "Wähle aus, welche Dateien Retriever durchsuchen darf."
    override val downloadPausedTitle = "Download pausiert"

    override fun downloadPausedDetail(words: DeviceWords, size: String) =
        "Es geht weiter, sobald du mit WLAN verbunden bist. Wenn dein ${device(words)} voll ist, braucht der Download $size freien Speicher."

    override fun downloadingTitle(percent: Int?) = "Suche per Beschreibung wird heruntergeladen" + (percent?.let { " · ${withPercent(it)}" } ?: "")

    override val downloadingDetail = "Du kannst Retriever weiter benutzen."

    override val addingDescriptionTitle = "Suche per Beschreibung wird ergänzt"
    override val allFilesProcessedTitle = "Alle Dateien verarbeitet"
    override val gettingReadyTitle = "Wird vorbereitet…"
    override val notNowTitle = "Dateien vorerst über den Namen finden"

    override val lookingForFacesTitle = "Personen in deinen Fotos werden gefunden"
    override val deepeningTitle = "Der Rest einiger langer Dateien wird verarbeitet"
    override val readingPhotoWordsTitle = "Der Text in deinen Fotos wird gelesen"
    override val learningYourFilesTitle = "Der Inhalt deiner Dateien wird erfasst"

    override val stagesHeader = "Was du suchen kannst"

    override fun stageUnlock(stage: Stage, textOnly: Boolean) = when (stage) {
        Stage.NAMES -> "Jede Datei über ihren Namen finden"
        Stage.MEANING -> if (textOnly) "Die Wörter in deinen Dateien durchsuchen" else "Jedes Foto und jede Datei per Beschreibung finden"
        Stage.WORDS -> "Die Wörter in deinen Fotos durchsuchen, etwa Belege und Schilder"
        Stage.PEOPLE -> "Fotos von jemandem über den Namen finden"
        Stage.REST -> "Lange PDFs und Videos bis zum Ende durchsuchen"
    }

    override fun stageStateLabel(state: StageState) = when (state) {
        StageState.DONE -> "Fertig"
        StageState.NOW -> "Läuft"
        StageState.NEXT -> "Folgt"
        StageState.OFF -> "Optional"
    }

    override fun stepOf(step: Int, steps: Int) = "Schritt $step von $steps"

    override fun stageNoteTitle(stage: Stage) = when (stage) {
        Stage.NAMES -> "Jede Datei ist über ihren Namen auffindbar"
        Stage.MEANING -> "Deine Dateien sind bereit für die Suche"
        Stage.WORDS -> "Die Wörter in deinen Fotos sind durchsuchbar"
        Stage.PEOPLE -> "Personen ist bereit"
        Stage.REST -> "Lange Dateien sind bis zum Ende durchsuchbar"
    }

    override fun stageNote(stage: Stage, textOnly: Boolean) = when (stage) {
        Stage.NAMES -> "Tippe einen Teil eines Dateinamens ein, um die Datei zu finden."

        Stage.MEANING -> if (textOnly) {
            "Such nach einem Wort in einem beliebigen Dokument. Probier „Rechnung“."
        } else {
            "Beschreib ein Foto, um es zu finden. Probier „Hund am Strand“."
        }

        Stage.WORDS -> "Such nach Wörtern, die du auf einem Foto gesehen hast. Probier „Kassenbon“."

        Stage.PEOPLE -> "Gib jemandem unter Personen einen Namen und such dann nach diesem Namen."

        Stage.REST -> "Such nach etwas tief in einem langen PDF oder Video, etwa einem Satz von einer späteren Seite."
    }
    override val lookingTitle = "Suche nach neuen Dateien…"
    override val caughtUpTitle = "Fertig. Retriever hat alles verarbeitet, was möglich ist"

    override val processingReadyDetail =
        "Dein Fortschritt ist gespeichert. Du kannst jetzt starten, oder die Verarbeitung geht weiter, " +
            "sobald die Bedingungen es erlauben."
    override val keepGoingInBackground = "Im Hintergrund weitermachen"
    override val processingAdaptiveDetail = "Die Verarbeitung passt sich an, damit dein Handy flüssig reagiert."
    override val processingBatteryOverrideDetail = "Die Verarbeitung läuft im Akkubetrieb bis ${withPercent(ReadingPolicy.RESUMED_BATTERY_FLOOR)}."

    override val processingKeepPaused = "Pausiert lassen"
    override val processingPlugInDetail =
        "Schließe dein Handy an, um weiter zu verarbeiten. Retriever verarbeitet im Akkubetrieb bis ${withPercent(ReadingPolicy.RESUMED_BATTERY_FLOOR)}."
    override val processingTapToContinue = "Tippen, um fortzufahren"

    override val automaticChargingPreferenceDetail =
        "Zwischen zwei Durchläufen wartet Retriever auf das Laden. Mit Fortsetzen kannst du für diesen Durchlauf im Akkubetrieb starten."

    override val automaticProcessingPreferenceTitle = "Wann verarbeiten"
    override val automaticProcessingPreferenceDetail =
        "Zwischen zwei Durchläufen stoppt die Verarbeitung im Akkubetrieb unter ${withPercent(ReadingPolicy.BATTERY_FLOOR)}. Eine von dir gestartete oder " +
            "fortgesetzte Verarbeitung läuft bis ${withPercent(ReadingPolicy.RESUMED_BATTERY_FLOOR)}."

    override val pausedDetail = "Die Suche funktioniert weiter."

    override fun powerSaveDetail(words: DeviceWords) = "Geht weiter, wenn der ${powerSave(words)} deaktiviert ist."

    override val hotDetail = "Geht von selbst weiter."
    override val lowBatteryDetail = "Schließ das Ladekabel an, dann macht Retriever weiter."
    override val chargingUpDetail = "Die Verarbeitung startet, sobald dein Akku etwas mehr geladen ist."

    override fun chargingDetail(words: DeviceWords) = "Retriever macht weiter, wenn dein ${device(words)} lädt."

    override val manualDetail = "Du kannst jede Datei über ihren Namen finden."

    override fun meaningDetail(words: DeviceWords) =
        "Damit du Dateien und Fotos über ihren Inhalt finden kannst, indem du ihn beschreibst. Nur auf diesem ${device(words)}."

    override val peopleDownloadDetail = "Ein einmaliger Download für „Personen“. Alle deine Dateien sind schon durchsuchbar."
    override val facesDetail = "Ein optionaler Zusatzschritt. Alle deine Dateien sind schon durchsuchbar, und nichts wird noch einmal verarbeitet."

    override fun facesWaitingDetail(words: DeviceWords) =
        "Retriever erkennt Personen, wenn dein ${device(words)} es zulässt. Alle deine Dateien sind schon durchsuchbar."

    override val deepeningDetail = "Du findest sie schon über ihren Anfang."
    override val limitedDetail = "Einige lange Dateien wurden nur teilweise verarbeitet. Du findest sie schon über ihren Anfang."
    override val readingMeaningDetail = "Außerdem wird die Suche per Beschreibung ergänzt."

    override fun readingDetail(words: DeviceWords) = if (words.ios) {
        "Die Suche funktioniert die ganze Zeit. Retriever verarbeitet deine Dateien, solange es geöffnet ist, und manchmal, wenn iOS es zulässt."
    } else {
        "Du kannst Retriever schließen, es macht weiter. Die Suche funktioniert die ganze Zeit."
    }

    override fun waitingLongFilesDetail(words: DeviceWords) =
        "Retriever verarbeitet den Rest einiger langer Dateien, wenn dein ${device(words)} es zulässt. Die Suche funktioniert schon jetzt."

    override fun waitingPhotoWordsDetail(words: DeviceWords) =
        "Retriever liest den Text in deinen Fotos, wenn dein ${device(words)} es zulässt. Die Suche funktioniert schon jetzt."

    override fun waitingMeaningDetail(words: DeviceWords) =
        "Retriever ergänzt die Suche per Beschreibung, wenn dein ${device(words)} es zulässt. Die Suche funktioniert schon jetzt."

    override val diskFullDetail = "Gib etwas Speicher frei, dann macht Retriever weiter."

    override fun waitingDetail(words: DeviceWords) = "Dein ${device(words)} entscheidet, wann Retriever weitermacht. Nichts geht verloren."

    override val caughtUpDetail = "Neue Dateien werden zu den Zeiten verarbeitet, die du in den Einstellungen wählst."

    override val gettingReadersDetail = "Ein einmaliger Download von den Google Play-Diensten. Die Suche über den Namen funktioniert schon."

    override val peopleWaitsForWifi = "Der Download für „Personen“ wartet auf WLAN."

    override fun deepeningLead(left: Long) = "${notReadYet(left)}."

    override fun longFilesWait(n: Long) = "Alle Dateien verarbeitet. " +
        if (n == 1L) "Der Rest von ${formatCount(n)} langen Datei wartet." else "Der Rest von ${formatCount(n)} langen Dateien wartet."

    override val allFilesProcessedLead = "Alle Dateien verarbeitet."

    override val foundByOther = "Der Name. Retriever kann den Inhalt dieses Dateityps noch nicht verarbeiten."
    override val foundByBeginningRestPending = "Name und Anfang. Der Rest ist noch nicht verarbeitet."
    override val foundByPartCouldntFinish = "Name und ein Teil des Inhalts. Retriever konnte die Verarbeitung nicht abschließen."
    override val foundByBeginning = "Name und Anfang"
    override val foundByInsideMeaningLimited = "Name und Inhalt. Die Suche per Beschreibung konnte nicht abgeschlossen werden."
    override val foundByInsideDeepening = "Name und Inhalt. Die Suche per Beschreibung folgt noch."
    override val foundByInside = "Name und Inhalt"
    override val foundByReadingOff = "Der Name. Die Verarbeitung ist für diese Art von Datei deaktiviert."

    override fun foundBySkipped(tries: Long) = "Der Name. Retriever konnte die Datei nach ${plural(tries, "Versuch", "Versuchen")} nicht verarbeiten."

    override val foundByNothingInside = "Der Name. Vom Inhalt ließ sich nichts verarbeiten."
    override val foundByNotYet = "Der Name. Noch nicht verarbeitet."

    override val processingFailed = "Beim Verarbeiten deiner Dateien ist etwas schiefgelaufen. Retriever versucht es noch einmal."

    // FileActions
    override val fileNameField = "Name"

    // Notifications
    override fun notificationBody(count: String?, detail: String?, read: Long) = listOfNotNull(count, detail).joinToString(". ").ifEmpty { readCount(read) }

    // SearchResults
    override fun understoodChip(labels: List<String>) = labels.joinToString(" · ")

    override fun noMatchesMessage(byMeaning: Boolean, waiting: Boolean, wordsOnly: Boolean) =
        listOfNotNull(noMatchesDetail(byMeaning), moreWaiting.takeIf { waiting }, searchingWithoutModel.takeIf { wordsOnly }).joinToString(sentenceGap)

    override fun reasonLine(parts: List<String>) = parts.joinToString(" · ")

    // SearchScreen
    override val appSettings = "Einstellungen"

    // SettingsScreen
    override fun scheduleSummary(title: String, body: String) = "$title. $body"

    override val sizePending = "…"

    // FirstRunStages
    override fun folderNames(names: List<String>) = names.joinToString(" · ")

    // AppModel+Setup
    override fun kindsReadSpoken(parts: List<String>) = parts.joinToString(", ")

    // SearchModel
    override val scopePhotos = "Fotos"

    override val scopeDocuments = "Dokumente"

    // HomeView
    override fun titleDetailSpoken(title: String, detail: String?) = listOfNotNull(title, detail).joinToString(", ")

    // ResultViews
    override fun resultRowSpoken(kind: String, name: String, date: String, position: String?, extent: String?, reason: String?) =
        listOfNotNull(kind, name, date, position, extent, reason).joinToString(", ")

    // Access
    override val onlyPickedPhotos = "Nur die Fotos, die du ausgewählt hast"

    override val musicAndRecordings = "Musik und Aufnahmen"

    override fun notCoveredMedia(photos: Boolean, videos: Boolean, music: Boolean) = listOfNotNull(
        "Fotos".takeIf { photos },
        "Videos".takeIf { videos },
        "Musik".takeIf { music },
    ).let { m ->
        "Nicht abgedeckt: " +
            if (m.size == 1) m[0] else m.dropLast(1).joinToString(", ") + " und " + m.last()
    }

    override fun notCoveredDocuments(hasFolders: Boolean) =
        if (hasFolders) "Nicht abgedeckt: Dokumente und Downloads außerhalb der hinzugefügten Ordner" else "Nicht abgedeckt: Dokumente und Downloads"

    override val pickFolderOnPhone = "Wähle einen Ordner im Speicher dieses Handys."

    override val alreadySearchesFolder = "Retriever durchsucht diesen Ordner bereits."

    /** "deine Fotos, deine Videos und 2 Ordner" */
    private fun granted(photos: Boolean, videos: Boolean, music: Boolean, folders: Int) = listOfNotNull(
        "deine Fotos".takeIf { photos },
        "deine Videos".takeIf { videos },
        "deine Musik".takeIf { music },
        plural(folders.toLong(), "Ordner", "Ordner").takeIf { folders > 0 },
    ).let { m -> if (m.size <= 1) m.firstOrNull().orEmpty() else m.dropLast(1).joinToString(", ") + " und " + m.last() }

    override fun canSeeOnly(photos: Boolean, videos: Boolean, music: Boolean, folders: Int) = "Retriever sieht ${granted(photos, videos, music, folders)}."

    override fun seesPartOf(photos: Boolean, videos: Boolean, music: Boolean, folders: Int, words: DeviceWords) =
        "${granted(photos, videos, music, folders).replaceFirstChar(Char::uppercase)}, nicht dein ganzes ${device(words)}"

    override fun seesWholeDevice(words: DeviceWords) = "Dein ganzes ${device(words)}"

    override fun searchedOnly(photos: Boolean, videos: Boolean, music: Boolean, folders: Int) =
        "Nur ${granted(photos, videos, music, folders)} durchsucht. Downloads und Dateien anderer Apps waren nicht dabei."

    // AccessService
    override val couldntKeepFolder = "Der Zugriff auf diesen Ordner konnte nicht gespeichert werden. Wähle ihn noch einmal aus."

    // BrowseScreen
    override val allFiles = "Alle Dateien"

    override val filesRetrieverCanAccess = "Dateien, auf die Retriever zugreifen kann"

    override val sortLargestDetail = "Größte zuerst"

    override val sortNameDetail = "Nach Namen, A bis Z"

    override val sortNewestDetail = "Neueste zuerst · nach Änderungsdatum"

    override val couldntChangePerson = "Diese Person konnte nicht geändert werden. Versuch es noch einmal."

    override val back = "Zurück"
    override val home = "Startseite"
    override val fullScreen = "Vollbild"
    override val showBesideList = "Neben der Liste zeigen"
    override val searchYourFiles = "Deine Dateien durchsuchen"

    override val couldntLoadYourFiles = "Deine Dateien konnten nicht geladen werden"

    override val wentWrongReadingThem = "Beim Laden ist etwas schiefgelaufen. Versuch es noch einmal."

    override val keepUsefulFilesClose = "Wichtige Dateien griffbereit"

    override val tapStarToFindHere = "Tippe bei einer Datei auf den Stern, um sie hier zu finden."

    override val chooseFilesToSearch = "Wähle Dateien zum Durchsuchen"

    override val addPhotosOrFolder = "Füge Fotos oder einen Ordner hinzu."

    override val manageAccess = "Zugriff verwalten"

    override val nothingHereYet = "Noch nichts hier"

    override fun filesShowUpHere(words: DeviceWords) = "Dateien erscheinen hier, sobald Retriever sie auf deinem ${device(words)} findet."

    override val moreOptions = "Weitere Optionen"

    override val couldntLoadMoreFiles = "Weitere Dateien konnten nicht geladen werden"

    override val sortNewest = "Neueste"

    override val sortLargest = "Größte"

    override val sortName = "Name"

    // BrowseViewModel
    override val couldntLoadTheseFiles = "Diese Dateien konnten nicht geladen werden."

    // PersonActions
    override val rename = "Umbenennen"

    // DuplicatesScreen
    override val duplicates = "Doppelte Dateien"

    override val keepOneCopy = "Behalte von jeder Datei mindestens eine Kopie"

    override fun filesCouldntBeDeleted(n: Long) = if (n == 1L) "${files(n)} konnte nicht gelöscht werden" else "${files(n)} konnten nicht gelöscht werden"

    override fun deleteSelected(n: Long, size: String) = "${formatCount(n)} ausgewählte löschen ($size)"

    override val lookingForCopiesEllipsis = "Suche nach Kopien…"

    override val couldntLookForDuplicates = "Suche nach doppelten Dateien fehlgeschlagen"

    override val noDuplicatesFound = "Keine doppelten Dateien gefunden"

    override fun comparedFiles(n: Long) = "Retriever hat ${files(n)} verglichen."

    override fun couldBeFreed(size: String) = "$size lassen sich freigeben"

    override fun copiesEach(n: Long, size: String) = "${formatCount(n)} Kopien · je $size"

    override fun openNamed(name: String) = "$name öffnen"

    override val deselect = "Auswahl aufheben"

    override val select = "Auswählen"

    override val deleteFilesTitle = "Dateien löschen?"

    override fun deleteFilesMessage(n: Long) = "${files(n)} löschen? Das lässt sich nicht rückgängig machen."

    override val delete = "Löschen"

    // DuplicatesViewModel
    override val somethingWentWrongTryAgain = "Etwas ist schiefgelaufen. Versuch es noch einmal."

    // FileActions
    override val addToStarred = "Zu „Markiert“ hinzufügen"

    override val share = "Teilen"

    override val openInAnotherApp = "In anderer App öffnen"

    override val fileInfo = "Dateiinfo"

    override val removeFromStarred = "Aus „Markiert“ entfernen"

    override val topLevel = "Oberste Ebene"

    override val couldntChangeStar = "Die Markierung konnte nicht geändert werden. Versuch es noch einmal."

    override val findSimilar = "Ähnliche finden"

    override val showInFolder = "Im Ordner anzeigen"

    override fun selectedCount(n: Long) = "${formatCount(n)} ausgewählt"

    override val couldntFinishThat = "Das hat nicht geklappt. Die Datei wurde vielleicht verschoben, oder der Zugriff hat sich geändert."

    override val renameFile = "Datei umbenennen"

    override val deleteThisFile = "Diese Datei löschen?"

    // FileInfo
    override fun kindAndSize(kind: String, size: String) = "$kind · $size"

    override val locationLabel = "Speicherort"

    override val hideFullPath = "Vollständigen Pfad ausblenden"

    override val showFullPath = "Vollständigen Pfad anzeigen"

    override val locationDetails = "Details zum Speicherort"

    override val pathClipLabel = "Pfad"

    override val copyPath = "Pfad kopieren"

    override val dimensionsLabel = "Abmessungen"

    override fun dimensions(width: Int, height: Int) = "$width × $height"

    override val durationLabel = "Dauer"

    override val pagesLabel = "Seiten"

    override val internalStorage = "Interner Speicher/"

    // FoldersScreen
    override val folders = "Ordner"

    override val foldersDidntLoad = "Ordner wurden nicht geladen"

    override val noFoldersYet = "Noch keine Ordner"

    override val tryAgainInAMoment = "Versuch es gleich noch einmal."

    override val foldersAppearPhone = "Ordner erscheinen, sobald Retriever Dateien auf deinem Handy gefunden hat."

    override fun stopSearchingAsk(name: String) = "$name nicht mehr durchsuchen?"

    override fun stopSearchingMessage(n: Long, name: String) = if (n == 1L) {
        "Die ${files(n)} in $name wird jetzt aus der Suche entfernt. Um sie zurückzuholen, muss sie neu verarbeitet werden. "
    } else {
        "Die ${files(n)} in $name werden jetzt aus der Suche entfernt. Um sie zurückzuholen, müssen alle neu verarbeitet werden. "
    } + "Du kannst den Ordner über die Statuszeile oben auf der Startseite zurückholen."

    override val stopSearching = "Nicht mehr durchsuchen"

    override fun filesInParent(n: Long, parent: String?) = if (parent == null) files(n) else "${files(n)} · $parent"

    override fun moreOptionsFor(name: String) = "Weitere Optionen für $name"

    override val stopSearchingThisFolder = "Diesen Ordner nicht mehr durchsuchen"

    // FoldersViewModel
    override val couldntLoadYourFolders = "Deine Ordner konnten nicht geladen werden. Versuch es gleich noch einmal."

    override val couldntStopSearchingFolder = "Der Ordner konnte nicht aus der Suche genommen werden. Versuch es gleich noch einmal."

    // HomeScreen
    override val onThisDayHidden = "„An diesem Tag“ ausgeblendet"

    override val undo = "Rückgängig"

    override val showProcessingDetails = "Details zur Verarbeitung anzeigen"

    override val lookingForYourFiles = "Deine Dateien werden gesucht"

    override val nothingToSearchYet = "Noch nichts zu durchsuchen"

    override val takesAMinute = "Das dauert eine Minute. Sobald sie auftauchen, kannst du nach Namen suchen."

    override val saveAPdfHint = "Speichere ein PDF, mach einen Screenshot oder ein Foto, und es erscheint hier."

    // HomeSections
    override val showAll = "Alle anzeigen"

    override fun sectionSpoken(title: String, subtitle: String) = "$title, $subtitle"

    override val openAction = "Öffnen"

    override fun personTileSpoken(name: String, photos: Long) = "$name, ${photos(photos)}"

    override fun browseTileSpoken(label: String, count: String) = "$label, $count"

    override val onThisDay = "An diesem Tag"

    override fun onThisDaySpoken(year: Int, count: String, date: String) = "$year, $count vom $date"

    override val showThem = "Anzeigen"

    override val hideOnThisDay = "„An diesem Tag“ ausblenden"

    override val starredSection = "Markiert"

    // ModelDownload
    override val searchModelDownloadTitle = "Suchmodell von Retriever"

    // ModelRepository
    override val downloadStopped = "Der Download wurde unterbrochen. Prüfe deine Verbindung und versuch es noch einmal."

    override val downloadDamaged = "Der Download war beschädigt. Versuch es noch einmal."

    override fun needsFreeSpace(gb: String) = "Benötigt $gb${NBSP}GB freien Speicher. Gib etwas Speicher frei und versuch es dann noch einmal."

    // SearchResults
    override fun likeFile(name: String) = "Ähnlich wie $name"

    override val photoYoureSearchingWith = "Das Foto, mit dem du suchst"

    override val stopSearchingByPhoto = "Suche per Foto beenden"

    override val searchWordsAsTyped = "Stattdessen genau nach den eingegebenen Wörtern suchen"

    override val photoSearchAfterSetup = "Die Suche per Foto funktioniert, sobald die Einrichtung fertig ist."

    override val matchingExactWordsForNow = "Vorerst wird nach deinen Wörtern im genauen Wortlaut gesucht. Ergebnisse der Suche per Beschreibung sind " +
        "gleich wieder da."

    override fun noMatchesIn(scope: String) = "Keine Treffer in „$scope“"

    override val nothingLikePhotoThere = "Dort gibt es nichts Ähnliches zu diesem Foto."

    override fun nothingForThere(query: String) = "Dort gibt es keine Treffer für „$query“."

    override val searchAllFiles = "Alle Dateien durchsuchen"

    override val nothingLikeThisYet = "Noch nichts Ähnliches"

    override val tryAnotherPhoto = "Probier ein anderes Foto."

    override val tryFewerWords = "Weniger Wörter versuchen"

    override val filterAll = "Alle"

    override val filterStarred = "Markiert"

    override val expanded = "Ausgeklappt"

    override val collapsed = "Eingeklappt"

    override fun lessLikelyMatches(n: Long) = "Weniger wahrscheinliche Treffer (${formatCount(n)})"

    override val moreDocuments = "Weitere Dokumente"

    override val recordings = "Aufnahmen"

    override val documentsAndMore = "Dokumente und mehr"

    // SearchScreen
    override val searchWithPhoto = "Mit einem Foto suchen"

    override val clearSearch = "Suche löschen"

    override val recentSearches = "Letzte Suchen"

    override fun removeFromHistory(query: String) = "$query aus dem Verlauf entfernen"

    override val searchAction = "Suchen"

    override val clearHistory = "Verlauf löschen"

    // SearchViewModel
    override val likeYourPhoto = "Ähnlich wie dein Foto"

    override val couldntOpenPhoto = "Dieses Foto konnte nicht geöffnet werden. Wähle ein anderes."

    override val thatDidntWork = "Das hat nicht geklappt. Versuch es noch einmal."

    // SettingsScreen
    override val includedFilesFooter = "$kindOff Ein ausgelassener Ordner wird nicht durchsucht."

    override val onlyWhenYouChoose = "Nur wenn du die Verarbeitung startest oder fortsetzt."

    override val showProcessingProgress = "Verarbeitungsfortschritt anzeigen"

    override val processingProgressNotice =
        "Aktiviere eine einzelne lautlose Benachrichtigung, um den Fortschritt zu sehen, während Retriever deine Dateien verarbeitet."

    override val notificationsOn = "An"

    override val includedFiles = "Einbezogene Dateien"

    override val noneExcluded = "Keine"

    override fun includeNamed(folder: String) = "$folder einbeziehen"

    override val includeFolder = "Ordner einbeziehen"

    override val privacy = "Datenschutz"

    override val saveRecentSearches = "Letzte Suchen speichern"

    override val savedOnDeviceShortcuts = "Auf diesem Gerät gespeichert. Die Suchen werden auch für Suchverknüpfungen genutzt."

    override val clearRecentSearches = "Letzte Suchen löschen"

    override val clearSearchesAndShortcutsAsk = "Letzte Suchen und Verknüpfungen löschen?"

    override val clear = "Löschen"

    override val storage = "Speicher"

    override val appearance = "Darstellung"

    override val wallpaperColours = "Farben des Hintergrundbilds"

    override val wallpaperColoursOff = "Aus: Retriever nutzt sein warmes Bernsteingelb."

    override val about = "Über"

    override val version = "Version"

    override val openSourceNotices = "Open-Source-Hinweise"

    override val removeDownloadConfirm = "Download entfernen"

    override fun howItWorksBody(words: DeviceWords) = "${wholeDeviceNote(words)} ${onDevice(words)}. " +
        "Such- und Texterkennungsmodelle müssen eventuell aus dem Internet heruntergeladen werden. " +
        "Dein Dateianbieter nutzt eventuell auch das Internet, um Dateien aus der Cloud zu öffnen."

    override val howItWorksTimes = "Retriever verarbeitet deine Dateien zu den Zeiten, die du unter „$automaticProcessingPreferenceTitle“ wählst. " +
        "Lange Dateien werden Stück für Stück verarbeitet: Du findest sie zuerst über ihren Anfang."

    override val runSetupCheck = "Einrichtung prüfen"

    override val tuningFewMinutes = "Verarbeitung wird angepasst… höchstens ein paar Minuten"

    override fun tuneFailedMessage(message: String) = "Anpassung fehlgeschlagen: $message"

    override val processingIsSetUp = "Die Verarbeitung ist eingerichtet"

    override val adjustsProcessingSpeed = "Passt das Verarbeitungstempo an dieses Handy an."

    override val useMobileDataForDownloads = "Mobile Daten für Downloads nutzen"

    override val downloadsWaitForWifiShort = "Aus: Downloads warten auf WLAN."

    override val loading = "Wird geladen…"

    // SettingsViewModel
    override val couldntSaveChange = "Diese Änderung konnte nicht gespeichert werden. Versuch es noch einmal."

    override val searchDownloadRemoved = "Such-Download entfernt"

    // Starring
    override val starAdded = "Markiert"

    override val starRemoved = "Markierung entfernt"

    // StatusScreen
    override val checkingYourFiles = "Deine Dateien werden geprüft…"

    override val hide = "Ausblenden"

    override val reviewFiles = "Dateien ansehen"

    override val letProcessWhileAway = "Retriever Dateien verarbeiten lassen, während du es nicht benutzt"

    override fun batteryMayStop(chargingOnly: Boolean) = "Dein Handy hält die Verarbeitung eventuell an, um Akku zu sparen." + sentenceGap +
        if (chargingOnly) automaticChargingPreferenceDetail else automaticProcessingPreferenceDetail

    override val settingsRowDetail = "Was verarbeitet wird, Speicher und mehr"

    override val chooseMorePhotos = "Mehr Fotos auswählen"

    override val foldersYouAdded = "Von dir hinzugefügte Ordner"

    override fun removeNamed(name: String) = "$name entfernen"

    override val allowAccessToAllFiles = "Zugriff auf alle Dateien erlauben"

    // Format
    override fun starredScope(kind: String?) = kind?.let { "markierte $it" } ?: starredFiles

    override fun pageBadge(page: Int) = "Seite $page"

    // FileRow
    override fun openAtPage(page: Int) = "Auf Seite $page öffnen"

    override fun playFrom(time: String) = "Ab $time abspielen"

    override fun kindSizeDate(kind: String, size: String, date: String) = "$kind · $size · $date"

    override fun fileRowSpoken(kind: String, name: String, date: String, position: String?, note: String?) =
        "$kind, $name, $date" + (position?.let { ", $it" } ?: "") + (note?.let { ", $it" } ?: "")

    // ReadingFiles
    override val fileActions = "Dateiaktionen"

    override fun fileSpoken(name: String, kind: String, date: String) = "$name, $kind, $date"

    // DocScreen
    override val notProcessedThisYet = "Retriever hat diese Datei noch nicht verarbeitet"

    override val waitingItsTurn = "Sie wartet mit den anderen neuen Dateien, bis sie dran ist. Öffne sie in einer anderen App, um sie jetzt zu sehen."

    override val noTextFound = "Retriever hat in dieser Datei keinen Text gefunden"

    override val scanOrPictures = "Die Datei ist vielleicht ein Scan oder enthält nur Bilder. Öffne sie in einer anderen App, um sie anzusehen."

    override val couldntOpenFile = "Diese Datei konnte nicht geöffnet werden"

    override val maybeMovedOrDeleted = "Sie wurde vielleicht verschoben oder gelöscht."

    override val previousMatch = "Vorheriger Treffer"

    override fun matchOf(at: Int, total: Int) = "Treffer $at von $total"

    override fun matchCount(n: Long) = plural(n, "Treffer", "Treffer")

    override val nextMatch = "Nächster Treffer"

    // Opener
    override val noAppOpensFile = "Keine App auf diesem Handy kann diese Datei öffnen"

    // PdfPageScreen
    override fun pdfSubtitle(page: Int, count: Int, matchedPage: Int?) = (matchedPage?.let { "${matchOnPage(it)} · " } ?: "") + pageOf(page, count)

    override val closePage = "Seite schließen"

    override val pdfNeedsPassword = "Dieses PDF braucht ein Passwort"

    override val pdfFindableByName = "Retriever findet es weiterhin über den Namen. Du kannst es in einer anderen App öffnen."

    override val folderAccessChanged = "Der Zugriff auf diesen Ordner hat sich geändert"

    override val chooseFolderAgain = "Wähle den Ordner noch einmal aus, um diese Datei zu öffnen."

    override val fileNoLongerAvailable = "Diese Datei ist nicht mehr verfügbar"

    override val goBack = "Zurück"

    override val couldntOpenPdf = "Dieses PDF konnte nicht geöffnet werden"

    override fun pageZoomed(page: Int) = "Seite $page, vergrößert"

    override fun pageOf(page: Int, count: Int) = "Seite $page von $count"

    override val zoomPage = "Seite vergrößern"

    override fun pageSpoken(label: String, matched: Boolean) = label + if (matched) ", hier hat deine Suche einen Treffer" else ""

    override val matchedHere = "Treffer hier"

    // PhotoScreen
    override val couldntShowPhoto = "Dieses Foto konnte nicht angezeigt werden"

    // PlayerScreen
    override fun matchAt(time: String) = "Treffer bei $time"

    override fun couldntPlay(audio: Boolean) = "${if (audio) "Diese Aufnahme" else "Dieses Video"} konnte nicht abgespielt werden"

    override val maybeMovedOrCantPlay = "Die Datei wurde vielleicht verschoben oder gelöscht. Oder dieses Handy kann sie nicht abspielen."

    // ViewerQueue
    override fun positionOf(at: Int, count: Int) = "$at von $count"

    // ViewerTopBar
    override val previousFile = "Vorherige Datei"

    override val nextFile = "Nächste Datei"

    // AppModel+Loop
    override fun keepProcessingFailed(words: DeviceWords) = "iOS konnte die Verarbeitung nicht fortsetzen, während du Retriever nicht benutzt hast. " +
        "Lass es geöffnet, oder lass es deine Dateien verarbeiten, während dein ${device(words)} lädt."

    // AppModel+Settings
    override val couldntChangePersonCurly = "Diese Person konnte nicht geändert werden. Versuch es noch einmal."

    // FolderSource
    override val aFolder = "Ein Ordner"

    // SearchModel
    override val starredFiles = "markierte Dateien"

    // Copy
    override val photosAccessOff = "Der Fotozugriff ist deaktiviert"

    override val photosOffNoteDetail =
        "Die Suche funktioniert weiter für bereits verarbeitete Dateien. Aktiviere den Zugriff, um auch deine Fotos und Screenshots zu durchsuchen."

    override val seesOnlyPickedPhotos = "Retriever sieht nur die Fotos, die du ausgewählt hast"

    override val canSearchJustThose = "Nur diese kann es durchsuchen."

    override val folderAdded = "Ordner hinzugefügt. Retriever verarbeitet seinen Inhalt."

    override fun folderOverlaps(name: String) = "Dieser Ordner überschneidet sich mit „$name“, den Retriever bereits durchsucht."

    override val folderRefused = "iOS hat Retriever nicht erlaubt, diesen Ordner zu behalten. Versuch einen anderen."

    override fun backgroundNote(words: DeviceWords) =
        "Retriever verarbeitet deine Dateien, solange es geöffnet ist, und manchmal, wenn du es nicht benutzt, sofern iOS es zulässt. " +
            "Die automatische Verarbeitung im Hintergrund wartet, bis dein ${device(
                words,
            )} lädt. Das Starten oder Fortsetzen der Verarbeitung braucht etwas Akku."

    override val screenKeptOn =
        "Dein Bildschirm bleibt an, während Retriever deine Dateien verarbeitet. Das kannst du in den Einstellungen ausschalten."

    override fun showingLabels(labels: List<String>) = "Angezeigt: " + labels.joinToString(" · ")

    override val searchMyExactWords = "Genau meine Wörter suchen"

    override fun processedSoFar(n: Long) = "Bisher ${readCount(n)}"

    // Format
    override fun opensAtPage(page: Int) = "Öffnet auf Seite $page"

    override fun playsFrom(time: String) = "Spielt ab $time ab"

    override val opensHint = "Öffnet"

    // BrowseView
    override val couldntLoadFiles = "Dateien konnten nicht geladen werden"

    override val wentWrongReadingList = "Beim Laden der Liste ist etwas schiefgelaufen."

    override fun sizeFolderDate(size: String, folder: String, date: String) = "$size · $folder · $date"

    override val sortBy = "Sortieren nach"

    override val sortMenu = "Sortieren"

    override val ok = "OK"

    // DuplicatesView
    override val noDuplicatesMessage =
        "Retriever hat die Dateien in deinen hinzugefügten Ordnern verglichen und keine Kopien über 1 MB gefunden. Fotos werden nicht verglichen."

    override val lookingForCopies = "Suche nach Kopien"

    override val thisCanTakeAMinute = "Das kann eine Minute dauern."

    override fun deleteFilesAsk(n: Long) = if (n == 1L) "${formatCount(n)} Datei löschen?" else "${files(n)} löschen?"

    override val cantBeRecovered = "Sie lassen sich nicht wiederherstellen."

    override val comparesFilesNote = "Vergleicht Dateien in den hinzugefügten Ordnern, keine Fotos. Von jeder Datei bleibt die älteste Kopie erhalten. " +
        "Tippe auf eine Kopie, um zu ändern, was gelöscht wird."

    override fun folderAndDate(folder: String, date: String) = "$folder · $date"

    override val selectedToDeleteHint = "Zum Löschen ausgewählt. Doppeltippen, um sie zu behalten."

    override val keptHint = "Wird behalten. Doppeltippen, um sie zum Löschen auszuwählen."

    override fun filesWerentDeleted(n: Long) = if (n == 1L) "${files(n)} wurde nicht gelöscht" else "${files(n)} wurden nicht gelöscht"

    // FileActions
    override val couldntOpenFolderUnavailable = "Diese Datei konnte nicht geöffnet werden. Ihr Ordner ist vielleicht nicht verfügbar."

    override val photosAppName = "Fotos"

    override val moreActions = "Weitere Aktionen"

    override val couldntFinish = "Das hat nicht geklappt"

    // FoldersView
    override val foldersWithFiles = "Ordner mit Dateien"

    override val couldntLoadFolderList = "Die Ordnerliste konnte nicht geladen werden. Erneut versuchen"

    override val removeFolder = "Entfernen"

    override val cantOpenFolderAnyMore = "Retriever kann diesen Ordner nicht mehr öffnen. Entferne ihn und füge ihn dann wieder hinzu."

    override val alsoSearchesOwnFolder =
        "Retriever durchsucht auch seinen eigenen Ordner in der Dateien-App. Wenn du hier einen Ordner entfernst, wird er nie gelöscht."

    override val couldntLoadFolders = "Ordner konnten nicht geladen werden"

    override val wentWrongReadingFolders = "Beim Laden deiner Ordner ist etwas schiefgelaufen."

    override val foldersAppearFiles = "Ordner erscheinen, sobald Retriever deine Dateien gefunden hat."

    override fun stopSearchingForget(n: Long, name: String) = "Retriever vergisst die ${files(n)} in $name. Du kannst den Ordner hier zurückholen."

    override fun removeFolderAsk(name: String) = "$name entfernen?"

    override val removeFolderMessage =
        "Retriever durchsucht ihn dann nicht mehr und vergisst, was es dort verarbeitet hat. Der Ordner und seine Dateien bleiben, wo sie sind."

    // HomeSections
    override val showsAll = "Zeigt alle an"

    override val showsThem = "Zeigt sie an"

    override val hideOnThisDayIos = "„An diesem Tag“ ausblenden"

    override fun homeTileSpoken(kind: String, name: String, date: String, starred: Boolean) = "$kind, $name, $date" + if (starred) ", markiert" else ""

    // HomeView
    override val addFolderOrPhotos = "Füge einen Ordner hinzu oder lass Retriever deine Fotos sehen, dann findet es, was darin ist."

    override val allowPhotosAccess = "Fotozugriff erlauben"

    override val turnOnPhotosAccess = "Fotozugriff aktivieren"

    override val onThisDayTitle = "An diesem Tag"

    override val openSettings = "Einstellungen öffnen"

    override val alsoSearchesFolders = "Retriever durchsucht auch Ordner, die du hinzufügst."

    // ReadingFiles
    override val openFileHint = "Öffnet die Datei"

    // ResultViews
    override fun sharedPhotoName(ext: String?) = "Foto.${ext ?: "jpg"}"

    // RootView
    override val setupTitle = "Einrichtung"

    // SearchView
    override val docsShort = "Dok."

    override val searchByPhoto = "Per Foto suchen"

    override val removeSearch = "Entfernen"

    override val removeFromRecentSearches = "Aus letzten Suchen entfernen"

    override val somethingWentWrongSearching = "Bei der Suche ist etwas schiefgelaufen."

    override val lookingEllipsis = "Suche läuft…"

    override fun turnOnToSearchScope(scope: String) = "Aktiviere den Zugriff in den Einstellungen, damit Retriever auch in „$scope“ suchen kann."

    override val noMatchesInPickedPhotos = "Keine Treffer in den ausgewählten Fotos"

    override val onlySeesChosenPhotos = "Retriever sieht nur die Fotos, die du ausgewählt hast. Wähle mehr aus, um mehr davon zu durchsuchen."

    override val notQuiteReady = "Noch nicht ganz bereit"

    override fun nothingLikeThatOn(words: DeviceWords) = "Noch nichts Ähnliches auf diesem ${device(words)}."

    override fun nothingInScopeFits(scope: String) = "In „$scope“ passt nichts. Versuch es mit allen Dateien."

    override fun noStarredMatch(query: String) = "Keine markierte Datei passt zu „$query“"

    override val onlyStarredShowHere = "Hier erscheinen nur Dateien, die du markiert hast. Versuch es mit allen Dateien."

    override fun nothingLikePhotoIn(scope: String) = "Nichts Ähnliches zu diesem Foto in „$scope“"

    override fun noMatchesFor(query: String) = "Keine Treffer für „$query“"

    override val tryAllFilesInstead = "Versuch es stattdessen mit allen Dateien."

    override fun nothingInScopeMatches(scope: String) = "In „$scope“ gibt es keine Treffer. Versuch es mit allen Dateien."

    override val likeThisPhoto = "Ähnlich wie dieses Foto"

    override val photosOffResults = "Der Fotozugriff ist deaktiviert, daher fehlen deine Fotos in diesen Ergebnissen."

    // SettingsView
    override val cleared = "Gelöscht"

    override fun savedOnDeviceOnly(words: DeviceWords) = "Nur auf diesem ${device(words)} gespeichert. Nichts wird irgendwohin gesendet."

    override val clearRecentSearchesAsk = "Letzte Suchen löschen?"

    override fun privacyBody(words: DeviceWords) =
        "Retriever verarbeitet deine Dateien auf diesem ${device(words)}. Deine Suchen, Markierungen und was es in Dateien findet, bleiben hier. " +
            "Der Download der Suche per Beschreibung und das Öffnen von Dateien in iCloud können das Internet nutzen."

    override val longFilesBitAtATime = "Lange Dateien werden Stück für Stück verarbeitet: Du findest sie zuerst über ihren Anfang, der Rest folgt."

    override val onlyWhenYouChooseAnyFile = "Nur wenn du die Verarbeitung startest oder fortsetzt. Du kannst jede Datei jederzeit über ihren Namen finden."

    override val keepScreenOnToggle = "Bildschirm während der Verarbeitung anlassen"

    override fun adjustProcessingFor(words: DeviceWords) = "Verarbeitung an dieses ${device(words)} anpassen"

    override val runSetupCheckAgain = "Einrichtung noch einmal prüfen."

    override val kindsFooterVideos = "$kindOff Videos: nur Bilder, kein Ton."

    override val photosLibrary = "Fotomediathek"

    override val foldersFromFiles = "Ordner aus „Dateien“"

    override val addFoldersFooter = "PDFs und Dokumente in Ordnern, die du aus „Dateien“ hinzufügst, werden durchsuchbar."

    override val searchDocumentsToo = "Auch Dokumente durchsuchen"

    override val searchDocumentsTooBody =
        "Füge Ordner aus „Dateien“ hinzu, etwa Downloads oder iCloud Drive, und Retriever durchsucht die PDFs und Dokumente darin."

    override val downloadsOnWifiOrData = "Downloads über WLAN oder mobile Daten."

    override val downloadsWaitForWifi = "Downloads warten auf WLAN, damit sie deine mobilen Daten nicht nutzen."

    override val noticesUnavailable = "Retriever basiert auf Open-Source-Software. Die Lizenzen können gerade nicht angezeigt werden."

    // SetupView
    override val searchesForThis = "Sucht danach"

    // SplitRoot
    override val searchTab = "Suche"

    // StatusView
    override val onlySeesPickedPhotos = "Retriever sieht nur die Fotos, die du ausgewählt hast."

    override val photosOffCantSearch = "Der Fotozugriff ist deaktiviert, daher kann Retriever deine Fotos nicht durchsuchen."

    // TextReader
    override val notProcessedOpenOriginal = "Retriever hat diese Datei noch nicht verarbeitet. Du kannst stattdessen das Original öffnen."

    override val noTextToShow = "Kein Text zum Anzeigen"

    override val noReadableText = "Retriever hat in dieser Datei keinen erkennbaren Text gefunden."

    override val couldntShowText = "Der Text konnte nicht angezeigt werden"

    override val wentWrongReadingIt = "Beim Laden ist etwas schiefgelaufen. Versuch es gleich noch einmal."

    override val textFromThisFile = "Text aus dieser Datei"

    override val onlyBeginningIncluded = "Nur der Anfang dieser Datei ist enthalten."

    // Viewer
    override fun matchOnPage(page: Int) = "Treffer auf Seite $page"

    override val anotherAppCanAsk = "Eine andere App kann dich nach dem Passwort fragen."

    override val damagedOrUnsupported = "Die Datei ist vielleicht beschädigt oder in einem Format, das Retriever nicht anzeigen kann."

    override fun couldntPlayIos(audio: Boolean) =
        if (audio) "Diese Audiodatei konnte nicht abgespielt werden" else "Dieses Video konnte nicht abgespielt werden"

    override val iCloudOrCantPlay = "Die Datei ist vielleicht nur in iCloud verfügbar und es gibt keine Verbindung. Oder Retriever kann ihr Format " +
        "nicht abspielen."

    override fun photoSpoken(kind: String, name: String) = "$kind, $name"

    override val turnOnToSeePhotos = "Aktiviere den Zugriff in den Einstellungen, um deine Fotos hier zu sehen."

    override val onlyInICloudNow = "Das Foto ist vielleicht nur in iCloud verfügbar, und gerade gibt es keine Verbindung."

    override val downloadingFromICloud = "Wird aus iCloud geladen"
}
