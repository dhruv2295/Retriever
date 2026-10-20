import Photos
@preconcurrency import RetrieverCore
import SwiftUI

// MARK: Fixtures

/// Pinned for screenshots and previews: each case is a whole-app state, no index needed.
enum AppFixture {
    /// Setup's welcome and its access choice: not asked yet, limited or denied Photos, or a folder added.
    case firstRun, photosLimited, photosDenied, folderAdded
    /// Setup's sample read with no download: starting, reading, paused, or done; then Try and the choice of when to read more.
    case sampleStart, sampleReading, samplePaused, sampleDone
    case trying(suggestions: Bool)
    case choosing
    /// The meaning upgrade: offered (after a search), downloading, waiting for Wi-Fi, failed, tuning, adding meaning.
    case noModel
    case downloading(Double)
    case waitingForWifi, downloadFailed, tuning
    case meaning(Int)
    case reading(done: Int, total: Int)
    case caughtUp, paused
    case skipped(Int)
    case noAccess
    case tailFiles(Int)
    case textOnly(ramGb: Double)
}

extension AppModel {
    convenience init(fixture: AppFixture) {
        var snap = Snap(
            setUp: true, byKind: [.photo: 2_140, .screenshot: 812, .pdf: 64, .doc: 31, .video: 95], indexed: 3_142,
            lastStop: .done, downloadBytes: ModelVariant.m440.generic.sizeBytes)
        var photos = PHAuthorizationStatus.authorized
        var download: (ModelDownload.State, Double) = (.ready, 1)
        var tune = Tune.done(Copy.tuneSummary(.cpu, imageMs: 410))
        var setup = SetupInputs(mark: .accessChosen, skipped: false, decided: true, planned: 0, accessGiven: false)
        var folders = ["Work"]
        var modelMissing = false
        var skipped: [SkippedFile] = []
        var taste = TasteSnap()
        var sample: [SampleFile] = []
        var suggestions = ["wifi password", "boarding pass", "electricity bill"]
        let files = Self.readingFiles
        /// Before access is chosen: the welcome, nothing read, no download.
        func inSetup(_ access: PHAuthorizationStatus, folders picked: [String] = []) {
            (snap.setUp, snap.indexed, snap.pending, snap.lastStop, snap.accessChosen) = (false, 0, 0, nil, false)
            (photos, download, tune, modelMissing, folders) = (access, (.idle, 0), .idle, true, picked)
            setup = SetupInputs(mark: .none, skipped: false, decided: false, planned: 0, accessGiven: false)
        }
        /// The sample, read with no download: `read` of 54 planned files, reading while any wait.
        func inSample(_ mark: SetupMark, read: Int = 30) {
            let done = read == 54
            (snap.setUp, snap.indexed, snap.pending, snap.waiting, snap.trial) = (false, read, 54 - read, 3_088, true)
            (snap.running, download, tune, modelMissing) = (!done, (.idle, 0), .idle, true)
            setup = SetupInputs(mark: mark, skipped: false, decided: false, planned: 54, accessGiven: false)
            let screenshots = min(read, 6)
            let pdfs = min(max(read - 6, 0), 8)
            taste = TasteSnap(
                counts: [
                    .screenshot: .init(read: screenshots, planned: 6), .pdf: .init(read: pdfs, planned: 8),
                    .photo: .init(read: read - screenshots - pdfs, planned: 40),
                ], readyToTry: read >= 30, pending: 54 - read)
            sample = (1...54).map {
                SampleFile(index: Int32($0 - 1), file: Self.readingFile($0), read: $0 <= read, note: $0 <= read ? nil : "Waiting to read")
            }
            snap.reading = ReadingPreview(current: done ? nil : files[0], recent: read == 0 ? [] : Array(files.dropFirst()))
        }
        /// Set up and read with no download: the meaning upgrade is still to come.
        func withoutModel(_ state: (ModelDownload.State, Double)) {
            (snap.setUp, modelMissing, download, tune) = (false, true, state, .idle)
        }
        switch fixture {
        case .firstRun: inSetup(.notDetermined)
        case .photosLimited: inSetup(.limited)
        case .photosDenied: inSetup(.denied)
        case .folderAdded: inSetup(.notDetermined, folders: ["Work"])
        case .sampleStart: inSample(.accessChosen, read: 0)
        case .sampleReading: inSample(.accessChosen)
        case .samplePaused:
            inSample(.accessChosen)
            snap.running = false
            snap.reading.current = nil
            snap.reading.pauseReason = "Paused while you use other apps. It carries on when you come back."
        case .sampleDone: inSample(.accessChosen, read: 54)
        case .trying(let offered):
            inSample(.trying, read: 54)
            if !offered { suggestions = [] }
        case .choosing: inSample(.choosing, read: 54)
        case .noModel: withoutModel((.idle, 0))
        case .downloading(let p): withoutModel((.downloading, p))
        case .waitingForWifi: withoutModel((.waitingForWifi, 0))
        case .downloadFailed: withoutModel((.failed(Words.shared.DOWNLOAD_FAILED), 0.41))
        case .tuning:
            withoutModel((.ready, 1))
            (modelMissing, tune) = (false, .running)
        case .meaning(let n): snap.meaningWaiting = n
        case .reading(let done, let total):
            (snap.indexed, snap.pending, snap.running, snap.lastStop) = (done, total - done, true, nil)
            (snap.processed, snap.pendingAtStart, snap.filesPerMinute) = (120, total - done + 120, 38)
            snap.etaMinutes = Int((Double(total - done) / 38).rounded(.up))
            snap.pendingByKind = [.photo: total - done]
            snap.current = "Photo 2026-09-14 at 18.42.07"
            snap.reading = ReadingPreview(current: files[0], recent: Array(files.dropFirst()))
            snap.tailFiles = 3
            snap.partlyRead = 3
        case .caughtUp:
            break
        case .tailFiles(let n):
            snap.tailFiles = n
            snap.partlyRead = n
        case .textOnly(let ramGb):
            (snap.textOnly, snap.ramGb, snap.byKind) = (true, ramGb, [.pdf: 64, .doc: 31, .photo: 0])
            (download, tune) = ((.idle, 0), .idle)
            // Setup shows its welcome, with what this phone reads; Status ignores setup.
            setup = SetupInputs(mark: .none, skipped: false, decided: false, planned: 0, accessGiven: false)
        case .paused:
            (snap.indexed, snap.pending, snap.paused, snap.lastStop) = (1_904, 1_238, true, .paused)
            snap.pendingByKind = [.photo: 1_238]
        case .skipped(let n):
            (snap.indexed, snap.skipped) = (3_142 - n, n)
            skipped = (0..<min(n, 6)).map { i in
                SkippedFile(
                    id: Int64(i), name: ["Scan 2019.pdf", "Locked statement.pdf", "IMG_0412.HEIC", "notes.pages", "Clip 3.mov", "export.zip"][i],
                    folder: ["Retriever", "Downloads", "Photos/Camera", "Retriever/Work", "Photos/Videos", "Downloads"][i])
            }
        case .noAccess:
            (photos, snap.byKind, snap.indexed) = (.denied, [.pdf: 12, .doc: 9], 21)
        }
        snap.readByKind = snap.byKind.mapValues { $0 }
        for (kind, pending) in snap.pendingByKind { snap.readByKind[kind] = max(0, (snap.byKind[kind] ?? 0) - pending) }
        snap.prefs = Self.prefs(searchTried: true)
        self.init(snap: snap, photos: photos, download: download, tune: tune, setup: setup, folders: folders, modelMissing: modelMissing, skipped: skipped)
        self.taste = taste
        self.sample = sample
        func file(_ name: String, _ kind: Kind, _ folder: String, size: Int64, daysAgo: Double) -> SourceFile {
            SourceFile(
                uri: "folder://fixture/\(name)", name: name, folder: folder, mime: "application/pdf", kind: kind, size: size,
                modifiedAt: Int64((Date().timeIntervalSince1970 - daysAgo * 86400) * 1000), takenAt: nil)
        }
        let docs = [
            file("Lease agreement 2026.pdf", .pdf, "Retriever/Download", size: 2_100_000, daysAgo: 0),
            file("Electricity bill October.pdf", .pdf, "Retriever/Download", size: 340_000, daysAgo: 1),
            file("Trip itinerary.docx", .doc, "Retriever/Documents", size: 88_000, daysAgo: 14),
        ]
        home = HomeFacts(
            newMedia: [],
            newShots: (1...6).map { file("Screenshot \($0).png", .screenshot, "Photos/Screenshots", size: 400_000, daysAgo: Double($0)) },
            newDocs: docs,
            starred: [docs[0], docs[2]], starredCount: 2,
            folders: 12, largeCount: 4, largeBytes: 6_400_000_000, nameOnly: 0, topFolders: [])
        // Photos never says a size on iOS; folder files do.
        kindTotals = snap.byKind.filter { $0.value > 0 }.mapValues { n in
            FileTotals(files: Int64(n), knownBytes: 0, unknownSizes: Int64(n))
        }
        .merging([.pdf: FileTotals(files: 64, knownBytes: 120_000_000, unknownSizes: 0), .doc: FileTotals(files: 31, knownBytes: 8_400_000, unknownSizes: 0)]) {
            _, folder in folder
        }
        folderTotals = [
            FolderTotals(path: "Retriever/Download", totals: FileTotals(files: 64, knownBytes: 120_000_000, unknownSizes: 0)),
            FolderTotals(path: "Retriever/Documents", totals: FileTotals(files: 31, knownBytes: 8_400_000, unknownSizes: 0)),
        ]
        storage = Storage(index: 412_000_000, model: ModelVariant.m440.generic.sizeBytes, cache: 38_000_000)
        starred = ["folder://fixture/Lease agreement 2026.pdf", "folder://fixture/Trip itinerary.docx"]
        self.suggestions = suggestions
    }

    /// What reading shows: the file being read, then the newest read. A mix of kinds, as a sample reads them.
    private static let readingFiles = (1...8).map(readingFile)

    nonisolated private static func readingFile(_ i: Int) -> SourceFile {
        let (name, kind, mime) =
            switch i {
            case 3: ("Lease notes.docx", Kind.doc, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            case 4: ("Electricity bill.pdf", .pdf, "application/pdf")
            case 5: ("Screenshot \(i).png", .screenshot, "image/png")
            default: ("Photo \(i).jpg", .photo, "image/jpeg")
            }
        return SourceFile(
            uri: "folder://fixture/reading-\(i)", name: name, folder: "Photos", mime: mime, kind: kind, size: 240_000, modifiedAt: 1_760_000_000_000,
            takenAt: nil)
    }

    /// Prefs as a fresh install keeps them, with a first search made.
    private static func prefs(searchTried: Bool) -> Prefs {
        Prefs(
            kinds: Set(Snap.inside), onlyWhileCharging: true, schedule: .charging, readNowUntil: 0, nameSearchHintDismissed: false,
            scheduleOfferDismissed: false, excludedFolders: [], paused: false, sampleSize: nil, allowMobileData: false, rememberSearches: true,
            dynamicColor: true, setupStarted: false, notificationsDismissed: false, noMatchSeen: 0, showOnThisDay: true, readingCycle: 0,
            dismissedReadingCycle: -1, searchTried: searchTried, upgradeOfferDismissed: false)
    }
}
