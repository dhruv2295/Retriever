import Observation
import Photos
import PhotosUI
@preconcurrency import RetrieverCore
import SwiftUI
import UIKit
import os

/// The app's state for every screen, on the main actor. With services it mirrors the Kotlin facade and runs setup
/// and the foreground reading loop; with a fixture (`init(fixture:)`) it only holds a state, so any screen renders
/// any state without an index, and every action is a no-op.
@Observable @MainActor
final class AppModel {
    /// One RetrieverSnapshot as Swift values.
    struct Snap: Equatable {
        var setUp: Bool?
        var byKind: [Kind: Int] = [:]
        /// Files of each kind still waiting to be read.
        var pendingByKind: [Kind: Int] = [:]
        var readByKind: [Kind: Int] = [:]
        var caughtUpDismissed = false
        var indexed = 0
        var pending = 0
        var trial = false
        /// Files outside the first run's short read, left unread until the person chooses (core's `counts.waiting`).
        var waiting = 0
        var skipped = 0
        /// Long files whose later parts are read for keywords but not yet for meaning.
        var tailFiles = 0
        /// Too little memory to understand photos: files are found by their names and the words inside them.
        var textOnly = false
        var ramGb = 0.0
        var running = false
        var processed = 0
        var failed = 0
        var pendingAtStart = 0
        var current: String?
        var reading = ReadingPreview()
        var lastStop: StopReason?
        var lastError: String?
        var filesPerMinute = 0.0
        var etaMinutes: Int?
        var backend: Backend?
        var paused = false
        var excludedFolders: Set<String> = []
        var allowMobileData = false
        var onlyWhileCharging = false
        var rememberSearches = true
        var showOnThisDay = true
        /// What Retriever reads inside; the rest is found by name. Settings offers `Snap.inside`.
        var kinds = Set(Snap.inside)
        var downloadBytes: Int64 = 0

        /// The kinds iOS reads inside, in the order Settings lists them. There is no audio source on iOS.
        static let inside: [Kind] = [.pdf, .doc, .screenshot, .photo, .video]
        /// Text-only iPhones read the words in documents and screenshots only; which kinds those are is core's.
        static let insideTextOnly: [Kind] = inside.filter { RetrieverKt.textOnlyKinds.contains($0) }

        var total: Int { indexed + pending + skipped }
        var libraryTotal: Int { byKind.values.reduce(0, +) }

        /// The short read is done and the rest waits on the person: Home offers to read it. Core counts `waiting` only
        /// under the short read's scope, so this is `scope == SAMPLE && waiting > 0`.
        var asksCommit: Bool { waiting > 0 && pending == 0 }

        /// Everything asked for is read and the reader is idle: the moment CAUGHT_UP is for, once ever.
        var isCaughtUp: Bool { setUp == true && indexed > 0 && pending == 0 && waiting == 0 && !running && !paused }

        /// What changes with every file read. `AppModel.snap` leaves it at its defaults, so a tick doesn't redraw the
        /// screens that don't show it; read `AppModel.live` where it is shown.
        var progress: Progress {
            get {
                Progress(processed: processed, current: current, filesPerMinute: filesPerMinute, etaMinutes: etaMinutes, lastError: lastError, reading: reading)
            }
            set {
                (processed, current, filesPerMinute, etaMinutes, lastError) = (
                    newValue.processed, newValue.current, newValue.filesPerMinute, newValue.etaMinutes, newValue.lastError
                )
                reading = newValue.reading
            }
        }

        /// The same counts and prefs with no per-file progress.
        var settled: Snap {
            var s = self
            s.progress = Progress()
            return s
        }
    }

    struct Progress: Equatable {
        var processed = 0
        var current: String?
        var filesPerMinute = 0.0
        var etaMinutes: Int?
        var lastError: String?
        var reading = ReadingPreview()
    }

    /// Bytes Retriever keeps on this device.
    struct Storage: Equatable {
        var index: Int64
        var model: Int64
        var cache: Int64
    }

    enum Tune: Equatable {
        case idle, running
        case done(String)
        case failed
    }

    struct SkippedFile: Identifiable {
        let id: Int64
        let name: String
        let folder: String
    }

    let services: AppServices?
    /// Nil until the first snapshot arrives. Assigned only when it differs, without per-file `progress`.
    var snap: Snap?
    /// Per-file progress, read only by Status and Home's status row.
    var progress = Progress()
    /// `snap` with `progress` put back; reading it follows both.
    var live: Snap? {
        guard var s = snap else { return nil }
        s.progress = progress
        return s
    }

    /// Own properties, so a screen reading one is not redrawn by every count that changes in `snap`.
    private(set) var setUp = false
    private(set) var textOnly = false
    /// Times `snap` was assigned; a test's way to see that an unchanged snapshot is skipped.
    @ObservationIgnored private(set) var snapWrites = 0
    var recent: [String] = []
    var photos: PHAuthorizationStatus
    /// Folders picked in the Files app, by name.
    var folders: [String] = []
    var tune: Tune = .idle
    var skippedFiles: [SkippedFile] = []
    /// Photos access was answered or a folder added: the download and reading start.
    var started: Bool {
        didSet { UserDefaults.standard.set(started, forKey: Self.startedKey) }
    }

    /// The person left setup for Home; until then setup stays on screen while the download and tune run.
    var setupSeen: Bool {
        didSet { UserDefaults.standard.set(setupSeen, forKey: Self.setupSeenKey) }
    }

    /// Prefs.allowMobileData, kept here so the toggle answers at once.
    var useMobileData: Bool
    /// Starred files by uri; Retriever.setStarred changes it.
    var starred: Set<String> = []
    /// The model file is gone: files are found by name until it downloads again.
    var modelMissing = false
    /// Nil until Settings has measured it.
    var storage: Storage?
    /// "Try searching" examples for the kinds that are ready.
    var suggestions: [String] = []
    /// Home's newest files, folder count and large files; nil until the first load.
    var fileRevision = 0
    var home: HomeFacts?
    /// Past years' photos on today's date (`refreshOnThisDay`); Home draws them while `Snap.showOnThisDay`.
    var memories: [OnThisDay.Card] = []
    @ObservationIgnored var homeLoadedAt = Date.distantPast
    /// Until the old setting is in Prefs, a snapshot's older value must not overwrite it.
    @ObservationIgnored var migratingMobileData = false

    /// The foreground loop keeps the screen awake for the batch it's running.
    var screenKeptOn = false
    /// A read the person started carries on after they leave (IndexTask.continuedId); the foreground loop stands
    /// aside until it ends.
    var continuing = false

    /// Status offers to keep reading after the person leaves: iOS 26 and files waiting.
    var canKeepReading: Bool {
        guard #available(iOS 26.0, *), let snap else { return false }
        return snap.setUp == true && snap.pending > 0 && !snap.paused && !continuing
    }

    private let fixtureDownload: (ModelDownload.State, Double)

    var download: ModelDownload.State { services?.download.state ?? fixtureDownload.0 }
    var downloadProgress: Double { services?.download.progress ?? fixtureDownload.1 }

    /// CAUGHT_UP is due: the first full read finished and was never celebrated. Home's status row or Status's header
    /// plays it and calls `celebrate()`.
    private(set) var caughtUpDue = false
    /// Core's `celebrated` flag as last read, and the persistence behind it (swapped in tests, and a no-op on fixtures).
    @ObservationIgnored private(set) var celebrated = false
    @ObservationIgnored private var checkingCelebration = false
    @ObservationIgnored var readCelebrated: () async -> Bool = { true }
    @ObservationIgnored var markCelebrated: () async -> Void = {}

    @ObservationIgnored var observation: KotlinAutoCloseable?
    @ObservationIgnored var tokens: [NSObjectProtocol] = []
    @ObservationIgnored var active = false
    @ObservationIgnored var loop: Task<Void, Never>?
    @ObservationIgnored var waiter: CheckedContinuation<Void, Never>?
    @ObservationIgnored var woken = false
    /// A foreground batch is running; a thermal change re-checks the screen.
    @ObservationIgnored var reading = false
    /// The app left the front while the tune ran, so its graphics-chip test failed: tune again once back.
    @ObservationIgnored var tuneLeft = false
    /// The running tune is a Tune again (DEBUG), so it keeps the stored photo detail.
    @ObservationIgnored var tuningAgain = false

    private static let startedKey = "started"
    private static let setupSeenKey = "setupSeen"
    /// Where mobile data was kept before it moved into Prefs.
    static let legacyMobileDataKey = "useMobileData"
    static let log = Logger(subsystem: "app.retriever", category: "app")

    init(services: AppServices) {
        self.services = services
        photos = services.photos.status
        started = UserDefaults.standard.bool(forKey: Self.startedKey) || services.photos.isReady
        setupSeen = UserDefaults.standard.bool(forKey: Self.setupSeenKey)
        // The pref is read once the snapshot arrives; until then, and for a download that starts first, the old key answers.
        useMobileData = UserDefaults.standard.bool(forKey: Self.legacyMobileDataKey)
        fixtureDownload = (.idle, 0)
        folders = Self.pickedFolders(services)
        let textOnly = services.retriever.textOnly
        let ramGb = services.retriever.probe.ramGb
        observation = services.retriever.observeAll { @Sendable [weak self] snapshot in
            let snap = Snap(snapshot, textOnly: textOnly, ramGb: ramGb)
            Task { @MainActor in
                self?.apply(snap, starred: snapshot.starred, modelMissing: snapshot.modelMissing, suggestions: self?.suggestions ?? [])
            }
        }
        readCelebrated = { (try? await services.retriever.celebrated().boolValue) ?? true }
        markCelebrated = { try? await services.retriever.markCelebrated() }
        migrateMobileData(services)
        services.lifecycle?.onLibraryChange = { [weak self] in self?.wake() }
        services.lifecycle?.keepsReading = { [weak self] in self?.continuing ?? false }
        tokens.append(
            NotificationCenter.default.addObserver(forName: IndexTask.ended, object: nil, queue: .main) { [weak self] note in
                let continued = (note.object as? String) == IndexTask.continuedId
                MainActor.assumeIsolated {
                    guard let self else { return }
                    if continued {
                        self.continuing = false
                        self.startLoop()
                    }
                    self.wake()
                }
            })
        for name in [
            ProcessInfo.thermalStateDidChangeNotification, Notification.Name.NSProcessInfoPowerStateDidChange,
            UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification,
        ] {
            tokens.append(
                NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated { self?.keepScreenOn(self?.reading ?? false) }
                })
        }
        watchDownload()
    }

    /// Fixture state; see init(fixture:).
    init(snap: Snap, photos: PHAuthorizationStatus, download: (ModelDownload.State, Double), tune: Tune, started: Bool, skipped: [SkippedFile]) {
        services = nil
        self.snap = snap.settled
        progress = snap.progress
        setUp = snap.setUp == true
        textOnly = snap.textOnly
        self.photos = photos
        fixtureDownload = download
        self.tune = tune
        self.started = started
        setupSeen = started
        skippedFiles = skipped
        folders = started ? ["Work"] : []
        useMobileData = false
        screenKeptOn = snap.running
    }

    /// Each property is assigned only when it differs, so a snapshot that changed nothing a screen shows redraws nothing.
    func apply(_ incoming: Snap, starred: Set<String>, modelMissing: Bool, suggestions: [String]) {
        if self.starred != starred { self.starred = starred }
        if self.modelMissing != modelMissing { self.modelMissing = modelMissing }
        if self.suggestions != suggestions { self.suggestions = suggestions }
        if !migratingMobileData, useMobileData != incoming.allowMobileData { useMobileData = incoming.allowMobileData }
        if progress != incoming.progress { progress = incoming.progress }
        let new = incoming.settled
        guard snap != new else { return }
        let becameSetUp = new.setUp == true && snap?.setUp != true
        // Files waiting or not decides the screen, so re-decide when that flips (the first counts included).
        let waitingChanged = (new.pending > 0) != ((snap?.pending ?? 0) > 0)
        snap = new
        snapWrites += 1
        if setUp != (new.setUp == true) { setUp = new.setUp == true }
        if textOnly != new.textOnly { textOnly = new.textOnly }
        if waitingChanged { keepScreenOn(reading) }
        considerCelebration(new)
        guard becameSetUp else { return }
        // Tuned in an earlier launch: say so rather than "starts after the download".
        if tune == .idle { tune = .done(new.backend.map { Copy.tuneSummary($0, imageMs: nil) } ?? "Tuned for this \(Copy.device)") }
        wake()
    }

    func filesChanged() async {
        fileRevision += 1
        await refreshHome()
    }

    func dismissCaughtUp() {
        snap?.caughtUpDismissed = true
        Task { try? await services?.retriever.dismissCaughtUp() }
    }

    /// Asks core once per catch-up whether the first full read was celebrated; once it was (or is known to be), never again.
    private func considerCelebration(_ s: Snap) {
        guard s.isCaughtUp else {
            if caughtUpDue { caughtUpDue = false }
            return
        }
        guard !celebrated, !caughtUpDue, !checkingCelebration else { return }
        checkingCelebration = true
        Task {
            celebrated = await readCelebrated()
            checkingCelebration = false
            caughtUpDue = !celebrated && snap?.isCaughtUp == true
        }
    }

    /// The dog played CAUGHT_UP: never offer it again, in this launch or a later one.
    func celebrate() {
        guard caughtUpDue else { return }
        caughtUpDue = false
        celebrated = true
        Task { await markCelebrated() }
    }

    /// Read while charging / Read now, from Home's commit card: the rest is read from now on. The same steps as setup's
    /// commit choice; on iOS 26 Read now hands the reading to a task that goes on after the person leaves.
    func commit(whileCharging: Bool) {
        guard let retriever = services?.retriever else { return }
        Task {
            try? await retriever.commit(whileCharging: whileCharging)
            IndexTask.schedule()
            wake()
            setupSeen = true
            guard !whileCharging, #available(iOS 26.0, *) else { return }
            // The count of waiting files follows the new scope a moment later; keepReading needs it.
            for _ in 0..<10 where !canKeepReading { try? await Task.sleep(for: .milliseconds(100)) }
            keepReading()
        }
    }
}

extension AppModel.Snap {
    init(_ s: RetrieverSnapshot, textOnly: Bool = false, ramGb: Double = 0) {
        self.textOnly = textOnly
        self.ramGb = ramGb
        let state = s.counts.byState
        setUp = s.setUp?.boolValue
        byKind = s.counts.byKind.mapValues { $0.intValue }
        for (key, n) in s.counts.byKindState where key.second == FileState.pending {
            if let kind = key.first { pendingByKind[kind] = n.intValue }
        }
        for (key, n) in s.counts.byKindState where key.second == FileState.indexed {
            if let kind = key.first { readByKind[kind] = n.intValue }
        }
        caughtUpDismissed = s.prefs.caughtUpDismissed
        allowMobileData = s.prefs.allowMobileData
        onlyWhileCharging = s.prefs.onlyWhileCharging
        rememberSearches = s.prefs.rememberSearches
        showOnThisDay = s.prefs.showOnThisDay
        kinds = s.prefs.kinds
        indexed = state[FileState.indexed]?.intValue ?? 0
        pending = state[FileState.pending]?.intValue ?? 0
        waiting = Int(s.counts.waiting)
        trial = s.counts.trial
        skipped = state[FileState.skipped]?.intValue ?? 0
        tailFiles = Int(s.counts.tailFiles)
        running = s.state.running
        processed = Int(s.state.processed)
        failed = Int(s.state.failed)
        pendingAtStart = Int(s.state.pendingAtStart)
        current = s.state.current
        reading = ReadingPreview(current: s.state.currentFile, recent: s.state.recentFiles, pauseReason: s.state.pauseReason)
        lastStop = s.state.lastStop
        lastError = s.state.lastError
        filesPerMinute = s.state.filesPerMinute
        etaMinutes = s.state.etaMinutes?.intValue
        backend = s.state.backend
        paused = s.prefs.paused
        excludedFolders = s.prefs.excludedFolders
        downloadBytes = s.requiredBuild.sizeBytes
    }
}
