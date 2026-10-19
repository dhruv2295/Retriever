import Foundation
import Photos
import RetrieverCore
import UIKit

/// The note under Home's status while Photos access is off or limited.
struct PhotosNote: Equatable {
    var off: Bool
    var title: String
    var detail: String
}

/// Every sentence the shell shows, in plain words from the person's side. Ported from Android's StatusScreen and
/// SetupScreen copy; "phone" reads "iPhone" or "iPad" here. Main-actor, as UIDevice is; the background task's
/// lines are nonisolated.
@MainActor
enum Copy {
    /// What the person holds, as they'd call it.
    static let device = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
    /// The device's names in core's shared sentences.
    static let words = DeviceWords.companion.ios(device: device)

    /// Core's reading status: the live one, or before it arrives the same decision made here.
    static func reading(_ s: AppModel.Snap) -> ReadingStatus {
        s.readingStatus
            ?? ReadingPolicy.shared.decide(
                progress: s.readProgress, blocker: s.paused ? .paused : s.lastStop, running: s.running, reading: s.reading.current != nil, words: words)
    }

    static func homeStatus(_ s: AppModel.Snap?) -> HomeStatus? {
        s.flatMap { ReadingPolicy.shared.homeStatus(setUp: $0.setUp == true, libraryTotal: $0.readProgress.libraryTotal, reading: reading($0)) }
    }

    /// Shown under the status row while Photos access is off or limited; it never takes the status row's place, so
    /// reading progress and Resume stay on Home.
    static func photosNote(_ photos: PHAuthorizationStatus) -> PhotosNote? {
        switch photos {
        case .denied, .restricted:
            PhotosNote(
                off: true, title: "Photos access is off",
                detail: "Search still works on files already read. Turn it on to search your photos and screenshots too.")
        case .limited:
            PhotosNote(off: false, title: "Retriever sees only the photos you picked", detail: "It can search just those.")
        default: nil
        }
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
        Words.shared.files(n: n) + " over 100\u{00A0}MB"
    }

    nonisolated static func count(_ n: Int) -> String {
        n.formatted(.number)
    }

    nonisolated static func bytes(_ n: Int64) -> String {
        Words.shared.keepTogether(size: ByteCountFormatter.string(fromByteCount: n, countStyle: .file))
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

    static func meaningWaiting(_ n: Int) -> String {
        "\(count(n)) read for words · search by meaning to come"
    }

    static func deepReading(_ n: Int) -> String {
        "\(count(n)) partly read · more meaning search to come"
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

    /// The Lock Screen's progress for a read that carries on after the person leaves.
    nonisolated static let continuedTitle = "Reading your files"

    nonisolated static func continuedSubtitle(_ files: Int) -> String {
        "\(count(files)) files read this session"
    }

    // MARK: Home sections

    /// A section header's second line: the count, then its reading line from core ("1,716 · 1,200 waiting to read").
    static func sectionSubtitle(_ n: Int, _ detail: String? = nil) -> String {
        [count(n), detail].compactMap { $0 }.joined(separator: " · ")
    }

    /// "22,000 photos · 1,733 videos"; a kind with none is left out.
    static func mediaSubtitle(photos: Int, videos: Int) -> String {
        [
            photos > 0 ? Words.shared.plural(n: Int64(photos), one: "photo", many: "photos") : nil,
            videos > 0 ? Words.shared.plural(n: Int64(videos), one: "video", many: "videos") : nil,
        ].compactMap { $0 }.joined(separator: " · ")
    }

    /// "1,591 PDFs · 125 documents"; a kind with none is left out.
    static func documentsSubtitle(pdfs: Int, docs: Int, detail: String? = nil) -> String {
        [
            pdfs > 0 ? Words.shared.plural(n: Int64(pdfs), one: "PDF", many: "PDFs") : nil,
            docs > 0 ? Words.shared.plural(n: Int64(docs), one: "document", many: "documents") : nil, detail,
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
        return Words.shared.keepTogether(size: formatter.string(fromByteCount: bytes))
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

    // MARK: Try it and the commit choice (onboarding-spec 2.5, 2.6)

    static let tryItTitle = "Try it on your files"
    static let tryItWords = "Try your own words: something you remember seeing in a photo or a screenshot."
    static let tryItWordsTextOnly = "Try a word you remember from a screenshot or a PDF."
    static let tryItContinue = "Continue"
    static let tryItDone = "Done"

    static let commitTitle = "Search inside more files"
    static let commitWhileCharging = "While charging"
    static let commitNotYet = "Not now"
    /// Not "several nights" as a number: nothing is measured in the background until the first run has timed itself.
    static let commitNights = "A large library can take hours of reading, spread across several sessions. You don’t need to wait."

    /// iOS 26 and later keep reading after the person leaves; before that, only while the app is open.
    nonisolated static func commitReadNow(continued: Bool) -> String {
        "Read now for 15 minutes"
    }
}
