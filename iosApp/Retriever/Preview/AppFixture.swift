import Photos
@preconcurrency import RetrieverCore

// MARK: Fixtures

/// Pinned for screenshots and previews: each case is a whole-app state, no index needed.
enum AppFixture {
    /// Hello, then the access screen: nothing given yet, some photos, Photos refused, a folder added, or both given.
    case firstRun, accessNone, photosLimited, photosDenied, folderAdded, accessBoth
    /// The first search, its download running: picking (no plan yet), starting, processing, or done; processing while the
    /// download waits for Wi-Fi; processing with search by description ready; and paused on a low battery.
    case samplePicking, sampleStart, sampleReading, sampleDone, sampleWaitingForWifi, sampleReady, samplePaused
    /// Home's one-time card after setup, search by description ready or still downloading.
    case homeIntro(ready: Bool)
    /// The meaning upgrade: offered (after a search), downloading, waiting for Wi-Fi, failed, tuning, adding meaning.
    case noModel
    case downloading(Double)
    case waitingForWifi, downloadFailed, tuning
    case meaning(Int)
    case reading(done: Int, total: Int)
    /// Home's status line (clarity-proposal section 3): reading while plugged in, waiting to be plugged in, plugged in
    /// but charging up first, Read for 15 minutes, the meaning pass after the download, the first day (found by name
    /// only), Not now chosen after the demo, the phone's files still being counted, and no access at all (Photos off,
    /// no folder).
    case readingWhileCharging, unplugged, chargingUp, readNow, meaningPass, firstDay, notNow, counting, lostAccess
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
        var sample: [SampleFile]? = []
        var suggestions = ["wifi password", "boarding pass", "electricity bill"]
        let files = Self.readingFiles
        /// Before access is chosen: Hello (`.none`) or the access screen (`.greeted`), nothing read, no download yet.
        func inSetup(_ access: PHAuthorizationStatus, folders picked: [String] = [], mark: SetupMark = .greeted) {
            (snap.setUp, snap.indexed, snap.pending, snap.lastStop, snap.accessChosen) = (false, 0, 0, nil, false)
            (photos, download, tune, modelMissing, folders) = (access, (.idle, 0), .idle, true, picked)
            setup = SetupInputs(mark: mark, skipped: false, decided: false, planned: 0, accessGiven: false)
        }
        /// The first search, its download at 64% (Get started began it): `read` of its 30 files, processing while any
        /// wait. No chips before the first file: the hint holds their place.
        func inSample(read: Int = 12, download state: (ModelDownload.State, Double) = (.downloading, 0.64)) {
            let done = read == 30
            (snap.setUp, snap.indexed, snap.pending, snap.waiting, snap.trial) = (false, read, 30 - read, 25_763, true)
            (snap.running, download, tune, modelMissing) = (!done, state, .idle, true)
            setup = SetupInputs(mark: .accessChosen, skipped: false, decided: false, planned: 30, accessGiven: false)
            let screenshots = min(read, 6)
            let pdfs = min(max(read - 6, 0), 8)
            taste = TasteSnap(
                counts: [
                    .screenshot: .init(read: screenshots, planned: 6), .pdf: .init(read: pdfs, planned: 8),
                    .photo: .init(read: read - screenshots - pdfs, planned: 16),
                ], pending: 30 - read)
            if read == 0 { suggestions = [] }
            sample = (1...30).map {
                SampleFile(index: Int32($0 - 1), file: Self.readingFile($0), read: $0 <= read, note: $0 <= read ? nil : "Not processed yet")
            }
            snap.reading = ReadingPreview(current: done ? nil : files[0], recent: read == 0 ? [] : Array(files.dropFirst()))
        }
        /// Set up and read with no download: the meaning upgrade is still to come.
        func withoutModel(_ state: (ModelDownload.State, Double)) {
            (snap.setUp, modelMissing, download, tune) = (false, true, state, .idle)
        }
        /// Partway through reading the whole phone, as `.reading` is.
        func midway(running: Bool) {
            (snap.indexed, snap.pending, snap.running, snap.lastStop) = (1_204, 4_106, running, nil)
            snap.pendingByKind = [.photo: 4_106]
            snap.reading = ReadingPreview(current: running ? files[0] : nil, recent: Array(files.dropFirst()))
        }
        /// Pins core's decision for a state the snapshot alone can't say (plugged in, minutes of Read for 15 left).
        func decided(_ blocker: StopReason?, minutes: Int32 = 0, pluggedIn: Bool = false) {
            snap.readingStatus = ReadingPolicy.shared.decide(
                progress: snap.readProgress, blocker: blocker, running: snap.running, reading: snap.reading.current != nil, words: Copy.words,
                readNowMinutes: minutes, pluggedIn: pluggedIn, faces: nil, facesWaiting: nil, people: nil, readersMissing: false)
        }
        switch fixture {
        case .readingWhileCharging:
            midway(running: true)
            decided(nil, pluggedIn: true)
        case .unplugged:
            midway(running: false)
            snap.lastStop = .unplugged
            decided(.unplugged)
        case .chargingUp:
            midway(running: false)
            snap.lastStop = .chargingUp
            decided(.chargingUp, pluggedIn: true)
        case .readNow:
            midway(running: true)
            decided(nil, minutes: 12)
        case .meaningPass:
            (snap.meaningWaiting, snap.running, snap.lastStop) = (1_204, true, nil)
            snap.reading = ReadingPreview(current: files[0], recent: Array(files.dropFirst()))
        case .firstDay:
            (snap.indexed, snap.waiting, snap.trial, snap.lastStop) = (0, 3_142, true, nil)
        case .notNow:
            // The demo's 30 carry over: "30 of 25,793 files processed".
            (snap.indexed, snap.waiting, snap.trial, snap.notNow, snap.lastStop) = (30, 25_763, true, true, nil)
        case .counting:
            snap.counted = false
        case .lostAccess:
            (photos, folders, snap.byKind, snap.indexed) = (.denied, [], [.pdf: 12, .doc: 9], 21)
        case .firstRun: inSetup(.notDetermined, mark: .none)
        case .accessNone: inSetup(.notDetermined)
        case .photosLimited: inSetup(.limited)
        case .photosDenied: inSetup(.denied)
        case .folderAdded: inSetup(.notDetermined, folders: ["Work"])
        case .accessBoth: inSetup(.authorized, folders: ["Downloads", "Work"])
        case .samplePicking:
            inSample(read: 0)
            setup = SetupInputs(mark: .accessChosen, skipped: false, decided: false, planned: 0, accessGiven: false)
            (taste, sample, snap.reading.current) = (TasteSnap(), nil, nil)
        case .sampleStart: inSample(read: 0)
        case .sampleReading: inSample()
        case .sampleDone: inSample(read: 30)
        case .sampleWaitingForWifi: inSample(download: (.waitingForWifi, 0.2))
        case .samplePaused:
            inSample()
            (snap.running, snap.reading.current) = (false, nil)
            snap.reading.pauseReason = ReadingPolicy.shared.stopCopy(stop: .lowBattery, words: Copy.words)
        case .sampleReady:
            inSample(read: 18, download: (.ready, 1))
            (snap.setUp, modelMissing, tune) = (true, false, .done(Copy.tuneSummary(.gpu, imageMs: 120)))
        case .homeIntro(let ready):
            midway(running: true)
            decided(nil, pluggedIn: true)
            setup = SetupInputs(mark: .accessChosen, skipped: false, decided: true, planned: 30, accessGiven: false)
            if !ready { withoutModel((.downloading, 0.34)) }
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
            snap.processed = 120
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
            // Setup shows Hello, with what this phone can do; Status ignores setup.
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
        snap.prefs = Self.prefs(searchTried: true, homeIntroDue: { if case .homeIntro = fixture { true } else { false } }())
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
        home = HomeFacts(
            newMedia: (1...4).map { file("Photo \($0).jpg", .photo, "Photos", size: 2_400_000, daysAgo: Double($0) / 2) },
            newShots: (1...3).map { file("Screenshot \($0).png", .screenshot, "Photos/Screenshots", size: 400_000, daysAgo: Double($0)) },
            newDocs: docs,
            starred: [docs[0], docs[2]], starredCount: 2,
            folders: 12, largeCount: 4, largeBytes: 6_400_000_000, possibleCopies: 38, kindTotals: kindTotals, folderTotals: [])
        storage = Storage(index: 412_000_000, model: ModelVariant.m440.generic.sizeBytes, cache: 38_000_000)
        starred = ["folder://fixture/Lease agreement 2026.pdf", "folder://fixture/Trip itinerary.docx"]
        self.suggestions = suggestions
        trySuggestions = suggestions
    }

    /// What reading shows: the file being read, then the newest read. A mix of kinds, as the demo processes them.
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
    private static func prefs(searchTried: Bool, homeIntroDue: Bool) -> Prefs {
        Prefs(
            kinds: Set(Snap.inside), onlyWhileCharging: true, schedule: .charging, readNowUntil: 0, excludedFolders: [],
            paused: false, sampleSize: nil, allowMobileData: false, rememberSearches: true,
            dynamicColor: true, setupStarted: false, notificationsDismissed: false, notificationsOfferDismissed: false,
            notificationsPermissionRequested: false, noMatchSeen: 0, showOnThisDay: true, readingCycle: 0,
            dismissedReadingCycle: -1, searchTried: searchTried, upgradeOfferDismissed: false,
            homeIntroDue: homeIntroDue, notifyWhenCaughtUp: false, recognizePeople: false, peopleOfferDismissed: false)
    }
}
