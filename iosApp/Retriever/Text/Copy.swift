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
        switch stop {
        case StopReason.done: "All caught up"
        case StopReason.powerSave: "Paused: Low Power Mode"
        case StopReason.hot: "Resting while your \(device) cools down"
        case StopReason.unplugged: "Waiting for the charger"
        case StopReason.budget: "Taking a short break"
        case StopReason.busy: "Paused while you use your \(device)"
        case StopReason.paused: "Paused"
        default: nil
        }
    }

    /// Why files are still waiting. A batch that ended done finished before the newer files arrived.
    static func waiting(_ stop: StopReason?) -> String? {
        stop == StopReason.done ? "Will read the new files soon" : self.stop(stop)
    }

    static func statusTitle(_ s: AppModel.Snap) -> String {
        if s.running && s.pendingAtStart == 0 { return "Looking for your files" }
        if s.paused { return "Paused" }
        return s.pending > 0 ? "Reading your files" : "All caught up"
    }

    /// What Home's first row says; nil while the counts load, and while there are no files, when Home says it in one
    /// message instead. The first match wins, as home-spec section 3 orders them.
    static func homeStatus(_ s: AppModel.Snap?, download: ModelDownload.State = .idle, progress: Double = 0, nameOnly: Int64 = 0) -> HomeStatus? {
        guard let s, !(s.total == 0 && (s.running || s.setUp == true)) else { return nil }
        let of = "\(count(s.indexed)) of \(count(s.total))"
        let readSoFar = "Search works on everything read so far."
        if s.setUp != true {
            let step =
                switch download {
                case .downloading: "Downloading · \(progress.formatted(.percent.precision(.fractionLength(0))))"
                case .waitingForWifi: "Waiting for Wi-Fi to download"
                case .failed: "The download stopped"
                default: "Getting ready"
                }
            return HomeStatus(phase: .settingUp, title: "\(step). For now Retriever finds files by name.")
        }
        if s.running && s.pendingAtStart == 0 { return HomeStatus(phase: .looking, title: "Looking for new files…") }
        if s.running {
            return HomeStatus(
                phase: .reading, title: "Reading your files · \(of)", detail: s.etaMinutes.map(eta),
                progress: s.total > 0 ? Double(s.indexed) / Double(s.total) : 0)
        }
        if s.paused { return HomeStatus(phase: .paused, title: "Paused · \(count(s.indexed)) of \(count(s.total)) searchable", detail: readSoFar) }
        if s.pending > 0 {
            return HomeStatus(phase: .waiting, title: "\(waiting(s.lastStop) ?? "Waiting") · \(of) searchable", detail: readSoFar)
        }
        return HomeStatus(
            phase: .caughtUp, title: "All caught up · \(count(s.indexed)) files searchable",
            detail: nameOnly > 0 ? "\(count(Int(nameOnly))) of them by name only" : nil)
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
        "Retriever reads your files while it's open, on battery too, and keeps the screen on as it goes. After you leave, it carries on where \(device) allows, mostly overnight on the charger."

    static let screenKeptOn = "Retriever keeps your screen on while it reads. Lock your \(device) any time to stop."

    static func deepReading(_ n: Int) -> String {
        n == 1 ? "1 long file still being read in depth" : "\(count(n)) long files still being read in depth"
    }

    static func textOnlyNote(ramGb: Double) -> String {
        "This \(device) has \(Int(ramGb.rounded())) GB of memory, too little to understand photos or meaning, so Retriever finds files by their names and the words inside them."
    }

    static let textOnlySearch = "Retriever finds files here by their names and the words inside them."

    /// What the search made of the words: "Showing Screenshots · Last week".
    static func understood(_ labels: [String]) -> String { "Showing \(labels.joined(separator: " · "))" }
    static let exactWords = "Search my exact words"
    static let star = "Star"
    static let removeStar = "Remove star"

    static let lessLikely = "Less likely matches"
    static func nothingWell(_ q: String) -> String { "Nothing matches “\(q)” well" }
    static let closestFiles = "These are the closest files, but they may not be what you're looking for."
    static let closestFilesShort = "These are the closest files."

    static func extent(_ extent: ReadExtent?) -> String? {
        switch extent {
        case ReadExtent.firstPart: "Only the first part of this file was read"
        case ReadExtent.deepening: "Still reading the rest of this file"
        default: nil
        }
    }

    static let keepReading = "Keep reading after I leave"
    static let keepReadingNote = "Your \(device) shows how it's going and lets you stop it any time."

    /// The Lock Screen's progress for a read that carries on after the person leaves.
    nonisolated static let continuedTitle = "Reading your files"

    nonisolated static func continuedSubtitle(_ s: AppModel.Snap) -> String {
        "\(count(s.indexed)) of \(count(s.total))"
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
    static let firstRunNamesNow = "Names are searchable now."
    static let firstRunSearchByName = "Search by name"
    static let firstRunSkip = "Skip"
    static let firstRunDone = "Retriever is set up."
    static let firstRunStartSearching = "Start searching"

    static let firstRunReadingHeadline = "Reading a few of your files"
    static let firstRunReadyToSearch = "Ready to search"
    static let firstRunTryNow = "Try it now"
    static let firstRunStaysSearchable = "Everything it reads now stays searchable."

    /// "79 files · about a minute left"
    static func firstRunReadingLine(files: Int, secondsLeft: Int?) -> String {
        [files == 1 ? "1 file" : "\(count(files)) files", timeLeft(secondsLeft)].compactMap { $0 }.joined(separator: " · ")
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

    static let commitTitle = "Read everything?"
    static let commitWhileCharging = "Read while charging"
    static let commitNotYet = "Not yet"
    /// Not "several nights" as a number: nothing is measured in the background until the first run has timed itself.
    static let commitNights = "iPhone reads more slowly in the background, so this takes several nights on the charger."

    /// No hours, while the estimate is uncalibrated. `files` is what is left beyond the short read, when known.
    static func commitRest(files: Int?) -> String {
        "Reading \(files.map { "the other \(count($0))" } ?? "the rest") takes hours; Retriever measures this \(device) as it goes."
    }

    /// iOS 26 and later keep reading after the person leaves; before that, only while the app is open.
    nonisolated static func commitReadNow(continued: Bool) -> String {
        continued ? "Read now" : "Read while Retriever is open"
    }
}
