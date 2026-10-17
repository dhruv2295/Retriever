import Foundation
import RetrieverCore
import UIKit

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

    /// The one-line summary on Home; nil until the counts load.
    static func statusLine(_ s: AppModel.Snap?, download: ModelDownload.State = .idle, progress: Double = 0) -> String? {
        guard let s else { return nil }
        let of = "\(count(s.indexed)) of \(count(s.total))"
        if s.setUp != true {
            let step = switch download {
            case .downloading: "Downloading · \(progress.formatted(.percent.precision(.fractionLength(0))))"
            case .waitingForWifi: "Waiting for Wi-Fi to download"
            case .failed: "The download stopped"
            default: "Getting ready"
            }
            return "\(step). For now Retriever finds files by name."
        }
        if s.running && s.pendingAtStart == 0 { return "Looking for your files…" }
        if s.running { return "Reading your files · \(of)" }
        if s.pending > 0 { return "\(of) files searchable" + (waiting(s.lastStop).map { " · \($0)" } ?? "") }
        return "All caught up · \(count(s.indexed)) files searchable"
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
