package app.retriever.core

import app.retriever.core.Words.NBSP

/**
 * Japanese. A machine translation awaiting native review: nothing here has been checked by a native speaker yet.
 * Reviewed by a second model (Codex).
 *
 * Polite です/ます in sentences; buttons and labels are short noun or verb forms. No plurals: counts take a counter
 * ([count]), directly after the number. Sentences end with 。 and are joined with no space ([sentenceGap]).
 * No spaces between Japanese text and Latin words or counters ("Retrieverは", "1,234件", "このiPhone").
 * Keep non-breaking spaces in formatted sizes, and preserve the OS labels and " · " separators.
 *
 * Glossary, one rendering each, for the reviewer to check:
 * - process → 処理(する); processed → 処理済み; processed in part → 一部処理済み; not processed yet → 未処理
 * - search by description (feature name) → 説明で検索, in 「」 inside a sentence
 * - found by name → 名前で検索可能; found by what's inside → 中身で検索可能; the File info row "Found by" → 検索方法
 * - phone → スマートフォン (iOS: the device name as given, "iPhone"/"iPad")
 * - Battery Saver → バッテリー セーバー (AOSP Settings `battery_saver`, values-ja); Low Power Mode → 低電力モード (iOS)
 * - Allow access to manage all files → 全ファイルの管理権を付与 (AOSP `permit_manage_external_storage`, values-ja);
 *   Battery → バッテリー, Unrestricted → 制限なし (AOSP `power_usage_summary_title`, `manager_battery_usage_unrestricted_title`)
 * - model (downloaded data) → 検索用データ; file provider → ファイルを提供するアプリ
 * - starred → スター付き; star (verb) → スターを付ける / スターを外す
 * - People (the feature) → 人物; On this day → 過去の今日; Home → ホーム; Settings → 設定; the iOS Files app → 「ファイル」アプリ
 * - document → ドキュメント (short scope button: 文書); photo → 写真; audio → 音声; video → 動画; folder → フォルダ; download → ダウンロード; mobile data → モバイルデータ
 * - counters: files and matches 件, photos and screenshots 枚, videos 本, folders 個, tries 回
 */
internal object Japanese : Strings {
    private const val PHONE = "スマートフォン"

    /** The device noun: "スマートフォン" on Android, the device's own name on iOS. */
    private fun dev(words: DeviceWords) = if (words.ios) words.device else PHONE

    /** The OS's own name for its power-saving mode, as Japanese Settings shows it. */
    private fun saver(words: DeviceWords) = if (words.ios) "低電力モード" else "バッテリー セーバー"

    /** "1,234件": a grouped count followed directly by its Japanese counter. */
    private fun count(n: Long, counter: String) = "${formatCount(n)}$counter"

    /** The counter for a kind of file: sheets for pictures, reels for videos, items for the rest. */
    private fun counter(kind: Kind) = when (kind) {
        Kind.PHOTO, Kind.SCREENSHOT -> "枚"
        Kind.VIDEO -> "本"
        else -> "件"
    }

    override val sentenceGap = ""

    override val modified = "更新日"
    override val taken = "撮影日"

    override fun files(n: Long) = "${count(n, "件")}のファイル"

    override fun photos(n: Long) = "${count(n, "枚")}の写真"

    override fun videos(n: Long) = "${count(n, "本")}の動画"

    override fun photosAndVideos(n: Long) = "写真と動画${count(n, "件")}"

    override fun readOf(done: Long, total: Long) = "ファイル${count(total, "件")}中${count(done, "件")}を処理済み"

    override fun meaningOf(done: Long, total: Long) = "ファイル${count(total, "件")}中${count(done, "件")}が「説明で検索」に対応"

    override fun facesOf(done: Long, total: Long) = "写真${count(total, "枚")}中${count(done, "枚")}で人物を確認済み"

    override val processedInPart = "一部処理済み"
    override val couldntBeOpened = "開けませんでした"
    override val stillToProcess = "未処理"
    override val longFilesStillBeingFinished = "残りを処理中の長いファイル"
    override val checkedForPeople = "人物を確認済み"
    override val stillToCheckForPeople = "人物の確認待ち"

    override fun largeFiles(n: Long) = "100${NBSP}MBを超える${files(n)}"

    override fun readCount(read: Long) = "${files(read)}を処理済み"

    override fun partlyReadCount(n: Long) = "${count(n, "件")}は一部処理済み"

    override fun extent(extent: ReadExtent?) = when (extent) {
        ReadExtent.FIRST_PART -> "一部処理済み · 冒頭のみ"
        ReadExtent.MEANING_LIMITED -> "一部処理済み · 「説明で検索」の処理を完了できませんでした"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "一部処理済み · 冒頭のみ、「説明で検索」なし"
        ReadExtent.FIRST_PART_DEEPENING -> "一部処理済み · 今のところ冒頭のみ"
        ReadExtent.DEEPENING -> "一部処理済み · 「説明で検索」はこれから"
        ReadExtent.WHOLE, null -> null
    }

    override val couldntProcessLabel = "処理できませんでした"
    override val notProcessedYet = "未処理"
    override val partRestToCome = "一部処理済み · 残りはこれから"
    override val partCouldntFinish = "一部処理済み · 完了できませんでした"

    override fun kindLabel(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "ドキュメント"
        Kind.SCREENSHOT -> "スクリーンショット"
        Kind.PHOTO -> "写真"
        Kind.AUDIO -> "音声"
        Kind.VIDEO -> "動画"
        Kind.OTHER -> "ファイル"
    }

    override fun kindLabelPlural(kind: Kind) = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "ドキュメント"
        Kind.SCREENSHOT -> "スクリーンショット"
        Kind.PHOTO -> "写真"
        Kind.AUDIO -> "音声"
        Kind.VIDEO -> "動画"
        Kind.OTHER -> "その他のファイル"
    }

    override fun kindsInSentence(kind: Kind) = kindLabelPlural(kind)

    override fun spokenPage(page: Int) = "${formatCount(page.toLong())}ページ目"

    override fun spokenTime(hours: Long, minutes: Long, seconds: Long): String {
        val parts = listOf(hours to "時間", minutes to "分", seconds to "秒").filter { it.first > 0 }.map { (n, unit) -> "${formatCount(n)}$unit" }
        return parts.joinToString("").ifEmpty { "0秒" } + "の位置"
    }

    override fun textOnlyNote(ramGb: Int, words: DeviceWords) =
        "この${dev(words)}のメモリは${formatCount(ramGb.toLong())}${NBSP}GBで、「説明で検索」には足りません。そのため、Retrieverはファイルを名前と中身の言葉で見つけます。"

    override fun notReadYet(files: Long) = "残り${count(files, "件")}"

    override fun couldntProcess(files: Long) = "${files(files)}を処理できませんでした"

    override val details = "詳細"
    override val starting = "準備しています…"
    override val processingNow = "処理中"
    override val recentlyProcessed = "最近処理したファイル"
    override val processingYourFiles = "ファイルを処理しています"
    override val pause = "一時停止"
    override val processTheseKinds = "処理するファイルの種類"

    override val continueAction = "続ける"
    override val tryAgain = "再試行"
    override val download = "ダウンロード"
    override val notNow = "後で"
    override val cancel = "キャンセル"
    override val done = "完了"

    override fun helloTitle(textOnly: Boolean) = if (textOnly) "ファイルを中身で見つけましょう" else "説明するだけで、何でも見つかります"

    override val helloBody = "写真、スクリーンショット、ドキュメント、動画をまとめて検索。"
    override val forExample = "たとえば"
    override val helloExamples = listOf(
        Words.HelloExample("大家さんから届いたWi-Fiのパスワード", "3月14日のスクリーンショット", "「Wi-Fiパスワード：…」と書かれています"),
        Words.HelloExample("ソファで寝ている犬", "先週日曜日の写真", "写っているもので見つかりました", needsModel = true),
        Words.HelloExample("賃貸契約書", "賃貸契約2025.pdf", "3ページ目、署名済みのもの"),
    )
    override val downloads = "ダウンロード"

    override fun onDevice(words: DeviceWords) = "ファイルはお使いの${dev(words)}上で処理されます"

    override val getStarted = "始める"

    override fun downloadNote(size: String) = "初回のみ、Wi-Fi接続時に${size}をダウンロードします。"

    override fun downloadLine(size: String) = "初回のみ${size}をダウンロード（Wi-Fiのみ）"

    override val accessTitle = "Retrieverで検索する対象を選択"
    override val accessBody = "1つ以上選んでください。あとから追加できます。"
    override val allow = "許可"
    override val allowAccess = "アクセスを許可"
    override val choose = "選択"
    override val chooseMore = "さらに選択"
    override val settings = "設定"
    override val documents = "ドキュメント"
    override val includesScreenshots = "スクリーンショットを含む"
    override val allowed = "許可済み"
    override val somePhotos = "一部の写真を選択済み"
    override val photosOff = "オフになっています。設定でオンにしてください。"
    override val pickFolders = "「ファイル」アプリで、ダウンロードなどのフォルダを選択"

    override fun foldersAdded(n: Long) = "フォルダを${count(n, "個")}追加しました"

    override val androidAccessBody = "写真、ドキュメント、ダウンロードしたファイルを、説明するだけで見つけられるようになります。"
    override val accessScanning = "Retrieverが写真やファイルのスキャンを開始し、検索できるようにします。"
    override val startScanning = "スキャンを開始"
    override val allFilesOff = "スマートフォン全体を検索するには「全ファイルの管理権を付与」をオンにするか、対象を選んでください。"
    override val includeTitle = "対象を選択"
    override val sharedFilesAndFolders = "このスマートフォン内のファイルとフォルダ"
    override val photosAndVideosLabel = "写真と動画"
    override val addFolder = "フォルダを追加"
    override val nothingSelected = "まだファイルが選択されていません。写真かフォルダを選んで始めましょう。"

    override fun wholeDeviceNote(words: DeviceWords) = "Retrieverはこの${dev(words)}にあるファイルを処理し、中身から見つけられるようにします。"

    override val firstLook = "まずは最近のファイルから"

    override fun firstSearchHint(state: DescriptionState) = if (state == DescriptionState.READY) "写真やファイルを説明してください" else "名前や言葉で検索"

    override fun descriptionLine(state: DescriptionState, percent: Int?) = when (state) {
        DescriptionState.DOWNLOADING -> "「説明で検索」をダウンロード中" + (percent?.let { " · $it%" } ?: "")
        DescriptionState.WAITING_FOR_WIFI -> "「説明で検索」のダウンロードはWi-Fi接続を待っています。"
        DescriptionState.PREPARING -> "「説明で検索」を準備中"
        DescriptionState.FAILED -> "「説明で検索」のダウンロードが停止しました。"
        DescriptionState.NO_SPACE -> "空き容量が足りないため、「説明で検索」をダウンロードできません。"
        DescriptionState.READY, DescriptionState.NOT_STARTED, DescriptionState.UNAVAILABLE -> null
    }

    override val descriptionReady = "「説明で検索」が使えるようになりました"
    override val descriptionReadyBody = "最近のファイルを説明して、探してみましょう。"

    override val homeIntroTitle = "このあとの流れ"
    override val homeIntroBody = "開始したスキャンは、ほかのアプリを使っている間も続きます。通知から一時停止できます。"
    override val gotIt = "OK"
    override val newFiles = "新しい写真とファイル"
    override val newFilesBody = "新しいファイルは、設定で選んだタイミングで処理されます。"
    override val notifyWhenSearchable = "すべて検索できるようになったら通知する"
    override val caughtUpNoticeTitle = "すべて検索できるようになりました"

    override fun caughtUpNotice(files: Long, textOnly: Boolean) = "Retrieverが${files(files)}をすべて処理しました。" +
        if (textOnly) "どれも名前や中身の言葉で見つけられます。" else "何でも説明するだけで見つかります。"

    override fun recentFiles(n: Long) = when (n) {
        0L -> "最近のファイル"
        1L -> "最新のファイル"
        else -> "最近のファイル${count(n, "件")}"
    }

    override fun otherFiles(rest: Long?) = when (rest) {
        null -> "残りのファイル"
        else -> "残りの${files(rest)}"
    }

    override fun recentFilesBody(state: DescriptionState) = when (state) {
        DescriptionState.READY -> "説明して見つけられます。"

        DescriptionState.UNAVAILABLE -> "名前や言葉で見つけられます。「説明で検索」には、メモリの多いスマートフォンが必要です。"

        DescriptionState.WAITING_FOR_WIFI -> "今は名前や言葉で見つけられます。初回のみのダウンロードを完了するため、Wi-Fi接続を待っています。"

        DescriptionState.FAILED -> "今は名前や言葉で見つけられます。ダウンロードが停止しました。"

        DescriptionState.NO_SPACE -> "今は名前や言葉で見つけられます。空き容量が足りないため、「説明で検索」をダウンロードできません。"

        // Not "one-time": after a chip build fails on its first run, this is the second download.
        DescriptionState.NOT_STARTED -> "今は名前や言葉で見つけられます。「説明で検索」にはダウンロードが必要です。"

        DescriptionState.DOWNLOADING, DescriptionState.PREPARING ->
            "今は名前や言葉で見つけられます。準備が終わると「説明で検索」が使えるようになります。"
    }

    override fun recentFilesAction(state: DescriptionState, size: String) = when (state) {
        DescriptionState.WAITING_FOR_WIFI -> "モバイルデータを使用（$size）"
        DescriptionState.FAILED -> tryAgain
        DescriptionState.NOT_STARTED -> "ダウンロード（$size）"
        else -> null
    }

    override val demoPicking = "最近のファイルを選んでいます…"
    override val demoHint = "最近の写真、スクリーンショット、ドキュメントで覚えている言葉を入力してください。"

    override fun demoSearchScope(done: Long, finished: Boolean, words: DeviceWords) = when {
        finished -> "${dev(words)}内のすべてのファイルを名前で検索し、デモ用の${count(done, "件")}のファイルは中身も検索します。"

        // The first frame: nothing processed yet, so no "0 demo files".
        done == 0L -> "${dev(words)}内のすべてのファイルを名前で検索します。"

        else -> "${dev(words)}内のすべてのファイルを名前で検索します。これまでに処理したデモ用の${count(done, "件")}のファイルは中身も検索します。"
    }

    override fun seeAll(files: Long) = if (files == 1L) "ファイルを表示" else "${files(files)}をすべて表示"

    override val read = "中身で検索可能"
    override val foundByName = "名前で検索可能"
    override val foundBy = "検索方法"

    override fun kindRead(read: Long, planned: Long, kind: Kind) = "${kindLabelPlural(kind)}${count(planned, counter(kind))}中${count(read, counter(kind))}を処理済み"

    override val tryLabel = "たとえば："

    override val startProcessing = "処理を開始"

    override val progressChannel = "処理の進み具合"
    override val progressChannelBody = "処理の進み具合を表示し、デモが終わったときに一度だけお知らせします。"
    override val demoDoneNoticeTitle = "デモが完了しました"

    override fun demoDoneNotice(done: Long, planned: Long) = "${readOf(done, planned)}。Retrieverを開いて検索してみましょう。"

    override val searchField = "何を探していますか？"
    override val nameMatches = "名前が一致"
    override val textMatches = "テキストが一致"
    override val searchingContents = "ファイルの中身を検索しています…"

    override fun copies(n: Int) = "${count(n.toLong(), "件")}のコピー"

    override fun openCopyIn(folder: String) = "${folder}内のコピーを開く"

    override val noMatches = "見つかりませんでした"

    override fun noMatchesDetail(byMeaning: Boolean) = (if (byMeaning) "中身を説明するか、書かれている内容について尋ねてみてください。" else "含まれている言葉や、名前の一部で試してみてください。") +
        "「先週」のように時期を加えることもできます。"

    override val moreWaiting = "Retrieverはまだファイルを処理中です。しばらくしてからもう一度検索してください。"
    override val searchFailed = "検索を完了できませんでした"
    override val showingNameMatches = "名前が一致したものを表示しています"
    override val searchingWithoutModel = "名前と処理済みのテキストを検索しています"

    override fun matches(labels: List<String>) = "一致：" + labels.joinToString("、")

    private val weekdayNames = listOf("月曜日", "火曜日", "水曜日", "木曜日", "金曜日", "土曜日", "日曜日")

    override fun dateLabel(date: DateFilter) = when (date) {
        DateFilter.Today -> "今日"

        DateFilter.Yesterday -> "昨日"

        is DateFilter.DaysAgo -> "${formatCount(date.days.toLong())}日前"

        is DateFilter.Period -> when (date.unit) {
            PeriodUnit.WEEK -> if (date.last) "先週" else "今週"
            PeriodUnit.MONTH -> if (date.last) "先月" else "今月"
            PeriodUnit.YEAR -> if (date.last) "去年" else "今年"
        }

        is DateFilter.Weekday -> when (date.which) {
            Which.LAST -> "この前の"
            Which.THIS -> "直近の"
            Which.BARE -> ""
        } + weekdayNames[date.day]

        is DateFilter.Month -> (date.year?.let { "${it}年" } ?: "") + "${date.month}月"

        is DateFilter.Day -> (date.year?.let { "${it}年" } ?: "") + "${date.month}月${date.day}日"

        is DateFilter.Year -> "${date.year}年"
    }

    override fun noneFound(kinds: List<Kind>, people: List<String>, date: DateFilter?): String {
        val what = kinds.joinToString("や", transform = ::kindLabelPlural).ifEmpty { "ファイル" }
        val lead = listOfNotNull(date?.let { dateLabel(it) + "の" }, people.takeIf { it.isNotEmpty() }?.joinToString("と")?.let { "${it}の" }).joinToString("")
        return "$lead${what}は見つかりませんでした"
    }

    override fun relaxedNote(missing: String, scope: String?) = "$missing。代わりに" + if (scope == null) "すべてを表示しています。" else "${scope}をすべて表示しています。"

    override fun filterQuery(kind: Kind, date: DateFilter) = "${dateLabel(date)}の${kindLabelPlural(kind)}"

    override val downloadForDescription = "「説明で検索」をダウンロード"

    override val readingStatus = "処理"
    override val recentlyAdded = "最近追加"
    override val moreOnPhone = "ファイル一覧"
    override val allFolders = "すべてのフォルダ"

    override val howItWorks = "Retrieverのしくみとプライバシー"
    override val whenToRead = "処理するタイミング"
    override val whatItCanSee = "Retrieverがアクセスできる範囲"
    override val foldersToSkip = "スキップするフォルダ"
    override val kindOff = "オフ：名前で検索できますが、処理した中身の情報は消去されます。ファイル自体はそのまま残ります。"
    override val showOnThisDay = "「過去の今日」を表示（過去の同じ日付の写真）"

    override val people = "人物"
    override val recognizePeople = "人物を認識"
    override val peopleOfferTitle = "人物の写真を探す"
    override val peopleOfferBody =
        "Retrieverは写真の顔をグループにまとめられます。名前を付ければ、その名前で探せます。顔はこのスマートフォン上で処理され、外部に送られることはありません。"
    override val turnOn = "オンにする"
    override fun wholeDeviceTitle(words: DeviceWords) = "${dev(words)}全体を検索"
    override val wholeDeviceBody = "ダウンロード、WhatsApp、ほかのアプリのフォルダにあるファイルも見つかります。"
    override val allowAllFiles = "すべてのファイルへのアクセスを許可"
    override val changeWhatsIncluded = "対象を変更"
    override val allFilesChoice = "すべてのファイル"
    override val recommended = "おすすめ"
    override val allFilesChoiceBody = "写真、書類、ダウンロード、ほかのアプリのファイル。いちばんよく見つかります。"
    override val orOnly = "または次だけ"
    override fun peopleChoiceBody(words: DeviceWords) = "顔をグループ分けして、名前で検索できるようにします。この${dev(words)}の中だけで処理されます。"
    override fun peopleRowBody(words: DeviceWords) = "写っている人で写真を探せます。ファイルの処理のあとに、この${dev(words)}の中で顔をグループ分けします。"
    override val recognizePeopleDetail =
        "写真の顔をグループにまとめ、名前を付けて探せるようにします。このスマートフォン上で処理され、バックアップされることはありません。"
    override val forgetPeopleTitle = "顔のグループと名前をすべて消去しますか？"
    override val forgetPeopleBody = "Retrieverがまとめた顔のグループと、付けた名前を消去します。写真はそのまま残ります。"
    override val forget = "消去"
    override val notThisPerson = "この人ではない"
    override val addAName = "名前を追加"
    override val name = "名前"
    override val save = "保存"
    override val samePersonAs = "同じ人物を選択…"
    override val samePersonAsk = "同じ人物ですか？"
    override val samePersonYes = "同じ人物"
    override val samePersonNo = "別の人物"
    override val samePersonPrevious = "前のペア"
    override val samePersonNext = "次のペア"

    override fun pairOf(at: Int, of: Int) = "${formatCount(at.toLong())} / ${formatCount(of.toLong())}"

    override fun facesToGo(left: Long) = "人物の確認まで、あと${photos(left)}"

    override val retrieversData = "Retrieverのデータ"
    override val temporaryFiles = "一時ファイル"

    override fun keptOn(size: String, words: DeviceWords) = "$size · この${dev(words)}に保存"

    override val searchByMeaning = "説明で検索"
    override val removeDownload = "検索用ダウンロードを削除"
    override val removeDownloadAsk = "検索用ダウンロードを削除しますか？"
    override val namesStillWork = "ファイル名と処理済みのテキストは、引き続き検索できます。"

    override fun downloaded(size: String) = "ダウンロード済み · $size"

    override fun removeDownloadMessage(size: String?) = (if (size != null) "${size}の空き容量が増えます。" else "") + "$namesStillWork「説明で検索」を使うには、もう一度ダウンロードが必要です。"

    override val freeUpSpace = "空き容量を増やす"
    override val largeFilesTitle = "大きなファイル"
    override val findDuplicates = "重複ファイルを探す"
    override val neverDeletes = "Retrieverが確認なしに何かを削除することはありません。"

    override fun possibleCopies(n: Long) = "${files(n)}が重複している可能性があります"

    override fun deleteFrom(words: DeviceWords) = "${dev(words)}から削除"

    override fun deleteConfirm(words: DeviceWords) = "このファイルをお使いの${dev(words)}から削除します。"

    override val upgradeTitle = "説明するだけでファイルを見つける"
    override val waitingForWifi = "Wi-Fi接続を待ってダウンロードします"
    override val useMobileData = "モバイルデータを使用"
    override val waitForWifi = "Wi-Fiを待つ"
    override val checkingDownload = "ダウンロードを確認しています…"
    override val downloadFailed = "ダウンロードが中断されました。接続を確認して、もう一度お試しください。"
    override val noSpace = "空き容量が足りないため、ダウンロードできません"
    override val tuneLeaving = "今は名前で検索できます。戻ってくると準備を再開します。"

    override fun upgradeBody(size: String, words: DeviceWords) = "Wi-Fi接続時に${size}を1回ダウンロードします。" +
        "その後は「夕暮れの海辺」のように検索すると、その言葉がなくても写真が見つかります。データはすべてこの${dev(words)}内にとどまります。"

    override fun downloadProgress(done: String, total: String, percent: Int) = "$done / $total · $percent%"

    override fun mobileDataAsk(size: String) = "モバイルデータで${size}をダウンロードしますか？"

    override fun freeUp(size: String) = "続けるには、空き容量を${size}増やしてください"

    override fun tuneTitle(words: DeviceWords) = "この${dev(words)}に合わせて処理を準備しています"

    override fun tuneBody(words: DeviceWords) = "1分ほどかかります。この${dev(words)}に合った処理速度を探しています。"

    override fun tuneFailed(words: DeviceWords) = "この${dev(words)}に合わせた処理の準備を完了できませんでした。"

    override fun addingMeaning(done: Long, total: Long) = "「説明で検索」を追加中 · ファイル${count(total, "件")}中${count(done, "件")}"

    override val meaningCatchingUp = "「説明で検索」が使えるようになりました。準備ができる前に処理したファイルにも、Retrieverが順次追加していきます。"

    override fun scheduleTitle(schedule: ReadingSchedule) = when (schedule) {
        ReadingSchedule.CHARGING -> "充電中"
        ReadingSchedule.BATTERY -> "充電していないときも"
        ReadingSchedule.MANUAL -> "開始したときだけ"
    }

    override fun statusAction(action: StatusAction) = when (action) {
        StatusAction.CONTINUE -> continueAction
        StatusAction.ALLOW_ACCESS -> allowAccess
        StatusAction.STOP -> "停止"
        StatusAction.RESUME -> "再開"
        StatusAction.DISMISS -> "閉じる"
        StatusAction.TRY_AGAIN -> tryAgain
        StatusAction.START -> startProcessing
        StatusAction.PAUSE -> pause
    }

    override fun stopCopy(stop: StopReason, words: DeviceWords) = when (stop) {
        StopReason.PAUSED -> "一時停止中"
        StopReason.POWER_SAVE -> "一時停止 · ${saver(words)}をオフにすると続行します"
        StopReason.HOT -> "一時停止 · ${dev(words)}が冷えると続行します"
        StopReason.LOW_BATTERY, StopReason.UNPLUGGED -> "一時停止 · 充電すると続行します"
        StopReason.CHARGING_UP -> "一時停止 · バッテリーがもう少し充電されると続行します"
        StopReason.MANUAL -> "開始したときだけ処理します"
        StopReason.DISK_FULL -> "${dev(words)}の容量がいっぱいのため一時停止中"
        StopReason.DONE, StopReason.BUDGET -> "少し休憩しています"
    }

    override val noAccessTitle = "Retrieverがファイルにアクセスできません"

    override fun noAccessDetail(words: DeviceWords) = "処理済みのファイルは引き続き検索できます。" + if (words.ios) "${dev(words)}の「設定」が開きます。" else ""

    override val finishSetupTitle = "準備を完了しましょう"
    override val finishSetupDetail = "Retrieverで検索する対象を選んでください。"
    override val downloadPausedTitle = "ダウンロードを一時停止中"

    override fun downloadPausedDetail(words: DeviceWords, size: String) = "Wi-Fiに接続すると再開します。${dev(words)}の容量がいっぱいの場合は、${size}の空き容量が必要です。"

    override fun downloadingTitle(percent: Int?) = "「説明で検索」をダウンロード中" + (percent?.let { " · $it%" } ?: "")

    override val downloadingDetail = "Retrieverは引き続き使えます。"

    override val addingDescriptionTitle = "「説明で検索」を追加中"
    override val allFilesProcessedTitle = "すべてのファイルを処理しました"
    override val gettingReadyTitle = "準備しています…"
    override val notNowTitle = "今のところ名前でだけ見つけられます"

    override val lookingForFacesTitle = "写真の中の人物を見つけています"
    override val deepeningTitle = "一部の長いファイルの残りを処理しています"
    override val lookingTitle = "新しいファイルを確認しています…"
    override val caughtUpTitle = "完了。Retrieverは処理できるものをすべて処理しました"

    override val processingReadyDetail = "進捗は保存されています。今すぐ開始するか、条件が整うと自動的に処理が続きます。"
    override val keepGoingInBackground = "バックグラウンドで続ける"
    override val processingAdaptiveDetail = "スマートフォンの操作が快適になるように処理速度を調整します。"
    override val processingBatteryOverrideDetail = "処理はバッテリー残量${ReadingPolicy.RESUMED_BATTERY_FLOOR}%まで続きます。"

    override val processingKeepPaused = "一時停止を続ける"
    override val processingSearchAvailableDetail = "今すぐ検索できます。ファイル名と処理済みの内容を検索できます。"
    override val processingPlugInDetail = "処理を続けるには充電してください。Retrieverはバッテリー残量${ReadingPolicy.RESUMED_BATTERY_FLOOR}%まで処理します。"
    override val processingTapToContinue = "タップして続行"

    override val automaticChargingPreferenceDetail = "スキャンとスキャンの間は充電を待ちます。「再開」で、この処理中はバッテリーでも処理を始められます。"

    override val automaticProcessingPreferenceTitle = "処理するタイミング"
    override val automaticProcessingPreferenceDetail =
        "スキャンとスキャンの間は、バッテリー残量${ReadingPolicy.BATTERY_FLOOR}%未満で処理を止めます。開始または再開した処理は${ReadingPolicy.RESUMED_BATTERY_FLOOR}%まで続きます。"

    override val pausedDetail = "検索は引き続き使えます。"

    override fun powerSaveDetail(words: DeviceWords) = "${saver(words)}がオフになると再開します。"

    override val hotDetail = "自動で再開します。"
    override val lowBatteryDetail = "充電するとRetrieverが再開します。"
    override val chargingUpDetail = "バッテリーがもう少し充電されると処理を始めます。"

    override fun chargingDetail(words: DeviceWords) = "${dev(words)}の充電中にRetrieverが再開します。"

    override val manualDetail = "どのファイルも名前で見つけられます。"

    override fun meaningDetail(words: DeviceWords) = "意味や、写真に写っているもので検索できるようにします。この${dev(words)}上だけで行います。"

    override val peopleDownloadDetail = "「人物」のための初回のみのダウンロードです。すべてのファイルをすでに検索できます。"
    override val facesDetail = "任意の追加ステップです。すべてのファイルをすでに検索でき、もう一度処理されるものはありません。"

    override fun facesWaitingDetail(words: DeviceWords) = "${dev(words)}が許可したときに、Retrieverが人物を見つけます。すべてのファイルをすでに検索できます。"

    override val deepeningDetail = "冒頭部分で見つけられます。"
    override val limitedDetail = "いくつかの長いファイルは一部のみ処理しました。冒頭部分で見つけられます。"
    override val readingMeaningDetail = "「説明で検索」も追加しています。"

    override fun readingDetail(words: DeviceWords) = if (words.ios) {
        "検索はいつでも使えます。Retrieverは、開いている間とiOSが許可したときに、ファイルを処理します。"
    } else {
        "Retrieverを閉じても処理は続きます。検索はいつでも使えます。"
    }

    override fun waitingLongFilesDetail(words: DeviceWords) = "${dev(words)}が許可したときに、Retrieverが一部の長いファイルの残りを処理します。検索は今すぐ使えます。"

    override fun waitingMeaningDetail(words: DeviceWords) = "${dev(words)}が許可したときに、Retrieverが「説明で検索」を追加します。検索は今すぐ使えます。"

    override val diskFullDetail = "空き容量を増やすと、Retrieverが再開します。"

    override fun waitingDetail(words: DeviceWords) = "Retrieverを再開するタイミングは${dev(words)}が決めます。データが失われることはありません。"

    override val caughtUpDetail = "新しいファイルは、設定で選んだタイミングで処理されます。"

    override val gettingReadersDetail = "Google Play 開発者サービスから一度だけダウンロードします。名前での検索はすぐに使えます。"

    override val peopleWaitsForWifi = "「人物」のダウンロードはWi-Fi接続を待っています。"

    override fun deepeningLead(left: Long) = "${notReadYet(left)}。"

    override fun longFilesWait(n: Long) = "すべてのファイルを処理しました。長いファイル${count(n, "件")}の残りは待機中です。"

    override val allFilesProcessedLead = "すべてのファイルを処理しました。"

    override val foundByOther = "名前。この種類のファイルは、Retrieverでまだ処理できません。"
    override val foundByBeginningRestPending = "名前と冒頭部分。残りはまだ処理されていません。"
    override val foundByPartCouldntFinish = "名前と中身の一部。Retrieverは処理を完了できませんでした。"
    override val foundByBeginning = "名前と冒頭部分"
    override val foundByInsideMeaningLimited = "名前と中身。「説明で検索」の処理は完了できませんでした。"
    override val foundByInsideDeepening = "名前と中身。「説明で検索」はこれから追加されます。"
    override val foundByInside = "名前と中身"
    override val foundByReadingOff = "名前。この種類のファイルは処理がオフになっています。"

    override fun foundBySkipped(tries: Long) = "名前。${count(tries, "回")}試しましたが、Retrieverは処理できませんでした。"

    override val foundByNothingInside = "名前。中身は処理できませんでした。"
    override val foundByNotYet = "名前。まだ処理されていません。"

    override val processingFailed = "ファイルの処理中に問題が発生しました。Retrieverがもう一度試します。"

    // FileActions
    override val fileNameField = "ファイル名"

    // Notifications
    override fun notificationBody(count: String?, detail: String?, read: Long) = listOfNotNull(count, detail).joinToString("。").ifEmpty { readCount(read) }

    // SearchResults
    override fun understoodChip(labels: List<String>) = labels.joinToString(" · ")

    override fun noMatchesMessage(byMeaning: Boolean, waiting: Boolean, wordsOnly: Boolean) =
        listOfNotNull(noMatchesDetail(byMeaning), moreWaiting.takeIf { waiting }, searchingWithoutModel.takeIf { wordsOnly }?.let { "$it。" }).joinToString("")

    override fun reasonLine(parts: List<String>) = parts.joinToString(" · ")

    // SearchScreen
    override val appSettings = "設定"

    // SettingsScreen
    override fun scheduleSummary(title: String, body: String) = "$title。$body"

    override val sizePending = "…"

    // FirstRunStages
    override fun folderNames(names: List<String>) = names.joinToString(" · ")

    // AppModel+Setup
    override fun kindsReadSpoken(parts: List<String>) = parts.joinToString("、")

    // SearchModel
    override val scopePhotos = "写真"

    override val scopeDocuments = "ドキュメント"

    // HomeView
    override fun titleDetailSpoken(title: String, detail: String?) = listOfNotNull(title, detail).joinToString("、")

    // ResultViews
    override fun resultRowSpoken(kind: String, name: String, date: String, position: String?, extent: String?, reason: String?) =
        listOfNotNull(kind, name, date, position, extent, reason).joinToString("、")

    // Access
    override val onlyPickedPhotos = "選択した写真のみ"

    override val musicAndRecordings = "音楽と録音"

    override fun notCoveredMedia(photos: Boolean, videos: Boolean, music: Boolean) = listOfNotNull(
        "写真".takeIf {
            photos
        },
        "動画".takeIf { videos },
        "音楽".takeIf { music },
    ).let { m ->
        "対象外：" + m.joinToString(if (m.size <= 2) "と" else "、").ifEmpty { "なし" }
    }

    override fun notCoveredDocuments(hasFolders: Boolean) = if (hasFolders) "対象外：追加したフォルダ以外のドキュメントとダウンロード" else "対象外：ドキュメントとダウンロード"

    override val pickFolderOnPhone = "このスマートフォンのストレージにあるフォルダを選んでください。"

    override val alreadySearchesFolder = "そのフォルダはすでにRetrieverの検索対象です。"

    /** "写真、動画、フォルダ2個" */
    private fun granted(photos: Boolean, videos: Boolean, music: Boolean, folders: Int) = listOfNotNull(
        "写真".takeIf { photos },
        "動画".takeIf { videos },
        "音楽".takeIf { music },
        "フォルダ${count(folders.toLong(), "個")}".takeIf { folders > 0 },
    ).let { m -> m.joinToString(if (m.size <= 2) "と" else "、") }

    override fun canSeeOnly(photos: Boolean, videos: Boolean, music: Boolean, folders: Int) = "Retrieverが見られるのは${granted(photos, videos, music, folders)}だけです。"

    override fun seesPartOf(photos: Boolean, videos: Boolean, music: Boolean, folders: Int, words: DeviceWords) =
        "${granted(photos, videos, music, folders)}のみ（${dev(words)}全体ではありません）"

    override fun seesWholeDevice(words: DeviceWords) = "${dev(words)}全体"

    override fun searchedOnly(photos: Boolean, videos: Boolean, music: Boolean, folders: Int) =
        "${granted(photos, videos, music, folders)}だけを検索しました。ダウンロードやほかのアプリのファイルは含まれていません。"

    // AccessService
    override val couldntKeepFolder = "そのフォルダへのアクセスを維持できませんでした。もう一度選んでください。"

    // BrowseScreen
    override val allFiles = "すべてのファイル"

    override val filesRetrieverCanAccess = "Retrieverがアクセスできるファイル"

    override val sortLargestDetail = "大きい順"

    override val sortNameDetail = "名前順（A〜Z）"

    override val sortNewestDetail = "新しい順 · 更新日"

    override val couldntChangePerson = "この人物を変更できませんでした。もう一度お試しください。"

    override val back = "戻る"
    override val home = "ホーム"
    override val fullScreen = "全画面表示"
    override val showBesideList = "リストの横に表示"
    override val searchYourFiles = "ファイルを検索"

    override val couldntLoadYourFiles = "ファイル一覧を表示できませんでした"

    override val wentWrongReadingThem = "ファイル一覧を表示できませんでした。もう一度お試しください。"

    override val keepUsefulFilesClose = "よく使うファイルをすぐ手元に"

    override val tapStarToFindHere = "ファイルのスターをタップすると、ここに表示されます。"

    override val chooseFilesToSearch = "検索するファイルを選択"

    override val addPhotosOrFolder = "写真かフォルダを追加してください。"

    override val manageAccess = "アクセスを管理"

    override val nothingHereYet = "まだ何もありません"

    override fun filesShowUpHere(words: DeviceWords) = "Retrieverが${dev(words)}内でファイルを見つけると、ここに表示されます。"

    override val moreOptions = "その他のオプション"

    override val couldntLoadMoreFiles = "続きのファイルを表示できませんでした"

    override val sortNewest = "新しい順"

    override val sortLargest = "大きい順"

    override val sortName = "名前"

    // BrowseViewModel
    override val couldntLoadTheseFiles = "これらのファイルを表示できませんでした。"

    // PersonActions
    override val rename = "名前を変更"

    // DuplicatesScreen
    override val duplicates = "重複"

    override val keepOneCopy = "各ファイルを少なくとも1つ残してください"

    override fun filesCouldntBeDeleted(n: Long) = "${files(n)}を削除できませんでした"

    override fun deleteSelected(n: Long, size: String) = "選択した${count(n, "件")}を削除（$size）"

    override val lookingForCopiesEllipsis = "重複ファイルを探しています…"

    override val couldntLookForDuplicates = "重複を探せませんでした"

    override val noDuplicatesFound = "重複は見つかりませんでした"

    override fun comparedFiles(n: Long) = "Retrieverは${files(n)}を比較しました。"

    override fun couldBeFreed(size: String) = "${size}の空き容量を確保できます"

    override fun copiesEach(n: Long, size: String) = "${count(n, "件")}のコピー · 各$size"

    override fun openNamed(name: String) = "「$name」を開く"

    override val deselect = "選択を解除"

    override val select = "選択"

    override val deleteFilesTitle = "ファイルを削除しますか？"

    override fun deleteFilesMessage(n: Long) = "${files(n)}を削除しますか？この操作は元に戻せません。"

    override val delete = "削除"

    // DuplicatesViewModel
    override val somethingWentWrongTryAgain = "問題が発生しました。もう一度お試しください。"

    // FileActions
    override val addToStarred = "スターを付ける"

    override val share = "共有"

    override val openInAnotherApp = "別のアプリで開く"

    override val fileInfo = "ファイル情報"

    override val removeFromStarred = "スターを外す"

    override val topLevel = "最上位"

    override val couldntChangeStar = "スターの状態を変更できませんでした。もう一度お試しください。"

    override val findSimilar = "似たものを探す"

    override val couldntFinishThat = "完了できませんでした。ファイルが移動されたか、アクセス権が変わった可能性があります。"

    override val renameFile = "ファイル名を変更"

    override val deleteThisFile = "このファイルを削除しますか？"

    // FileInfo
    override fun kindAndSize(kind: String, size: String) = "$kind · $size"

    override val locationLabel = "場所"

    override val hideFullPath = "フルパスを隠す"

    override val showFullPath = "フルパスを表示"

    override val locationDetails = "場所の詳細"

    override val pathClipLabel = "パス"

    override val copyPath = "パスをコピー"

    override val dimensionsLabel = "画像サイズ"

    override fun dimensions(width: Int, height: Int) = "$width × $height"

    override val durationLabel = "長さ"

    override val pagesLabel = "ページ数"

    override val internalStorage = "内部ストレージ/"

    // FoldersScreen
    override val folders = "フォルダ"

    override val foldersDidntLoad = "フォルダ一覧を表示できませんでした"

    override val noFoldersYet = "フォルダはまだありません"

    override val tryAgainInAMoment = "しばらくしてからもう一度お試しください。"

    override val foldersAppearPhone = "Retrieverがスマートフォン内でファイルを見つけると、フォルダも表示されます。"

    override fun stopSearchingAsk(name: String) = "「$name」の検索をやめますか？"

    override fun stopSearchingMessage(n: Long, name: String) = "「$name」内の${files(n)}は今すぐ検索対象から外れます。元に戻すと、すべてもう一度処理することになります。" +
        "元に戻すには、ホーム上部のステータス行から操作できます。"

    override val stopSearching = "検索をやめる"

    override fun filesInParent(n: Long, parent: String?) = if (parent == null) files(n) else "${files(n)} · $parent"

    override fun moreOptionsFor(name: String) = "「$name」のその他のオプション"

    override val stopSearchingThisFolder = "このフォルダの検索をやめる"

    // FoldersViewModel
    override val couldntLoadYourFolders = "フォルダ一覧を表示できませんでした。しばらくしてからもう一度お試しください。"

    override val couldntStopSearchingFolder = "そのフォルダの検索をやめられませんでした。しばらくしてからもう一度お試しください。"

    // HomeScreen
    override val onThisDayHidden = "「過去の今日」を非表示にしました"

    override val undo = "元に戻す"

    override val showProcessingDetails = "処理の詳細を表示"

    override val lookingForYourFiles = "ファイルを探しています"

    override val nothingToSearchYet = "まだ検索できるものがありません"

    override val takesAMinute = "1分ほどかかります。見つかったファイルから、すぐに名前で検索できます。"

    override val saveAPdfHint = "PDFを保存したり、スクリーンショットや写真を撮ったりすると、ここに表示されます。"

    // HomeSections
    override val showAll = "すべて表示"

    override fun sectionSpoken(title: String, subtitle: String) = "$title、$subtitle"

    override val openAction = "開く"

    override fun personTileSpoken(name: String, photos: Long) = "$name、${photos(photos)}"

    override fun browseTileSpoken(label: String, count: String) = "$label、$count"

    override val onThisDay = "過去の今日"

    override fun onThisDaySpoken(year: Int, count: String, date: String) = "${year}年、${date}の$count"

    override val showThem = "表示"

    override val hideOnThisDay = "「過去の今日」を非表示"

    override val starredSection = "スター付き"

    // ModelDownload
    override val searchModelDownloadTitle = "Retrieverの検索用データ"

    // ModelRepository
    override val downloadStopped = "ダウンロードが停止しました。接続を確認して、もう一度お試しください。"

    override val downloadDamaged = "ダウンロードしたデータが破損しています。もう一度お試しください。"

    override fun needsFreeSpace(gb: String) = "$gb${NBSP}GBの空き容量が必要です。空き容量を増やしてから、もう一度お試しください。"

    // SearchResults
    override fun likeFile(name: String) = "「$name」に似たもの"

    override val photoYoureSearchingWith = "検索に使っている写真"

    override val stopSearchingByPhoto = "写真での検索をやめる"

    override val searchWordsAsTyped = "入力どおりに検索"

    override val photoSearchAfterSetup = "写真での検索は、準備が終わると使えるようになります。"

    override val matchingExactWordsForNow = "今は言葉が完全に一致するものを表示しています。「説明で検索」の結果はまもなく表示されます。"

    override fun noMatchesIn(scope: String) = "${scope}の中には見つかりませんでした"

    override val nothingLikePhotoThere = "そこには、この写真に似たものはありません。"

    override fun nothingForThere(query: String) = "そこには「$query」に一致するものはありません。"

    override val searchAllFiles = "すべてのファイルを検索"

    override val nothingLikeThisYet = "似たものはまだありません"

    override val tryAnotherPhoto = "別の写真で試してください。"

    override val tryFewerWords = "言葉を減らす"

    override val filterAll = "すべて"

    override val filterStarred = "スター付き"

    override val expanded = "展開済み"

    override val collapsed = "折りたたみ済み"

    override fun lessLikelyMatches(n: Long) = "関連性の低い結果（${formatCount(n)}）"

    override val moreDocuments = "その他のドキュメント"

    override val recordings = "録音"

    override val documentsAndMore = "ドキュメントなど"

    // SearchScreen
    override val searchWithPhoto = "写真で検索"

    override val clearSearch = "検索をクリア"

    override val recentSearches = "最近の検索"

    override fun removeFromHistory(query: String) = "「$query」を履歴から削除"

    override val searchAction = "検索"

    override val clearHistory = "履歴を消去"

    // SearchViewModel
    override val likeYourPhoto = "選んだ写真に似たもの"

    override val couldntOpenPhoto = "この写真を開けませんでした。別の写真を選んでください。"

    override val thatDidntWork = "うまくいきませんでした。もう一度お試しください。"

    // SettingsScreen
    override val includedFilesFooter = "${kindOff}スキップするフォルダは検索の対象外になります。"

    override val onlyWhenYouChoose = "処理を開始または再開したときだけ処理します。"

    override val showProcessingProgress = "処理の進み具合を表示"

    override val processingProgressNotice = "Retrieverがファイルを処理している間、音の鳴らない通知を1件表示して、進み具合を確認できます。"

    override val notificationsOn = "オン"

    override val includedFiles = "対象のファイル"

    override val noneExcluded = "なし"

    override fun includeNamed(folder: String) = "「$folder」を対象に含める"

    override val includeFolder = "フォルダを含める"

    override val privacy = "プライバシー"

    override val saveRecentSearches = "最近の検索を保存"

    override val savedOnDeviceShortcuts = "このデバイスに保存されます。検索のショートカットにも使われます。"

    override val clearRecentSearches = "最近の検索を消去"

    override val clearSearchesAndShortcutsAsk = "最近の検索とショートカットを消去しますか？"

    override val clear = "消去"

    override val storage = "ストレージ"

    override val appearance = "外観"

    override val wallpaperColours = "壁紙の色"

    override val wallpaperColoursOff = "オフにすると、Retrieverの暖かみのある琥珀色を使います。"

    override val about = "このアプリについて"

    override val version = "バージョン"

    override val openSourceNotices = "オープンソースのライセンス"

    override val removeDownloadConfirm = "ダウンロードを削除"

    override fun howItWorksBody(words: DeviceWords) = "${wholeDeviceNote(
        words,
    )}${onDevice(
        words,
    )}。検索と文字認識に必要なデータを、インターネットからダウンロードする場合があります。ファイルを提供するアプリも、クラウド上のファイルを開くときにインターネットを使うことがあります。"

    override val howItWorksTimes = "「$automaticProcessingPreferenceTitle」で選んだタイミングでファイルを処理します。長いファイルは少しずつ処理されるため、" +
        "まず冒頭部分で見つけられるようになります。"

    override val runSetupCheck = "準備チェックを実行"

    override val tuningFewMinutes = "調整中…最大で数分"

    override fun tuneFailedMessage(message: String) = "調整に失敗しました：$message"

    override val processingIsSetUp = "処理の準備ができています"

    override val adjustsProcessingSpeed = "このスマートフォンに合わせて処理速度を調整します。"

    override val useMobileDataForDownloads = "ダウンロードにモバイルデータを使用"

    override val downloadsWaitForWifiShort = "オフ：ダウンロードはWi-Fi接続を待ちます。"

    override val loading = "読み込んでいます…"

    // SettingsViewModel
    override val couldntSaveChange = "この変更を保存できませんでした。もう一度お試しください。"

    override val searchDownloadRemoved = "検索用ダウンロードを削除しました"

    // Starring
    override val starAdded = "スターを付けました"

    override val starRemoved = "スターを外しました"

    // StatusScreen
    override val checkingYourFiles = "ファイルを確認しています…"

    override val hide = "非表示"

    override val reviewFiles = "ファイルを確認"

    override val letProcessWhileAway = "Retrieverを使っていないときも処理を続ける"

    override fun batteryMayStop(chargingOnly: Boolean) =
        "バッテリーを節約するため、スマートフォンが処理を止めることがあります。" + sentenceGap + if (chargingOnly) automaticChargingPreferenceDetail else automaticProcessingPreferenceDetail

    override val settingsRowDetail = "処理する対象、ストレージなど"

    override val chooseMorePhotos = "写真をさらに選択"

    override val foldersYouAdded = "追加したフォルダ"

    override fun removeNamed(name: String) = "「$name」を対象から外す"

    override val allowAccessToAllFiles = "すべてのファイルへのアクセスを許可"

    // Format
    override fun starredScope(kind: String?) = kind?.let { "スター付きの$it" } ?: starredFiles

    override fun pageBadge(page: Int) = "${formatCount(page.toLong())}ページ"

    // FileRow
    override fun openAtPage(page: Int) = "${formatCount(page.toLong())}ページ目で開く"

    override fun playFrom(time: String) = "${time}から再生"

    override fun kindSizeDate(kind: String, size: String, date: String) = "$kind · $size · $date"

    override fun fileRowSpoken(kind: String, name: String, date: String, position: String?, note: String?) =
        "$kind、$name、$date" + (position?.let { "、$it" } ?: "") + (note?.let { "、$it" } ?: "")

    // ReadingFiles
    override val fileActions = "ファイルの操作"

    override fun fileSpoken(name: String, kind: String, date: String) = "$name、$kind、$date"

    // DocScreen
    override val notProcessedThisYet = "Retrieverはまだこのファイルを処理していません"

    override val waitingItsTurn = "ほかの新しいファイルと一緒に順番を待っています。今すぐ見るには、別のアプリで開いてください。"

    override val noTextFound = "このファイルにはテキストが見つかりませんでした"

    override val scanOrPictures = "スキャンした書類か、画像だけのファイルかもしれません。別のアプリで開いて確認してください。"

    override val couldntOpenFile = "このファイルを開けませんでした"

    override val maybeMovedOrDeleted = "移動または削除された可能性があります。"

    override val previousMatch = "前の一致箇所"

    override fun matchOf(at: Int, total: Int) = "一致箇所${formatCount(at.toLong())} / ${formatCount(total.toLong())}"

    override fun matchCount(n: Long) = "一致：${count(n, "件")}"

    override val nextMatch = "次の一致箇所"

    // Opener
    override val noAppOpensFile = "このファイルを開けるアプリが、このスマートフォンにありません"

    // PdfPageScreen
    override fun pdfSubtitle(page: Int, count: Int, matchedPage: Int?) = (matchedPage?.let { "${matchOnPage(it)} · " } ?: "") + pageOf(page, count)

    override val closePage = "ページを閉じる"

    override val pdfNeedsPassword = "このPDFにはパスワードが必要です"

    override val pdfFindableByName = "名前では引き続き見つけられます。別のアプリで開くこともできます。"

    override val folderAccessChanged = "このフォルダへのアクセスが変更されました"

    override val chooseFolderAgain = "このファイルを開くには、もう一度フォルダを選んでください。"

    override val fileNoLongerAvailable = "このファイルは利用できなくなりました"

    override val goBack = "戻る"

    override val couldntOpenPdf = "このPDFを開けませんでした"

    override fun pageZoomed(page: Int) = "${formatCount(page.toLong())}ページ目、拡大表示"

    override fun pageOf(page: Int, count: Int) = "${formatCount(count.toLong())}ページ中${formatCount(page.toLong())}ページ目"

    override val zoomPage = "ページを拡大"

    override fun pageSpoken(label: String, matched: Boolean) = label + if (matched) "、検索に一致した箇所" else ""

    override val matchedHere = "ここで一致"

    // PhotoScreen
    override val couldntShowPhoto = "この写真を表示できませんでした"

    // PlayerScreen
    override fun matchAt(time: String) = "${time}で一致"

    override fun couldntPlay(audio: Boolean) = "この${if (audio) "録音" else "動画"}を再生できませんでした"

    override val maybeMovedOrCantPlay = "移動または削除されたか、このスマートフォンでは再生できない可能性があります。"

    // ViewerQueue
    override fun positionOf(at: Int, count: Int) = "${formatCount(at.toLong())} / ${formatCount(count.toLong())}"

    // ViewerTopBar
    override val previousFile = "前のファイル"

    override val nextFile = "次のファイル"

    // AppModel+Loop
    override fun keepProcessingFailed(words: DeviceWords) = "Retrieverを使っていない間、iOSで処理を続けられませんでした。Retrieverを開いたままにするか、${dev(words)}の充電中に処理するよう設定してください。"

    // AppModel+Settings
    override val couldntChangePersonCurly = "この人物を変更できませんでした。もう一度お試しください。"

    // FolderSource
    override val aFolder = "フォルダ"

    // SearchModel
    override val starredFiles = "スター付きのファイル"

    // Copy
    override val photosAccessOff = "写真へのアクセスがオフです"

    override val photosOffNoteDetail = "処理済みのファイルは引き続き検索できます。写真とスクリーンショットも検索するには、オンにしてください。"

    override val seesOnlyPickedPhotos = "Retrieverがアクセスできるのは、選択した写真だけです"

    override val canSearchJustThose = "検索できるのは、それらの写真だけです。"

    override val folderAdded = "フォルダを追加しました。Retrieverが中身を処理します。"

    override fun folderOverlaps(name: String) = "そのフォルダは、すでにRetrieverが検索している「$name」と重なっています。"

    override val folderRefused = "iOSにより、そのフォルダへのアクセスを保存できませんでした。別のフォルダを試してください。"

    override fun backgroundNote(words: DeviceWords) =
        "Retrieverは開いている間にファイルを処理します。使っていない間も、iOSが許可したときに処理することがあります。バックグラウンドでの自動処理は、${dev(words)}を充電するまで待ちます。処理を開始または再開すると、バッテリーを少し使います。"

    override val screenKeptOn = "Retrieverがファイルを処理している間、画面はオンのままになります。これは設定でオフにできます。"

    override fun showingLabels(labels: List<String>) = "表示中：" + labels.joinToString(" · ")

    override val searchMyExactWords = "入力どおりに検索"

    override fun processedSoFar(n: Long) = "これまでに${readCount(n)}"

    // Format
    override fun opensAtPage(page: Int) = "${formatCount(page.toLong())}ページ目で開きます"

    override fun playsFrom(time: String) = "${time}から再生します"

    override val opensHint = "開きます"

    // BrowseView
    override val couldntLoadFiles = "ファイル一覧を表示できませんでした"

    override val wentWrongReadingList = "ファイル一覧を表示できませんでした。"

    override fun sizeFolderDate(size: String, folder: String, date: String) = "$size · $folder · $date"

    override val sortBy = "並べ替え"

    override val sortMenu = "並べ替え"

    override val ok = "OK"

    // DuplicatesView
    override val noDuplicatesMessage = "追加したフォルダ内のファイルを比較しましたが、1 MBを超える重複ファイルは見つかりませんでした。写真は比較の対象外です。"

    override val lookingForCopies = "重複ファイルを探しています"

    override val thisCanTakeAMinute = "1分ほどかかることがあります。"

    override fun deleteFilesAsk(n: Long) = "${files(n)}を削除しますか？"

    override val cantBeRecovered = "復元することはできません。"

    override val comparesFilesNote = "写真ではなく、追加したフォルダ内のファイルを比較します。各ファイルの最も古いコピーが残ります。" +
        "削除するものを変えるには、コピーをタップしてください。"

    override fun folderAndDate(folder: String, date: String) = "$folder · $date"

    override val selectedToDeleteHint = "削除対象として選択済みです。残すにはダブルタップします。"

    override val keptHint = "残します。削除対象に選ぶにはダブルタップします。"

    override fun filesWerentDeleted(n: Long) = "${files(n)}を削除できませんでした"

    // FileActions
    override val couldntOpenFolderUnavailable = "このファイルを開けませんでした。フォルダを利用できない可能性があります。"

    override val photosAppName = "写真"

    override val moreActions = "その他の操作"

    override val couldntFinish = "完了できませんでした"

    // FoldersView
    override val foldersWithFiles = "ファイルのあるフォルダ"

    override val couldntLoadFolderList = "フォルダ一覧を表示できませんでした。再試行"

    override val removeFolder = "対象から外す"

    override val cantOpenFolderAnyMore = "Retrieverでこのフォルダを開けなくなりました。いったん検索対象から外し、もう一度追加してください。"

    override val alsoSearchesOwnFolder = "Retrieverは「ファイル」アプリ内の専用フォルダも検索します。ここでフォルダを対象から外しても、フォルダ自体はそのまま残ります。"

    override val couldntLoadFolders = "フォルダ一覧を表示できませんでした"

    override val wentWrongReadingFolders = "フォルダ一覧を表示できませんでした。"

    override val foldersAppearFiles = "Retrieverがファイルを見つけると、フォルダも表示されます。"

    override fun stopSearchingForget(n: Long, name: String) = "「$name」内の${files(n)}を検索対象から外し、処理した情報を消去します。ここから再び検索対象に追加できます。"

    override fun removeFolderAsk(name: String) = "「$name」を検索対象から外しますか？"

    override val removeFolderMessage = "このフォルダを検索対象から外し、処理した中身の情報を消去します。フォルダとファイルはそのまま残ります。"

    // HomeSections
    override val showsAll = "すべて表示します"

    override val showsThem = "表示します"

    override val hideOnThisDayIos = "「過去の今日」を非表示"

    override fun homeTileSpoken(kind: String, name: String, date: String, starred: Boolean) = "$kind、$name、$date" + if (starred) "、スター付き" else ""

    // HomeView
    override val addFolderOrPhotos = "フォルダを追加するか、Retrieverに写真へのアクセスを許可すると、中身から見つけられるようになります。"

    override val allowPhotosAccess = "写真へのアクセスを許可"

    override val turnOnPhotosAccess = "写真へのアクセスをオンにする"

    override val onThisDayTitle = "過去の今日"

    override val openSettings = "設定を開く"

    override val alsoSearchesFolders = "Retrieverは追加したフォルダも検索します。"

    // ReadingFiles
    override val openFileHint = "ファイルを開きます"

    // ResultViews
    override fun sharedPhotoName(ext: String?) = "写真.${ext ?: "jpg"}"

    // RootView
    override val setupTitle = "準備"

    // SearchView
    override val docsShort = "文書"

    override val searchByPhoto = "写真で検索"

    override val removeSearch = "削除"

    override val removeFromRecentSearches = "最近の検索から削除"

    override val somethingWentWrongSearching = "検索中に問題が発生しました。"

    override val lookingEllipsis = "探しています…"

    override fun turnOnToSearchScope(scope: String) = "設定でオンにすると、Retrieverで${scope}も検索できます。"

    override val noMatchesInPickedPhotos = "選択した写真の中には見つかりませんでした"

    override val onlySeesChosenPhotos = "Retrieverがアクセスできるのは、選択した写真だけです。もっと検索するには、写真を追加で選んでください。"

    override val notQuiteReady = "まだ準備ができていません"

    override fun nothingLikeThatOn(words: DeviceWords) = "この${dev(words)}には、まだそれに似たものはありません。"

    override fun nothingInScopeFits(scope: String) = "${scope}には該当するものがありません。すべてのファイルで試してください。"

    override fun noStarredMatch(query: String) = "「$query」に一致するスター付きのファイルはありません"

    override val onlyStarredShowHere = "ここにはスターを付けたファイルだけが表示されます。すべてのファイルで試してください。"

    override fun nothingLikePhotoIn(scope: String) = "${scope}には、この写真に似たものはありません"

    override fun noMatchesFor(query: String) = "「$query」に一致するものはありません"

    override val tryAllFilesInstead = "代わりに、すべてのファイルで試してください。"

    override fun nothingInScopeMatches(scope: String) = "${scope}には一致するものがありません。すべてのファイルで試してください。"

    override val likeThisPhoto = "この写真に似たもの"

    override val photosOffResults = "写真へのアクセスがオフのため、この結果には写真が含まれていません。"

    // SettingsView
    override val cleared = "消去しました"

    override fun savedOnDeviceOnly(words: DeviceWords) = "この${dev(words)}にのみ保存されます。どこにも送信されません。"

    override val clearRecentSearchesAsk = "最近の検索を消去しますか？"

    override fun privacyBody(words: DeviceWords) =
        "Retrieverはこの${dev(words)}上でファイルを処理します。検索内容、スター、ファイルの中から見つけた内容はここにとどまります。「説明で検索」のダウンロードや、iCloudに保存されたファイルを開くときに、インターネットを使用することがあります。"

    override val longFilesBitAtATime = "長いファイルは少しずつ処理されます。まず冒頭部分で見つけられるようになり、残りはあとから続きます。"

    override val onlyWhenYouChooseAnyFile = "処理を開始または再開したときだけ処理します。 ファイル名ではいつでも検索できます。"

    override val keepScreenOnToggle = "処理中は画面をオンのままにする"

    override fun adjustProcessingFor(words: DeviceWords) = "この${dev(words)}に合わせて処理を調整"

    override val runSetupCheckAgain = "準備チェックをもう一度実行します。"

    override val kindsFooterVideos = "${kindOff}動画：映像のみで、音声は含みません。"

    override val photosLibrary = "写真ライブラリ"

    override val foldersFromFiles = "「ファイル」アプリのフォルダ"

    override val addFoldersFooter = "「ファイル」アプリから追加したフォルダ内のPDFやドキュメントを検索できるようになります。"

    override val searchDocumentsToo = "ドキュメントも検索"

    override val searchDocumentsTooBody =
        "「ファイル」アプリから「ダウンロード」やiCloud Driveなどのフォルダを追加すると、その中のPDFやドキュメントもRetrieverで検索できます。"

    override val downloadsOnWifiOrData = "Wi-Fiまたはモバイルデータでダウンロードします。"

    override val downloadsWaitForWifi = "ダウンロードはWi-Fi接続を待つので、モバイルデータは使いません。"

    override val noticesUnavailable = "Retrieverはオープンソースのソフトウェアで作られています。現在、そのライセンスを表示できません。"

    // SetupView
    override val searchesForThis = "この言葉で検索します"

    // SplitRoot
    override val searchTab = "検索"

    // StatusView
    override val onlySeesPickedPhotos = "Retrieverがアクセスできるのは、選択した写真だけです。"

    override val photosOffCantSearch = "写真へのアクセスがオフのため、Retrieverは写真を検索できません。"

    // TextReader
    override val notProcessedOpenOriginal = "Retrieverはまだこのファイルを処理していません。代わりに元のファイルを開くことができます。"

    override val noTextToShow = "表示するテキストがありません"

    override val noReadableText = "このファイルには、表示できるテキストが見つかりませんでした。"

    override val couldntShowText = "テキストを表示できませんでした"

    override val wentWrongReadingIt = "テキストを表示できませんでした。しばらくしてからもう一度お試しください。"

    override val textFromThisFile = "このファイルのテキスト"

    override val onlyBeginningIncluded = "このファイルの冒頭部分だけを表示しています。"

    // Viewer
    override fun matchOnPage(page: Int) = "${formatCount(page.toLong())}ページ目に一致"

    override val anotherAppCanAsk = "別のアプリで開くと、パスワードを入力できる場合があります。"

    override val damagedOrUnsupported = "破損しているか、Retrieverが表示できない形式の可能性があります。"

    override fun couldntPlayIos(audio: Boolean) = if (audio) "この音声を再生できませんでした" else "この動画を再生できませんでした"

    override val iCloudOrCantPlay = "iCloudにのみ保存されていて接続がないか、Retrieverが再生できない形式の可能性があります。"

    override fun photoSpoken(kind: String, name: String) = "$kind、$name"

    override val turnOnToSeePhotos = "ここに写真を表示するには、設定でオンにしてください。"

    override val onlyInICloudNow = "iCloudにのみ保存されていて、現在接続がない可能性があります。"

    override val downloadingFromICloud = "iCloudからダウンロード中"
}
