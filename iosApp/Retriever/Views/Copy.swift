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
/// SetupScreen copy; "phone" reads "iPhone" or "iPad" here.
enum Copy {
    /// What the person holds, as they'd call it.
    static let device = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"

    static func stop(_ stop: StopReason?) -> String? {
        switch stop {
        case StopReason.done: "All caught up"
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
            let step = switch download {
            case .downloading: "Downloading · \(progress.formatted(.percent.precision(.fractionLength(0))))"
            case .waitingForWifi: "Waiting for Wi-Fi to download"
            case .failed: "The download stopped"
            default: "Getting ready"
            }
            return HomeStatus(phase: .settingUp, title: "\(step). For now Retriever finds files by name.")
        }
        if s.running && s.pendingAtStart == 0 { return HomeStatus(phase: .looking, title: "Looking for new files…") }
        if s.running {
            return HomeStatus(phase: .reading, title: "Reading your files · \(of)", detail: s.etaMinutes.map(eta),
                              progress: s.total > 0 ? Double(s.indexed) / Double(s.total) : 0)
        }
        if s.paused { return HomeStatus(phase: .paused, title: "Paused · \(count(s.indexed)) of \(count(s.total)) searchable", detail: readSoFar) }
        if s.pending > 0 {
            return HomeStatus(phase: .waiting, title: "\(waiting(s.lastStop) ?? "Waiting") · \(of) searchable", detail: readSoFar)
        }
        return HomeStatus(phase: .caughtUp, title: "All caught up · \(count(s.indexed)) files searchable",
                          detail: nameOnly > 0 ? "\(count(Int(nameOnly))) of them by name only" : nil)
    }

    /// Shown under the status row while Photos access is off or limited; it never takes the status row's place, so
    /// reading progress and Resume stay on Home.
    static func photosNote(_ photos: PHAuthorizationStatus) -> HomeStatus? {
        switch photos {
        case .denied, .restricted:
            HomeStatus(phase: .photosOff, title: "Photos access is off",
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

    /// "Download · Today · 2.1 MB": the folder's own name, so a long path doesn't crowd the row.
    static func newFileLine(_ f: SourceFile) -> String {
        let folder = f.folder.split(separator: "/").last.map(String.init) ?? f.folder
        return [folder, day(f.modifiedAt), bytes(f.size)].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    static func largeFiles(_ n: Int64) -> String {
        n == 1 ? "1 file over 100 MB" : "\(count(Int(n))) files over 100 MB"
    }

    static func count(_ n: Int) -> String {
        n.formatted(.number)
    }

    static func bytes(_ n: Int64) -> String {
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
        case let .overlaps(name): "That folder overlaps “\(name)”, which Retriever already searches."
        case .failed: "iOS wouldn't let Retriever keep that folder. Try another one."
        }
    }

    static func tuneSummary(_ backend: Backend, imageMs: Double?) -> String {
        let chip = backend == Backend.gpu ? "the graphics chip" : "the processor"
        let speed = imageMs.map { String(format: " · about %.1f s per photo", $0 / 1000) } ?? ""
        return "Using \(chip)\(speed)"
    }

    /// Stand-in documents for the first tune, as on Android (CalibrationSamples.FALLBACK_TEXTS).
    static let calibrationTexts = [
        "Lease agreement with a two month security deposit",
        "Flight booking confirmation for Bengaluru to Delhi",
        "Notes from the team meeting about the launch date",
        "Recipe for masala chai with ginger and cardamom",
        "Electricity bill for September",
        "Screenshot of an error dialog in the banking app",
        "Doctor's prescription for antibiotics",
        "Workout plan for the week",
        "Invoice 48213 for office chairs",
        "Photos from the beach trip at sunset",
    ]

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
    static let continuedTitle = "Reading your files"

    static func continuedSubtitle(_ s: AppModel.Snap) -> String {
        "\(count(s.indexed)) of \(count(s.total))"
    }

    static let oneTimeDownload = "One-time download"
    static let downloadNote = "It lets Retriever understand the photos and documents on this \(device)."
}
