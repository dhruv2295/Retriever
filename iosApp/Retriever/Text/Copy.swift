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
        s.flatMap { ReadingPolicy.shared.homeStatus(libraryChosen: $0.accessChosen, libraryTotal: $0.readProgress.libraryTotal, reading: reading($0)) }
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

    /// Setup's Photos row: the authorization as it stands; nil before it is asked.
    static func photosAccess(_ photos: PHAuthorizationStatus) -> String? {
        switch photos {
        case .authorized: Words.shared.ALL_PHOTOS
        case .limited: Words.shared.SELECTED_PHOTOS
        case .denied, .restricted: "Photos access is off"
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

    nonisolated static func count(_ n: Int) -> String {
        n.formatted(.number)
    }

    nonisolated static func bytes(_ n: Int64) -> String {
        Words.shared.keepTogether(size: ByteCountFormatter.string(fromByteCount: n, countStyle: .file))
    }

    /// Whole megabytes, as a download is quoted: "388 MB".
    nonisolated static func downloadSize(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.isAdaptive = false
        return Words.shared.keepTogether(size: formatter.string(fromByteCount: bytes))
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

    /// "120 files · 640 MB", "At least …" or "Size unavailable" (Photos never says its sizes), by core's rule.
    static func countAndSize(_ totals: FileTotals) -> String {
        Words.shared.countAndSize(totals: totals, knownSize: bytes(totals.knownBytes))
    }

    /// The files of these kinds as one group: Home's sections cover one kind or two.
    static func totals(_ kinds: [Kind], in byKind: [Kind: FileTotals]) -> FileTotals {
        let parts = kinds.compactMap { byKind[$0] }
        return FileTotals(
            files: parts.reduce(0) { $0 + $1.files }, knownBytes: parts.reduce(0) { $0 + $1.knownBytes },
            unknownSizes: parts.reduce(0) { $0 + $1.unknownSizes })
    }

    /// A section header's second line: count and size, then its reading line from core ("812 files · 1.2 GB · 40 waiting to read").
    static func sectionSubtitle(_ totals: FileTotals, _ detail: String? = nil) -> String {
        [countAndSize(totals), detail].compactMap { $0 }.joined(separator: " · ")
    }

    /// The mosaic's reading pill: "14,700 waiting to read".
    static func photosWaiting(_ n: Int, paused: Bool) -> String {
        Words.shared.waitingToRead(files: Int64(n)) + (paused ? " · Paused" : "")
    }

    /// "Today · 2.1 MB" under a document's page.
    static func cardLine(_ f: SourceFile) -> String {
        [day(f.modifiedAt), bytes(f.size)].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    // MARK: Setup

    static let filesYouCanTry = "Files you can try"
    static let tuneFailed = "Couldn't finish setting up reading for this \(device)."
    /// Not "several nights" as a number: nothing is measured in the background until the first run has timed itself.
    static let commitNights = "A large library can take hours of reading, spread across several sessions. You don’t need to wait."
}
