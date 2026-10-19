import Foundation
import Photos
import RetrieverCore
import UIKit

/// Home's first row: how reading is going, or what stops Retriever seeing files.
struct HomeStatus: Equatable {
    enum Phase { case settingUp, photosOff, photosLimited, reading, looking, paused, waiting, caughtUp }

    var phase: Phase
    var title: String
    var detail: String?
    var progress: Double?
}

/// Every sentence the shell shows, in plain words from the person's side. Ported from Android's StatusScreen and
/// SetupScreen copy; "phone" reads "iPhone" or "iPad" here. Main-actor, as UIDevice is; the background task's
/// lines are nonisolated.
@MainActor
enum Copy {
    /// What the person holds, as they'd call it.
    static let device = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"

    static func stop(_ stop: StopReason?) -> String? {
        guard let stop else { return nil }
        let progress = ReadingProgress(
            read: 0, partlyRead: 0, scheduled: 1, notScheduled: 0, nameOnly: 0, couldntRead: 0, laterPasses: 0, sample: false, rereads: 0, meaningWaiting: 0)
        return ReadingPolicy.shared.decide(progress: progress, blocker: stop, running: false, reading: false, ios: true).title
    }
    static func waiting(_ stop: StopReason?) -> String? { self.stop(stop) }

    static func readCount(_ s: AppModel.Snap) -> String {
        "\(count(s.indexed)) \(s.indexed == 1 ? "file" : "files") read" + (s.partlyRead > 0 ? " · \(count(s.partlyRead)) partly read" : "")
    }

    static func decision(_ s: AppModel.Snap) -> ReadingStatus {
        ReadingPolicy.shared.decide(
            progress: s.readProgress, blocker: s.paused ? .paused : s.lastStop, running: s.running, reading: s.reading.current != nil, ios: true)
    }

    static func statusTitle(_ s: AppModel.Snap) -> String { s.readingTitle ?? decision(s).title }

    static func homeStatus(_ s: AppModel.Snap?, download: ModelDownload.State = .idle, progress: Double = 0, nameOnly: Int64 = 0) -> HomeStatus? {
        guard let s, !(s.readProgress.libraryTotal == 0 && s.setUp == true) else { return nil }
        if s.setUp != true { return HomeStatus(phase: .settingUp, title: "Finishing setup. You can search file names now.") }
        let phase = s.readingPhase ?? decision(s).phase
        let homePhase: HomeStatus.Phase =
            phase == .paused
            ? .paused
            : (phase == .caughtUp || phase == .limited)
                ? .caughtUp : phase == .looking ? .looking : (s.readingActive || decision(s).active) ? .reading : .waiting
        return HomeStatus(phase: homePhase, title: statusTitle(s), detail: s.readingDetail ?? decision(s).detail)
    }

    /// Shown under the status row while Photos access is off or limited; it never takes the status row's place, so
    /// reading progress and Resume stay on Home.
    static func photosNote(_ photos: PHAuthorizationStatus) -> HomeStatus? {
        switch photos {
        case .denied, .restricted:
            HomeStatus(
                phase: .photosOff, title: "Photos access is off",
                detail: "Search still works on files already read. Turn it on to search your photos and screenshots too.")
        case .limited:
            HomeStatus(phase: .photosLimited, title: "Retriever sees only the photos you picked", detail: "It can search just those.")
        default: nil
        }
    }

    /// One kind's line under its name in Your Files: how far its reading is while files wait, nothing once all is read.
    static func kindProgress(_ kind: Kind, total: Int, pending: Int, reading: Bool, readInside: Bool = true) -> String? {
        if kind == .other || !readInside { return "Found by name" }
        guard reading else { return nil }
        if pending == 0 { return "Ready" }
        return pending >= total ? "Waiting" : "\(count(pending)) to go"
    }

    /// "Today", "Yesterday" or the date, in the person's language.
    static func day(_ ms: Int64) -> String {
        dayFormatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.doesRelativeDateFormatting = true
        return f
    }()

    static func largeFiles(_ n: Int64) -> String {
        n == 1 ? "1 file over 100 MB" : "\(count(Int(n))) files over 100 MB"
    }

    nonisolated static func count(_ n: Int) -> String {
        n.formatted(.number)
    }

    nonisolated static func bytes(_ n: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
    }

    static func eta(_ minutes: Int) -> String {
        switch minutes {
        case ..<1: "Less than a minute left"
        case 1: "About a minute left"
        case ..<60: "About \(minutes) minutes left"
        case ..<90: "About an hour left"
        case ..<(48 * 60): "About \((minutes + 30) / 60) hours left"
        default: "About \((minutes + 12 * 60) / (24 * 60)) days left"
        }
    }

    static func addFolderResult(_ result: FolderSource.AddResult) -> String {
        switch result {
        case .added: "Folder added. Retriever will read what's inside."
        case .overlaps(let name): "That folder overlaps “\(name)”, which Retriever already searches."
        case .failed: "iOS wouldn't let Retriever keep that folder. Try another one."
        }
    }

    static func tuneSummary(_ backend: Backend, imageMs: Double?) -> String {
        let chip = backend == Backend.gpu ? "the graphics chip" : "the processor"
        let speed = imageMs.map { String(format: " · about %.1f s per photo", $0 / 1000) } ?? ""
        return "Using \(chip)\(speed)"
    }

    static let backgroundNote =
        "Retriever reads while it’s open and in background time provided by iOS. Automatic reading waits for charging. A short reading session can use battery. Background timing is up to iOS; plugging in doesn’t guarantee an immediate start."

    static let screenKeptOn = "Keep screen awake is on for this session. You can turn it off in Reading settings."

    static func deepReading(_ n: Int) -> String {
        "\(count(n)) partly read · more meaning search to come"
    }

    static func textOnlyNote(ramGb: Double) -> String {
        "This \(device) has \(Int(ramGb.rounded())) GB of memory, too little to understand photos or meaning, so Retriever finds files by their names and the words inside them."
    }

    static let textOnlySearch = "Retriever finds files here by their names and the words inside them."

    /// What the search made of the words: "Showing Screenshots · Last week".
    static func understood(_ labels: [String]) -> String { "Showing \(labels.joined(separator: " · "))" }
    static let exactWords = "Search my exact words"
    static let star = "Add to Starred"
    static let removeStar = "Remove from Starred"

    static let lessLikely = "Less likely matches"
    static func nothingWell(_ q: String) -> String { "Nothing matches “\(q)” well" }
    static let closestFiles = "These are the closest files, but they may not be what you're looking for."
    static let closestFilesShort = "These are the closest files."

    static func extent(_ extent: ReadExtent?) -> String? {
        switch extent {
        case ReadExtent.firstPart: "Partly read · only the beginning is included"
        case ReadExtent.meaningLimited: "Partly read · couldn’t finish meaning search"
        case ReadExtent.firstPartMeaningLimited: "Partly read · only the beginning is included; couldn’t finish meaning search"
        case ReadExtent.firstPartDeepening: "Partly read · only the beginning is included; more meaning search to come"
        case ReadExtent.deepening: "Partly read · more meaning search to come"
        default: nil
        }
    }

    static let keepReading = "Read now for 15 minutes"
    static let keepReadingNote = "Can continue after you leave if iOS allows."

    /// The Lock Screen's progress for a read that carries on after the person leaves.
    nonisolated static let continuedTitle = "Reading your files"

    nonisolated static func continuedSubtitle(_ files: Int) -> String {
        "\(count(files)) files read this session"
    }

    // MARK: Home sections

    /// A section header's second line: the count, then how far its reading is while files wait ("1,716 · 1,200 to go").
    static func sectionSubtitle(_ n: Int, _ detail: String? = nil) -> String {
        [count(n), detail].compactMap { $0 }.joined(separator: " · ")
    }

    /// "22,000 photos · 1,733 videos"; a kind with none is left out.
    static func mediaSubtitle(photos: Int, videos: Int) -> String {
        [
            photos > 0 ? (photos == 1 ? "1 photo" : "\(count(photos)) photos") : nil,
            videos > 0 ? (videos == 1 ? "1 video" : "\(count(videos)) videos") : nil,
        ].compactMap { $0 }.joined(separator: " · ")
    }

    /// "1,591 PDFs · 125 documents"; a kind with none is left out.
    static func documentsSubtitle(pdfs: Int, docs: Int, detail: String? = nil) -> String {
        [
            pdfs > 0 ? (pdfs == 1 ? "1 PDF" : "\(count(pdfs)) PDFs") : nil,
            docs > 0 ? (docs == 1 ? "1 document" : "\(count(docs)) documents") : nil, detail,
        ].compactMap { $0 }.joined(separator: " · ")
    }

    /// The mosaic's reading pill.
    static func photosToGo(_ n: Int, paused: Bool) -> String {
        "\(count(n)) to go" + (paused ? " · Paused" : "")
    }

    /// "Today · 2.1 MB" under a document's page.
    static func cardLine(_ f: SourceFile) -> String {
        [day(f.modifiedAt), bytes(f.size)].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    // MARK: First run (onboarding-spec section 4)

    static let firstRunHeadline = "Find anything by what's inside it"
    static let firstRunWelcomeBody =
        "Search your photos, screenshots, PDFs and documents in your own words. Everything is read on this \(device) and stays on it."
    static let firstRunTry = "Try it on a few files first"
    static let firstRunReadAll = "Read everything now"
    static let firstRunTextOnlyTime = "Ready in a few minutes on a handful of your files. Reading everything takes hours, and you choose when."
    static func firstRunExamples(textOnly: Bool) -> [String] {
        let examples = ["“sunset at the beach”", "“the Wi-Fi password screenshot”", "“lease that mentions the deposit”"]
        return textOnly ? Array(examples.dropFirst()) : examples
    }

    nonisolated static func downloadSize(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.isAdaptive = false
        return formatter.string(fromByteCount: bytes)
    }

    static func firstRunTime(size: String?) -> String {
        "A one-time \(size.map { "\($0) " } ?? "")download, then a few minutes on a handful of your files. Reading everything takes hours, and you choose when."
    }

    static let firstRunMobileDataTitle = "Download over mobile data?"
    static let firstRunUseMobileData = "Use mobile data"
    static let firstRunWaitForWifi = "Wait for Wi-Fi"
    static func firstRunMobileDataMessage(size: String?) -> String {
        "The download is \(size ?? "large"). On Wi-Fi it won't use your data."
    }

    static let firstRunAccessTitle = "Allow access to your files"
    static let firstRunAllowPhotos = "Allow access to Photos"
    static let firstRunPhotosNote = "Retriever looks inside your photos and screenshots so you can find them by what's in them."
    static let firstRunPhotosOff = "Photos access is off, so Retriever can't search your photos. You can turn it on in Settings."
    static let firstRunLimitedNote = "Retriever can see the photos you picked. Choose more to search them too."
    static let firstRunAddFolder = "Add a folder"
    static let firstRunFolderNote = "Files you put in Retriever's folder in the Files app are always included."
    static let firstRunNothingAllowed = "Nothing is allowed yet. Any one of these is enough to start."

    /// The limited-Photos footnote: what Retriever can see, and where to change it.
    static func limitedPhotosFootnote(photos: Int, folders: Int) -> String {
        let pictures = photos == 1 ? "the photo you chose" : "the \(count(photos)) photos you chose"
        let places = folders == 0 ? "" : folders == 1 ? " and 1 folder" : " and \(count(folders)) folders"
        return "Retriever can see \(pictures)\(places). You can change this in Settings."
    }

    /// The access screen's one-line download status.
    static func firstRunDownloadLine(_ progress: Double) -> String { "Downloading · \(percent(progress)) %" }
    static func firstRunDownloadA11y(_ progress: Double) -> String { "Downloading, \(percent(progress)) percent" }
    private static func percent(_ progress: Double) -> Int { Int((progress * 100).rounded()) }

    static let firstRunWaitingTitle = "Getting Retriever ready to read"
    static let firstRunTuneTitle = "Getting to know this \(device)"
    static let firstRunTuneBody = "About a minute. Retriever times a few reads to pick the fastest way to run on this \(device)."
    static let firstRunWhyDownload = "Why this download"
    static let firstRunUnderstandsTitle = "It understands what's inside"
    static let firstRunUnderstandsBody = "So “sunset at the beach” finds the photo, even when the file is called IMG_2041."
    static let firstRunOnDeviceTitle = "It runs on this \(device)"
    static let firstRunOnDeviceBody = "Everything is read on this \(device) and stays on it. That's why it's big: the whole model lives here."
    static let firstRunDownloadOnceTitle = "You download it once"
    static func firstRunDownloadOnceBody(size: String?) -> String {
        "\(size.map { "\($0), one time." } ?? "A one-time download.") Search works without the internet after this."
    }
    static let firstRunWhileWaiting = "While you wait"
    static let firstRunTuneFailed = "Couldn't finish getting to know this \(device)."
    static let firstRunWaitingForWifi = "Waiting for Wi-Fi. It starts by itself when you're on Wi-Fi."
    static let firstRunStopped = "Download stopped."
    static let firstRunStop = "Stop"
    static let firstRunChecking = "Checking the download…"
    static let firstRunDownloaded = "Downloaded"
    static let firstRunDownload = "Download"
    static let firstRunTryAgain = "Try again"
    static let firstRunNamesNow = "You can search file names now."
    static let firstRunSearchByName = "Search by name"
    static let firstRunSkip = "Skip"
    static let firstRunDone = "Retriever is set up."
    static let firstRunStartSearching = "Start searching"

    static let firstRunReadingHeadline = "Reading a few files to try"
    static let firstRunReadyToSearch = "Files you can try"
    static let firstRunTryNow = "Try it now"
    static let firstRunStaysSearchable = "You can keep searching the content read so far."

    /// "79 files · about a minute left"
    static func firstRunReadingLine(files: Int, secondsLeft: Int?) -> String {
        "\(count(files)) \(files == 1 ? "file" : "files") read"
    }

    /// The short read's time left, from measured per-file cost.
    nonisolated static func timeLeft(_ seconds: Int?) -> String? {
        guard let seconds, seconds > 0 else { return nil }
        return switch seconds {
        case ...10: "almost done"
        case ..<60: "less than a minute left"
        case ..<90: "about a minute left"
        default: "about \((seconds + 30) / 60) minutes left"
        }
    }

    // MARK: Try it and the commit choice (onboarding-spec 2.5, 2.6)

    static let tryItTitle = "Try it on your files"
    static let tryItWords = "Try your own words: something you remember seeing in a photo or a screenshot."
    static let tryItWordsTextOnly = "Try a word you remember from a screenshot or a PDF."
    static let tryItContinue = "Continue"
    static let tryItDone = "Done"
    static func closestIn(_ files: Int) -> String { "Closest in these \(count(files)) files" }
    static func stillReading(_ files: Int) -> String { "Still reading \(count(files)) more" }

    static let commitTitle = "Search inside more files"
    static let commitWhileCharging = "While charging"
    static let commitNotYet = "Not now"
    /// Not "several nights" as a number: nothing is measured in the background until the first run has timed itself.
    static let commitNights = "A large library can take hours of reading, spread across several sessions. You don’t need to wait."

    /// No hours, while the estimate is uncalibrated. `files` is what is left beyond the short read, when known.
    static func commitRest(files: Int?) -> String {
        files.map { "\(count($0)) more files can be read. You can already find them by name." } ?? "You can already find your files by name."
    }

    /// iOS 26 and later keep reading after the person leaves; before that, only while the app is open.
    nonisolated static func commitReadNow(continued: Bool) -> String {
        "Read now for 15 minutes"
    }
}
