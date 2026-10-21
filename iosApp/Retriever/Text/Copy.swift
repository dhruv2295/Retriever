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
        let msLeft = Double(s.readNowUntil) - Date.now.timeIntervalSince1970 * 1000
        return s.readingStatus
            ?? ReadingPolicy.shared.decide(
                progress: s.readProgress, blocker: s.paused ? .paused : s.lastStop, running: s.running, reading: s.reading.current != nil, words: words,
                readNowMinutes: Int32(max(0, (msLeft / 60_000).rounded(.up))), pluggedIn: [.charging, .full].contains(UIDevice.current.batteryState),
                mark: ReadMark(percent: 0, files: 0))
    }

    /// The one status line on Home, Reading status and Settings: access, setup and the download first, then reading.
    /// Photos access that is only limited, or off while folders still give files, keeps its own note (`photosNote`).
    static func homeStatus(_ s: AppModel.Snap?, hasAccess: Bool = true, download: DownloadState? = nil) -> ReadingStatus? {
        s.flatMap {
            ReadingPolicy.shared.line(
                reading: reading($0), libraryChosen: $0.accessChosen, hasAccess: hasAccess, libraryTotal: $0.readProgress.libraryTotal, words: words,
                download: download)
        }
    }

    /// Shown under the status row while Photos access is off or limited; it never takes the status row's place, so
    /// reading progress and Resume stay on Home.
    static func photosNote(_ photos: PHAuthorizationStatus) -> PhotosNote? {
        switch photos {
        case .denied, .restricted:
            PhotosNote(
                off: true, title: "Photos access is off",
                detail: "Search still works on files already processed. Turn it on to search your photos and screenshots too.")
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
        case .added: "Folder added. Retriever will process what's inside."
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
        "Retriever processes your files while it’s open, and sometimes while you’re not using it, when iOS allows. Automatic processing waits until your \(device) is charging. \(Words.shared.READ_FOR_15) uses some battery."

    static let screenKeptOn = "Your screen stays on while Retriever processes your files for 15 minutes. You can turn this off in Settings."

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
    nonisolated static let continuedTitle = Words.shared.PROCESSING_YOUR_FILES

    nonisolated static func continuedSubtitle(_ files: Int) -> String {
        "\(Words.shared.readCount(read: Int64(files), partlyRead: 0)) so far"
    }
}
