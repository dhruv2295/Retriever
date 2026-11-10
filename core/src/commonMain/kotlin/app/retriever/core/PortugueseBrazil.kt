package app.retriever.core

import app.retriever.core.Words.NBSP

/**
 * Brazilian Portuguese (pt-BR). A machine translation of [English], awaiting review by a native speaker.
 * Reviewed by a second model (Codex).
 *
 * Term choices, for the reviewer to check they hold everywhere:
 * - process: "processar" (processa, processado, processamento); never ler, analisar, indexar, escanear.
 * - search by description: "busca por descrição"; search: "busca" / "buscar" (as iOS pt-BR says Buscar).
 * - found by name: "Encontrado pelo nome"; processed in part: "Processado em parte".
 * - phone: "celular" (o celular, seu celular, neste celular); "iPhone" and "iPad" are masculine too, so every sentence
 *   reads the same with all three.
 * - Battery Saver: "Economia de bateria" (Android, feminine); Low Power Mode: "Modo Pouca Energia" (iOS, masculine).
 * - Settings: "Ajustes" for the iOS system app; "Configurações" for Android's system app and Retriever's own settings.
 * - starred: "com estrela" (the collection "Com estrela"; Marcar com estrela, Remover estrela).
 * - People: "Pessoas"; On this day: "Neste dia" on both platforms; Home: "Início"; Browse: "Explorar".
 * - match: "resultado"; delete: "excluir"; download: "baixar" / "o download"; mobile data: "dados móveis".
 * - Retriever takes the article: "o Retriever", "do Retriever".
 * - Plural: 0 and 1 are singular (CLDR pt).
 */
internal object PortugueseBrazil : Strings {
    private fun isOne(n: Long) = n == 0L || n == 1L

    private fun plural(n: Long, one: String, many: String = "${one}s"): String = "${formatCount(n)} ${if (isOne(n)) one else many}"

    private fun device(words: DeviceWords) = if (words.ios) words.device else "celular"

    private fun processed(n: Long) = if (isOne(n)) "processado" else "processados"

    override val sentenceGap = " "

    override val modified = "Data de modificação"
    override val taken = "Data de captura"

    override fun files(n: Long) = plural(n, "arquivo")

    override fun photos(n: Long) = plural(n, "foto")

    override fun videos(n: Long) = plural(n, "vídeo")

    override fun photosAndVideos(n: Long) = "${formatCount(n)} ${if (isOne(n)) "foto ou vídeo" else "fotos e vídeos"}"

    override fun readOf(done: Long, total: Long) = "${formatCount(done)} de ${files(total)} ${processed(total)}"

    override fun meaningOf(done: Long, total: Long) = "Busca por descrição em ${formatCount(done)} de ${files(total)}"

    override fun facesOf(done: Long, total: Long) = "${formatCount(done)} de ${photos(total)} ${if (isOne(total)) "processada" else "processadas"}"

    override val processedInPart = "Processado em parte"
    override val couldntBeOpened = "Não foi possível abrir"
    override val stillToProcess = "Ainda a processar"
    override val longFilesStillBeingFinished = "Arquivos longos ainda em processamento"

    override fun largeFiles(n: Long) = "${files(n)} acima de 100${NBSP}MB"

    override fun readCount(read: Long) = "${files(read)} ${processed(read)}"

    override fun partlyReadCount(n: Long) = "${formatCount(n)} ${processed(n)} em parte"

    override fun extent(extent: ReadExtent?) = when (extent) {
        ReadExtent.FIRST_PART -> "Processado em parte · só o começo"
        ReadExtent.MEANING_LIMITED -> "Processado em parte · não foi possível concluir a busca por descrição"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "Processado em parte · só o começo, sem busca por descrição"
        ReadExtent.FIRST_PART_DEEPENING -> "Processado em parte · só o começo, por enquanto"
        ReadExtent.DEEPENING -> "Processado em parte · a busca por descrição ainda será adicionada"
        ReadExtent.WHOLE, null -> null
    }

    override val couldntProcessLabel = "Não foi possível processar"
    override val notProcessedYet = "Ainda não processado"
    override val partRestToCome = "Processado em parte · o restante ainda vai ser processado"
    override val partCouldntFinish = "Processado em parte · não foi possível concluir"

    override fun kindLabel(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "Documento"
        Kind.SCREENSHOT -> "Captura de tela"
        Kind.PHOTO -> "Foto"
        Kind.AUDIO -> "Áudio"
        Kind.VIDEO -> "Vídeo"
        Kind.OTHER -> "Arquivo"
    }

    override fun kindLabelPlural(kind: Kind) = when (kind) {
        Kind.PDF -> "PDFs"
        Kind.DOC -> "Documentos"
        Kind.SCREENSHOT -> "Capturas de tela"
        Kind.PHOTO -> "Fotos"
        Kind.AUDIO -> "Áudios"
        Kind.VIDEO -> "Vídeos"
        Kind.OTHER -> "Outros arquivos"
    }

    override fun kindsInSentence(kind: Kind) = kindLabelPlural(kind).let { if (it.length > 1 && it[1].isUpperCase()) it else it.lowercase() }

    override fun spokenPage(page: Int) = "página $page"

    override fun spokenTime(hours: Long, minutes: Long, seconds: Long): String {
        val parts = listOf(hours to "hora", minutes to "minuto", seconds to "segundo").filter { it.first > 0 }.map { (n, unit) -> plural(n, unit) }
        val spoken = if (parts.size > 1) parts.dropLast(1).joinToString(", ") + " e " + parts.last() else parts.firstOrNull()
        return "em " + (spoken ?: "0 segundo")
    }

    override fun textOnlyNote(ramGb: Int, words: DeviceWords) = "Este ${device(
        words,
    )} tem $ramGb${NBSP}GB de memória, pouco para a busca por descrição, então o Retriever encontra os arquivos pelo nome e pelas palavras que eles contêm."

    override fun notReadYet(files: Long) = "${if (isOne(files)) "Falta" else "Faltam"} ${formatCount(files)}"

    override fun couldntProcess(files: Long) = "Não foi possível processar ${files(files)}"

    override val details = "Detalhes"
    override val starting = "Começando…"
    override val processingNow = "Processando agora"
    override val recentlyProcessed = "Processados recentemente"
    override val processingYourFiles = "Processando seus arquivos"
    override val pause = "Pausar"
    override val processTheseKinds = "Processar estes tipos de arquivo"

    override val continueAction = "Continuar"
    override val tryAgain = "Tentar de novo"
    override val download = "Baixar"
    override val notNow = "Agora não"
    override val cancel = "Cancelar"
    override val done = "Concluído"
    override val readFor15 = "Processar por 15 minutos"
    override val optional = "Opcional"

    override fun helloTitle(textOnly: Boolean) = if (textOnly) "Encontre arquivos pelo que há dentro deles" else "Encontre qualquer coisa só descrevendo"

    override val helloBody = "Fotos, capturas de tela, documentos e vídeos, em uma só busca."
    override val forExample = "Por exemplo"
    override val helloExamples = listOf(
        Words.HelloExample("senha do Wi-Fi que o proprietário mandou", "Captura de tela de 14 de março", "Diz “Senha do Wi-Fi: …”"),
        Words.HelloExample("cachorro dormindo no sofá", "Foto do último domingo", "Encontrada pelo que aparece nela", needsModel = true),
        Words.HelloExample("meu contrato de aluguel", "Contrato de aluguel 2025.pdf", "Página 3, a cópia assinada"),
    )
    override val downloads = "Downloads"

    override fun onDevice(words: DeviceWords) = "Seus arquivos são processados no seu ${device(words)}"

    override val getStarted = "Começar"

    override fun downloadNote(size: String) = "Inclui um único download de $size por Wi-Fi."

    override val accessTitle = "Escolha o que o Retriever pode buscar"
    override val accessBody = "Escolha uma ou mais opções. Você pode adicionar outras depois."
    override val allow = "Permitir"
    override val allowAccess = "Permitir acesso"
    override val choose = "Escolher"
    override val chooseMore = "Escolher mais"
    override val settings = "Ajustes"
    override val documents = "Documentos"
    override val includesScreenshots = "Inclui capturas de tela"
    override val allowed = "Permitido"
    override val somePhotos = "Algumas fotos selecionadas"
    override val photosOff = "Desativado. Ative nos Ajustes."
    override val pickFolders = "Escolha pastas no app Arquivos, como Downloads"

    override fun foldersAdded(n: Long) = "${plural(n, "pasta")} ${if (isOne(n)) "adicionada" else "adicionadas"}"

    override val androidAccessBody = "Assim você encontra qualquer foto, documento ou download descrevendo o que procura."
    override val allFilesOff =
        "Ative “Permitir acesso para gerenciar todos os arquivos” para buscar em todo o celular, ou escolha o que incluir."
    override val includeTitle = "Escolha o que incluir"
    override val sharedFilesAndFolders = "Arquivos e pastas neste celular"
    override val photosAndVideosLabel = "Fotos e vídeos"
    override val addFolder = "Adicionar uma pasta"
    override val nothingSelected = "Nenhum arquivo selecionado ainda. Escolha fotos ou uma pasta para começar."

    override fun wholeDeviceNote(words: DeviceWords) = "O Retriever processa os arquivos deste ${device(words)} para você encontrá-los pelo que há neles."

    override val firstLook = "Primeiro, seus arquivos recentes"

    override fun firstSearchHint(state: DescriptionState) = if (state ==
        DescriptionState.READY
    ) {
        "Descreva uma foto ou um arquivo"
    } else {
        "Busque por nome ou palavras"
    }

    override fun descriptionLine(state: DescriptionState, percent: Int?) = when (state) {
        DescriptionState.DOWNLOADING -> "Baixando a busca por descrição" + (percent?.let { " · $it%" } ?: "")
        DescriptionState.WAITING_FOR_WIFI -> "Aguardando Wi-Fi para baixar a busca por descrição."
        DescriptionState.PREPARING -> "Preparando a busca por descrição"
        DescriptionState.FAILED -> "O download da busca por descrição parou."
        DescriptionState.NO_SPACE -> "Não há espaço livre suficiente para baixar a busca por descrição."
        DescriptionState.READY, DescriptionState.NOT_STARTED, DescriptionState.UNAVAILABLE -> null
    }

    override val descriptionReady = "A busca por descrição está pronta"
    override val descriptionReadyBody = "Agora descreva seus arquivos recentes para encontrá-los."

    override val homeIntroTitle = "O Retriever continua sozinho"
    override val homeIntroBody = "Veja o que acontece a seguir."
    override val gotIt = "Entendi"
    override val newFiles = "Novas fotos e arquivos"
    override val newFilesBody = "Incluídos automaticamente. Você não precisa fazer nada."
    override val notifyWhenSearchable = "Avisar quando tudo estiver pronto para a busca"
    override val caughtUpNoticeTitle = "Tudo pronto para a busca"

    override fun caughtUpNotice(files: Long, textOnly: Boolean) =
        (if (isOne(files)) "O Retriever processou ${files(files)}. " else "O Retriever processou todos os ${files(files)}. ") +
            if (textOnly) "Busque pelo nome ou pelas palavras do conteúdo." else "Descreva qualquer coisa para encontrá-la."

    override fun recentFiles(n: Long) = when (n) {
        0L -> "Seus arquivos recentes"
        1L -> "Seu arquivo mais recente"
        else -> "Seus ${formatCount(n)} arquivos recentes"
    }

    override fun otherFiles(rest: Long?) = when (rest) {
        null -> "O restante dos seus arquivos"
        1L -> "O outro arquivo"
        else -> "Mais ${files(rest)}"
    }

    override fun otherFilesBody(words: DeviceWords) =
        "Quando seu ${device(words)} estiver carregando e você não estiver usando o aparelho. Com muitos arquivos, isso pode levar algumas noites."

    override fun otherFilesOnBatteryBody(words: DeviceWords) = "Quando você não estiver usando seu ${device(
        words,
    )}: na bateria, enquanto a carga estiver acima de ${ReadingPolicy.BATTERY_FLOOR}%, e enquanto carrega. " +
        "Com muitos arquivos, isso pode levar alguns dias."

    override fun onBatteryBody(words: DeviceWords) =
        "Também na bateria, enquanto a carga estiver acima de ${ReadingPolicy.BATTERY_FLOOR}%, para sobrar bateria para o seu dia. " +
            "O processamento para quando " +
            (if (words.ios) "o Modo Pouca Energia estiver ativado" else "a Economia de bateria estiver ativada") +
            " ou seu ${device(words)} estiver quente."

    override fun recentFilesBody(state: DescriptionState) = when (state) {
        DescriptionState.READY -> "Descreva-os para encontrá-los."

        DescriptionState.UNAVAILABLE -> "Encontre-os por nome ou palavras. A busca por descrição precisa de um celular com mais memória."

        DescriptionState.WAITING_FOR_WIFI -> "Já dá para encontrá-los por nome ou palavras. Aguardando Wi-Fi para concluir um único download."

        DescriptionState.FAILED -> "Já dá para encontrá-los por nome ou palavras. O download parou."

        DescriptionState.NO_SPACE -> "Já dá para encontrá-los por nome ou palavras. Não há espaço livre suficiente para baixar a busca por descrição."

        // Not "one-time": after a chip build fails on its first run, this is the second download.
        DescriptionState.NOT_STARTED -> "Já dá para encontrá-los por nome ou palavras. A busca por descrição precisa de um download."

        DescriptionState.DOWNLOADING, DescriptionState.PREPARING ->
            "Já dá para encontrá-los por nome ou palavras. A busca por descrição estará disponível quando a configuração terminar."
    }

    override fun recentFilesAction(state: DescriptionState, size: String) = when (state) {
        DescriptionState.WAITING_FOR_WIFI -> "Usar dados móveis ($size)"
        DescriptionState.FAILED -> tryAgain
        DescriptionState.NOT_STARTED -> "Baixar $size"
        else -> null
    }

    override val demoPicking = "Escolhendo seus arquivos recentes…"
    override val demoHint = "Digite uma palavra que você lembra de ter visto em uma foto, captura de tela ou documento recente."

    override fun demoSearchScope(done: Long, finished: Boolean, words: DeviceWords): String {
        val demoFiles = plural(done, "arquivo de demonstração", "arquivos de demonstração")
        return when {
            finished -> "Encontra qualquer arquivo no seu ${device(words)} pelo nome e busca no conteúdo de $demoFiles."

            // The first frame: nothing processed yet, so no "0 demo files".
            done == 0L -> "Encontra qualquer arquivo no seu ${device(words)} pelo nome."

            else -> "Encontra qualquer arquivo no seu ${device(words)} pelo nome. Busca no conteúdo de $demoFiles ${processed(done)} até agora."
        }
    }

    override fun seeAll(files: Long) = if (files == 1L) "Ver o arquivo" else "Ver ${files(files)}"

    override val read = "Encontrado pelo que há dentro"
    override val foundByName = "Encontrado pelo nome"
    override val foundBy = "Encontrado por"

    override fun kindRead(read: Long, planned: Long, kind: Kind): String {
        val label = (if (isOne(planned)) kindLabel(kind) else kindLabelPlural(kind)).let { if (kind == Kind.PDF) it else it.lowercase() }
        val feminine = kind == Kind.PHOTO || kind == Kind.SCREENSHOT
        val agreement = if (isOne(planned)) {
            if (feminine) "processada" else "processado"
        } else {
            if (feminine) "processadas" else "processados"
        }
        return "${formatCount(read)} de ${formatCount(planned)} $label $agreement"
    }

    override val chooseWhen = "Escolher quando processar"
    override val tryLabel = "Experimente:"

    override fun chooseTitle(words: DeviceWords) = "Agora, o restante do seu ${device(words)}"

    override fun chooseBody(rest: Long?) = when (rest) {
        null -> "O restante dos seus arquivos ainda não foi processado. Até lá, você pode encontrá-los pelo nome, mas não pelo que há dentro."

        1L -> "1 arquivo ainda não foi processado. Até lá, você pode encontrá-lo pelo nome, mas não pelo que há dentro."

        else -> "${files(
            rest,
        )} ${if (isOne(
                rest,
            )
        ) {
            "ainda não foi processado. Até lá, você pode encontrá-lo"
        } else {
            "ainda não foram processados. Até lá, você pode encontrá-los"
        }} pelo nome, mas não pelo que há dentro."
    }

    override fun choosePace(words: DeviceWords) =
        "Processá-los leva horas, então o Retriever faz um pouco de cada vez. Você pode usar seu ${device(words)} e o Retriever o tempo todo."

    override val recommended = "Recomendado"
    override val read15Now = "Enquanto carrega e por 15 minutos agora"
    override val notNowBody = "Até você escolher, só os nomes podem ser encontrados. Você pode escolher a qualquer momento no Início."
    override val startProcessing = "Processar"
    override val goToHome = "Ir ao Início"

    override fun whileChargingBody(words: DeviceWords) =
        "Quando seu ${device(words)} estiver carregando e você não estiver usando o aparelho, geralmente à noite. Você pode pausar quando quiser."

    override fun chargingAndNowBody(words: DeviceWords) =
        "Começa agora mesmo e usa um pouco de bateria, depois continua enquanto carrega." + if (words.ios) " Mantenha o Retriever aberto." else ""

    override fun readNowBody(words: DeviceWords) = "Usa um pouco de bateria. Para antes se seu ${device(words)} esquentar. Depois continua enquanto carrega." +
        if (words.ios) " Mantenha o Retriever aberto." else ""

    override fun notificationsAsk(demoPending: Boolean) =
        "Um pequeno aviso no topo da tela enquanto ele trabalha, para você ver quanto já foi feito ou pausar. Sem sons nem outros alertas." +
            if (demoPending) " Também podemos avisar quando a demonstração terminar." else ""

    override val progressChannel = "Progresso do processamento"
    override val progressChannelBody = "Mostra quanto do processamento já foi feito e avisa uma vez quando a demonstração termina."
    override val demoDoneNoticeTitle = "A demonstração terminou"

    override fun demoDoneNotice(done: Long, planned: Long) = "${readOf(done, planned)}. Abra o Retriever e faça uma busca."

    override val searchField = "O que você está procurando?"
    override val nameMatches = "Resultados pelo nome"
    override val textMatches = "Resultados pelo texto"
    override val searchingContents = "Buscando no conteúdo dos arquivos…"

    override fun copies(n: Int) = plural(n.toLong(), "cópia")

    override fun openCopyIn(folder: String) = "Abrir a cópia em $folder"

    override val noMatches = "Nada encontrado"

    override fun noMatchesDetail(byMeaning: Boolean) = // No "add when, like last week": QueryParser reads only English time words.
        (if (byMeaning) "Descreva o que há no arquivo ou pergunte o que ele diz." else "Tente uma palavra do conteúdo ou do nome.")

    override val moreWaiting = "O Retriever ainda está processando seus arquivos, então busque de novo mais tarde."
    override val searchFailed = "Não foi possível concluir a busca"
    override val showingNameMatches = "Mostrando resultados pelo nome"
    override val searchingWithoutModel = "Buscando nos nomes e no texto já processado"

    override fun matches(labels: List<String>) = "Corresponde a: " + labels.joinToString(", ")

    override val downloadForDescription = "Baixar a busca por descrição"

    override val readingStatus = "Processamento"
    override val recentlyAdded = "Adicionados recentemente"
    override val moreOnPhone = "Explorar"
    override val allFolders = "Todas as pastas"

    override val howItWorks = "Funcionamento e privacidade"
    override val whenToRead = "Quando processar"
    override val whatItCanSee = "O que o Retriever pode ver"
    override val foldersToSkip = "Pastas a ignorar"
    override val kindOff =
        "Desativado: o Retriever ainda encontra estes arquivos pelo nome, mas esquece o que há dentro deles. Seus arquivos não são alterados."
    override val showOnThisDay = "Mostrar Neste dia (fotos desta data em anos anteriores)"

    override val people = "Pessoas"
    override val recognizePeople = "Reconhecer pessoas"
    override val peopleOfferTitle = "Encontre fotos de pessoas"
    override val peopleOfferBody =
        "O Retriever pode agrupar os rostos das suas fotos, para você dar nome a alguém e encontrar essa pessoa pelo nome. " +
            "Os rostos são processados neste celular e nunca saem dele."
    override val turnOn = "Ativar"
    override val recognizePeopleDetail =
        "Agrupa os rostos das suas fotos para você dar nome às pessoas e encontrá-las pelo nome. Os rostos são processados neste celular e nunca " +
            "entram em backups."
    override val forgetPeopleTitle = "Esquecer todos os rostos e nomes?"
    override val forgetPeopleBody = "O Retriever esquece os rostos que agrupou e os nomes que você deu. Suas fotos não são alteradas."
    override val forget = "Esquecer"
    override val notThisPerson = "Não é esta pessoa"
    override val addAName = "Adicionar um nome"
    override val name = "Nome"
    override val save = "Salvar"
    override val samePersonAs = "Mesma pessoa que…"
    override val samePersonAsk = "Mesma pessoa?"
    override val samePersonYes = "Mesma pessoa"
    override val samePersonNo = "Não é a mesma"
    override val samePersonPrevious = "Par anterior"
    override val samePersonNext = "Próximo par"

    override fun pairOf(at: Int, of: Int) = "$at de $of"

    override fun facesToGo(left: Long) = "${photos(left)} a verificar"

    override val retrieversData = "Dados do Retriever"
    override val temporaryFiles = "Arquivos temporários"

    override fun keptOn(size: String, words: DeviceWords) = "$size · guardado neste ${device(words)}"

    override val searchByMeaning = "Busca por descrição"
    override val removeDownload = "Remover download da busca"
    override val removeDownloadAsk = "Remover o download da busca?"
    override val namesStillWork = "Você ainda pode buscar pelo nome dos arquivos e pelo texto já processado."

    override fun downloaded(size: String) = "Baixado · $size"

    override fun removeDownloadMessage(size: String?) =
        (if (size != null) "Isso libera $size. " else "") + "$namesStillWork A busca por descrição vai precisar do download de novo."

    override val freeUpSpace = "Liberar espaço"
    override val largeFilesTitle = "Arquivos grandes"
    override val findDuplicates = "Buscar duplicados"
    override val neverDeletes = "O Retriever nunca exclui nada sem perguntar antes."

    override fun possibleCopies(n: Long) = "${files(n)} ${if (isOne(n)) "pode ser uma cópia" else "podem ser cópias"}"

    override fun deleteFrom(words: DeviceWords) = "Excluir do ${device(words)}"

    override fun deleteConfirm(words: DeviceWords) = "Isso exclui o arquivo do seu ${device(words)}."

    override val upgradeTitle = "Encontre arquivos descrevendo-os"
    override val waitingForWifi = "Aguardando Wi-Fi para baixar"
    override val useMobileData = "Usar dados móveis"
    override val waitForWifi = "Esperar o Wi-Fi"
    override val checkingDownload = "Verificando o download…"
    override val downloadFailed = "Download interrompido. Verifique sua conexão e tente de novo."
    override val noSpace = "Não há espaço livre suficiente para baixar"
    override val tuneLeaving = "Você já pode buscar por nome. A configuração continua quando você voltar."

    override fun upgradeBody(size: String, words: DeviceWords) = "Um único download de $size por Wi-Fi. " +
        "Depois, uma busca como “pôr do sol na praia” encontra fotos mesmo sem essas palavras. Tudo fica neste ${device(words)}."

    override fun downloadProgress(done: String, total: String, percent: Int) = "$done de $total · $percent%"

    override fun mobileDataAsk(size: String) = "Baixar $size usando dados móveis?"

    override fun freeUp(size: String) = "Libere $size para continuar"

    override fun tuneTitle(words: DeviceWords) = "Configurando o processamento para este ${device(words)}"

    override fun tuneBody(words: DeviceWords) = "Cerca de um minuto. Ajustando a velocidade de processamento para este ${device(words)}."

    override fun tuneFailed(words: DeviceWords) = "Não foi possível concluir a configuração do processamento para este ${device(words)}."

    override fun addingMeaning(done: Long, total: Long) = "Adicionando a busca por descrição · ${formatCount(done)} de ${files(total)}"

    override val meaningCatchingUp =
        "A busca por descrição já funciona. Os arquivos processados antes de ela ficar pronta vão recebê-la conforme o Retriever continua."

    override fun scheduleTitle(schedule: ReadingSchedule) = when (schedule) {
        ReadingSchedule.CHARGING -> "Enquanto carrega"
        ReadingSchedule.BATTERY -> "Também na bateria"
        ReadingSchedule.MANUAL -> "Quando eu iniciar"
    }

    override fun statusAction(action: StatusAction) = when (action) {
        StatusAction.CONTINUE -> continueAction
        StatusAction.ALLOW_ACCESS -> allowAccess
        StatusAction.CHOOSE_WHEN -> chooseWhen
        StatusAction.READ_FOR_15 -> readFor15
        StatusAction.STOP -> "Parar"
        StatusAction.RESUME -> "Retomar"
        StatusAction.DISMISS -> "Dispensar"
        StatusAction.TRY_AGAIN -> tryAgain
    }

    override fun stopCopy(stop: StopReason, words: DeviceWords) = when (stop) {
        StopReason.PAUSED -> "Pausado"
        StopReason.POWER_SAVE -> "Pausado enquanto " + if (words.ios) "o Modo Pouca Energia estiver ativado" else "a Economia de bateria estiver ativada"
        StopReason.HOT -> "Pausado para seu ${device(words)} esfriar"
        StopReason.LOW_BATTERY -> "Pausado, a bateria está fraca"
        StopReason.CHARGING_UP -> "Carregando a bateria primeiro"
        StopReason.BUSY -> "Esperando você deixar o ${device(words)} de lado"
        StopReason.UNPLUGGED -> "Espera você carregar"
        StopReason.MANUAL -> "Processa só quando você iniciar"
        StopReason.DISK_FULL -> "Pausado, seu ${device(words)} está sem espaço"
        StopReason.DONE, StopReason.BUDGET -> "Fazendo uma pausa curta"
    }

    override val noAccessTitle = "O Retriever não consegue ver seus arquivos"

    override fun noAccessDetail(words: DeviceWords) =
        "A busca ainda funciona nos arquivos já processados." + if (words.ios) " Isto abre os Ajustes do seu ${device(words)}." else ""

    override val finishSetupTitle = "Concluir a configuração"
    override val finishSetupDetail = "Escolha onde o Retriever pode procurar."
    override val downloadPausedTitle = "Download pausado"

    override fun downloadPausedDetail(words: DeviceWords, size: String) =
        "O download continua quando você estiver no Wi-Fi. Se seu ${device(words)} estiver sem espaço, ele precisa de $size livres."

    override fun downloadingTitle(percent: Int?) = "Baixando a busca por descrição" + (percent?.let { " · $it%" } ?: "")

    override val downloadingDetail = "Você pode continuar usando o Retriever."

    override val addingDescriptionTitle = "Adicionando a busca por descrição"
    override val allFilesProcessedTitle = "Todos os arquivos processados"
    override val countingTitle = "Contando seus arquivos…"
    override val notNowTitle = "Por enquanto, só os nomes podem ser encontrados"

    override fun readNowTitle(minutesLeft: Int) =
        "Processando por 15 minutos · ${if (isOne(minutesLeft.toLong())) "falta" else "faltam"} ${formatCount(minutesLeft.toLong())}${NBSP}min"

    override val sampleTitle = "Processando os arquivos de demonstração"
    override val gettingPeopleReadyTitle = "Preparando Pessoas"
    override val lookingForFacesTitle = "Procurando rostos nas suas fotos"
    override val deepeningTitle = "Processando o restante de alguns arquivos longos"
    override val chargingReadTitle = "Processando enquanto carrega"
    override val lookingTitle = "Verificando se há arquivos novos…"
    override val caughtUpTitle = "Tudo pronto. O Retriever processou tudo o que podia"
    override val limitedTitle = "Tudo pronto. Alguns arquivos longos foram processados só em parte"

    override val pausedDetail = "A busca continua funcionando."

    override fun powerSaveDetail(words: DeviceWords) =
        "Continua quando " + (if (words.ios) "o Modo Pouca Energia for desativado" else "a Economia de bateria for desativada") + "."

    override val hotDetail = "Continua sozinho."
    override val lowBatteryDetail = "Conecte o carregador e o Retriever continua."
    override val chargingUpDetail = "O processamento começa quando a bateria tiver um pouco mais de carga."
    override val busyDetail = "Ou abra o Retriever para continuar agora."

    override fun chargingDetail(words: DeviceWords) = "O Retriever continua quando seu ${device(words)} estiver carregando."

    override val manualDetail = "Você pode encontrar qualquer arquivo pelo nome."

    override fun readNowDetail(words: DeviceWords) = "Usa um pouco de bateria. " + if (words.ios) "Mantenha o Retriever aberto." else "Para sozinho."

    override val sampleDetail = "Cerca de um minuto. Nada é alterado nem enviado."

    override fun meaningDetail(words: DeviceWords) =
        "Para você buscar pelo sentido das coisas e pelo que aparece nas suas fotos. Somente neste ${device(words)}."

    override val peopleDownloadDetail = "Um único download para Pessoas. A busca funciona o tempo todo."
    override val facesDetail = "Todos os seus arquivos foram processados. A busca funciona o tempo todo."
    override val deepeningDetail = "Você pode encontrá-los pelo começo."
    override val readingMeaningDetail = "Adicionando também a busca por descrição."

    override fun readingDetail(words: DeviceWords) = if (words.ios) {
        "A busca funciona o tempo todo. O Retriever processa seus arquivos enquanto está aberto e, às vezes, quando o iOS permite."
    } else {
        "Você pode fechar o Retriever; ele continua. A busca funciona o tempo todo."
    }

    override fun waitingLongFilesDetail(words: DeviceWords) =
        "O Retriever processa o restante de alguns arquivos longos quando seu ${device(words)} permitir. A busca já funciona."

    override fun waitingMeaningDetail(words: DeviceWords) =
        "O Retriever adiciona a busca por descrição quando seu ${device(words)} permitir. A busca já funciona."

    override val diskFullDetail = "Libere um pouco de espaço e o Retriever continua."

    override fun waitingDetail(words: DeviceWords) = "Seu ${device(words)} decide quando o Retriever continua. Nada se perde."

    override val caughtUpDetail = "Ele processa os arquivos novos sozinho."

    override val peopleWaitsForWifi = "O download de Pessoas aguarda o Wi-Fi."

    override fun deepeningLead(left: Long) = "${notReadYet(left)}."

    override fun longFilesWait(n: Long) = "Todos os arquivos processados. O restante de ${plural(n, "arquivo longo", "arquivos longos")} aguarda."

    override val allFilesProcessedLead = "Todos os arquivos processados."

    override val foundByOther = "O nome. O Retriever ainda não consegue processar este tipo de arquivo."
    override val foundByBeginningRestPending = "O nome e o começo. O restante ainda não foi processado."
    override val foundByPartCouldntFinish = "O nome e parte do conteúdo. O Retriever não conseguiu concluir o processamento."
    override val foundByBeginning = "O nome e o começo"
    override val foundByInsideMeaningLimited = "O nome e o conteúdo. Não foi possível concluir a busca por descrição."
    override val foundByInsideDeepening = "O nome e o conteúdo. A busca por descrição ainda será adicionada."
    override val foundByInside = "O nome e o conteúdo"
    override val foundByReadingOff = "O nome. O processamento está desativado para este tipo de arquivo."

    override fun foundBySkipped(tries: Long) = "O nome. O Retriever não conseguiu processá-lo depois de ${plural(tries, "tentativa")}."

    override val foundByNothingInside = "O nome. Nada no conteúdo pôde ser processado."
    override val foundByNotYet = "O nome. Ainda não processado."

    override val processingFailed = "Algo deu errado ao processar seus arquivos. O Retriever vai tentar de novo."

    // FileActions
    override val fileNameField = "Nome"

    // Notifications
    override fun notificationBody(count: String?, detail: String?, read: Long) = listOfNotNull(count, detail).joinToString(". ").ifEmpty { readCount(read) }

    // SearchResults
    override fun understoodChip(labels: List<String>) = labels.joinToString(" · ")

    override fun noMatchesMessage(byMeaning: Boolean, waiting: Boolean, wordsOnly: Boolean) =
        listOfNotNull(noMatchesDetail(byMeaning), moreWaiting.takeIf { waiting }, searchingWithoutModel.takeIf { wordsOnly }).joinToString(" ")

    override fun reasonLine(parts: List<String>) = parts.joinToString(" · ")

    // SearchScreen
    override val appSettings = "Configurações"

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
    override val onlyPickedPhotos = "Só as fotos que você escolheu"

    override val musicAndRecordings = "Músicas e gravações"

    override fun notCoveredMedia(photos: Boolean, videos: Boolean, music: Boolean) = listOfNotNull(
        "fotos".takeIf { photos },
        "vídeos".takeIf { videos },
        "músicas".takeIf { music },
    ).let { m ->
        "Fora da busca: " +
            if (m.size == 1) m[0] else m.dropLast(1).joinToString(", ") + " e " + m.last()
    }

    override fun notCoveredDocuments(hasFolders: Boolean) = if (hasFolders) {
        "Fora da busca: documentos e downloads que não estão nas pastas que você adicionou"
    } else {
        "Fora da busca: documentos e downloads"
    }

    override val pickFolderOnPhone = "Escolha uma pasta no armazenamento deste celular."

    override val alreadySearchesFolder = "O Retriever já busca nessa pasta."

    // AccessService
    override val couldntKeepFolder = "Não foi possível manter o acesso a essa pasta. Tente escolhê-la de novo."

    // BrowseScreen
    override val allFiles = "Todos os arquivos"

    override val filesRetrieverCanAccess = "Arquivos que o Retriever pode acessar"

    override val sortLargestDetail = "Maiores primeiro"

    override val sortNameDetail = "Por nome, de A a Z"

    override val sortNewestDetail = "Mais recentes primeiro · pela data de modificação"

    override val couldntChangePerson = "Não foi possível alterar esta pessoa. Tente de novo."

    override val back = "Voltar"

    override val couldntLoadYourFiles = "Não foi possível carregar seus arquivos"

    override val wentWrongReadingThem = "Algo deu errado ao carregá-los. Tente outra vez."

    override val keepUsefulFilesClose = "Deixe os arquivos úteis por perto"

    override val tapStarToFindHere = "Toque na estrela de um arquivo para encontrá-lo aqui."

    override val chooseFilesToSearch = "Escolha os arquivos para buscar"

    override val addPhotosOrFolder = "Adicione fotos ou uma pasta."

    override val manageAccess = "Gerenciar acesso"

    override val nothingHereYet = "Nada aqui ainda"

    override fun filesShowUpHere(words: DeviceWords) = "Os arquivos aparecem aqui quando o Retriever os encontra no seu ${device(words)}."

    override val moreOptions = "Mais opções"

    override val couldntLoadMoreFiles = "Não foi possível carregar mais arquivos"

    override val sortNewest = "Mais recentes"

    override val sortLargest = "Maiores"

    override val sortName = "Nome"

    // BrowseViewModel
    override val couldntLoadTheseFiles = "Não foi possível carregar estes arquivos."

    // PersonActions
    override val rename = "Renomear"

    // DuplicatesScreen
    override val duplicates = "Duplicados"

    override val keepOneCopy = "Mantenha pelo menos uma cópia de cada arquivo"

    override fun filesCouldntBeDeleted(n: Long) = "${files(n)} ${if (isOne(n)) "não pôde ser excluído" else "não puderam ser excluídos"}"

    override fun deleteSelected(n: Long, size: String) = "Excluir ${formatCount(n)} ${if (isOne(n)) "selecionado" else "selecionados"} ($size)"

    override val lookingForCopiesEllipsis = "Procurando cópias…"

    override val couldntLookForDuplicates = "Não foi possível procurar duplicados"

    override val noDuplicatesFound = "Nenhum duplicado encontrado"

    override fun comparedFiles(n: Long) = "O Retriever comparou ${files(n)}."

    override fun couldBeFreed(size: String) = "É possível liberar $size"

    override fun copiesEach(n: Long, size: String) = "${plural(n, "cópia")} · $size cada"

    override fun openNamed(name: String) = "Abrir $name"

    override val deselect = "Desmarcar"

    override val select = "Selecionar"

    override val deleteFilesTitle = "Excluir arquivos?"

    override fun deleteFilesMessage(n: Long) = "Excluir ${files(n)}? Não é possível desfazer."

    override val delete = "Excluir"

    // DuplicatesViewModel
    override val somethingWentWrongTryAgain = "Algo deu errado. Tente de novo."

    // FileActions
    override val addToStarred = "Marcar com estrela"

    override val share = "Compartilhar"

    override val openInAnotherApp = "Abrir em outro app"

    override val fileInfo = "Informações do arquivo"

    override val removeFromStarred = "Remover estrela"

    override val topLevel = "Pasta principal"

    override val couldntChangeStar = "Não foi possível alterar a estrela. Tente de novo."

    override val findSimilar = "Encontrar parecidos"

    override val couldntFinishThat = "Não foi possível concluir. O arquivo pode ter sido movido ou o acesso pode ter mudado."

    override val renameFile = "Renomear arquivo"

    override val deleteThisFile = "Excluir este arquivo?"

    // FileInfo
    override fun kindAndSize(kind: String, size: String) = "$kind · $size"

    override val locationLabel = "Local"

    override val hideFullPath = "Ocultar caminho completo"

    override val showFullPath = "Mostrar caminho completo"

    override val locationDetails = "Detalhes do local"

    override val pathClipLabel = "Caminho"

    override val copyPath = "Copiar caminho"

    override val dimensionsLabel = "Dimensões"

    override fun dimensions(width: Int, height: Int) = "$width × $height"

    override val durationLabel = "Duração"

    override val pagesLabel = "Páginas"

    override val internalStorage = "Armazenamento interno/"

    // FoldersScreen
    override val folders = "Pastas"

    override val foldersDidntLoad = "As pastas não carregaram"

    override val noFoldersYet = "Nenhuma pasta ainda"

    override val tryAgainInAMoment = "Tente de novo daqui a pouco."

    override val foldersAppearPhone = "As pastas aparecem depois que o Retriever encontra os arquivos no seu celular."

    override fun stopSearchingAsk(name: String) = "Parar de buscar em $name?"

    override fun stopSearchingMessage(n: Long, name: String) = (
        if (isOne(n)) {
            "${files(n)} em $name sai da busca agora. Para voltar à busca, esse arquivo precisa ser processado de novo. "
        } else {
            "${files(n)} em $name saem da busca agora. Para voltar à busca, esses arquivos precisam ser processados de novo. "
        }
        ) + "Você pode trazer a pasta de volta pela linha de status no topo do Início."

    override val stopSearching = "Parar de buscar"

    override fun filesInParent(n: Long, parent: String?) = if (parent == null) files(n) else "${files(n)} · $parent"

    override fun moreOptionsFor(name: String) = "Mais opções para $name"

    override val stopSearchingThisFolder = "Parar de buscar nesta pasta"

    // FoldersViewModel
    override val couldntLoadYourFolders = "Não foi possível carregar suas pastas. Tente de novo daqui a pouco."

    override val couldntStopSearchingFolder = "Não foi possível parar de buscar nessa pasta. Tente de novo daqui a pouco."

    // HomeScreen
    override val onThisDayHidden = "“Neste dia” foi ocultado"

    override val undo = "Desfazer"

    override val showProcessingDetails = "Mostrar detalhes do processamento"

    override val lookingForYourFiles = "Procurando seus arquivos"

    override val nothingToSearchYet = "Nada para buscar ainda"

    override val takesAMinute = "Isso leva um minuto. Você pode buscar por nome assim que eles aparecerem."

    override val saveAPdfHint = "Salve um PDF, faça uma captura de tela ou tire uma foto para ver o arquivo aqui."

    // HomeSections
    override val showAll = "Mostrar tudo"

    override fun sectionSpoken(title: String, subtitle: String) = "$title, $subtitle"

    override val openAction = "Abrir"

    override fun personTileSpoken(name: String, photos: Long) = "$name, ${photos(photos)}"

    override fun browseTileSpoken(label: String, count: String) = "$label, $count"

    override val onThisDay = "Neste dia"

    override fun onThisDaySpoken(year: Int, count: String, date: String) = "$year, $count de $date"

    override val showThem = "Mostrar"

    override val hideOnThisDay = "Ocultar Neste dia"

    override val starredSection = "Com estrela"

    // ReadingPermission
    override val notificationPermissionAsk = "Mostrar uma notificação enquanto o Retriever processa seus arquivos?"

    override val processFasterAsk = "Processar mais rápido enquanto carrega?"

    override val processFasterBody =
        "O Android usa as partes mais lentas do processador para apps em segundo plano. Com o uso da bateria do Retriever definido como “Sem " +
            "restrições”, ele " +
            "usa as partes mais rápidas enquanto seu celular carrega e termina muito antes. Em Configurações, abra “Bateria” e escolha “Sem restrições”."

    override val openSettingsLowercase = "Abrir Configurações"

    // ModelDownload
    override val searchModelDownloadTitle = "Modelo de busca do Retriever"

    // ModelRepository
    override val downloadStopped = "O download parou. Verifique sua conexão e tente de novo."

    override val downloadDamaged = "O download está danificado. Tente de novo."

    override fun needsFreeSpace(gb: String) = "São necessários $gb${NBSP}GB livres. Libere espaço e tente de novo."

    // SearchResults
    override fun likeFile(name: String) = "Parecido com $name"

    override val photoYoureSearchingWith = "A foto que você está usando na busca"

    override val stopSearchingByPhoto = "Parar de buscar por foto"

    override val searchWordsAsTyped = "Buscar as palavras exatamente como digitadas"

    override val photoSearchAfterSetup = "A busca por foto funciona quando a configuração terminar."

    override val matchingExactWordsForNow = "Por enquanto, buscando as palavras exatas. Os resultados por descrição voltam em breve."

    override fun noMatchesIn(scope: String) = "Nenhum resultado em $scope"

    override val nothingLikePhotoThere = "Nada parecido com esta foto ali."

    override fun nothingForThere(query: String) = "Nada para “$query” ali."

    override val searchAllFiles = "Buscar em tudo"

    override val nothingLikeThisYet = "Nada parecido ainda"

    override val tryAnotherPhoto = "Tente outra foto."

    override val tryFewerWords = "Usar menos palavras"

    override val filterAll = "Tudo"

    override val filterStarred = "Com estrela"

    override val expanded = "Expandido"

    override val collapsed = "Recolhido"

    override fun lessLikelyMatches(n: Long) = "Resultados menos prováveis (${formatCount(n)})"

    override val moreDocuments = "Mais documentos"

    override val recordings = "Gravações"

    override val documentsAndMore = "Documentos e mais"

    // SearchScreen
    override val searchWithPhoto = "Buscar com uma foto"

    override val clearSearch = "Limpar busca"

    override val recentSearches = "Buscas recentes"

    override fun removeFromHistory(query: String) = "Remover $query do histórico"

    override val searchAction = "Buscar"

    override val clearHistory = "Limpar histórico"

    // SearchViewModel
    override val likeYourPhoto = "Parecido com sua foto"

    override val couldntOpenPhoto = "Não foi possível abrir esta foto. Escolha outra."

    override val thatDidntWork = "Não deu certo. Tente de novo."

    // SettingsScreen
    override val includedFilesFooter = "$kindOff Uma pasta ignorada fica fora da busca."

    override val onlyWhenYouChoose = "Só quando você escolher “$readFor15”."

    override val processingNotifications = "Notificações de processamento"

    override val notificationsOn = "Ativadas"

    override val notificationsOff = "Desativadas. O processamento continua; você só não vê quanto já foi feito."

    override val includedFiles = "Arquivos incluídos"

    override val noneExcluded = "Nenhuma"

    override fun includeNamed(folder: String) = "Incluir $folder"

    override val includeFolder = "Incluir pasta"

    override val privacy = "Privacidade"

    override val saveRecentSearches = "Salvar buscas recentes"

    override val savedOnDeviceShortcuts = "Salvas neste dispositivo. Também usadas nos atalhos de busca."

    override val clearRecentSearches = "Limpar buscas recentes"

    override val clearSearchesAndShortcutsAsk = "Limpar buscas recentes e atalhos?"

    override val clear = "Limpar"

    override val storage = "Armazenamento"

    override val appearance = "Aparência"

    override val wallpaperColours = "Cores do plano de fundo"

    override val wallpaperColoursOff = "Quando desativado, usa o tom de âmbar do Retriever."

    override val about = "Sobre"

    override val version = "Versão"

    override val openSourceNotices = "Avisos de código aberto"

    override val removeDownloadConfirm = "Remover download"

    override fun howItWorksBody(words: DeviceWords) = "${wholeDeviceNote(words)} ${onDevice(words)}. " +
        "A busca e o reconhecimento de texto podem precisar de um download pela internet. " +
        "O app que fornece seus arquivos também pode usar a internet para abrir arquivos na nuvem."

    override val howItWorksTimes =
        "Ele processa seus arquivos conforme a opção que você escolher em “$whenToRead”. Arquivos longos são processados aos poucos: " +
            "você pode encontrá-los primeiro pelo começo."

    override val runSetupCheck = "Verificar a configuração"

    override val tuningFewMinutes = "Ajustando… alguns minutos no máximo"

    override fun tuneFailedMessage(message: String) = "Falha no ajuste: $message"

    override val processingIsSetUp = "O processamento está configurado"

    override val adjustsProcessingSpeed = "Ajusta a velocidade de processamento a este celular."

    override val useMobileDataForDownloads = "Usar dados móveis para downloads"

    override val downloadsWaitForWifiShort = "Desativado: os downloads esperam o Wi-Fi."

    override val loading = "Carregando…"

    // SettingsViewModel
    override val couldntSaveChange = "Não foi possível salvar esta alteração. Tente de novo."

    override val searchDownloadRemoved = "Download da busca removido"

    // Starring
    override val starAdded = "Marcado com estrela"

    override val starRemoved = "Estrela removida"

    // StatusScreen
    override val checkingYourFiles = "Verificando seus arquivos…"

    override val hide = "Ocultar"

    override val reviewFiles = "Revisar arquivos"

    override val letProcessWhileAway = "Deixe o Retriever processar arquivos quando você não estiver usando o app"

    override fun batteryMayStop(chargingOnly: Boolean) = "Seu celular pode interromper o processamento para economizar bateria." +
        if (chargingOnly) " Ele continua processando arquivos só enquanto carrega." else ""

    override val settingsRowDetail = "O que processar, armazenamento e mais"

    override val chooseMorePhotos = "Escolher mais fotos"

    override val foldersYouAdded = "Pastas que você adicionou"

    override fun removeNamed(name: String) = "Remover $name"

    override val allowAccessToAllFiles = "Permitir acesso a todos os arquivos"

    // Format
    override fun starredScope(kind: String?) = kind?.let { "$it com estrela" } ?: starredFiles

    override fun pageBadge(page: Int) = "Página $page"

    // FileRow
    override fun openAtPage(page: Int) = "Abrir na página $page"

    override fun playFrom(time: String) = "Reproduzir a partir de $time"

    override fun kindSizeDate(kind: String, size: String, date: String) = "$kind · $size · $date"

    override fun fileRowSpoken(kind: String, name: String, date: String, position: String?, note: String?) =
        "$kind, $name, $date" + (position?.let { ", $it" } ?: "") + (note?.let { ", $it" } ?: "")

    // ReadingFiles
    override val fileActions = "Ações do arquivo"

    override fun fileSpoken(name: String, kind: String, date: String) = "$name, $kind, $date"

    // DocScreen
    override val notProcessedThisYet = "O Retriever ainda não processou este arquivo"

    override val waitingItsTurn = "Ele está esperando a vez junto com os outros arquivos novos. Abra em outro app para ver agora."

    override val noTextFound = "O Retriever não encontrou texto neste arquivo"

    override val scanOrPictures = "Pode ser um documento digitalizado ou ter só imagens. Abra em outro app para ver."

    override val couldntOpenFile = "Não foi possível abrir este arquivo"

    override val maybeMovedOrDeleted = "Ele pode ter sido movido ou excluído."

    override val previousMatch = "Resultado anterior"

    override fun matchOf(at: Int, total: Int) = "Resultado $at de $total"

    override fun matchCount(n: Long) = plural(n, "resultado")

    override val nextMatch = "Próximo resultado"

    // Opener
    override val noAppOpensFile = "Nenhum app neste celular abre este arquivo"

    // PdfPageScreen
    override fun pdfSubtitle(page: Int, count: Int, matchedPage: Int?) = (matchedPage?.let { "${matchOnPage(it)} · " } ?: "") + pageOf(page, count)

    override val closePage = "Fechar página"

    override val pdfNeedsPassword = "Este PDF precisa de senha"

    override val pdfFindableByName = "O Retriever ainda pode encontrá-lo pelo nome. Você pode abri-lo em outro app."

    override val folderAccessChanged = "O acesso a esta pasta mudou"

    override val chooseFolderAgain = "Escolha a pasta de novo para abrir este arquivo."

    override val fileNoLongerAvailable = "Este arquivo não está mais disponível"

    override val goBack = "Voltar"

    override val couldntOpenPdf = "Não foi possível abrir este PDF"

    override fun pageZoomed(page: Int) = "Página $page, ampliada"

    override fun pageOf(page: Int, count: Int) = "Página $page de $count"

    override val zoomPage = "Ampliar página"

    override fun pageSpoken(label: String, matched: Boolean) = label + if (matched) ", onde sua busca encontrou resultado" else ""

    override val matchedHere = "Encontrado aqui"

    // PhotoScreen
    override val couldntShowPhoto = "Não foi possível mostrar esta foto"

    // PlayerScreen
    override fun matchAt(time: String) = "Resultado em $time"

    override fun couldntPlay(audio: Boolean) = "Não foi possível reproduzir ${if (audio) "esta gravação" else "este vídeo"}"

    override val maybeMovedOrCantPlay = "Ele pode ter sido movido ou excluído, ou este celular não consegue reproduzi-lo."

    // ViewerQueue
    override fun positionOf(at: Int, count: Int) = "$at de $count"

    // ViewerTopBar
    override val previousFile = "Arquivo anterior"

    override val nextFile = "Próximo arquivo"

    // AppModel+Loop
    override fun keepProcessingFailed(words: DeviceWords) = "O iOS não conseguiu continuar o processamento enquanto você não usava o Retriever. " +
        "Mantenha-o aberto ou deixe-o processar seus arquivos enquanto seu ${device(words)} carrega."

    // AppModel+Settings
    override val couldntChangePersonCurly = "Não foi possível alterar esta pessoa. Tente de novo."

    // FolderSource
    override val aFolder = "Uma pasta"

    // SearchModel
    override val starredFiles = "arquivos com estrela"

    // Copy
    override val photosAccessOff = "O acesso às fotos está desativado"

    override val photosOffNoteDetail = "A busca ainda funciona nos arquivos já processados. Ative para buscar também nas suas fotos e capturas de tela."

    override val seesOnlyPickedPhotos = "O Retriever vê só as fotos que você escolheu"

    override val canSearchJustThose = "Ele pode buscar só nelas."

    override val folderAdded = "Pasta adicionada. O Retriever vai processar o que há dentro."

    override fun folderOverlaps(name: String) = "Essa pasta se sobrepõe a “$name”, onde o Retriever já busca."

    override val folderRefused = "O iOS não deixou o Retriever manter essa pasta. Tente outra."

    override fun backgroundNote(words: DeviceWords) =
        "O Retriever processa seus arquivos enquanto está aberto e, às vezes, quando você não está usando, se o iOS permitir. " +
            "O processamento automático espera seu ${device(words)} estar carregando. “$readFor15” usa um pouco de bateria."

    override val screenKeptOn = "Sua tela fica ligada enquanto o Retriever processa seus arquivos por 15 minutos. Você pode desativar isso nas Configurações."

    override fun showingLabels(labels: List<String>) = "Mostrando " + labels.joinToString(" · ")

    override val searchMyExactWords = "Buscar minhas palavras exatas"

    override fun processedSoFar(n: Long) = "${readCount(n)} até agora"

    // Format
    override fun opensAtPage(page: Int) = "Abre na página $page"

    override fun playsFrom(time: String) = "Reproduz a partir de $time"

    override val opensHint = "Abre"

    // BrowseView
    override val couldntLoadFiles = "Não foi possível carregar os arquivos"

    override val wentWrongReadingList = "Algo deu errado ao carregar a lista."

    override fun sizeFolderDate(size: String, folder: String, date: String) = "$size · $folder · $date"

    override val sortBy = "Ordenar por"

    override val sortMenu = "Ordenar"

    override val ok = "OK"

    // DuplicatesView
    override val noDuplicatesMessage =
        "O Retriever verificou os arquivos nas pastas que você adicionou e não encontrou cópias maiores que 1 MB. As fotos não são verificadas."

    override val lookingForCopies = "Procurando cópias"

    override val thisCanTakeAMinute = "Isso pode levar um minuto."

    override fun deleteFilesAsk(n: Long) = if (n == 1L) "Excluir 1 arquivo?" else "Excluir ${files(n)}?"

    override val cantBeRecovered = "Não será possível recuperar o que for excluído."

    override val comparesFilesNote = "Compara arquivos nas pastas que você adicionou, não fotos. A cópia mais antiga de cada arquivo é mantida. " +
        "Toque em uma cópia para mudar qual será excluída."

    override fun folderAndDate(folder: String, date: String) = "$folder · $date"

    override val selectedToDeleteHint = "Selecionado para excluir. Toque duas vezes para manter."

    override val keptHint = "Mantido. Toque duas vezes para selecionar para exclusão."

    override fun filesWerentDeleted(n: Long) = if (isOne(n)) "${files(n)} não foi excluído" else "${files(n)} não foram excluídos"

    // FileActions
    override val couldntOpenFolderUnavailable = "Não foi possível abrir este arquivo. A pasta dele pode estar indisponível."

    override val photosAppName = "Fotos"

    override val moreActions = "Mais ações"

    override val couldntFinish = "Não foi possível concluir"

    // FoldersView
    override val foldersWithFiles = "Pastas com arquivos"

    override val couldntLoadFolderList = "Não foi possível carregar a lista de pastas. Tente de novo"

    override val removeFolder = "Remover"

    override val cantOpenFolderAnyMore = "O Retriever não consegue mais abrir esta pasta. Remova e adicione de novo."

    override val alsoSearchesOwnFolder = "O Retriever também busca na própria pasta dele no app Arquivos. Remover uma pasta aqui nunca a exclui."

    override val couldntLoadFolders = "Não foi possível carregar as pastas"

    override val wentWrongReadingFolders = "Algo deu errado ao carregar suas pastas."

    override val foldersAppearFiles = "As pastas aparecem depois que o Retriever encontra seus arquivos."

    override fun stopSearchingForget(n: Long, name: String) = "O Retriever vai esquecer ${files(n)} em $name. Você pode adicionar a pasta de volta aqui."

    override fun removeFolderAsk(name: String) = "Remover $name?"

    override val removeFolderMessage =
        "O Retriever vai parar de buscar nela e esquecer o que processou ali. A pasta e os arquivos dela continuam onde estão."

    // HomeSections
    override val showsAll = "Mostra tudo"

    override val showsThem = "Mostra"

    override val hideOnThisDayIos = "Ocultar Neste dia"

    override fun homeTileSpoken(kind: String, name: String, date: String, starred: Boolean) = "$kind, $name, $date" + if (starred) ", com estrela" else ""

    // HomeView
    override val addFolderOrPhotos = "Adicione uma pasta ou deixe o Retriever ver suas fotos, e ele vai encontrar o que há dentro delas."

    override val allowPhotosAccess = "Permitir acesso às Fotos"

    override val turnOnPhotosAccess = "Ativar acesso às Fotos"

    override val onThisDayTitle = "Neste dia"

    override val openSettings = "Abrir Ajustes"

    override val alsoSearchesFolders = "O Retriever também busca nas pastas que você adicionar."

    // ReadingFiles
    override val openFileHint = "Abrir arquivo"

    // ResultViews
    override fun sharedPhotoName(ext: String?) = "Foto.${ext ?: "jpg"}"

    // RootView
    override val setupTitle = "Configuração"

    // SearchView
    override val docsShort = "Docs"

    override val searchByPhoto = "Buscar por foto"

    override val removeSearch = "Remover"

    override val removeFromRecentSearches = "Remover das buscas recentes"

    override val somethingWentWrongSearching = "Algo deu errado na busca."

    override val lookingEllipsis = "Procurando…"

    override fun turnOnToSearchScope(scope: String) = "Ative nos Ajustes e o Retriever também poderá buscar em $scope."

    override val noMatchesInPickedPhotos = "Nenhum resultado nas fotos que você escolheu"

    override val onlySeesChosenPhotos = "O Retriever só vê as fotos que você escolheu. Escolha mais para buscar em mais fotos."

    override val notQuiteReady = "Ainda não está pronto"

    override fun nothingLikeThatOn(words: DeviceWords) = "Nada parecido neste ${device(words)} ainda."

    override fun nothingInScopeFits(scope: String) = "Nada em $scope corresponde. Tente todos os seus arquivos."

    override fun noStarredMatch(query: String) = "Nenhum arquivo com estrela corresponde a “$query”"

    override val onlyStarredShowHere = "Só os arquivos marcados com estrela aparecem aqui. Tente todos os seus arquivos."

    override fun nothingLikePhotoIn(scope: String) = "Nada parecido com esta foto em $scope"

    override fun noMatchesFor(query: String) = "Nenhum resultado para “$query”"

    override val tryAllFilesInstead = "Tente todos os seus arquivos."

    override fun nothingInScopeMatches(scope: String) = "Nenhum resultado em $scope. Tente todos os seus arquivos."

    override val likeThisPhoto = "Parecido com esta foto"

    override val photosOffResults = "O acesso às fotos está desativado, então estes resultados não incluem suas fotos."

    // SettingsView
    override val cleared = "Limpo"

    override fun savedOnDeviceOnly(words: DeviceWords) = "Salvas só neste ${device(words)}. Nada é enviado."

    override val clearRecentSearchesAsk = "Limpar buscas recentes?"

    override fun privacyBody(words: DeviceWords) =
        "O Retriever processa seus arquivos neste ${device(words)}. Suas buscas, estrelas e o que ele encontra dentro dos arquivos ficam aqui. " +
            "Baixar a busca por descrição e abrir arquivos guardados no iCloud pode usar a internet."

    override val longFilesBitAtATime =
        "Arquivos longos são processados aos poucos: você pode encontrá-los primeiro pelo começo, e o restante vem depois."

    override val onlyWhenYouChooseAnyFile = "Só quando você escolher “$readFor15”. Você sempre pode encontrar qualquer arquivo pelo nome."

    override fun alsoOnBatteryIosBody(words: DeviceWords) = "Processa seus arquivos enquanto o Retriever está aberto, com a bateria acima de 20%. " +
        "Quando você não está usando, o iOS espera seu ${device(words)} estar carregando. " +
        "Pausa quando seu ${device(words)} está quente ou no Modo Pouca Energia."

    override val keepScreenOnToggle = "Manter a tela ligada ao processar por 15 minutos"

    override fun adjustProcessingFor(words: DeviceWords) = "Ajustar o processamento para este ${device(words)}"

    override val runSetupCheckAgain = "Verifique a configuração de novo."

    override val kindsFooterVideos = "$kindOff Vídeos: só as imagens, sem o som."

    override fun includeQuoted(name: String) = "Incluir “$name”"

    override val doesntLookInThese = "O Retriever não busca nestas pastas. Seus arquivos ficam onde estão."

    override val downloadsOnWifiOrData = "Downloads por Wi-Fi ou dados móveis."

    override val downloadsWaitForWifi = "Os downloads esperam o Wi-Fi, para não usar seus dados móveis."

    override val noticesUnavailable = "O Retriever é feito com software de código aberto. Não foi possível mostrar as licenças agora."

    // SetupView
    override val searchesForThis = "Busca por isto"

    // SplitRoot
    override val searchTab = "Buscar"

    // StatusView
    override val onlySeesPickedPhotos = "O Retriever só vê as fotos que você escolheu."

    override val photosOffCantSearch = "O acesso às fotos está desativado, então o Retriever não pode buscar nas suas fotos."

    // TextReader
    override val notProcessedOpenOriginal = "O Retriever ainda não processou este arquivo. Você pode abrir o original."

    override val noTextToShow = "Nenhum texto para mostrar"

    override val noReadableText = "O Retriever não encontrou texto legível neste arquivo."

    override val couldntShowText = "Não foi possível mostrar o texto"

    override val wentWrongReadingIt = "Algo deu errado ao abri-lo. Tente de novo daqui a pouco."

    override val textFromThisFile = "Texto deste arquivo"

    override val onlyBeginningIncluded = "Só o começo deste arquivo está incluído."

    // Viewer
    override fun matchOnPage(page: Int) = "Resultado na página $page"

    override val anotherAppCanAsk = "Outro app pode pedir a senha."

    override val damagedOrUnsupported = "O arquivo pode estar danificado ou em um formato que o Retriever não consegue mostrar."

    override fun couldntPlayIos(audio: Boolean) = if (audio) "Não foi possível reproduzir este áudio" else "Não foi possível reproduzir este vídeo"

    override val iCloudOrCantPlay = "Ele pode estar só no iCloud sem conexão, ou em um formato que o Retriever não consegue reproduzir."

    override fun photoSpoken(kind: String, name: String) = "$kind, $name"

    override val turnOnToSeePhotos = "Ative nos Ajustes para ver suas fotos aqui."

    override val onlyInICloudNow = "Ele pode estar só no iCloud, e não há conexão agora."

    override val downloadingFromICloud = "Baixando do iCloud"
}
