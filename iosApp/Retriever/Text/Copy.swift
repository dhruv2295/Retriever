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
                faces: nil, facesWaiting: nil, people: nil
            )
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
                off: true, title: Words.shared.strings.photosAccessOff, detail: Words.shared.strings.photosOffNoteDetail)
        case .limited:
            PhotosNote(off: false, title: Words.shared.strings.seesOnlyPickedPhotos, detail: Words.shared.strings.canSearchJustThose)
        default: nil
        }
    }

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
        case .added: Words.shared.strings.folderAdded
        case .overlaps(let name): Words.shared.strings.folderOverlaps(name: name)
        case .failed: Words.shared.strings.folderRefused
        }
    }

    static func tuneSummary(_ backend: Backend, imageMs: Double?, locale: Locale = .autoupdatingCurrent) -> String {
        let chip = backend == Backend.gpu ? "the graphics chip" : "the processor"
        // Android's words ("about 1.2 s"), in the person's digits as Android's format gives them.
        let seconds = imageMs.map { ($0 / 1000).formatted(.number.precision(.fractionLength(1)).locale(locale)) }
        return "Using \(chip)\(seconds.map { " · about \($0) s per photo" } ?? "")"
    }

    static var backgroundNote: String { Words.shared.strings.backgroundNote(words: words) }

    static var screenKeptOn: String { Words.shared.strings.screenKeptOn }

    /// What the search made of the words: "Showing Screenshots · Last week".
    static func understood(_ labels: [String]) -> String { Words.shared.strings.showingLabels(labels: labels) }
    static var exactWords: String { Words.shared.strings.searchMyExactWords }
    static var star: String { Words.shared.strings.addToStarred }
    static var removeStar: String { Words.shared.strings.removeFromStarred }

    /// The Lock Screen's progress for a read that carries on after the person leaves.
    nonisolated static var continuedTitle: String { Words.shared.PROCESSING_YOUR_FILES }

    nonisolated static func continuedSubtitle(_ files: Int) -> String {
        Words.shared.strings.processedSoFar(n: Int64(files))
    }
}
