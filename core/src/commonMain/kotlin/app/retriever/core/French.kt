package app.retriever.core

import app.retriever.core.Words.NBSP

/**
 * French (fr). A machine translation awaiting native review: no native speaker has checked it yet.
 * Reviewed by a second model (Codex).
 *
 * Term choices, for the reviewer to check consistency:
 * - process: traiter (traité, traitement), everywhere; never lire, analyser, indexer or scanner. "Lire" is only for
 *   playing audio and video.
 * - search by description: « recherche par description », the feature's one name.
 * - found by name: « trouvé par son nom »; found by what's inside: « trouvé par son contenu ».
 * - processed in part: « traité en partie ». 15 minutes: « 15 minutes ».
 * - phone: téléphone (ce téléphone, le téléphone, du téléphone); on iOS cet iPhone, l’iPhone, de l’iPad.
 * - Battery Saver: « Économiseur de batterie » (Android); Low Power Mode: « Mode économie d’énergie » (iOS).
 *   Unrestricted battery use: « Sans restriction » (Android).
 * - System Settings: Réglages on iOS, Paramètres on Android. Retriever’s own settings: Paramètres on both.
 *   App: appli (Android and shared), app (iOS only).
 * - cloud files: fichiers stockés en ligne; file provider: l’appli qui gère vos fichiers.
 * - starred: favoris (Ajouter aux favoris, Favoris); People: Personnes; On this day: Ce jour-là; Browse: Parcourir;
 *   Downloads: Téléchargements; demo: démo; mobile data: données mobiles.
 * - "vous" throughout. 0 and 1 take the singular. U+00A0 (NBSP) before : ; ? ! and %, and inside « ».
 */
internal object French : Strings {
    private fun plural(n: Long, one: String, many: String = "${one}s"): String = "${formatCount(n)} ${agree(n, one, many)}"

    /** The singular for 0 and 1, as French counts. */
    private fun agree(n: Long, one: String, many: String = "${one}s"): String = if (n < 2) one else many

    private fun device(words: DeviceWords) = if (words.ios) words.device else "téléphone"

    /** "ce téléphone", "cet iPhone". */
    private fun thisDevice(words: DeviceWords) = if (words.ios) "cet ${words.device}" else "ce téléphone"

    /** "du téléphone", "de l’iPhone". */
    private fun fromDevice(words: DeviceWords) = if (words.ios) "de l’${words.device}" else "du téléphone"

    /** The OS's own name in French Settings, with its article. */
    private fun powerSave(words: DeviceWords) = if (words.ios) "le mode économie d’énergie" else "l’économiseur de batterie"

    override val sentenceGap = " "

    override val modified = "Modification"
    override val taken = "Prise de vue"

    override fun files(n: Long) = plural(n, "fichier")

    override fun photos(n: Long) = plural(n, "photo")

    override fun videos(n: Long) = plural(n, "vidéo")

    override fun photosAndVideos(n: Long) = "${formatCount(n)} ${agree(n, "photo ou vidéo", "photos et vidéos")}"

    override fun readOf(done: Long, total: Long) = "${files(done)} ${agree(done, "traité")} sur ${formatCount(total)}"

    override fun meaningOf(done: Long, total: Long) = "${formatCount(done)} sur ${files(total)} avec la recherche par description"

    override fun facesOf(done: Long, total: Long) = "${photos(done)} ${agree(done, "traitée")} sur ${formatCount(total)}"

    override val processedInPart = "Traité en partie"
    override val couldntBeOpened = "Impossible à ouvrir"
    override val stillToProcess = "Encore à traiter"
    override val longFilesStillBeingFinished = "Fichiers longs encore en traitement"

    override fun largeFiles(n: Long) = "${files(n)} de plus de 100${NBSP}Mo"

    override fun readCount(read: Long) = "${files(read)} ${agree(read, "traité")}"

    override fun partlyReadCount(n: Long) = "${formatCount(n)} ${agree(n, "traité")} en partie"

    override fun extent(extent: ReadExtent?) = when (extent) {
        ReadExtent.FIRST_PART -> "Traité en partie · seulement son début"
        ReadExtent.MEANING_LIMITED -> "Traité en partie · préparation de la recherche par description inachevée"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "Traité en partie · seulement son début, sans la recherche par description"
        ReadExtent.FIRST_PART_DEEPENING -> "Traité en partie · seulement son début pour l’instant"
        ReadExtent.DEEPENING -> "Traité en partie · la recherche par description est à venir"
        ReadExtent.WHOLE, null -> null
    }

    override val couldntProcessLabel = "Impossible à traiter"
    override val notProcessedYet = "Pas encore traité"
    override val partRestToCome = "Traité en partie · la suite est à venir"
    override val partCouldntFinish = "Traité en partie · le traitement n’a pas pu être terminé"

    override fun kindLabel(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "Document"
        Kind.SCREENSHOT -> "Capture d’écran"
        Kind.PHOTO -> "Photo"
        Kind.AUDIO -> "Audio"
        Kind.VIDEO -> "Vidéo"
        Kind.OTHER -> "Fichier"
    }

    override fun kindLabelPlural(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "Documents"
        Kind.SCREENSHOT -> "Captures d’écran"
        Kind.PHOTO -> "Photos"
        Kind.AUDIO -> "Fichiers audio"
        Kind.VIDEO -> "Vidéos"
        Kind.OTHER -> "Autres fichiers"
    }

    override fun kindsInSentence(kind: Kind) = kindLabelPlural(kind).let { if (it.length > 1 && it[1].isUpperCase()) it else it.lowercase() }

    override fun spokenPage(page: Int) = "page $page"

    override fun spokenTime(hours: Long, minutes: Long, seconds: Long): String {
        val parts = listOf(hours to "heure", minutes to "minute", seconds to "seconde").filter { it.first > 0 }.map { (n, unit) -> plural(n, unit) }
        return "à " + parts.joinToString(" ").ifEmpty { "0 seconde" }
    }

    override fun textOnlyNote(ramGb: Int, words: DeviceWords) = thisDevice(words).replaceFirstChar(Char::uppercaseChar) +
        " a $ramGb${NBSP}Go de mémoire, trop peu pour la recherche par description$NBSP: " +
        "Retriever trouve donc les fichiers par leur nom et par les mots qu’ils contiennent."

    override fun notReadYet(files: Long) = "${formatCount(files)} à traiter"

    override fun couldntProcess(files: Long) = "Impossible de traiter ${files(files)}"

    override val details = "Détails"
    override val starting = "Démarrage…"
    override val processingNow = "En cours de traitement"
    override val recentlyProcessed = "Traités récemment"
    override val processingYourFiles = "Traitement de vos fichiers"
    override val pause = "Mettre en pause"
    override val processTheseKinds = "Traiter ces types de fichiers"

    override val continueAction = "Continuer"
    override val tryAgain = "Réessayer"
    override val download = "Télécharger"
    override val notNow = "Pas maintenant"
    override val cancel = "Annuler"
    override val done = "Terminé"
    override val readFor15 = "Traiter pendant 15 minutes"
    override val optional = "Facultatif"

    override fun helloTitle(textOnly: Boolean) = if (textOnly) "Trouvez vos fichiers grâce à leur contenu" else "Trouvez tout en le décrivant"

    override val helloBody = "Photos, captures d’écran, documents et vidéos, en une seule recherche."
    override val forExample = "Par exemple"
    override val helloExamples = listOf(
        Words.HelloExample("mot de passe Wi-Fi envoyé par le propriétaire", "Capture d’écran du 14 mars", "Indique «${NBSP}Mot de passe Wi-Fi$NBSP: …$NBSP»"),
        Words.HelloExample("chien endormi sur le canapé", "Photo de dimanche dernier", "Trouvée grâce à ce qu’on y voit", needsModel = true),
        Words.HelloExample("mon bail", "Bail 2025.pdf", "Page 3, l’exemplaire signé"),
    )
    override val downloads = "Téléchargements"

    override fun onDevice(words: DeviceWords) = "Vos fichiers sont traités sur votre ${device(words)}"

    override val getStarted = "Commencer"

    override fun downloadNote(size: String) = "Inclut un téléchargement unique de $size, en Wi-Fi."

    override val accessTitle = "Choisissez ce que Retriever peut rechercher"
    override val accessBody = "Choisissez-en un ou plusieurs. Vous pourrez en ajouter d’autres plus tard."
    override val allow = "Autoriser"
    override val allowAccess = "Autoriser l’accès"
    override val choose = "Choisir"
    override val chooseMore = "En choisir d’autres"
    override val settings = "Réglages"
    override val documents = "Documents"
    override val includesScreenshots = "Captures d’écran incluses"
    override val allowed = "Autorisé"
    override val somePhotos = "Photos choisies uniquement"
    override val photosOff = "Accès désactivé. Activez-le dans Réglages."
    override val pickFolders = "Choisissez des dossiers dans Fichiers, comme Téléchargements"

    override fun foldersAdded(n: Long) = "${plural(n, "dossier")} ${agree(n, "ajouté")}"

    override val androidAccessBody = "Pour retrouver vos photos, documents et fichiers téléchargés en les décrivant."
    override val allFilesOff = "Activez «${NBSP}Autoriser l’accès pour gérer tous les fichiers$NBSP» " +
        "pour rechercher dans tout votre téléphone, ou choisissez ce qu’il faut inclure."
    override val includeTitle = "Choisissez ce qu’il faut inclure"
    override val sharedFilesAndFolders = "Fichiers et dossiers de ce téléphone"
    override val photosAndVideosLabel = "Photos et vidéos"
    override val addFolder = "Ajouter un dossier"
    override val nothingSelected = "Aucun fichier sélectionné pour l’instant. Choisissez des photos ou un dossier pour commencer."

    override fun wholeDeviceNote(words: DeviceWords) =
        "Retriever traite les fichiers de ${thisDevice(words)} pour que vous puissiez les retrouver grâce à leur contenu."

    override val firstLook = "Un premier aperçu de vos fichiers récents"

    override fun firstSearchHint(state: DescriptionState) =
        if (state == DescriptionState.READY) "Décrivez une photo ou un fichier" else "Recherchez un nom ou un mot"

    override fun descriptionLine(state: DescriptionState, percent: Int?) = when (state) {
        DescriptionState.DOWNLOADING -> "Téléchargement de la recherche par description" + (percent?.let { " · $it$NBSP%" } ?: "")
        DescriptionState.WAITING_FOR_WIFI -> "En attente du Wi-Fi pour télécharger la recherche par description."
        DescriptionState.PREPARING -> "Préparation de la recherche par description"
        DescriptionState.FAILED -> "Le téléchargement de la recherche par description s’est interrompu."
        DescriptionState.NO_SPACE -> "Pas assez d’espace libre pour télécharger la recherche par description."
        DescriptionState.READY, DescriptionState.NOT_STARTED, DescriptionState.UNAVAILABLE -> null
    }

    override val descriptionReady = "La recherche par description est prête"
    override val descriptionReadyBody = "Décrivez maintenant vos fichiers récents pour les retrouver."

    override val homeIntroTitle = "Retriever continue tout seul"
    override val homeIntroBody = "Voici la suite."
    override val gotIt = "OK"
    override val newFiles = "Nouvelles photos et nouveaux fichiers"
    override val newFilesBody = "Pris en compte automatiquement. Vous n’avez rien à faire."
    override val notifyWhenSearchable = "Me prévenir quand tout sera prêt"
    override val caughtUpNoticeTitle = "Tout est prêt pour la recherche"

    override fun caughtUpNotice(files: Long, textOnly: Boolean) = "Retriever a terminé le traitement (${files(files)}). " +
        if (textOnly) "Retrouvez un fichier par son nom ou par les mots qu’il contient." else "Décrivez un fichier pour le retrouver."

    override fun recentFiles(n: Long) = when (n) {
        0L -> "Vos fichiers récents"
        1L -> "Votre fichier le plus récent"
        else -> "Vos ${formatCount(n)} fichiers récents"
    }

    override fun otherFiles(rest: Long?) = when (rest) {
        null -> "Le reste de vos fichiers"
        1L -> "L’autre fichier"
        else -> plural(rest, "autre fichier", "autres fichiers")
    }

    override fun otherFilesBody(words: DeviceWords) =
        "Quand votre ${device(words)} est en charge et que vous ne l’utilisez pas. S’il y a beaucoup de fichiers, cela peut prendre quelques nuits."

    override fun otherFilesOnBatteryBody(words: DeviceWords) =
        "Quand vous n’utilisez pas votre ${device(words)}$NBSP: sur batterie jusqu’à ce qu’elle descende à ${ReadingPolicy.BATTERY_FLOOR}$NBSP%, " +
            "et pendant la charge. S’il y a beaucoup de fichiers, cela peut prendre quelques jours."

    override fun onBatteryBody(words: DeviceWords) =
        "Aussi sur batterie, jusqu’à ce qu’elle descende à ${ReadingPolicy.BATTERY_FLOOR}$NBSP%, pour garder le reste de votre charge pour la journée. " +
            "S’arrête quand ${powerSave(words)} est activé ou que votre ${device(words)} chauffe."

    override fun recentFilesBody(state: DescriptionState) = when (state) {
        DescriptionState.READY -> "Retrouvez-les en les décrivant."

        DescriptionState.UNAVAILABLE ->
            "Retrouvez-les par leur nom ou un mot qu’ils contiennent. La recherche par description nécessite un téléphone " +
                "avec plus de mémoire."

        DescriptionState.WAITING_FOR_WIFI ->
            "Retrouvez-les dès maintenant par leur nom ou un mot qu’ils contiennent. En attente du Wi-Fi pour " +
                "terminer un téléchargement unique."

        DescriptionState.FAILED -> "Retrouvez-les dès maintenant par leur nom ou un mot qu’ils contiennent. Le téléchargement s’est interrompu."

        DescriptionState.NO_SPACE ->
            "Retrouvez-les dès maintenant par leur nom ou un mot qu’ils contiennent. Il n’y a pas assez d’espace libre pour télécharger la recherche " +
                "par description."

        // Not "one-time": after a chip build fails on its first run, this is the second download.
        DescriptionState.NOT_STARTED ->
            "Retrouvez-les dès maintenant par leur nom ou un mot qu’ils contiennent. La recherche par description " +
                "nécessite un téléchargement."

        DescriptionState.DOWNLOADING, DescriptionState.PREPARING ->
            "Retrouvez-les dès maintenant par leur nom ou un mot qu’ils contiennent. La recherche par description sera prête à la fin de la configuration."
    }

    override fun recentFilesAction(state: DescriptionState, size: String) = when (state) {
        DescriptionState.WAITING_FOR_WIFI -> "Utiliser les données mobiles ($size)"
        DescriptionState.FAILED -> tryAgain
        DescriptionState.NOT_STARTED -> "Télécharger $size"
        else -> null
    }

    override val demoPicking = "Sélection de vos fichiers récents…"
    override val demoHint = "Tapez un mot vu dans un fichier récent$NBSP: photo, capture d’écran ou document."

    /** "1 fichier de démo", "12 fichiers de démo". */
    private fun demoFiles(n: Long) = plural(n, "fichier de démo", "fichiers de démo")

    override fun demoSearchScope(done: Long, finished: Boolean, words: DeviceWords) = when {
        finished -> "Retrouve n’importe quel fichier de votre ${device(words)} par son nom, et ${demoFiles(done)} par ${agree(done, "son", "leur")} contenu."

        // The first frame: nothing processed yet, so no "0 demo files".
        done == 0L -> "Retrouve n’importe quel fichier de votre ${device(words)} par son nom."

        else -> "Retrouve n’importe quel fichier de votre ${device(
            words,
        )} par son nom. Recherche aussi dans le contenu de ${demoFiles(done)} déjà ${agree(done, "traité")}."
    }

    override fun seeAll(files: Long) = if (files == 1L) "Voir le fichier" else "Voir les fichiers (${formatCount(files)})"

    override val read = "Trouvé par son contenu"
    override val foundByName = "Trouvé par son nom"
    override val foundBy = "Trouvé par"

    override fun kindRead(read: Long, planned: Long, kind: Kind): String {
        val (one, many, feminine) = when (kind) {
            Kind.PDF -> Triple("PDF", "PDF", false)
            Kind.DOC -> Triple("document", "documents", false)
            Kind.SCREENSHOT -> Triple("capture d’écran", "captures d’écran", true)
            Kind.PHOTO -> Triple("photo", "photos", true)
            Kind.AUDIO -> Triple("fichier audio", "fichiers audio", false)
            Kind.VIDEO -> Triple("vidéo", "vidéos", true)
            Kind.OTHER -> Triple("autre fichier", "autres fichiers", false)
        }
        val processed = (if (feminine) "traitée" else "traité") + if (read < 2) "" else "s"
        return "${plural(read, one, many)} $processed sur ${formatCount(planned)}"
    }

    override val chooseWhen = "Choisir quand traiter"
    override val tryLabel = "Essayez$NBSP:"

    override fun chooseTitle(words: DeviceWords) = "Maintenant, le reste de votre ${device(words)}"

    override fun chooseBody(rest: Long?) = when (rest) {
        null -> "Le reste de vos fichiers n’est pas encore traité. En attendant, vous pouvez les retrouver par leur nom, mais pas par leur contenu."

        1L -> "1 fichier n’est pas encore traité. En attendant, vous pouvez le retrouver par son nom, mais pas par son contenu."

        else -> "${files(rest)} ${agree(rest, "n’est pas encore traité", "ne sont pas encore traités")}. " +
            "En attendant, vous pouvez ${agree(rest, "le", "les")} retrouver par ${agree(rest, "son", "leur")} nom, " +
            "mais pas par ${agree(rest, "son", "leur")} contenu."
    }

    override fun choosePace(words: DeviceWords) = "Leur traitement prend des heures, alors Retriever avance petit à petit. " +
        "Vous pouvez utiliser votre ${device(words)} et Retriever pendant tout ce temps."

    override val recommended = "Recommandé"
    override val read15Now = "Pendant la charge, et 15 minutes maintenant"
    override val notNowBody = "Vous ne pouvez retrouver les fichiers que par leur nom tant que vous n’avez pas choisi. Vous pouvez choisir à tout moment " +
        "depuis l’accueil."
    override val startProcessing = "Lancer le traitement"
    override val goToHome = "Aller à l’accueil"

    override fun whileChargingBody(words: DeviceWords) =
        "Quand votre ${device(words)} est en charge et que vous ne l’utilisez pas, en général la nuit. Vous pouvez mettre en pause à tout moment."

    override fun chargingAndNowBody(words: DeviceWords) =
        "Commence tout de suite et utilise un peu de batterie, puis continue pendant la charge." + if (words.ios) " Gardez Retriever ouvert." else ""

    override fun readNowBody(words: DeviceWords) =
        "Utilise un peu de batterie. S’arrête plus tôt si votre ${device(words)} chauffe. Puis continue pendant la charge." +
            if (words.ios) " Gardez Retriever ouvert." else ""

    override fun notificationsAsk(demoPending: Boolean) =
        "Une petite notification en haut de l’écran pendant le traitement, pour voir où il en est ou le mettre en pause. " +
            "Pas de son, pas d’autres alertes." +
            if (demoPending) " Nous pouvons aussi vous prévenir quand la démo sera terminée." else ""

    override val progressChannel = "Progression du traitement"
    override val progressChannelBody = "Indique la progression du traitement et signale une seule fois la fin de la démo."
    override val demoDoneNoticeTitle = "La démo est terminée"

    override fun demoDoneNotice(done: Long, planned: Long) = "${readOf(done, planned)}. Ouvrez Retriever pour y faire une recherche."

    override val searchField = "Que cherchez-vous$NBSP?"
    override val nameMatches = "Correspondances dans le nom"
    override val textMatches = "Correspondances dans le texte"
    override val searchingContents = "Recherche dans le contenu des fichiers…"

    override fun copies(n: Int) = plural(n.toLong(), "copie")

    override fun openCopyIn(folder: String) = "Ouvrir la copie dans $folder"

    override val noMatches = "Aucun résultat"

    override fun noMatchesDetail(byMeaning: Boolean) =
        (if (byMeaning) "Décrivez ce qu’il contient, ou demandez ce qu’il dit." else "Essayez un mot qu’il contient ou tiré de son nom.") +
            " Précisez une date ou une période, par exemple «${NBSP}la semaine dernière$NBSP»."

    override val moreWaiting = "Retriever traite encore vos fichiers$NBSP: refaites la recherche plus tard."
    override val searchFailed = "La recherche n’a pas pu aboutir"
    override val showingNameMatches = "Correspondances dans le nom affichées"
    override val searchingWithoutModel = "Recherche par nom et dans le texte déjà traité"

    override fun matches(labels: List<String>) = "Correspondances$NBSP: " + labels.joinToString(", ")

    private val monthNames = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )
    private val weekdayNames = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")

    /** "de" before a kind or date, elided before a vowel: "d’avril", "d’autres fichiers", "de mars". */
    private fun de(word: String) = if (foldLatin(word).firstOrNull() in setOf('a', 'e', 'i', 'o', 'u', 'œ', 'æ')) "d’$word" else "de $word"

    override fun dateLabel(date: DateFilter) = when (date) {
        DateFilter.Today -> "aujourd’hui"

        DateFilter.Yesterday -> "hier"

        is DateFilter.DaysAgo -> if (date.days == 1) "il y a 1${NBSP}jour" else "il y a ${formatCount(date.days.toLong())}${NBSP}jours"

        is DateFilter.Period -> when (date.unit) {
            PeriodUnit.WEEK -> if (date.last) "la semaine dernière" else "cette semaine"
            PeriodUnit.MONTH -> if (date.last) "le mois dernier" else "ce mois-ci"
            PeriodUnit.YEAR -> if (date.last) "l’année dernière" else "cette année"
        }

        is DateFilter.Weekday -> when (date.which) {
            Which.LAST -> "${weekdayNames[date.day]} dernier"
            Which.THIS -> "ce ${weekdayNames[date.day]}"
            Which.BARE -> weekdayNames[date.day]
        }

        is DateFilter.Month -> monthNames[date.month - 1] + (date.year?.let { " $it" } ?: "")

        is DateFilter.Day -> (if (date.day == 1) "1er" else "${date.day}") + " ${monthNames[date.month - 1]}" + (date.year?.let { " $it" } ?: "")

        is DateFilter.Year -> "${date.year}"
    }

    /** "de" with the date: "de la semaine dernière", "du mois dernier", "d’hier", "du 15 mars". */
    private fun fromDate(date: DateFilter): String = when (date) {
        DateFilter.Yesterday -> "d’hier"
        is DateFilter.Period -> if (date.last && date.unit == PeriodUnit.MONTH) "du mois dernier" else de(dateLabel(date))
        is DateFilter.Day -> "du ${dateLabel(date)}"
        else -> de(dateLabel(date))
    }

    override fun noneFound(kinds: List<Kind>, people: List<String>, date: DateFilter?): String {
        val what = kinds.joinToString(" ni ") { de(kindsInSentence(it)) }
        val of = if (people.isEmpty()) "" else " avec " + people.joinToString(" et ")
        val from = date?.let { " ${fromDate(it)}" }.orEmpty()
        return if (what.isEmpty()) "Rien$of$from" else "Pas $what$of$from"
    }

    override fun relaxedNote(missing: String, scope: String?) = "$missing, alors voici " + if (scope == null) "tout." else "vos $scope."

    override fun filterQuery(kind: Kind, date: DateFilter) = "${kindsInSentence(kind)} ${fromDate(date)}"

    override val downloadForDescription = "Télécharger la recherche par description"

    override val readingStatus = "Traitement"
    override val recentlyAdded = "Ajouts récents"
    override val moreOnPhone = "Parcourir"
    override val allFolders = "Tous les dossiers"

    override val howItWorks = "Fonctionnement de Retriever et confidentialité"
    override val whenToRead = "Quand traiter"
    override val whatItCanSee = "Ce que Retriever peut voir"
    override val foldersToSkip = "Dossiers à ignorer"
    override val kindOff =
        "Désactivé$NBSP: Retriever trouve toujours ces fichiers par leur nom, mais oublie leur contenu. Vos fichiers ne sont pas modifiés."
    override val showOnThisDay = "Afficher «${NBSP}Ce jour-là$NBSP» (photos prises à cette date les années précédentes)"

    override val people = "Personnes"
    override val recognizePeople = "Reconnaître les personnes"
    override val peopleOfferTitle = "Trouvez des photos de personnes"
    override val peopleOfferBody = "Retriever peut regrouper les visages de vos photos$NBSP: vous nommez une personne et la retrouvez par son nom. " +
        "Les visages sont traités sur ce téléphone et ne le quittent jamais."
    override val turnOn = "Activer"
    override val recognizePeopleDetail = "Regroupe les visages de vos photos pour que vous puissiez nommer des personnes et les retrouver par leur nom. " +
        "Les visages sont traités sur ce téléphone et ne sont jamais sauvegardés."
    override val forgetPeopleTitle = "Oublier tous les visages et tous les noms$NBSP?"
    override val forgetPeopleBody = "Retriever oublie les visages qu’il a regroupés et les noms que vous avez donnés. Vos photos ne sont pas modifiées."
    override val forget = "Oublier"
    override val notThisPerson = "Pas cette personne"
    override val addAName = "Ajouter un nom"
    override val name = "Nom"
    override val save = "Enregistrer"
    override val samePersonAs = "Même personne que…"
    override val samePersonAsk = "Même personne$NBSP?"
    override val samePersonYes = "Même personne"
    override val samePersonNo = "Pas la même"
    override val samePersonPrevious = "Paire précédente"
    override val samePersonNext = "Paire suivante"

    override fun pairOf(at: Int, of: Int) = "$at sur $of"

    override fun facesToGo(left: Long) = "${photos(left)} à traiter"

    override val retrieversData = "Données de Retriever"
    override val temporaryFiles = "Fichiers temporaires"

    override fun keptOn(size: String, words: DeviceWords) = "$size · stocké sur ${thisDevice(words)}"

    override val searchByMeaning = "Recherche par description"
    override val removeDownload = "Supprimer le téléchargement"
    override val removeDownloadAsk = "Supprimer le téléchargement$NBSP?"
    override val namesStillWork = "La recherche par nom et dans le texte déjà traité fonctionne toujours."

    override fun downloaded(size: String) = "Téléchargé · $size"

    override fun removeDownloadMessage(size: String?) =
        (if (size != null) "Cela libère $size. " else "") + "$namesStillWork La recherche par description nécessitera de nouveau le téléchargement."

    override val freeUpSpace = "Libérer de l’espace"
    override val largeFilesTitle = "Fichiers volumineux"
    override val findDuplicates = "Trouver les doublons"
    override val neverDeletes = "Retriever ne supprime jamais rien sans vous le demander d’abord."

    override fun possibleCopies(n: Long) = if (n < 2) "${files(n)} pourrait être une copie" else "${files(n)} pourraient être des copies"

    override fun deleteFrom(words: DeviceWords) = "Supprimer ${fromDevice(words)}"

    override fun deleteConfirm(words: DeviceWords) = "Le fichier sera supprimé de votre ${device(words)}."

    override val upgradeTitle = "Trouvez vos fichiers en les décrivant"
    override val waitingForWifi = "En attente du Wi-Fi pour télécharger"
    override val useMobileData = "Utiliser les données mobiles"
    override val waitForWifi = "Attendre le Wi-Fi"
    override val checkingDownload = "Vérification du téléchargement…"
    override val downloadFailed = "Téléchargement interrompu. Vérifiez votre connexion et réessayez."
    override val noSpace = "Pas assez d’espace libre pour télécharger"
    override val tuneLeaving = "Vous pouvez déjà rechercher par nom. La configuration reprendra à votre retour."

    override fun upgradeBody(size: String, words: DeviceWords) = "Un seul téléchargement de $size, en Wi-Fi. " +
        "Ensuite, une recherche comme «${NBSP}coucher de soleil à la plage$NBSP» trouve des photos même sans ces mots. Tout reste sur ${thisDevice(words)}."

    override fun downloadProgress(done: String, total: String, percent: Int) = "$done sur $total · $percent$NBSP%"

    override fun mobileDataAsk(size: String) = "Télécharger $size avec les données mobiles$NBSP?"

    override fun freeUp(size: String) = "Libérez $size pour continuer"

    override fun tuneTitle(words: DeviceWords) = "Configuration du traitement pour ${thisDevice(words)}"

    override fun tuneBody(words: DeviceWords) = "Environ une minute. Recherche d’une vitesse de traitement adaptée à ${thisDevice(words)}."

    override fun tuneFailed(words: DeviceWords) = "Impossible de terminer la configuration du traitement pour ${thisDevice(words)}."

    override fun addingMeaning(done: Long, total: Long) = "Ajout de la recherche par description · ${formatCount(done)} sur ${files(total)}"

    override val meaningCatchingUp =
        "La recherche par description fonctionne déjà. Les fichiers traités avant qu’elle soit prête en bénéficient au fil du traitement."

    override fun scheduleTitle(schedule: ReadingSchedule) = when (schedule) {
        ReadingSchedule.CHARGING -> "Pendant la charge"
        ReadingSchedule.BATTERY -> "Aussi sur batterie"
        ReadingSchedule.MANUAL -> "Quand je le lance"
    }

    override fun statusAction(action: StatusAction) = when (action) {
        StatusAction.CONTINUE -> continueAction
        StatusAction.ALLOW_ACCESS -> allowAccess
        StatusAction.CHOOSE_WHEN -> chooseWhen
        StatusAction.READ_FOR_15 -> readFor15
        StatusAction.STOP -> "Arrêter"
        StatusAction.RESUME -> "Reprendre"
        StatusAction.DISMISS -> "Ignorer"
        StatusAction.TRY_AGAIN -> tryAgain
    }

    override fun stopCopy(stop: StopReason, words: DeviceWords) = when (stop) {
        StopReason.PAUSED -> "En pause"
        StopReason.POWER_SAVE -> "En pause tant que ${powerSave(words)} est activé"
        StopReason.HOT -> "En pause pour laisser refroidir votre ${device(words)}"
        StopReason.LOW_BATTERY -> "En pause, batterie faible"
        StopReason.CHARGING_UP -> "En attente d’un peu plus de charge"
        StopReason.BUSY -> "En attente que vous posiez votre ${device(words)}"
        StopReason.UNPLUGGED -> "Attend la prochaine charge"
        StopReason.MANUAL -> "Traitement uniquement quand vous le lancez"
        StopReason.DISK_FULL -> "En pause, votre ${device(words)} est plein"
        StopReason.DONE, StopReason.BUDGET -> "Petite pause"
    }

    override val noAccessTitle = "Retriever ne peut pas voir vos fichiers"

    override fun noAccessDetail(words: DeviceWords) =
        "La recherche fonctionne toujours sur les fichiers déjà traités." + if (words.ios) " Cela ouvre les Réglages de votre ${device(words)}." else ""

    override val finishSetupTitle = "Terminer la configuration"
    override val finishSetupDetail = "Choisissez les fichiers que Retriever peut rechercher."
    override val downloadPausedTitle = "Téléchargement en pause"

    override fun downloadPausedDetail(words: DeviceWords, size: String) =
        "Il reprendra quand vous serez en Wi-Fi. Si votre ${device(words)} est plein, il faut $size d’espace libre."

    override fun downloadingTitle(percent: Int?) = "Téléchargement de la recherche par description" + (percent?.let { " · $it$NBSP%" } ?: "")

    override val downloadingDetail = "Vous pouvez continuer à utiliser Retriever."

    override val addingDescriptionTitle = "Ajout de la recherche par description"
    override val allFilesProcessedTitle = "Tous les fichiers sont traités"
    override val countingTitle = "Décompte de vos fichiers…"
    override val notNowTitle = "Recherche par nom pour l’instant"

    override fun readNowTitle(minutesLeft: Int) = "Traitement pendant 15 minutes · encore $minutesLeft${NBSP}min"

    override val sampleTitle = "Traitement des fichiers de démo"
    override val gettingPeopleReadyTitle = "Préparation de Personnes"
    override val lookingForFacesTitle = "Recherche de visages dans vos photos"
    override val deepeningTitle = "Traitement de la suite de certains fichiers longs"
    override val chargingReadTitle = "Traitement pendant la charge"
    override val lookingTitle = "Recherche de nouveaux fichiers…"
    override val caughtUpTitle = "Terminé. Retriever a traité tout ce qu’il pouvait"
    override val limitedTitle = "Terminé. Quelques fichiers longs n’ont été traités qu’en partie"

    override val pausedDetail = "La recherche fonctionne toujours."

    override fun powerSaveDetail(words: DeviceWords) = "Reprend quand ${powerSave(words)} est désactivé."

    override val hotDetail = "Reprend tout seul."
    override val lowBatteryDetail = "Branchez le chargeur et Retriever reprendra."
    override val chargingUpDetail = "Le traitement commence quand la batterie est un peu plus chargée."
    override val busyDetail = "Ou ouvrez Retriever pour reprendre maintenant."

    override fun chargingDetail(words: DeviceWords) = "Retriever reprend quand votre ${device(words)} est en charge."

    override val manualDetail = "Vous pouvez trouver n’importe quel fichier par son nom."

    override fun readNowDetail(words: DeviceWords) = "Utilise un peu de batterie. " + if (words.ios) "Gardez Retriever ouvert." else "S’arrête tout seul."

    override val sampleDetail = "Environ une minute. Rien n’est modifié ni envoyé."

    override fun meaningDetail(words: DeviceWords) =
        "Pour retrouver vos fichiers en décrivant leur contenu ou ce que montrent vos photos. Uniquement sur ${thisDevice(words)}."

    override val peopleDownloadDetail = "Un téléchargement unique pour Personnes. La recherche fonctionne pendant tout ce temps."
    override val facesDetail = "Tous vos fichiers sont traités. La recherche fonctionne pendant tout ce temps."
    override val deepeningDetail = "Vous pouvez les retrouver par leur début."
    override val readingMeaningDetail = "La recherche par description est aussi en cours d’ajout."

    override fun readingDetail(words: DeviceWords) = if (words.ios) {
        "La recherche fonctionne pendant tout ce temps. Retriever traite vos fichiers quand il est ouvert, et parfois quand iOS le permet."
    } else {
        "Vous pouvez fermer Retriever, il continue. La recherche fonctionne pendant tout ce temps."
    }

    override fun waitingLongFilesDetail(words: DeviceWords) =
        "Retriever traite la suite de certains fichiers longs quand votre ${device(words)} le permet. La recherche fonctionne déjà."

    override fun waitingMeaningDetail(words: DeviceWords) =
        "Retriever ajoute la recherche par description quand votre ${device(words)} le permet. La recherche fonctionne déjà."

    override val diskFullDetail = "Libérez de l’espace et Retriever reprendra."

    override fun waitingDetail(words: DeviceWords) = "Votre ${device(words)} décide quand Retriever reprend. Rien n’est perdu."

    override val caughtUpDetail = "Il traite les nouveaux fichiers tout seul."

    override val gettingReadersTitle = "Préparation du traitement de vos fichiers"
    override val gettingReadersDetail = "Un téléchargement unique depuis les services Google Play. La recherche par nom fonctionne déjà."

    override val peopleWaitsForWifi = "Le téléchargement de Personnes attend le Wi-Fi."

    override fun deepeningLead(left: Long) = "${notReadYet(left)}."

    override fun longFilesWait(n: Long) = "Tous les fichiers sont traités. La suite de ${plural(n, "fichier long", "fichiers longs")} attend."

    override val allFilesProcessedLead = "Tous les fichiers sont traités."

    override val foundByOther = "Son nom. Retriever ne peut pas encore traiter ce type de fichier."
    override val foundByBeginningRestPending = "Son nom et son début. La suite n’est pas encore traitée."
    override val foundByPartCouldntFinish = "Son nom et une partie de son contenu. Retriever n’a pas pu terminer de le traiter."
    override val foundByBeginning = "Son nom et son début"
    override val foundByInsideMeaningLimited = "Son nom et son contenu. La préparation de la recherche par description n’a pas pu être terminée."
    override val foundByInsideDeepening = "Son nom et son contenu. La recherche par description est à venir."
    override val foundByInside = "Son nom et son contenu"
    override val foundByReadingOff = "Son nom. Le traitement est désactivé pour ce type de fichier."

    override fun foundBySkipped(tries: Long) = "Son nom. Retriever n’a pas pu le traiter après ${plural(tries, "essai")}."

    override val foundByNothingInside = "Son nom. Rien de son contenu n’a pu être traité."
    override val foundByNotYet = "Son nom. Pas encore traité."

    override val processingFailed = "Un problème est survenu pendant le traitement de vos fichiers. Retriever va réessayer."

    // FileActions
    override val fileNameField = "Nom"

    // Notifications
    override fun notificationBody(count: String?, detail: String?, read: Long) = listOfNotNull(count, detail).joinToString(". ").ifEmpty { readCount(read) }

    // SearchResults
    override fun understoodChip(labels: List<String>) = labels.joinToString(" · ")

    override fun noMatchesMessage(byMeaning: Boolean, waiting: Boolean, wordsOnly: Boolean) =
        listOfNotNull(noMatchesDetail(byMeaning), moreWaiting.takeIf { waiting }, searchingWithoutModel.takeIf { wordsOnly }).joinToString(" ")

    override fun reasonLine(parts: List<String>) = parts.joinToString(" · ")

    // SearchScreen
    override val appSettings = "Paramètres"

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
    override val onlyPickedPhotos = "Seulement les photos que vous avez choisies"

    override val musicAndRecordings = "Musique et enregistrements"

    override fun notCoveredMedia(photos: Boolean, videos: Boolean, music: Boolean) = listOfNotNull(
        "photos".takeIf {
            photos
        },
        "vidéos".takeIf { videos },
        "musique".takeIf { music },
    ).let { m ->
        "Non inclus$NBSP: " +
            if (m.size == 1) m[0] else m.dropLast(1).joinToString(", ") + " et " + m.last()
    }

    override fun notCoveredDocuments(hasFolders: Boolean) = if (hasFolders) {
        "Non inclus$NBSP: documents et téléchargements hors des dossiers que vous avez ajoutés"
    } else {
        "Non inclus$NBSP: documents et téléchargements"
    }

    override val pickFolderOnPhone = "Choisissez un dossier dans le stockage de ce téléphone."

    override val alreadySearchesFolder = "Retriever recherche déjà dans ce dossier."

    // AccessService
    override val couldntKeepFolder = "Impossible de garder l’accès à ce dossier. Essayez de le choisir à nouveau."

    // BrowseScreen
    override val allFiles = "Tous les fichiers"

    override val filesRetrieverCanAccess = "Fichiers accessibles à Retriever"

    override val sortLargestDetail = "Les plus volumineux d’abord"

    override val sortNameDetail = "Par nom, de A à Z"

    override val sortNewestDetail = "Les plus récents d’abord · par date de modification"

    override val couldntChangePerson = "Impossible de modifier cette personne. Réessayez."

    override val back = "Retour"

    override val couldntLoadYourFiles = "Impossible de charger vos fichiers"

    override val wentWrongReadingThem = "Un problème est survenu pendant leur chargement. Réessayez."

    override val keepUsefulFilesClose = "Gardez vos fichiers utiles à portée de main"

    override val tapStarToFindHere = "Touchez l’étoile d’un fichier pour le retrouver ici."

    override val chooseFilesToSearch = "Choisissez les fichiers à rechercher"

    override val addPhotosOrFolder = "Ajoutez des photos ou un dossier."

    override val manageAccess = "Gérer l’accès"

    override val nothingHereYet = "Rien ici pour l’instant"

    override fun filesShowUpHere(words: DeviceWords) = "Les fichiers apparaissent ici dès que Retriever les trouve sur votre ${device(words)}."

    override val moreOptions = "Plus d’options"

    override val couldntLoadMoreFiles = "Impossible de charger plus de fichiers"

    override val sortNewest = "Plus récents"

    override val sortLargest = "Plus volumineux"

    override val sortName = "Nom"

    // BrowseViewModel
    override val couldntLoadTheseFiles = "Impossible de charger ces fichiers."

    // PersonActions
    override val rename = "Renommer"

    // DuplicatesScreen
    override val duplicates = "Doublons"

    override val keepOneCopy = "Gardez au moins une copie de chaque fichier"

    override fun filesCouldntBeDeleted(n: Long) = if (n < 2) "${files(n)} n’a pas pu être supprimé" else "${files(n)} n’ont pas pu être supprimés"

    override fun deleteSelected(n: Long, size: String) = "Supprimer ${files(n)} ($size)"

    override val lookingForCopiesEllipsis = "Recherche de copies…"

    override val couldntLookForDuplicates = "Impossible de rechercher les doublons"

    override val noDuplicatesFound = "Aucun doublon trouvé"

    override fun comparedFiles(n: Long) = "Retriever a comparé ${files(n)}."

    override fun couldBeFreed(size: String) = "Vous pourriez libérer $size"

    override fun copiesEach(n: Long, size: String) = "${plural(n, "copie")} · $size par copie"

    override fun openNamed(name: String) = "Ouvrir $name"

    override val deselect = "Désélectionner"

    override val select = "Sélectionner"

    override val deleteFilesTitle = "Supprimer les fichiers$NBSP?"

    override fun deleteFilesMessage(n: Long) = "Supprimer ${files(n)}$NBSP? Cette action est irréversible."

    override val delete = "Supprimer"

    // DuplicatesViewModel
    override val somethingWentWrongTryAgain = "Un problème est survenu. Réessayez."

    // FileActions
    override val addToStarred = "Ajouter aux favoris"

    override val share = "Partager"

    override val openInAnotherApp = "Ouvrir dans une autre appli"

    override val fileInfo = "Infos sur le fichier"

    override val removeFromStarred = "Retirer des favoris"

    override val topLevel = "Racine"

    override val couldntChangeStar = "Impossible de modifier le favori. Réessayez."

    override val findSimilar = "Trouver des fichiers similaires"

    override val couldntFinishThat = "Impossible de terminer. Le fichier a peut-être été déplacé ou l’accès a changé."

    override val renameFile = "Renommer le fichier"

    override val deleteThisFile = "Supprimer ce fichier$NBSP?"

    // FileInfo
    override fun kindAndSize(kind: String, size: String) = "$kind · $size"

    override val locationLabel = "Emplacement"

    override val hideFullPath = "Masquer le chemin complet"

    override val showFullPath = "Afficher le chemin complet"

    override val locationDetails = "Détails de l’emplacement"

    override val pathClipLabel = "Chemin"

    override val copyPath = "Copier le chemin"

    override val dimensionsLabel = "Dimensions"

    override fun dimensions(width: Int, height: Int) = "$width × $height"

    override val durationLabel = "Durée"

    override val pagesLabel = "Pages"

    override val internalStorage = "Stockage interne/"

    // FoldersScreen
    override val folders = "Dossiers"

    override val foldersDidntLoad = "Les dossiers n’ont pas pu être chargés"

    override val noFoldersYet = "Aucun dossier pour l’instant"

    override val tryAgainInAMoment = "Réessayez dans un instant."

    override val foldersAppearPhone = "Les dossiers apparaîtront quand Retriever aura parcouru votre téléphone."

    override fun stopSearchingAsk(name: String) = "Ne plus rechercher dans $name$NBSP?"

    override fun stopSearchingMessage(n: Long, name: String) = if (n < 2) {
        "${files(n)} du dossier «$NBSP$name$NBSP» est retiré de la recherche dès maintenant. Pour le retrouver, Retriever devra le traiter à nouveau. "
    } else {
        "Les ${files(n)} du dossier «$NBSP$name$NBSP» sont retirés de la recherche dès maintenant. Pour les retrouver, Retriever devra les traiter à nouveau. "
    } + "Vous pouvez remettre ce dossier depuis la ligne d’état en haut de l’accueil."

    override val stopSearching = "Ne plus rechercher"

    override fun filesInParent(n: Long, parent: String?) = if (parent == null) files(n) else "${files(n)} · $parent"

    override fun moreOptionsFor(name: String) = "Plus d’options pour $name"

    override val stopSearchingThisFolder = "Ne plus rechercher dans ce dossier"

    // FoldersViewModel
    override val couldntLoadYourFolders = "Impossible de charger vos dossiers. Réessayez dans un instant."

    override val couldntStopSearchingFolder = "Impossible d’arrêter la recherche dans ce dossier. Réessayez dans un instant."

    // HomeScreen
    override val onThisDayHidden = "«${NBSP}Ce jour-là$NBSP» masqué"

    override val undo = "Annuler"

    override val showProcessingDetails = "Afficher les détails du traitement"

    override val lookingForYourFiles = "Recherche de vos fichiers"

    override val nothingToSearchYet = "Rien à rechercher pour l’instant"

    override val takesAMinute = "Cela prend une minute. Vous pourrez rechercher par nom dès qu’ils apparaîtront."

    override val saveAPdfHint = "Enregistrez un PDF, faites une capture d’écran ou prenez une photo pour voir ce fichier apparaître ici."

    // HomeSections
    override val showAll = "Tout afficher"

    override fun sectionSpoken(title: String, subtitle: String) = "$title, $subtitle"

    override val openAction = "Ouvrir"

    override fun personTileSpoken(name: String, photos: Long) = "$name, ${photos(photos)}"

    override fun browseTileSpoken(label: String, count: String) = "$label, $count"

    override val onThisDay = "Ce jour-là"

    override fun onThisDaySpoken(year: Int, count: String, date: String) = "$year, $count du $date"

    override val showThem = "Les afficher"

    override val hideOnThisDay = "Masquer «${NBSP}Ce jour-là$NBSP»"

    override val starredSection = "Favoris"

    // ReadingPermission
    override val notificationPermissionAsk = "Afficher une notification pendant que Retriever traite vos fichiers$NBSP?"

    override val processFasterAsk = "Traiter plus vite pendant la charge$NBSP?"

    override val processFasterBody = "Android ralentit les applis en arrière-plan. " +
        "Si l’utilisation de la batterie de Retriever est réglée sur «${NBSP}Sans restriction$NBSP», " +
        "il traite les fichiers plus vite pendant la charge et termine bien plus tôt. " +
        "Dans Paramètres, ouvrez Batterie et choisissez «${NBSP}Sans restriction$NBSP»."

    override val openSettingsLowercase = "Ouvrir Paramètres"

    // ModelDownload
    override val searchModelDownloadTitle = "Modèle de recherche de Retriever"

    // ModelRepository
    override val downloadStopped = "Le téléchargement s’est interrompu. Vérifiez votre connexion et réessayez."

    override val downloadDamaged = "Le téléchargement est endommagé. Réessayez."

    override fun needsFreeSpace(gb: String) = "Il faut $gb${NBSP}Go d’espace libre. Libérez de l’espace, puis réessayez."

    // SearchResults
    override fun likeFile(name: String) = "Semblable à $name"

    override val photoYoureSearchingWith = "La photo utilisée pour la recherche"

    override val stopSearchingByPhoto = "Arrêter la recherche par photo"

    override val searchWordsAsTyped = "Rechercher les mots exacts"

    override val photoSearchAfterSetup = "La recherche par photo fonctionnera à la fin de la configuration."

    override val matchingExactWordsForNow = "Recherche des mots exacts pour l’instant. Les résultats par description reviendront bientôt."

    override fun noMatchesIn(scope: String) = "Aucun résultat dans «$NBSP$scope$NBSP»"

    override val nothingLikePhotoThere = "Rien de semblable à cette photo ici."

    override fun nothingForThere(query: String) = "Rien pour «$NBSP$query$NBSP» ici."

    override val searchAllFiles = "Rechercher tous les fichiers"

    override val nothingLikeThisYet = "Rien de semblable pour l’instant"

    override val tryAnotherPhoto = "Essayez une autre photo."

    override val tryFewerWords = "Essayez avec moins de mots"

    override val filterAll = "Tout"

    override val filterStarred = "Favoris"

    override val expanded = "Développé"

    override val collapsed = "Réduit"

    override fun lessLikelyMatches(n: Long) = "Résultats moins probables (${formatCount(n)})"

    override val moreDocuments = "Autres documents"

    override val recordings = "Enregistrements"

    override val documentsAndMore = "Documents et autres fichiers"

    // SearchScreen
    override val searchWithPhoto = "Rechercher avec une photo"

    override val clearSearch = "Effacer la recherche"

    override val recentSearches = "Recherches récentes"

    override fun removeFromHistory(query: String) = "Retirer $query de l’historique"

    override val searchAction = "Rechercher"

    override val clearHistory = "Effacer l’historique"

    // SearchViewModel
    override val likeYourPhoto = "Semblable à votre photo"

    override val couldntOpenPhoto = "Impossible d’ouvrir cette photo. Choisissez-en une autre."

    override val thatDidntWork = "Cela n’a pas fonctionné. Réessayez."

    // SettingsScreen
    override val includedFilesFooter = "$kindOff Un dossier à ignorer est exclu de la recherche."

    override val onlyWhenYouChoose = "Seulement quand vous choisissez «$NBSP$readFor15$NBSP»."

    override val processingNotifications = "Notifications de traitement"

    override val notificationsOn = "Activées"

    override val notificationsOff = "Désactivées. Le traitement continue, vous ne voyez simplement pas où il en est."

    override val includedFiles = "Fichiers inclus"

    override val noneExcluded = "Aucun"

    override fun includeNamed(folder: String) = "Inclure $folder"

    override val includeFolder = "Inclure le dossier"

    override val privacy = "Confidentialité"

    override val saveRecentSearches = "Enregistrer les recherches récentes"

    override val savedOnDeviceShortcuts = "Enregistrées sur cet appareil. Aussi utilisées pour les raccourcis de recherche."

    override val clearRecentSearches = "Effacer les recherches récentes"

    override val clearSearchesAndShortcutsAsk = "Effacer les recherches récentes et les raccourcis$NBSP?"

    override val clear = "Effacer"

    override val storage = "Stockage"

    override val appearance = "Apparence"

    override val wallpaperColours = "Couleurs du fond d’écran"

    override val wallpaperColoursOff = "Désactivé$NBSP: Retriever utilise son ambre chaleureux."

    override val about = "À propos"

    override val version = "Version"

    override val openSourceNotices = "Licences open source"

    override val removeDownloadConfirm = "Supprimer le téléchargement"

    override fun howItWorksBody(words: DeviceWords) = wholeDeviceNote(words) + " " + onDevice(words) +
        ". La recherche et la reconnaissance de texte peuvent nécessiter un téléchargement sur Internet. " +
        "L’appli qui gère vos fichiers peut aussi utiliser Internet pour ouvrir ceux qui sont stockés en ligne."

    override val howItWorksTimes =
        "Il traite vos fichiers aux moments choisis dans «$NBSP$whenToRead$NBSP». Les fichiers longs sont traités petit à petit$NBSP: " +
            "vous pouvez d’abord les retrouver par leur début."

    override val runSetupCheck = "Vérifier la configuration"

    override val tuningFewMinutes = "Réglage en cours… quelques minutes au plus"

    override fun tuneFailedMessage(message: String) = "Échec du réglage$NBSP: $message"

    override val processingIsSetUp = "Le traitement est configuré"

    override val adjustsProcessingSpeed = "Adapte la vitesse de traitement à ce téléphone."

    override val useMobileDataForDownloads = "Utiliser les données mobiles pour les téléchargements"

    override val downloadsWaitForWifiShort = "Désactivé$NBSP: les téléchargements attendent le Wi-Fi."

    override val loading = "Chargement…"

    // SettingsViewModel
    override val couldntSaveChange = "Impossible d’enregistrer cette modification. Réessayez."

    override val searchDownloadRemoved = "Téléchargement supprimé"

    // Starring
    override val starAdded = "Ajouté aux favoris"

    override val starRemoved = "Retiré des favoris"

    // StatusScreen
    override val checkingYourFiles = "Vérification de vos fichiers…"

    override val hide = "Masquer"

    override val reviewFiles = "Voir les fichiers"

    override val letProcessWhileAway = "Laisser Retriever traiter les fichiers quand vous ne l’utilisez pas"

    override fun batteryMayStop(chargingOnly: Boolean) = "Votre téléphone peut arrêter Retriever pour économiser la batterie." +
        if (chargingOnly) " Retriever traite toujours vos fichiers uniquement pendant la charge." else ""

    override val settingsRowDetail = "Ce qu’il faut traiter, stockage et plus"

    override val chooseMorePhotos = "Choisir d’autres photos"

    override val foldersYouAdded = "Dossiers que vous avez ajoutés"

    override fun removeNamed(name: String) = "Retirer $name"

    override val allowAccessToAllFiles = "Autoriser l’accès à tous les fichiers"

    // Format
    override fun starredScope(kind: String?) = kind?.let { "$it ${if (it in feminineKinds) "favorites" else "favoris"}" } ?: starredFiles

    /** The lowercase kind plurals [starredScope] receives that are feminine in French. */
    private val feminineKinds = setOf("captures d’écran", "photos", "vidéos")

    override fun pageBadge(page: Int) = "Page $page"

    // FileRow
    override fun openAtPage(page: Int) = "Ouvrir à la page $page"

    override fun playFrom(time: String) = "Lire à partir de $time"

    override fun kindSizeDate(kind: String, size: String, date: String) = "$kind · $size · $date"

    override fun fileRowSpoken(kind: String, name: String, date: String, position: String?, note: String?) =
        "$kind, $name, $date" + (position?.let { ", $it" } ?: "") + (note?.let { ", $it" } ?: "")

    // ReadingFiles
    override val fileActions = "Actions sur le fichier"

    override fun fileSpoken(name: String, kind: String, date: String) = "$name, $kind, $date"

    // DocScreen
    override val notProcessedThisYet = "Retriever n’a pas encore traité ce fichier"

    override val waitingItsTurn = "Il attend son tour avec les autres nouveaux fichiers. Ouvrez-le dans une autre appli pour le voir maintenant."

    override val noTextFound = "Retriever n’a pas trouvé de texte dans ce fichier"

    override val scanOrPictures = "C’est peut-être un document numérisé, ou il ne contient que des images. Ouvrez-le dans une autre appli pour le voir."

    override val couldntOpenFile = "Impossible d’ouvrir ce fichier"

    override val maybeMovedOrDeleted = "Il a peut-être été déplacé ou supprimé."

    override val previousMatch = "Résultat précédent"

    override fun matchOf(at: Int, total: Int) = "Résultat $at sur $total"

    override fun matchCount(n: Long) = plural(n, "résultat")

    override val nextMatch = "Résultat suivant"

    // Opener
    override val noAppOpensFile = "Aucune appli de ce téléphone ne peut ouvrir ce fichier"

    // PdfPageScreen
    override fun pdfSubtitle(page: Int, count: Int, matchedPage: Int?) = (matchedPage?.let { "${matchOnPage(it)} · " } ?: "") + pageOf(page, count)

    override val closePage = "Fermer la page"

    override val pdfNeedsPassword = "Ce PDF est protégé par un mot de passe"

    override val pdfFindableByName = "Retriever peut toujours le trouver par son nom. Vous pouvez l’ouvrir dans une autre appli."

    override val folderAccessChanged = "L’accès à ce dossier a changé"

    override val chooseFolderAgain = "Choisissez à nouveau le dossier pour ouvrir ce fichier."

    override val fileNoLongerAvailable = "Ce fichier n’est plus disponible"

    override val goBack = "Retour"

    override val couldntOpenPdf = "Impossible d’ouvrir ce PDF"

    override fun pageZoomed(page: Int) = "Page $page, agrandie"

    override fun pageOf(page: Int, count: Int) = "Page $page sur $count"

    override val zoomPage = "Agrandir la page"

    override fun pageSpoken(label: String, matched: Boolean) = label + if (matched) ", où votre recherche a trouvé un résultat" else ""

    override val matchedHere = "Trouvé ici"

    // PhotoScreen
    override val couldntShowPhoto = "Impossible d’afficher cette photo"

    // PlayerScreen
    override fun matchAt(time: String) = "Résultat à $time"

    override fun couldntPlay(audio: Boolean) = "Impossible de lire ${if (audio) "cet enregistrement" else "cette vidéo"}"

    override val maybeMovedOrCantPlay = "Le fichier a peut-être été déplacé ou supprimé, ou ce téléphone ne peut pas le lire."

    // ViewerQueue
    override fun positionOf(at: Int, count: Int) = "$at sur $count"

    // ViewerTopBar
    override val previousFile = "Fichier précédent"

    override val nextFile = "Fichier suivant"

    // AppModel+Loop
    override fun keepProcessingFailed(words: DeviceWords) = "iOS n’a pas pu poursuivre le traitement pendant que vous n’utilisiez pas Retriever. " +
        "Gardez-le ouvert, ou laissez-le traiter vos fichiers pendant que votre ${device(words)} charge."

    // AppModel+Settings
    override val couldntChangePersonCurly = "Impossible de modifier cette personne. Réessayez."

    // FolderSource
    override val aFolder = "Un dossier"

    // SearchModel
    override val starredFiles = "fichiers favoris"

    // Copy
    override val photosAccessOff = "L’accès aux photos est désactivé"

    override val photosOffNoteDetail =
        "La recherche fonctionne toujours sur les fichiers déjà traités. Activez-le pour rechercher aussi dans vos photos et captures d’écran."

    override val seesOnlyPickedPhotos = "Retriever ne voit que les photos que vous avez choisies"

    override val canSearchJustThose = "Il ne peut rechercher que dans celles-ci."

    override val folderAdded = "Dossier ajouté. Retriever va traiter son contenu."

    override fun folderOverlaps(name: String) = "Ce dossier contient des fichiers en commun avec «$NBSP$name$NBSP», où Retriever recherche déjà."

    override val folderRefused = "iOS n’a pas autorisé Retriever à conserver l’accès à ce dossier. Essayez-en un autre."

    override fun backgroundNote(words: DeviceWords) =
        "Retriever traite vos fichiers quand il est ouvert, et parfois quand vous ne l’utilisez pas, si iOS le permet. " +
            "Le traitement automatique attend que votre ${device(words)} soit en charge. «$NBSP$readFor15$NBSP» utilise un peu de batterie."

    override val screenKeptOn = "Votre écran reste allumé quand Retriever traite vos fichiers pendant 15 minutes. " +
        "Vous pouvez désactiver cette option dans les paramètres de Retriever."

    override fun showingLabels(labels: List<String>) = "Affichage$NBSP: " + labels.joinToString(" · ")

    override val searchMyExactWords = "Rechercher mes mots exacts"

    override fun processedSoFar(n: Long) = "${readCount(n)} jusqu’ici"

    // Format
    override fun opensAtPage(page: Int) = "S’ouvre à la page $page"

    override fun playsFrom(time: String) = "Lecture à partir de $time"

    override val opensHint = "Ouvre le fichier"

    // BrowseView
    override val couldntLoadFiles = "Impossible de charger les fichiers"

    override val wentWrongReadingList = "Un problème est survenu pendant le chargement de la liste."

    override fun sizeFolderDate(size: String, folder: String, date: String) = "$size · $folder · $date"

    override val sortBy = "Trier par"

    override val sortMenu = "Trier"

    override val ok = "OK"

    // DuplicatesView
    override val noDuplicatesMessage =
        "Retriever a vérifié les fichiers de vos dossiers ajoutés et n’a trouvé aucune copie de plus de 1 Mo. Les photos ne sont pas vérifiées."

    override val lookingForCopies = "Recherche de copies"

    override val thisCanTakeAMinute = "Cela peut prendre une minute."

    override fun deleteFilesAsk(n: Long) = if (n < 2) "Supprimer ${formatCount(n)} fichier$NBSP?" else "Supprimer ${formatCount(n)} fichiers$NBSP?"

    override val cantBeRecovered = "Ils ne pourront pas être récupérés."

    override val comparesFilesNote =
        "Compare les fichiers des dossiers que vous avez ajoutés, pas les photos. La copie la plus ancienne de chaque fichier est " +
            "conservée. Touchez une copie pour changer ce qui est supprimé."

    override fun folderAndDate(folder: String, date: String) = "$folder · $date"

    override val selectedToDeleteHint = "Sélectionné pour suppression. Touchez deux fois pour le garder."

    override val keptHint = "Conservé. Touchez deux fois pour le sélectionner pour suppression."

    override fun filesWerentDeleted(n: Long) =
        if (n < 2) "${formatCount(n)} fichier n’a pas été supprimé" else "${formatCount(n)} fichiers n’ont pas été supprimés"

    // FileActions
    override val couldntOpenFolderUnavailable = "Impossible d’ouvrir ce fichier. Son dossier n’est peut-être pas disponible."

    override val photosAppName = "Photos"

    override val moreActions = "Plus d’actions"

    override val couldntFinish = "Impossible de terminer"

    // FoldersView
    override val foldersWithFiles = "Dossiers contenant des fichiers"

    override val couldntLoadFolderList = "Impossible de charger la liste des dossiers. Réessayez"

    override val removeFolder = "Retirer"

    override val cantOpenFolderAnyMore = "Retriever ne peut plus ouvrir ce dossier. Retirez-le, puis ajoutez-le à nouveau."

    override val alsoSearchesOwnFolder = "Retriever recherche aussi dans son propre dossier de l’app Fichiers. Retirer un dossier ici ne le supprime jamais."

    override val couldntLoadFolders = "Impossible de charger les dossiers"

    override val wentWrongReadingFolders = "Un problème est survenu pendant le chargement de vos dossiers."

    override val foldersAppearFiles = "Les dossiers apparaîtront quand Retriever aura parcouru vos fichiers."

    override fun stopSearchingForget(n: Long, name: String) =
        "Retriever oubliera ${files(n)} du dossier «$NBSP$name$NBSP». Vous pourrez remettre ce dossier ici."

    override fun removeFolderAsk(name: String) = "Retirer $name$NBSP?"

    override val removeFolderMessage =
        "Retriever ne recherchera plus dans ce dossier et oubliera ce qu’il y a traité. Le dossier et ses fichiers restent où ils sont."

    // HomeSections
    override val showsAll = "Affiche tout"

    override val showsThem = "Les affiche"

    override val hideOnThisDayIos = "Masquer «${NBSP}Ce jour-là$NBSP»"

    override fun homeTileSpoken(kind: String, name: String, date: String, starred: Boolean) = "$kind, $name, $date" + if (starred) ", dans les favoris" else ""

    // HomeView
    override val addFolderOrPhotos = "Ajoutez un dossier ou autorisez l’accès aux photos pour retrouver vos fichiers grâce à leur contenu."

    override val allowPhotosAccess = "Autoriser l’accès aux photos"

    override val turnOnPhotosAccess = "Activer l’accès aux photos"

    override val onThisDayTitle = "Ce jour-là"

    override val openSettings = "Ouvrir Réglages"

    override val alsoSearchesFolders = "Retriever recherche aussi dans les dossiers que vous ajoutez."

    // ReadingFiles
    override val openFileHint = "Ouvre le fichier"

    // ResultViews
    override fun sharedPhotoName(ext: String?) = "Photo.${ext ?: "jpg"}"

    // RootView
    override val setupTitle = "Configuration"

    // SearchView
    override val docsShort = "Docs"

    override val searchByPhoto = "Rechercher par photo"

    override val removeSearch = "Retirer"

    override val removeFromRecentSearches = "Retirer des recherches récentes"

    override val somethingWentWrongSearching = "Un problème est survenu pendant la recherche."

    override val lookingEllipsis = "Recherche…"

    override fun turnOnToSearchScope(scope: String) = "Activez l’accès dans Réglages pour inclure «$NBSP$scope$NBSP» dans la recherche."

    override val noMatchesInPickedPhotos = "Aucun résultat dans les photos que vous avez choisies"

    override val onlySeesChosenPhotos = "Retriever ne voit que les photos que vous avez choisies. Choisissez-en d’autres pour élargir la recherche."

    override val notQuiteReady = "Pas tout à fait prêt"

    override fun nothingLikeThatOn(words: DeviceWords) = "Rien de semblable sur ${thisDevice(words)} pour l’instant."

    override fun nothingInScopeFits(scope: String) = "Aucun résultat dans «$NBSP$scope$NBSP». Essayez tous vos fichiers."

    override fun noStarredMatch(query: String) = "Aucun favori ne correspond à «$NBSP$query$NBSP»"

    override val onlyStarredShowHere = "Seuls vos favoris s’affichent ici. Essayez tous vos fichiers."

    override fun nothingLikePhotoIn(scope: String) = "Rien de semblable à cette photo dans «$NBSP$scope$NBSP»"

    override fun noMatchesFor(query: String) = "Aucun résultat pour «$NBSP$query$NBSP»"

    override val tryAllFilesInstead = "Essayez plutôt tous vos fichiers."

    override fun nothingInScopeMatches(scope: String) = "Aucun résultat dans «$NBSP$scope$NBSP». Essayez tous vos fichiers."

    override val likeThisPhoto = "Semblable à cette photo"

    override val photosOffResults = "L’accès aux photos est désactivé$NBSP: ces résultats n’incluent pas vos photos."

    // SettingsView
    override val cleared = "Effacé"

    override fun savedOnDeviceOnly(words: DeviceWords) = "Enregistrées uniquement sur ${thisDevice(words)}. Rien n’est envoyé nulle part."

    override val clearRecentSearchesAsk = "Effacer les recherches récentes$NBSP?"

    override fun privacyBody(words: DeviceWords) = "Retriever traite vos fichiers sur ${thisDevice(words)}. " +
        "Vos recherches, vos favoris et ce qu’il trouve dans vos fichiers restent ici. " +
        "Le téléchargement de la recherche par description et l’ouverture de fichiers stockés dans iCloud peuvent utiliser Internet."

    override val longFilesBitAtATime =
        "Les fichiers longs sont traités petit à petit$NBSP: vous pouvez d’abord les retrouver par leur début, puis la suite arrive."

    override val onlyWhenYouChooseAnyFile =
        "Seulement quand vous choisissez «$NBSP$readFor15$NBSP». Vous pouvez toujours trouver n’importe quel fichier par son nom."

    override fun alsoOnBatteryIosBody(words: DeviceWords) = "Traite vos fichiers quand Retriever est ouvert, au-dessus de 20$NBSP% de batterie. " +
        "Quand vous ne l’utilisez pas, iOS attend que votre ${device(words)} soit en charge. " +
        "Se met en pause quand votre ${device(words)} chauffe ou quand ${powerSave(words)} est activé."

    override val keepScreenOnToggle = "Garder l’écran allumé pendant les 15 minutes"

    override fun adjustProcessingFor(words: DeviceWords) = "Adapter le traitement à ${thisDevice(words)}"

    override val runSetupCheckAgain = "Relancer la vérification de la configuration."

    override val kindsFooterVideos = "$kindOff Vidéos$NBSP: images seulement, sans le son."

    override fun includeQuoted(name: String) = "Inclure «$NBSP$name$NBSP»"

    override val doesntLookInThese = "Ces dossiers sont exclus de la recherche. Vos fichiers restent où ils sont."

    override val downloadsOnWifiOrData = "Téléchargements en Wi-Fi ou avec les données mobiles."

    override val downloadsWaitForWifi = "Les téléchargements attendent le Wi-Fi, pour ne pas utiliser vos données."

    override val noticesUnavailable = "Retriever repose sur des logiciels open source. Leurs licences n’ont pas pu être affichées pour le moment."

    // SetupView
    override val searchesForThis = "Lance cette recherche"

    // SplitRoot
    override val searchTab = "Recherche"

    // StatusView
    override val onlySeesPickedPhotos = "Retriever ne voit que les photos que vous avez choisies."

    override val photosOffCantSearch = "L’accès aux photos est désactivé$NBSP: Retriever ne peut pas rechercher dans vos photos."

    // TextReader
    override val notProcessedOpenOriginal = "Retriever n’a pas encore traité ce fichier. Vous pouvez plutôt ouvrir l’original."

    override val noTextToShow = "Aucun texte à afficher"

    override val noReadableText = "Retriever n’a trouvé aucun texte lisible dans ce fichier."

    override val couldntShowText = "Impossible d’afficher le texte"

    override val wentWrongReadingIt = "Un problème est survenu pendant son chargement. Réessayez dans un instant."

    override val textFromThisFile = "Texte de ce fichier"

    override val onlyBeginningIncluded = "Seul le début de ce fichier est inclus."

    // Viewer
    override fun matchOnPage(page: Int) = "Résultat à la page $page"

    override val anotherAppCanAsk = "Une autre app pourra vous demander le mot de passe."

    override val damagedOrUnsupported = "Le fichier est peut-être endommagé, ou dans un format que Retriever ne peut pas afficher."

    override fun couldntPlayIos(audio: Boolean) = if (audio) "Impossible de lire ce fichier audio" else "Impossible de lire cette vidéo"

    override val iCloudOrCantPlay = "Le fichier est peut-être uniquement dans iCloud, sans connexion pour y accéder, ou son format ne permet pas de " +
        "le lire dans Retriever."

    override fun photoSpoken(kind: String, name: String) = "$kind, $name"

    override val turnOnToSeePhotos = "Activez-le dans Réglages pour voir vos photos ici."

    override val onlyInICloudNow = "Le fichier est peut-être uniquement dans iCloud, et il n’y a pas de connexion pour le moment."

    override val downloadingFromICloud = "Téléchargement depuis iCloud"
}
