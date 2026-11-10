package app.retriever.core

import app.retriever.core.Words.NBSP

/**
 * Spanish (es): a machine translation of [English], awaiting review by a native speaker. Reviewed by a second model (Codex).
 * Neutral international Spanish for Spain and Latin America, informal "tú" as current Android and iOS Spanish do.
 * Plural: 1 is singular, everything
 * else (0 included) plural.
 *
 * Glossary, so a reviewer can check every sentence uses the same words:
 * - process: "procesar" (procesado, procesamiento), never "leer", "analizar", "indexar", "escanear".
 * - search by description: "búsqueda por descripción" (verb form: "buscar por descripción").
 * - found by name: "encontrado por su nombre"; found by what's inside: "encontrado por lo que contiene".
 * - processed in part: "procesado en parte".
 * - phone: "teléfono" (masculine, like "iPhone" and "iPad", so "tu"/"este" fit all three).
 * - Battery Saver: "Ahorro de batería" (Android); Low Power Mode: "Modo de bajo consumo" (iOS).
 * - Allow access to manage all files: "Dar acceso para gestionar todos los archivos" (Android's setting).
 * - Android battery setting: «Batería», «Sin restricciones».
 * - Settings: "Ajustes" (app and system); Home: "Inicio"; Files: "Archivos"; Photos: "Fotos"; Downloads: "Descargas".
 *   System setting names follow Spanish for Spain; Latin American OS localizations may use different names.
 * - starred: "destacado" (the collection: "Destacados"); People: "Personas"; faces: "caras".
 * - On this day: "En este día"; demo: "demostración"; mobile data: "datos móviles"; delete: "eliminar"; remove: "quitar";
 *   add: "añadir"; video: "video" (no accent).
 * - Taken / Modified (File info rows): "Fecha de captura" / "Fecha de modificación".
 * - Quotes «»; percent with a non-breaking space ("50 %").
 */
internal object Spanish : Strings {
    private fun plural(n: Long, one: String, many: String): String = "${formatCount(n)} ${if (n == 1L) one else many}"

    private fun device(words: DeviceWords) = if (words.ios) words.device else "teléfono"

    private fun powerSave(words: DeviceWords) = if (words.ios) "Modo de bajo consumo" else "Ahorro de batería"

    private fun pct(p: Int) = "$p$NBSP%"

    /** Photos and screenshots are feminine in Spanish; every other kind is masculine. */
    private fun feminine(kind: Kind) = kind == Kind.PHOTO || kind == Kind.SCREENSHOT

    override val sentenceGap = " "

    override val modified = "Fecha de modificación"
    override val taken = "Fecha de captura"

    override fun files(n: Long) = plural(n, "archivo", "archivos")

    override fun photos(n: Long) = plural(n, "foto", "fotos")

    override fun videos(n: Long) = plural(n, "video", "videos")

    override fun photosAndVideos(n: Long) = "${formatCount(n)} ${if (n == 1L) "foto o video" else "fotos y videos"}"

    override fun readOf(done: Long, total: Long) = "${formatCount(done)} de ${files(total)} ${if (total == 1L) "procesado" else "procesados"}"

    override fun meaningOf(done: Long, total: Long) = "${formatCount(done)} de ${files(total)} con búsqueda por descripción"

    override fun facesOf(done: Long, total: Long) = "${formatCount(done)} de ${photos(total)} ${if (total == 1L) "revisada" else "revisadas"}"

    override val processedInPart = "Procesado en parte"
    override val couldntBeOpened = "No se pudo abrir"
    override val stillToProcess = "Por procesar"
    override val longFilesStillBeingFinished = "Archivos largos aún por terminar"

    override fun largeFiles(n: Long) = "${files(n)} de más de 100${NBSP}MB"

    override fun readCount(read: Long) = "${files(read)} ${if (read == 1L) "procesado" else "procesados"}"

    override fun partlyReadCount(n: Long) = "${formatCount(n)} ${if (n == 1L) "procesado" else "procesados"} en parte"

    override fun extent(extent: ReadExtent?) = when (extent) {
        ReadExtent.FIRST_PART -> "Procesado en parte · solo el principio"
        ReadExtent.MEANING_LIMITED -> "Procesado en parte · no se pudo completar la búsqueda por descripción"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "Procesado en parte · solo el principio, sin búsqueda por descripción"
        ReadExtent.FIRST_PART_DEEPENING -> "Procesado en parte · por ahora solo el principio"
        ReadExtent.DEEPENING -> "Procesado en parte · la búsqueda por descripción llegará más tarde"
        ReadExtent.WHOLE, null -> null
    }

    override val couldntProcessLabel = "No se pudo procesar"
    override val notProcessedYet = "Aún sin procesar"
    override val partRestToCome = "Procesado en parte · el resto llegará más tarde"
    override val partCouldntFinish = "Procesado en parte · no se pudo terminar"

    override fun kindLabel(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "Documento"
        Kind.SCREENSHOT -> "Captura de pantalla"
        Kind.PHOTO -> "Foto"
        Kind.AUDIO -> "Audio"
        Kind.VIDEO -> "Video"
        Kind.OTHER -> "Archivo"
    }

    override fun kindLabelPlural(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "Documentos"
        Kind.SCREENSHOT -> "Capturas de pantalla"
        Kind.PHOTO -> "Fotos"
        Kind.AUDIO -> "Audios"
        Kind.VIDEO -> "Videos"
        Kind.OTHER -> "Otros archivos"
    }

    override fun kindsInSentence(kind: Kind) = kindLabelPlural(kind).let { if (it.length > 1 && it[1].isUpperCase()) it else it.lowercase() }

    override fun spokenPage(page: Int) = "página $page"

    override fun spokenTime(hours: Long, minutes: Long, seconds: Long): String {
        val parts = listOf(
            Triple(hours, "hora", "horas"),
            Triple(minutes, "minuto", "minutos"),
            Triple(seconds, "segundo", "segundos"),
        ).filter { it.first > 0 }.map { (n, one, many) -> plural(n, one, many) }
        val spoken = if (parts.size > 1) parts.dropLast(1).joinToString(", ") + " y " + parts.last() else parts.firstOrNull()
        return "Posición: " + (spoken ?: "0 segundos")
    }

    override fun textOnlyNote(ramGb: Int, words: DeviceWords) =
        "Este ${device(words)} tiene $ramGb${NBSP}GB de memoria, demasiado poca para la búsqueda por descripción, " +
            "así que Retriever encuentra los archivos por su nombre y por las palabras que contienen."

    override fun notReadYet(files: Long) = if (files == 1L) "Queda ${formatCount(files)}" else "Quedan ${formatCount(files)}"

    override fun couldntProcess(files: Long) = if (files == 1L) "No se pudo procesar ${files(files)}" else "No se pudieron procesar ${files(files)}"

    override val details = "Detalles"
    override val starting = "Empezando…"
    override val processingNow = "Procesando ahora"
    override val recentlyProcessed = "Procesados recientemente"
    override val processingYourFiles = "Procesando tus archivos"
    override val pause = "Pausar"
    override val processTheseKinds = "Procesar estos tipos de archivo"

    override val continueAction = "Continuar"
    override val tryAgain = "Reintentar"
    override val download = "Descargar"
    override val notNow = "Ahora no"
    override val cancel = "Cancelar"
    override val done = "Listo"
    override val readFor15 = "Procesar 15 minutos"
    override val optional = "Opcional"

    override fun helloTitle(textOnly: Boolean) = if (textOnly) "Encuentra archivos por lo que contienen" else "Encuentra lo que sea describiéndolo"

    override val helloBody = "Fotos, capturas de pantalla, documentos y videos, en una sola búsqueda."
    override val forExample = "Por ejemplo"
    override val helloExamples = listOf(
        Words.HelloExample("contraseña de Wi-Fi que me mandó el dueño de la casa", "Captura de pantalla del 14 de marzo", "Dice «Contraseña de Wi-Fi: …»"),
        Words.HelloExample("perro dormido en el sofá", "Foto del domingo pasado", "Encontrada por lo que contiene", needsModel = true),
        Words.HelloExample("mi contrato de alquiler", "Contrato de alquiler 2025.pdf", "Página 3, la copia firmada"),
    )
    override val downloads = "Descargas"

    override fun onDevice(words: DeviceWords) = "Tus archivos se procesan en tu ${device(words)}"

    override val getStarted = "Empezar"

    override fun downloadNote(size: String) = "Incluye una descarga única de $size, por Wi-Fi."

    override val accessTitle = "Elige qué puede buscar Retriever"
    override val accessBody = "Elige una o más opciones. Puedes añadir más después."
    override val allow = "Permitir"
    override val allowAccess = "Permitir acceso"
    override val choose = "Elegir"
    override val chooseMore = "Elegir más"
    override val settings = "Ajustes"
    override val documents = "Documentos"
    override val includesScreenshots = "Incluye capturas de pantalla"
    override val allowed = "Permitido"
    override val somePhotos = "Algunas fotos seleccionadas"
    override val photosOff = "Desactivado. Actívalo en Ajustes."
    override val pickFolders = "Elige carpetas en Archivos, como Descargas"

    override fun foldersAdded(n: Long) = if (n == 1L) "${formatCount(n)} carpeta añadida" else "${formatCount(n)} carpetas añadidas"

    override val androidAccessBody = "Para encontrar cualquier foto, documento o descarga con una descripción."
    override val allFilesOff =
        "Activa «Dar acceso para gestionar todos los archivos» para buscar en todo tu teléfono o elige qué incluir."
    override val includeTitle = "Elige qué incluir"
    override val sharedFilesAndFolders = "Archivos y carpetas de este teléfono"
    override val photosAndVideosLabel = "Fotos y videos"
    override val addFolder = "Añadir carpeta"
    override val nothingSelected = "Aún no hay archivos seleccionados. Elige fotos o una carpeta para empezar."

    override fun wholeDeviceNote(words: DeviceWords) = "Retriever procesa los archivos de este ${device(words)} para que los encuentres por lo que contienen."

    override val firstLook = "Un primer vistazo a tus archivos recientes"

    override fun firstSearchHint(state: DescriptionState) =
        if (state == DescriptionState.READY) "Describe una foto o un archivo" else "Busca por nombre o por palabras"

    override fun descriptionLine(state: DescriptionState, percent: Int?) = when (state) {
        DescriptionState.DOWNLOADING -> "Descargando la búsqueda por descripción" + (percent?.let { " · ${pct(it)}" } ?: "")
        DescriptionState.WAITING_FOR_WIFI -> "Esperando Wi-Fi para descargar la búsqueda por descripción."
        DescriptionState.PREPARING -> "Preparando la búsqueda por descripción"
        DescriptionState.FAILED -> "La descarga de la búsqueda por descripción se detuvo."
        DescriptionState.NO_SPACE -> "No hay espacio libre suficiente para descargar la búsqueda por descripción."
        DescriptionState.READY, DescriptionState.NOT_STARTED, DescriptionState.UNAVAILABLE -> null
    }

    override val descriptionReady = "La búsqueda por descripción está lista"
    override val descriptionReadyBody = "Ahora describe tus archivos recientes para encontrarlos."

    override val homeIntroTitle = "Retriever sigue trabajando por su cuenta"
    override val homeIntroBody = "Esto es lo que viene ahora."
    override val gotIt = "Entendido"
    override val newFiles = "Fotos y archivos nuevos"
    override val newFilesBody = "Se añaden automáticamente. No tienes que hacer nada."
    override val notifyWhenSearchable = "Avisarme cuando se pueda buscar todo"
    override val caughtUpNoticeTitle = "Ya se puede buscar todo"

    override fun caughtUpNotice(files: Long, textOnly: Boolean) =
        (if (files == 1L) "Retriever ha procesado ${formatCount(files)} archivo. " else "Retriever ha procesado todos los ${files(files)}. ") +
            if (textOnly) {
                if (files ==
                    1L
                ) {
                    "Encuéntralo por su nombre o por las palabras que contiene."
                } else {
                    "Encuentra cualquiera por su nombre o por las palabras que contiene."
                }
            } else {
                "Describe lo que sea para encontrarlo."
            }

    override fun recentFiles(n: Long) = when (n) {
        0L -> "Tus archivos recientes"
        1L -> "Tu archivo más reciente"
        else -> "Tus ${formatCount(n)} archivos recientes"
    }

    override fun otherFiles(rest: Long?) = when (rest) {
        null -> "El resto de tus archivos"
        1L -> "El otro archivo"
        else -> "Los otros ${files(rest)}"
    }

    override fun otherFilesBody(words: DeviceWords) =
        "Cuando tu ${device(words)} se está cargando y no lo estás usando. Con muchos archivos, puede tardar unas cuantas noches."

    override fun otherFilesOnBatteryBody(words: DeviceWords) =
        "Cuando no estás usando tu ${device(words)}: con batería hasta que baje al ${pct(ReadingPolicy.BATTERY_FLOOR)}, y mientras se carga. " +
            "Con muchos archivos, puede tardar unos cuantos días."

    override fun onBatteryBody(words: DeviceWords) =
        "También con batería, hasta que baje al ${pct(ReadingPolicy.BATTERY_FLOOR)}, para que te quede el resto de la carga para tu día. " +
            "Se detiene cuando el ${powerSave(words)} está activado o tu ${device(words)} está caliente."

    override fun recentFilesBody(state: DescriptionState) = when (state) {
        DescriptionState.READY -> "Encuéntralos describiéndolos."

        DescriptionState.UNAVAILABLE -> "Encuéntralos por nombre o por palabras. La búsqueda por descripción necesita un teléfono con más memoria."

        DescriptionState.WAITING_FOR_WIFI -> "Encuéntralos ya por nombre o por palabras. Esperando Wi-Fi para terminar una descarga única."

        DescriptionState.FAILED -> "Encuéntralos ya por nombre o por palabras. La descarga se detuvo."

        DescriptionState.NO_SPACE ->
            "Encuéntralos ya por nombre o por palabras. No hay espacio libre suficiente para descargar la búsqueda por descripción."

        // Not "one-time": after a chip build fails on its first run, this is the second download.
        DescriptionState.NOT_STARTED -> "Encuéntralos ya por nombre o por palabras. La búsqueda por descripción necesita una descarga."

        DescriptionState.DOWNLOADING, DescriptionState.PREPARING ->
            "Encuéntralos ya por nombre o por palabras. La búsqueda por descripción llegará cuando termine la configuración."
    }

    override fun recentFilesAction(state: DescriptionState, size: String) = when (state) {
        DescriptionState.WAITING_FOR_WIFI -> "Usar datos móviles ($size)"
        DescriptionState.FAILED -> tryAgain
        DescriptionState.NOT_STARTED -> "Descargar $size"
        else -> null
    }

    override val demoPicking = "Eligiendo tus archivos recientes…"
    override val demoHint = "Escribe una palabra que recuerdes de una foto, captura de pantalla o documento reciente."

    private fun demoFiles(n: Long) = if (n == 1L) "${formatCount(n)} archivo de demostración" else "${formatCount(n)} archivos de demostración"

    override fun demoSearchScope(done: Long, finished: Boolean, words: DeviceWords) = when {
        finished -> "Encuentra cualquier archivo de tu ${device(words)} por su nombre y busca en el contenido de ${demoFiles(done)}."

        // The first frame: nothing processed yet, so no "0 demo files".
        done == 0L -> "Encuentra cualquier archivo de tu ${device(words)} por su nombre."

        else -> "Encuentra cualquier archivo de tu ${device(words)} por su nombre. " +
            "Busca en el contenido de ${demoFiles(done)} ${if (done == 1L) "procesado" else "procesados"} hasta ahora."
    }

    override fun seeAll(files: Long) = if (files == 1L) "Ver el archivo" else "Ver los ${files(files)}"

    override val read = "Encontrado por lo que contiene"
    override val foundByName = "Encontrado por su nombre"
    override val foundBy = "Encontrado por"

    override fun kindRead(read: Long, planned: Long, kind: Kind) =
        "${formatCount(read)} de ${formatCount(planned)} ${(if (planned == 1L) kindLabel(kind) else kindLabelPlural(kind)).let {
            if (kind == Kind.PDF) it else it.lowercase()
        }} " +
            if (feminine(kind)) {
                if (planned == 1L) "procesada" else "procesadas"
            } else {
                if (planned == 1L) "procesado" else "procesados"
            }

    override val chooseWhen = "Elegir cuándo procesar"
    override val tryLabel = "Prueba:"

    override fun chooseTitle(words: DeviceWords) = "Ahora, los demás archivos de tu ${device(words)}"

    override fun chooseBody(rest: Long?) = when (rest) {
        null -> "El resto de tus archivos aún no está procesado. Hasta que lo esté, puedes encontrarlos por su nombre, pero no por lo que contienen."
        1L -> "${files(1L)} aún no está procesado. Hasta que lo esté, puedes encontrarlo por su nombre, pero no por lo que contiene."
        else -> "${files(rest)} aún no están procesados. Hasta que lo estén, puedes encontrarlos por su nombre, pero no por lo que contienen."
    }

    override fun choosePace(words: DeviceWords) =
        "Procesarlos lleva horas, así que Retriever lo hace poco a poco. Puedes usar tu ${device(words)} y Retriever todo el tiempo."

    override val recommended = "Recomendado"
    override val read15Now = "Mientras se carga y 15 minutos ahora"
    override val notNowBody = "Hasta que elijas, solo puedes encontrar archivos por su nombre. Puedes elegir cuando quieras desde Inicio."
    override val startProcessing = "Procesar"
    override val goToHome = "Ir a Inicio"

    override fun whileChargingBody(words: DeviceWords) =
        "Cuando tu ${device(words)} se está cargando y no lo estás usando, normalmente por la noche. Puedes pausarlo cuando quieras."

    override fun chargingAndNowBody(words: DeviceWords) =
        "Empieza enseguida y gasta algo de batería; luego sigue mientras se carga." + if (words.ios) " Mantén Retriever abierto." else ""

    override fun readNowBody(words: DeviceWords) =
        "Gasta algo de batería. Se detiene antes si tu ${device(words)} se calienta. Luego sigue mientras se carga." +
            if (words.ios) " Mantén Retriever abierto." else ""

    override fun notificationsAsk(demoPending: Boolean) =
        "Un pequeño aviso en la parte superior de la pantalla mientras trabaja, para que veas cuánto lleva o lo pauses. " +
            "Sin sonidos ni otras alertas." +
            if (demoPending) " También podemos avisarte cuando termine la demostración." else ""

    override val progressChannel = "Progreso del procesamiento"
    override val progressChannelBody = "Muestra cuánto se ha procesado y avisa una vez cuando termina la demostración."
    override val demoDoneNoticeTitle = "La demostración ha terminado"

    override fun demoDoneNotice(done: Long, planned: Long) = "${readOf(done, planned)}. Abre Retriever para buscar en ${if (planned == 1L) "él" else "ellos"}."

    override val searchField = "¿Qué estás buscando?"
    override val nameMatches = "Coincidencias en el nombre"
    override val textMatches = "Coincidencias en el texto"
    override val searchingContents = "Buscando en el contenido de los archivos…"

    override fun copies(n: Int) = plural(n.toLong(), "copia", "copias")

    override fun openCopyIn(folder: String) = "Abrir la copia en $folder"

    override val noMatches = "No se encontró nada"

    override fun noMatchesDetail(byMeaning: Boolean) =
        (if (byMeaning) "Describe lo que contiene o pregunta qué dice." else "Prueba con una palabra del contenido o del nombre del archivo.") +
            " Añade una fecha o un periodo, como «la semana pasada»."

    override val moreWaiting = "Retriever aún está procesando tus archivos, así que vuelve a buscar más tarde."
    override val searchFailed = "No se pudo completar la búsqueda"
    override val showingNameMatches = "Mostrando coincidencias en el nombre"
    override val searchingWithoutModel = "Buscando en los nombres y en el texto ya procesado"

    override fun matches(labels: List<String>) = "Coincidencias: " + labels.joinToString(", ")

    private val monthNames = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val weekdayNames = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")

    override fun dateLabel(date: DateFilter) = when (date) {
        DateFilter.Today -> "hoy"

        DateFilter.Yesterday -> "ayer"

        is DateFilter.DaysAgo -> if (date.days == 1) "hace 1 día" else "hace ${formatCount(date.days.toLong())} días"

        is DateFilter.Period -> when (date.unit) {
            PeriodUnit.WEEK -> if (date.last) "la semana pasada" else "esta semana"
            PeriodUnit.MONTH -> if (date.last) "el mes pasado" else "este mes"
            PeriodUnit.YEAR -> if (date.last) "el año pasado" else "este año"
        }

        is DateFilter.Weekday -> when (date.which) {
            Which.LAST -> "el ${weekdayNames[date.day]} pasado"
            Which.THIS -> "este ${weekdayNames[date.day]}"
            Which.BARE -> weekdayNames[date.day]
        }

        is DateFilter.Month -> monthNames[date.month - 1] + (date.year?.let { " de $it" } ?: "")

        is DateFilter.Day -> "${date.day} de ${monthNames[date.month - 1]}" + (date.year?.let { " de $it" } ?: "")

        is DateFilter.Year -> "${date.year}"
    }

    /** "de" with the date, contracted where Spanish contracts it: "del mes pasado", "del lunes". */
    private fun fromDate(date: DateFilter): String = when (date) {
        is DateFilter.Period -> if (date.last && date.unit != PeriodUnit.WEEK) "del " + dateLabel(date).removePrefix("el ") else "de ${dateLabel(date)}"
        is DateFilter.Weekday -> if (date.which == Which.THIS) "de ${dateLabel(date)}" else "del " + dateLabel(date).removePrefix("el ")
        is DateFilter.Day -> "del ${dateLabel(date)}"
        else -> "de ${dateLabel(date)}"
    }

    override fun noneFound(kinds: List<Kind>, people: List<String>, date: DateFilter?): String {
        val what = kinds.joinToString(" ni ", transform = ::kindsInSentence)
        val of = if (people.isEmpty()) "" else " de " + people.joinToString(" y ")
        val from = date?.let { " ${fromDate(it)}" }.orEmpty()
        return if (what.isEmpty()) "No hay nada$of$from" else "No hay $what$of$from"
    }

    override fun relaxedNote(missing: String, scope: String?) = "$missing, así que aquí tienes " + if (scope == null) "todo." else "tus $scope."

    override fun filterQuery(kind: Kind, date: DateFilter) = "${kindsInSentence(kind)} ${fromDate(date)}"

    override val downloadForDescription = "Descargar para buscar por descripción"

    override val readingStatus = "Procesamiento"
    override val recentlyAdded = "Añadidos recientemente"
    override val moreOnPhone = "Explorar"
    override val allFolders = "Todas las carpetas"

    override val howItWorks = "Cómo funciona Retriever y tu privacidad"
    override val whenToRead = "Cuándo procesar"
    override val whatItCanSee = "Qué puede ver Retriever"
    override val foldersToSkip = "Carpetas que omitir"
    override val kindOff =
        "Desactivado: Retriever sigue encontrando estos archivos por su nombre, pero olvida lo que contienen. Tus archivos no se tocan."
    override val showOnThisDay = "Mostrar «En este día» (fotos de esta fecha en años anteriores)"

    override val people = "Personas"
    override val recognizePeople = "Reconocer personas"
    override val peopleOfferTitle = "Encuentra fotos de personas"
    override val peopleOfferBody =
        "Retriever puede agrupar las caras de tus fotos para que pongas nombre a alguien y lo encuentres por su nombre. " +
            "Las caras se procesan en este teléfono y nunca salen de él."
    override val turnOn = "Activar"
    override val recognizePeopleDetail =
        "Agrupa las caras de tus fotos para que puedas poner nombre a las personas y encontrarlas por su nombre. " +
            "Todo se procesa en este teléfono y nunca se guarda en copias de seguridad."
    override val forgetPeopleTitle = "¿Olvidar todas las caras y nombres?"
    override val forgetPeopleBody = "Retriever olvida las caras que agrupó y los nombres que les diste. Tus fotos no se tocan."
    override val forget = "Olvidar"
    override val notThisPerson = "No es esta persona"
    override val addAName = "Añadir un nombre"
    override val name = "Nombre"
    override val save = "Guardar"
    override val samePersonAs = "Misma persona que…"
    override val samePersonAsk = "¿Es la misma persona?"
    override val samePersonYes = "Misma persona"
    override val samePersonNo = "No es la misma"
    override val samePersonPrevious = "Par anterior"
    override val samePersonNext = "Par siguiente"

    override fun pairOf(at: Int, of: Int) = "$at de $of"

    override fun facesToGo(left: Long) = "${photos(left)} por revisar"

    override val retrieversData = "Datos de Retriever"
    override val temporaryFiles = "Archivos temporales"

    override fun keptOn(size: String, words: DeviceWords) = "$size · guardado en este ${device(words)}"

    override val searchByMeaning = "Búsqueda por descripción"
    override val removeDownload = "Quitar descarga de búsqueda"
    override val removeDownloadAsk = "¿Quitar la descarga de búsqueda?"
    override val namesStillWork = "Puedes seguir buscando por nombre y por el texto ya procesado."

    override fun downloaded(size: String) = "Descargado · $size"

    override fun removeDownloadMessage(size: String?) =
        (if (size != null) "Esto libera $size. " else "") + "$namesStillWork La búsqueda por descripción necesitará descargarse de nuevo."

    override val freeUpSpace = "Liberar espacio"
    override val largeFilesTitle = "Archivos grandes"
    override val findDuplicates = "Buscar archivos duplicados"
    override val neverDeletes = "Retriever nunca elimina nada sin preguntarte antes."

    override fun possibleCopies(n: Long) = if (n == 1L) "${files(n)} podría ser una copia" else "${files(n)} podrían ser copias"

    override fun deleteFrom(words: DeviceWords) = "Eliminar del ${device(words)}"

    override fun deleteConfirm(words: DeviceWords) = "Esto elimina el archivo de tu ${device(words)}."

    override val upgradeTitle = "Encuentra archivos describiéndolos"
    override val waitingForWifi = "Esperando Wi-Fi para descargar"
    override val useMobileData = "Usar datos móviles"
    override val waitForWifi = "Esperar al Wi-Fi"
    override val checkingDownload = "Comprobando la descarga…"
    override val downloadFailed = "Descarga interrumpida. Comprueba tu conexión y vuelve a intentarlo."
    override val noSpace = "No hay espacio libre suficiente para descargar"
    override val tuneLeaving = "Ya puedes buscar por nombre. La configuración continuará cuando vuelvas."

    override fun upgradeBody(size: String, words: DeviceWords) = "Una descarga de $size por Wi-Fi. " +
        "Después, una búsqueda como «atardecer en la playa» encuentra fotos aunque no tengan esas palabras. " +
        "Todo se queda en este ${device(words)}."

    override fun downloadProgress(done: String, total: String, percent: Int) = "$done de $total · ${pct(percent)}"

    override fun mobileDataAsk(size: String) = "¿Descargar $size con datos móviles?"

    override fun freeUp(size: String) = "Libera $size para continuar"

    override fun tuneTitle(words: DeviceWords) = "Configurando el procesamiento para este ${device(words)}"

    override fun tuneBody(words: DeviceWords) = "Alrededor de un minuto. Buscando la velocidad de procesamiento que mejor le va a este ${device(words)}."

    override fun tuneFailed(words: DeviceWords) = "No se pudo terminar de configurar el procesamiento para este ${device(words)}."

    override fun addingMeaning(done: Long, total: Long) = "Añadiendo búsqueda por descripción · ${formatCount(done)} de ${files(total)}"

    override val meaningCatchingUp =
        "La búsqueda por descripción ya funciona. Los archivos procesados antes de que estuviera lista la irán teniendo a medida que Retriever avanza."

    override fun scheduleTitle(schedule: ReadingSchedule) = when (schedule) {
        ReadingSchedule.CHARGING -> "Mientras se carga"
        ReadingSchedule.BATTERY -> "También con batería"
        ReadingSchedule.MANUAL -> "Cuando yo lo inicie"
    }

    override fun statusAction(action: StatusAction) = when (action) {
        StatusAction.CONTINUE -> continueAction
        StatusAction.ALLOW_ACCESS -> allowAccess
        StatusAction.CHOOSE_WHEN -> chooseWhen
        StatusAction.READ_FOR_15 -> readFor15
        StatusAction.STOP -> "Detener"
        StatusAction.RESUME -> "Reanudar"
        StatusAction.DISMISS -> "Descartar"
        StatusAction.TRY_AGAIN -> tryAgain
    }

    override fun stopCopy(stop: StopReason, words: DeviceWords) = when (stop) {
        StopReason.PAUSED -> "En pausa"
        StopReason.POWER_SAVE -> "En pausa mientras el ${powerSave(words)} está activado"
        StopReason.HOT -> "En pausa para que tu ${device(words)} se enfríe"
        StopReason.LOW_BATTERY -> "En pausa, queda poca batería"
        StopReason.CHARGING_UP -> "Cargando la batería primero"
        StopReason.BUSY -> "Esperando a que dejes tu ${device(words)}"
        StopReason.UNPLUGGED -> "Espera a que lo cargues"
        StopReason.MANUAL -> "Solo procesa cuando tú lo inicias"
        StopReason.DISK_FULL -> "En pausa, tu ${device(words)} está lleno"
        StopReason.DONE, StopReason.BUDGET -> "Tomándose un breve descanso"
    }

    override val noAccessTitle = "Retriever no puede ver tus archivos"

    override fun noAccessDetail(words: DeviceWords) =
        "La búsqueda sigue funcionando con los archivos que ya ha procesado." + if (words.ios) " Esto abre los Ajustes de tu ${device(words)}." else ""

    override val finishSetupTitle = "Termina la configuración"
    override val finishSetupDetail = "Elige qué archivos puede procesar Retriever."
    override val downloadPausedTitle = "Descarga en pausa"

    override fun downloadPausedDetail(words: DeviceWords, size: String) =
        "Continuará cuando haya Wi-Fi. Si tu ${device(words)} está lleno, necesita $size de espacio libre."

    override fun downloadingTitle(percent: Int?) = "Descargando la búsqueda por descripción" + (percent?.let { " · ${pct(it)}" } ?: "")

    override val downloadingDetail = "Puedes seguir usando Retriever."

    override val addingDescriptionTitle = "Añadiendo búsqueda por descripción"
    override val allFilesProcessedTitle = "Todos los archivos procesados"
    override val countingTitle = "Contando tus archivos…"
    override val notNowTitle = "Por ahora solo puedes buscar por nombre"

    override fun readNowTitle(minutesLeft: Int) =
        "Procesando durante 15 minutos · ${if (minutesLeft == 1) "queda" else "quedan"} ${formatCount(minutesLeft.toLong())}${NBSP}min"

    override val sampleTitle = "Procesando los archivos de demostración"
    override val gettingPeopleReadyTitle = "Preparando Personas"
    override val lookingForFacesTitle = "Buscando caras en tus fotos"
    override val deepeningTitle = "Procesando el resto de algunos archivos largos"
    override val chargingReadTitle = "Procesando mientras se carga"
    override val lookingTitle = "Buscando archivos nuevos…"
    override val caughtUpTitle = "Listo. Retriever ha procesado todo lo que puede"
    override val limitedTitle = "Listo. Algunos archivos largos solo se procesaron en parte"

    override val pausedDetail = "La búsqueda sigue funcionando."

    override fun powerSaveDetail(words: DeviceWords) = "Continuará cuando el ${powerSave(words)} esté desactivado."

    override val hotDetail = "Continuará por sí solo."
    override val lowBatteryDetail = "Conecta el cargador y Retriever continuará."
    override val chargingUpDetail = "El procesamiento empezará cuando la batería tenga algo más de carga."
    override val busyDetail = "O abre Retriever para continuar ahora."

    override fun chargingDetail(words: DeviceWords) = "Retriever continuará cuando tu ${device(words)} se esté cargando."

    override val manualDetail = "Puedes encontrar cualquier archivo por su nombre."

    override fun readNowDetail(words: DeviceWords) = "Gasta algo de batería. " + if (words.ios) "Mantén Retriever abierto." else "Se detiene solo."

    override val sampleDetail = "Alrededor de un minuto. No se cambia ni se envía nada."

    override fun meaningDetail(words: DeviceWords) =
        "Para que puedas buscar por lo que significan las cosas y por lo que aparece en tus fotos. Solo en este ${device(words)}."

    override val peopleDownloadDetail = "Una descarga única para Personas. La búsqueda funciona todo el tiempo."
    override val facesDetail = "Todos tus archivos están procesados. La búsqueda funciona todo el tiempo."
    override val deepeningDetail = "Puedes encontrarlos por su principio."
    override val readingMeaningDetail = "También se está añadiendo la búsqueda por descripción."

    override fun readingDetail(words: DeviceWords) = if (words.ios) {
        "La búsqueda funciona todo el tiempo. Retriever procesa tus archivos mientras está abierto y, a veces, cuando iOS lo permite."
    } else {
        "Puedes cerrar Retriever; seguirá trabajando. La búsqueda funciona todo el tiempo."
    }

    override fun waitingLongFilesDetail(words: DeviceWords) =
        "Retriever procesa el resto de algunos archivos largos cuando tu ${device(words)} lo permite. La búsqueda ya funciona."

    override fun waitingMeaningDetail(words: DeviceWords) =
        "Retriever añade la búsqueda por descripción cuando tu ${device(words)} lo permite. La búsqueda ya funciona."

    override val diskFullDetail = "Libera algo de espacio y Retriever continuará."

    override fun waitingDetail(words: DeviceWords) = "Tu ${device(words)} decide cuándo continúa Retriever. No se pierde nada."

    override val caughtUpDetail = "Procesa los archivos nuevos por sí solo."

    override val gettingReadersTitle = "Preparando el procesamiento de tus archivos"
    override val gettingReadersDetail = "Una descarga única de los servicios de Google Play. La búsqueda por nombre ya funciona."

    override val peopleWaitsForWifi = "La descarga de Personas espera al Wi-Fi."

    override fun deepeningLead(left: Long) = "${notReadYet(left)}."

    override fun longFilesWait(n: Long) = "Todos los archivos procesados. El resto de ${plural(n, "archivo largo", "archivos largos")} está en espera."

    override val allFilesProcessedLead = "Todos los archivos procesados."

    override val foundByOther = "Su nombre. Retriever aún no puede procesar este tipo de archivo."
    override val foundByBeginningRestPending = "El nombre y su principio. El resto aún no está procesado."
    override val foundByPartCouldntFinish = "El nombre y parte de lo que contiene. Retriever no pudo terminar de procesarlo."
    override val foundByBeginning = "El nombre y su principio"
    override val foundByInsideMeaningLimited = "El nombre y lo que contiene. No se pudo completar la búsqueda por descripción."
    override val foundByInsideDeepening = "El nombre y lo que contiene. La búsqueda por descripción llegará más tarde."
    override val foundByInside = "El nombre y lo que contiene"
    override val foundByReadingOff = "Su nombre. El procesamiento está desactivado para este tipo de archivo."

    override fun foundBySkipped(tries: Long) = "Su nombre. Retriever no pudo procesarlo tras ${plural(tries, "intento", "intentos")}."

    override val foundByNothingInside = "Su nombre. No se pudo procesar nada de su contenido."
    override val foundByNotYet = "Su nombre. Aún no está procesado."

    override val processingFailed = "Algo salió mal al procesar tus archivos. Retriever lo volverá a intentar."

    // FileActions
    override val fileNameField = "Nombre"

    // Notifications
    override fun notificationBody(count: String?, detail: String?, read: Long) = listOfNotNull(count, detail).joinToString(". ").ifEmpty { readCount(read) }

    // SearchResults
    override fun understoodChip(labels: List<String>) = labels.joinToString(" · ")

    override fun noMatchesMessage(byMeaning: Boolean, waiting: Boolean, wordsOnly: Boolean) =
        listOfNotNull(noMatchesDetail(byMeaning), moreWaiting.takeIf { waiting }, searchingWithoutModel.takeIf { wordsOnly }).joinToString(" ")

    override fun reasonLine(parts: List<String>) = parts.joinToString(" · ")

    // SearchScreen
    override val appSettings = "Ajustes"

    // SettingsScreen
    override fun scheduleSummary(title: String, body: String) = "$title. $body"

    override val sizePending = "…"

    // FirstRunStages
    override fun folderNames(names: List<String>) = names.joinToString(" · ")

    // AppModel+Setup
    override fun kindsReadSpoken(parts: List<String>) = parts.joinToString(", ")

    // SearchModel
    override val scopePhotos = "Fotos"

    override val scopeDocuments = "Documentos"

    // HomeView
    override fun titleDetailSpoken(title: String, detail: String?) = listOfNotNull(title, detail).joinToString(", ")

    // ResultViews
    override fun resultRowSpoken(kind: String, name: String, date: String, position: String?, extent: String?, reason: String?) =
        listOfNotNull(kind, name, date, position, extent, reason).joinToString(", ")

    // Access
    override val onlyPickedPhotos = "Solo las fotos que elegiste"

    override val musicAndRecordings = "Música y grabaciones"

    override fun notCoveredMedia(photos: Boolean, videos: Boolean, music: Boolean) = listOfNotNull(
        "fotos".takeIf { photos },
        "videos".takeIf { videos },
        "música".takeIf { music },
    ).let { m ->
        "Fuera de la búsqueda: " +
            if (m.size == 1) m[0] else m.dropLast(1).joinToString(", ") + " y " + m.last()
    }

    override fun notCoveredDocuments(hasFolders: Boolean) =
        if (hasFolders) "Fuera de la búsqueda: documentos y descargas fuera de las carpetas que añadiste" else "Fuera de la búsqueda: documentos y descargas"

    override val pickFolderOnPhone = "Elige una carpeta del almacenamiento de este teléfono."

    override val alreadySearchesFolder = "Retriever ya busca en esa carpeta."

    // AccessService
    override val couldntKeepFolder = "No se pudo mantener el acceso a esa carpeta. Vuelve a elegirla."

    // BrowseScreen
    override val allFiles = "Todos los archivos"

    override val filesRetrieverCanAccess = "Archivos a los que Retriever puede acceder"

    override val sortLargestDetail = "Primero los más grandes"

    override val sortNameDetail = "Por nombre, de la A a la Z"

    override val sortNewestDetail = "Primero los más recientes · por fecha de modificación"

    override val couldntChangePerson = "No se pudo cambiar esta persona. Vuelve a intentarlo."

    override val back = "Atrás"
    override val home = "Inicio"
    override val fullScreen = "Pantalla completa"
    override val showBesideList = "Mostrar junto a la lista"
    override val searchYourFiles = "Buscar en tus archivos"

    override val couldntLoadYourFiles = "No se pudieron cargar tus archivos"

    override val wentWrongReadingThem = "Algo salió mal al cargarlos. Vuelve a intentarlo."

    override val keepUsefulFilesClose = "Ten a mano los archivos útiles"

    override val tapStarToFindHere = "Toca la estrella de un archivo para encontrarlo aquí."

    override val chooseFilesToSearch = "Elige archivos para buscar"

    override val addPhotosOrFolder = "Añade fotos o una carpeta."

    override val manageAccess = "Administrar acceso"

    override val nothingHereYet = "Aún no hay nada aquí"

    override fun filesShowUpHere(words: DeviceWords) = "Los archivos aparecen aquí cuando Retriever los encuentra en tu ${device(words)}."

    override val moreOptions = "Más opciones"

    override val couldntLoadMoreFiles = "No se pudieron cargar más archivos"

    override val sortNewest = "Más recientes"

    override val sortLargest = "Más grandes"

    override val sortName = "Nombre"

    // BrowseViewModel
    override val couldntLoadTheseFiles = "No se pudieron cargar estos archivos."

    // PersonActions
    override val rename = "Cambiar nombre"

    // DuplicatesScreen
    override val duplicates = "Duplicados"

    override val keepOneCopy = "Conserva al menos una copia de cada archivo"

    override fun filesCouldntBeDeleted(n: Long) = if (n == 1L) "No se pudo eliminar ${files(n)}" else "No se pudieron eliminar ${files(n)}"

    override fun deleteSelected(n: Long, size: String) = "Eliminar ${formatCount(n)} ${if (n == 1L) "seleccionado" else "seleccionados"} ($size)"

    override val lookingForCopiesEllipsis = "Buscando copias…"

    override val couldntLookForDuplicates = "No se pudieron buscar duplicados"

    override val noDuplicatesFound = "No se encontraron duplicados"

    override fun comparedFiles(n: Long) = "Retriever comparó ${files(n)}."

    override fun couldBeFreed(size: String) = "Se podrían liberar $size"

    override fun copiesEach(n: Long, size: String) = "${plural(n, "copia", "copias")} · $size cada una"

    override fun openNamed(name: String) = "Abrir $name"

    override val deselect = "Anular selección"

    override val select = "Seleccionar"

    override val deleteFilesTitle = "¿Eliminar archivos?"

    override fun deleteFilesMessage(n: Long) = "¿Eliminar ${files(n)}? No se puede deshacer."

    override val delete = "Eliminar"

    // DuplicatesViewModel
    override val somethingWentWrongTryAgain = "Algo salió mal. Vuelve a intentarlo."

    // FileActions
    override val addToStarred = "Añadir a Destacados"

    override val share = "Compartir"

    override val openInAnotherApp = "Abrir en otra app"

    override val fileInfo = "Información del archivo"

    override val removeFromStarred = "Quitar de Destacados"

    override val topLevel = "Nivel superior"

    override val couldntChangeStar = "No se pudo cambiar la estrella. Vuelve a intentarlo."

    override val findSimilar = "Buscar similares"

    override val couldntFinishThat = "No se pudo completar. Puede que el archivo se haya movido o que el acceso haya cambiado."

    override val renameFile = "Cambiar nombre del archivo"

    override val deleteThisFile = "¿Eliminar este archivo?"

    // FileInfo
    override fun kindAndSize(kind: String, size: String) = "$kind · $size"

    override val locationLabel = "Ubicación"

    override val hideFullPath = "Ocultar ruta completa"

    override val showFullPath = "Mostrar ruta completa"

    override val locationDetails = "Detalles de la ubicación"

    override val pathClipLabel = "Ruta"

    override val copyPath = "Copiar ruta"

    override val dimensionsLabel = "Dimensiones"

    override fun dimensions(width: Int, height: Int) = "$width × $height"

    override val durationLabel = "Duración"

    override val pagesLabel = "Páginas"

    override val internalStorage = "Almacenamiento interno/"

    // FoldersScreen
    override val folders = "Carpetas"

    override val foldersDidntLoad = "No se cargaron las carpetas"

    override val noFoldersYet = "Aún no hay carpetas"

    override val tryAgainInAMoment = "Vuelve a intentarlo en un momento."

    override val foldersAppearPhone = "Las carpetas aparecen cuando Retriever encuentra los archivos de tu teléfono."

    override fun stopSearchingAsk(name: String) = "¿Dejar de buscar en $name?"

    override fun stopSearchingMessage(n: Long, name: String) = (
        if (n == 1L) {
            "El archivo de $name se quita de la búsqueda ahora, y recuperarlo implica volver a procesarlo. "
        } else {
            "Los ${files(n)} de $name se quitan de la búsqueda ahora, y recuperarlos implica volver a procesarlos todos. "
        }
        ) + "Puedes recuperar la carpeta desde la línea de estado, arriba en Inicio."

    override val stopSearching = "Dejar de buscar"

    override fun filesInParent(n: Long, parent: String?) = if (parent == null) files(n) else "${files(n)} · $parent"

    override fun moreOptionsFor(name: String) = "Más opciones para $name"

    override val stopSearchingThisFolder = "Dejar de buscar en esta carpeta"

    // FoldersViewModel
    override val couldntLoadYourFolders = "No se pudieron cargar tus carpetas. Vuelve a intentarlo en un momento."

    override val couldntStopSearchingFolder = "No se pudo dejar de buscar en esa carpeta. Vuelve a intentarlo en un momento."

    // HomeScreen
    override val onThisDayHidden = "«En este día» oculto"

    override val undo = "Deshacer"

    override val showProcessingDetails = "Mostrar detalles del procesamiento"

    override val lookingForYourFiles = "Buscando tus archivos"

    override val nothingToSearchYet = "Aún no hay nada que buscar"

    override val takesAMinute = "Esto tarda un minuto. Puedes buscar por nombre en cuanto aparezcan."

    override val saveAPdfHint = "Guarda un PDF, haz una captura de pantalla o una foto, y aparecerá aquí."

    // HomeSections
    override val showAll = "Mostrar todo"

    override fun sectionSpoken(title: String, subtitle: String) = "$title, $subtitle"

    override val openAction = "Abrir"

    override fun personTileSpoken(name: String, photos: Long) = "$name, ${photos(photos)}"

    override fun browseTileSpoken(label: String, count: String) = "$label, $count"

    override val onThisDay = "En este día"

    override fun onThisDaySpoken(year: Int, count: String, date: String) = "$year, $count del $date"

    override val showThem = "Mostrarlos"

    override val hideOnThisDay = "Ocultar «En este día»"

    override val starredSection = "Destacados"

    // ReadingPermission
    override val notificationPermissionAsk = "¿Mostrar una notificación mientras Retriever procesa tus archivos?"

    override val processFasterAsk = "¿Procesar más rápido mientras se carga?"

    override val processFasterBody = "Android ejecuta las apps en segundo plano en sus núcleos más lentos. " +
        "Si el uso de batería de Retriever está en «Sin restricciones», Retriever usa los núcleos rápidos mientras tu teléfono se carga " +
        "y termina mucho antes. En Ajustes, abre «Batería» y elige «Sin restricciones»."

    override val openSettingsLowercase = "Abrir Ajustes"

    // ModelDownload
    override val searchModelDownloadTitle = "Modelo de búsqueda de Retriever"

    // ModelRepository
    override val downloadStopped = "La descarga se detuvo. Comprueba tu conexión y vuelve a intentarlo."

    override val downloadDamaged = "La descarga está dañada. Vuelve a intentarlo."

    override fun needsFreeSpace(gb: String) = "Necesita $gb${NBSP}GB de espacio libre. Libera algo de espacio y vuelve a intentarlo."

    // SearchResults
    override fun likeFile(name: String) = "Parecido a $name"

    override val photoYoureSearchingWith = "La foto con la que estás buscando"

    override val stopSearchingByPhoto = "Dejar de buscar por foto"

    override val searchWordsAsTyped = "Buscar las palabras exactas"

    override val photoSearchAfterSetup = "La búsqueda por foto funcionará cuando termine la configuración."

    override val matchingExactWordsForNow = "Por ahora se buscan las palabras exactas. Los resultados por descripción volverán en breve."

    override fun noMatchesIn(scope: String) = "No hay resultados en tus $scope"

    override val nothingLikePhotoThere = "Ahí no hay nada parecido a esta foto."

    override fun nothingForThere(query: String) = "Ahí no hay nada para «$query»."

    override val searchAllFiles = "Buscar en todos los archivos"

    override val nothingLikeThisYet = "Aún no hay nada parecido"

    override val tryAnotherPhoto = "Prueba con otra foto."

    override val tryFewerWords = "Usar menos palabras"

    override val filterAll = "Todo"

    override val filterStarred = "Destacados"

    override val expanded = "Desplegado"

    override val collapsed = "Contraído"

    override fun lessLikelyMatches(n: Long) = "Resultados menos probables (${formatCount(n)})"

    override val moreDocuments = "Más documentos"

    override val recordings = "Grabaciones"

    override val documentsAndMore = "Documentos y más"

    // SearchScreen
    override val searchWithPhoto = "Buscar con una foto"

    override val clearSearch = "Borrar búsqueda"

    override val recentSearches = "Búsquedas recientes"

    override fun removeFromHistory(query: String) = "Quitar $query del historial"

    override val searchAction = "Buscar"

    override val clearHistory = "Borrar historial"

    // SearchViewModel
    override val likeYourPhoto = "Parecido a tu foto"

    override val couldntOpenPhoto = "No se pudo abrir esta foto. Elige otra."

    override val thatDidntWork = "No funcionó. Vuelve a intentarlo."

    // SettingsScreen
    override val includedFilesFooter = "$kindOff Las carpetas que omitas quedan fuera de la búsqueda."

    override val onlyWhenYouChoose = "Solo cuando eliges «$readFor15»."

    override val processingNotifications = "Notificaciones de procesamiento"

    override val notificationsOn = "Activadas"

    override val notificationsOff = "Desactivadas. El procesamiento sigue; solo que no ves cuánto lleva."

    override val includedFiles = "Archivos incluidos"

    override val noneExcluded = "Ninguna"

    override fun includeNamed(folder: String) = "Incluir $folder"

    override val includeFolder = "Incluir carpeta"

    override val privacy = "Privacidad"

    override val saveRecentSearches = "Guardar búsquedas recientes"

    override val savedOnDeviceShortcuts = "Se guardan en este dispositivo. También se usan para los accesos directos de búsqueda."

    override val clearRecentSearches = "Borrar búsquedas recientes"

    override val clearSearchesAndShortcutsAsk = "¿Borrar las búsquedas recientes y los accesos directos?"

    override val clear = "Borrar"

    override val storage = "Almacenamiento"

    override val appearance = "Apariencia"

    override val wallpaperColours = "Colores del fondo de pantalla"

    override val wallpaperColoursOff = "Si está desactivado, se usa el ámbar cálido de Retriever."

    override val about = "Información"

    override val version = "Versión"

    override val openSourceNotices = "Avisos de código abierto"

    override val removeDownloadConfirm = "Quitar descarga"

    override fun howItWorksBody(words: DeviceWords) = "${wholeDeviceNote(words)} ${onDevice(words)}. " +
        "Los modelos de búsqueda y de reconocimiento de texto pueden necesitar una descarga por internet. " +
        "Tu proveedor de archivos también puede usar internet para abrir archivos en la nube."

    override val howItWorksTimes = "Procesa tus archivos en los momentos que elijas en «$whenToRead». " +
        "Los archivos largos se procesan poco a poco: primero puedes encontrarlos por su principio."

    override val runSetupCheck = "Comprobar la configuración"

    override val tuningFewMinutes = "Ajustando… unos minutos como mucho"

    override fun tuneFailedMessage(message: String) = "No se pudo ajustar: $message"

    override val processingIsSetUp = "El procesamiento está configurado"

    override val adjustsProcessingSpeed = "Ajusta la velocidad de procesamiento a este teléfono."

    override val useMobileDataForDownloads = "Usar datos móviles para las descargas"

    override val downloadsWaitForWifiShort = "Desactivado: las descargas esperan al Wi-Fi."

    override val loading = "Cargando…"

    // SettingsViewModel
    override val couldntSaveChange = "No se pudo guardar este cambio. Vuelve a intentarlo."

    override val searchDownloadRemoved = "Se quitó la descarga de búsqueda"

    // Starring
    override val starAdded = "Destacado"

    override val starRemoved = "Se quitó la estrella"

    // StatusScreen
    override val checkingYourFiles = "Comprobando tus archivos…"

    override val hide = "Ocultar"

    override val reviewFiles = "Revisar archivos"

    override val letProcessWhileAway = "Deja que Retriever procese archivos mientras no lo usas"

    override fun batteryMayStop(chargingOnly: Boolean) =
        "Puede que tu teléfono lo detenga para ahorrar batería." + if (chargingOnly) " Aun así, solo procesa archivos mientras se carga." else ""

    override val settingsRowDetail = "Qué procesar, almacenamiento y más"

    override val chooseMorePhotos = "Elegir más fotos"

    override val foldersYouAdded = "Carpetas que añadiste"

    override fun removeNamed(name: String) = "Quitar $name"

    override val allowAccessToAllFiles = "Permitir acceso a todos los archivos"

    // Format
    override fun starredScope(kind: String?) =
        kind?.let { "$it ${if (it.startsWith("foto", ignoreCase = true) || it.startsWith("captura", ignoreCase = true)) "destacadas" else "destacados"}" }
            ?: starredFiles

    override fun pageBadge(page: Int) = "Página $page"

    // FileRow
    override fun openAtPage(page: Int) = "Abrir en la página $page"

    override fun playFrom(time: String) = "Reproducir desde $time"

    override fun kindSizeDate(kind: String, size: String, date: String) = "$kind · $size · $date"

    override fun fileRowSpoken(kind: String, name: String, date: String, position: String?, note: String?) =
        "$kind, $name, $date" + (position?.let { ", $it" } ?: "") + (note?.let { ", $it" } ?: "")

    // ReadingFiles
    override val fileActions = "Acciones del archivo"

    override fun fileSpoken(name: String, kind: String, date: String) = "$name, $kind, $date"

    // DocScreen
    override val notProcessedThisYet = "Retriever aún no ha procesado este archivo"

    override val waitingItsTurn = "Está esperando su turno con los demás archivos nuevos. Ábrelo en otra app para verlo ahora."

    override val noTextFound = "Retriever no encontró texto en este archivo"

    override val scanOrPictures = "Puede que sea un documento escaneado o que solo contenga imágenes. Ábrelo en otra app para verlo."

    override val couldntOpenFile = "No se pudo abrir este archivo"

    override val maybeMovedOrDeleted = "Puede que se haya movido o eliminado."

    override val previousMatch = "Coincidencia anterior"

    override fun matchOf(at: Int, total: Int) = "Coincidencia $at de $total"

    override fun matchCount(n: Long) = plural(n, "coincidencia", "coincidencias")

    override val nextMatch = "Coincidencia siguiente"

    // Opener
    override val noAppOpensFile = "Ninguna app de este teléfono puede abrir este archivo"

    // PdfPageScreen
    override fun pdfSubtitle(page: Int, count: Int, matchedPage: Int?) = (matchedPage?.let { "${matchOnPage(it)} · " } ?: "") + pageOf(page, count)

    override val closePage = "Cerrar página"

    override val pdfNeedsPassword = "Este PDF necesita una contraseña"

    override val pdfFindableByName = "Retriever puede seguir encontrándolo por su nombre. Puedes abrirlo en otra app."

    override val folderAccessChanged = "El acceso a esta carpeta ha cambiado"

    override val chooseFolderAgain = "Vuelve a elegir la carpeta para abrir este archivo."

    override val fileNoLongerAvailable = "Este archivo ya no está disponible"

    override val goBack = "Volver"

    override val couldntOpenPdf = "No se pudo abrir este PDF"

    override fun pageZoomed(page: Int) = "Página $page, ampliada"

    override fun pageOf(page: Int, count: Int) = "Página $page de $count"

    override val zoomPage = "Ampliar página"

    override fun pageSpoken(label: String, matched: Boolean) = label + if (matched) ", donde coincidió tu búsqueda" else ""

    override val matchedHere = "Coincide aquí"

    // PhotoScreen
    override val couldntShowPhoto = "No se pudo mostrar esta foto"

    // PlayerScreen
    override fun matchAt(time: String) = "Coincidencia en $time"

    override fun couldntPlay(audio: Boolean) = if (audio) "No se pudo reproducir esta grabación" else "No se pudo reproducir este video"

    override val maybeMovedOrCantPlay = "Puede que se haya movido o eliminado, o que este teléfono no pueda reproducirlo."

    // ViewerQueue
    override fun positionOf(at: Int, count: Int) = "$at de $count"

    // ViewerTopBar
    override val previousFile = "Archivo anterior"

    override val nextFile = "Archivo siguiente"

    // AppModel+Loop
    override fun keepProcessingFailed(words: DeviceWords) = "iOS no pudo seguir procesando mientras no usabas Retriever. " +
        "Mantenlo abierto o deja que procese tus archivos mientras tu ${device(words)} se carga."

    // AppModel+Settings
    override val couldntChangePersonCurly = "No se pudo cambiar esta persona. Vuelve a intentarlo."

    // FolderSource
    override val aFolder = "Una carpeta"

    // SearchModel
    override val starredFiles = "archivos destacados"

    // Copy
    override val photosAccessOff = "El acceso a Fotos está desactivado"

    override val photosOffNoteDetail =
        "La búsqueda sigue funcionando con los archivos ya procesados. Actívalo para buscar también en tus fotos y capturas de pantalla."

    override val seesOnlyPickedPhotos = "Retriever solo ve las fotos que elegiste"

    override val canSearchJustThose = "Puede buscar solo en esas."

    override val folderAdded = "Carpeta añadida. Retriever procesará lo que contiene."

    override fun folderOverlaps(name: String) = "Esa carpeta se solapa con «$name», en la que Retriever ya busca."

    override val folderRefused = "iOS no dejó que Retriever conservara esa carpeta. Prueba con otra."

    override fun backgroundNote(words: DeviceWords) =
        "Retriever procesa tus archivos mientras está abierto y, a veces, mientras no lo usas, cuando iOS lo permite. " +
            "El procesamiento automático espera a que tu ${device(words)} se esté cargando. «$readFor15» gasta algo de batería."

    override val screenKeptOn =
        "La pantalla se mantiene encendida mientras Retriever procesa tus archivos durante 15 minutos. Puedes desactivarlo en Ajustes."

    override fun showingLabels(labels: List<String>) = "Mostrando " + labels.joinToString(" · ")

    override val searchMyExactWords = "Buscar mis palabras exactas"

    override fun processedSoFar(n: Long) = "${readCount(n)} hasta ahora"

    // Format
    override fun opensAtPage(page: Int) = "Se abre en la página $page"

    override fun playsFrom(time: String) = "Se reproduce desde $time"

    override val opensHint = "Se abre"

    // BrowseView
    override val couldntLoadFiles = "No se pudieron cargar los archivos"

    override val wentWrongReadingList = "Algo salió mal al cargar la lista."

    override fun sizeFolderDate(size: String, folder: String, date: String) = "$size · $folder · $date"

    override val sortBy = "Ordenar por"

    override val sortMenu = "Ordenar"

    override val ok = "Aceptar"

    // DuplicatesView
    override val noDuplicatesMessage =
        "Retriever revisó los archivos de las carpetas que añadiste y no encontró copias de más de 1 MB. Las fotos no se revisan."

    override val lookingForCopies = "Buscando copias"

    override val thisCanTakeAMinute = "Esto puede tardar un minuto."

    override fun deleteFilesAsk(n: Long) = if (n == 1L) "¿Eliminar ${files(n)}?" else "¿Eliminar ${files(n)}?"

    override val cantBeRecovered = "Lo que elimines no se podrá recuperar."

    override val comparesFilesNote = "Compara los archivos de las carpetas que añadiste, no las fotos. " +
        "Se conserva la copia más antigua de cada archivo. Toca una copia para cambiar cuál se elimina."

    override fun folderAndDate(folder: String, date: String) = "$folder · $date"

    override val selectedToDeleteHint = "Seleccionado para eliminar. Toca dos veces para conservarlo."

    override val keptHint = "Se conserva. Toca dos veces para marcarlo para eliminar."

    override fun filesWerentDeleted(n: Long) = if (n == 1L) "No se eliminó ${files(n)}" else "No se eliminaron ${files(n)}"

    // FileActions
    override val couldntOpenFolderUnavailable = "No se pudo abrir este archivo. Puede que su carpeta no esté disponible."

    override val photosAppName = "Fotos"

    override val moreActions = "Más acciones"

    override val couldntFinish = "No se pudo completar"

    // FoldersView
    override val foldersWithFiles = "Carpetas con archivos"

    override val couldntLoadFolderList = "No se pudo cargar la lista de carpetas. Vuelve a intentarlo"

    override val removeFolder = "Quitar"

    override val cantOpenFolderAnyMore = "Retriever ya no puede abrir esta carpeta. Quítala y vuelve a añadirla."

    override val alsoSearchesOwnFolder = "Retriever también busca en su propia carpeta de la app Archivos. Quitar una carpeta aquí nunca la elimina."

    override val couldntLoadFolders = "No se pudieron cargar las carpetas"

    override val wentWrongReadingFolders = "Algo salió mal al cargar tus carpetas."

    override val foldersAppearFiles = "Las carpetas aparecen cuando Retriever encuentra tus archivos."

    override fun stopSearchingForget(n: Long, name: String) =
        (if (n == 1L) "Retriever olvidará el archivo de $name." else "Retriever olvidará los ${files(n)} de $name.") +
            " Puedes recuperar la carpeta aquí."

    override fun removeFolderAsk(name: String) = "¿Quitar $name?"

    override val removeFolderMessage =
        "Retriever dejará de buscar en ella y olvidará lo que procesó ahí. La carpeta y sus archivos se quedan donde están."

    // HomeSections
    override val showsAll = "Muestra todo"

    override val showsThem = "Los muestra"

    override val hideOnThisDayIos = "Ocultar «En este día»"

    override fun homeTileSpoken(kind: String, name: String, date: String, starred: Boolean) = "$kind, $name, $date" + if (starred) ", en Destacados" else ""

    // HomeView
    override val addFolderOrPhotos = "Añade una carpeta o deja que Retriever vea tus fotos, y encontrará lo que contienen."

    override val allowPhotosAccess = "Permitir acceso a Fotos"

    override val turnOnPhotosAccess = "Activar el acceso a Fotos"

    override val onThisDayTitle = "En este día"

    override val openSettings = "Abrir Ajustes"

    override val alsoSearchesFolders = "Retriever también busca en las carpetas que añadas."

    // ReadingFiles
    override val openFileHint = "Abrir archivo"

    // ResultViews
    override fun sharedPhotoName(ext: String?) = "Foto.${ext ?: "jpg"}"

    // RootView
    override val setupTitle = "Configuración"

    // SearchView
    override val docsShort = "Docs."

    override val searchByPhoto = "Buscar por foto"

    override val removeSearch = "Quitar"

    override val removeFromRecentSearches = "Quitar de búsquedas recientes"

    override val somethingWentWrongSearching = "Algo salió mal al buscar."

    override val lookingEllipsis = "Buscando…"

    override fun turnOnToSearchScope(scope: String) = "Actívalo en Ajustes y Retriever también podrá buscar en tus $scope."

    override val noMatchesInPickedPhotos = "No hay resultados en las fotos que elegiste"

    override val onlySeesChosenPhotos = "Retriever solo puede ver las fotos que elegiste. Elige más para buscar en más."

    override val notQuiteReady = "Aún no está listo"

    override fun nothingLikeThatOn(words: DeviceWords) = "Aún no hay nada así en este ${device(words)}."

    override fun nothingInScopeFits(scope: String) = "No hay nada en $scope que encaje. Prueba con todos tus archivos."

    override fun noStarredMatch(query: String) = "Ningún archivo destacado coincide con «$query»"

    override val onlyStarredShowHere = "Aquí solo aparecen los archivos que destacaste. Prueba con todos tus archivos."

    override fun nothingLikePhotoIn(scope: String) = "Nada parecido a esta foto en $scope"

    override fun noMatchesFor(query: String) = "No hay resultados para «$query»"

    override val tryAllFilesInstead = "Prueba mejor con todos tus archivos."

    override fun nothingInScopeMatches(scope: String) = "Nada en $scope coincide. Prueba con todos tus archivos."

    override val likeThisPhoto = "Parecido a esta foto"

    override val photosOffResults = "El acceso a Fotos está desactivado, así que estos resultados no incluyen tus fotos."

    // SettingsView
    override val cleared = "Borrado"

    override fun savedOnDeviceOnly(words: DeviceWords) = "Se guardan solo en este ${device(words)}. No se envía nada a ningún sitio."

    override val clearRecentSearchesAsk = "¿Borrar las búsquedas recientes?"

    override fun privacyBody(words: DeviceWords) =
        "Retriever procesa tus archivos en este ${device(words)}. Tus búsquedas, tus estrellas y lo que encuentra dentro de los archivos " +
            "se quedan aquí. Descargar la búsqueda por descripción y abrir archivos guardados en iCloud puede usar internet."

    override val longFilesBitAtATime =
        "Los archivos largos se procesan poco a poco: primero puedes encontrarlos por su principio, y el resto llega después."

    override val onlyWhenYouChooseAnyFile = "Solo cuando eliges «$readFor15». Siempre puedes encontrar cualquier archivo por su nombre."

    override fun alsoOnBatteryIosBody(words: DeviceWords) = "Procesa tus archivos mientras Retriever está abierto, con más del 20$NBSP% de batería. " +
        "Cuando no lo usas, iOS espera a que tu ${device(words)} se esté cargando. " +
        "Se pausa cuando tu ${device(words)} está caliente o en el ${powerSave(words)}."

    override val keepScreenOnToggle = "Mantener la pantalla encendida al procesar durante 15 minutos"

    override fun adjustProcessingFor(words: DeviceWords) = "Ajustar el procesamiento para este ${device(words)}"

    override val runSetupCheckAgain = "Vuelve a comprobar la configuración."

    override val kindsFooterVideos = "$kindOff Videos: solo imágenes, sin sonido."

    override fun includeQuoted(name: String) = "Incluir «$name»"

    override val doesntLookInThese = "Retriever no busca en estas carpetas. Tus archivos se quedan donde están."

    override val downloadsOnWifiOrData = "Descargas por Wi-Fi o datos móviles."

    override val downloadsWaitForWifi = "Las descargas esperan al Wi-Fi, así que no gastan tus datos."

    override val noticesUnavailable = "Retriever está hecho con software de código abierto. Ahora mismo no se pudieron mostrar sus licencias."

    // SetupView
    override val searchesForThis = "Busca esto"

    // SplitRoot
    override val searchTab = "Buscar"

    // StatusView
    override val onlySeesPickedPhotos = "Retriever solo puede ver las fotos que elegiste."

    override val photosOffCantSearch = "El acceso a Fotos está desactivado, así que Retriever no puede buscar en tus fotos."

    // TextReader
    override val notProcessedOpenOriginal = "Retriever aún no ha procesado este archivo. Puedes abrir el original."

    override val noTextToShow = "No hay texto que mostrar"

    override val noReadableText = "Retriever no encontró texto legible en este archivo."

    override val couldntShowText = "No se pudo mostrar el texto"

    override val wentWrongReadingIt = "Algo salió mal al cargarlo. Vuelve a intentarlo en un momento."

    override val textFromThisFile = "Texto de este archivo"

    override val onlyBeginningIncluded = "Solo se incluye el principio de este archivo."

    // Viewer
    override fun matchOnPage(page: Int) = "Coincidencia en la página $page"

    override val anotherAppCanAsk = "Otra app puede pedirte la contraseña."

    override val damagedOrUnsupported = "Puede que esté dañado o en un formato que Retriever no puede mostrar."

    override fun couldntPlayIos(audio: Boolean) = if (audio) "No se pudo reproducir este audio" else "No se pudo reproducir este video"

    override val iCloudOrCantPlay =
        "Puede que solo esté en iCloud y no haya conexión, o que esté en un formato que Retriever no puede reproducir."

    override fun photoSpoken(kind: String, name: String) = "$kind, $name"

    override val turnOnToSeePhotos = "Actívalo en Ajustes para ver aquí tus fotos."

    override val onlyInICloudNow = "Puede que solo esté en iCloud y ahora no haya conexión."

    override val downloadingFromICloud = "Descargando de iCloud"
}
