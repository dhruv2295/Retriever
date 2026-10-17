import Observation
import os
import Photos
import PhotosUI
import RetrieverCore
import SwiftUI
import UIKit

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
        var indexed = 0
        var pending = 0
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
        /// What Retriever reads inside; the rest is found by name. Settings offers `Snap.inside`.
        var kinds = Set(Snap.inside)
        var downloadBytes: Int64 = 0

        /// The kinds iOS reads inside, in the order Settings lists them. There is no audio source on iOS.
        static let inside: [Kind] = [.pdf, .doc, .screenshot, .photo, .video]
        /// Text-only iPhones read the words in documents and screenshots only; which kinds those are is core's.
        static let insideTextOnly: [Kind] = inside.filter { RetrieverKt.textOnlyKinds.contains($0) }

        var total: Int { indexed + pending + skipped }
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
    /// Nil until the first snapshot arrives.
    private(set) var snap: Snap?
    private(set) var recent: [String] = []
    private(set) var photos: PHAuthorizationStatus
    /// Folders picked in the Files app, by name.
    private(set) var folders: [String] = []
    private(set) var tune: Tune = .idle
    private(set) var skippedFiles: [SkippedFile] = []
    /// Photos access was answered or a folder added: the download and reading start.
    private(set) var started: Bool {
        didSet { UserDefaults.standard.set(started, forKey: Self.startedKey) }
    }

    /// The person left setup for Home; until then setup stays on screen while the download and tune run.
    private(set) var setupSeen: Bool {
        didSet { UserDefaults.standard.set(setupSeen, forKey: Self.setupSeenKey) }
    }

    /// Prefs.allowMobileData, kept here so the toggle answers at once.
    private(set) var useMobileData: Bool
    /// Starred files by uri; Retriever.setStarred changes it.
    private(set) var starred: Set<String> = []
    /// The model file is gone: files are found by name until it downloads again.
    private(set) var modelMissing = false
    /// Nil until Settings has measured it.
    private(set) var storage: Storage?
    /// "Try searching" examples for the kinds that are ready.
    private(set) var suggestions: [String] = []
    /// Home's newest files, folder count and large files; nil until the first load.
    private(set) var home: HomeFacts?
    @ObservationIgnored private var homeLoadedAt = Date.distantPast
    /// Until the old setting is in Prefs, a snapshot's older value must not overwrite it.
    @ObservationIgnored private var migratingMobileData = false

    /// The foreground loop keeps the screen awake for the batch it's running.
    private(set) var screenKeptOn = false
    /// A read the person started carries on after they leave (IndexTask.continuedId); the foreground loop stands
    /// aside until it ends.
    private(set) var continuing = false

    /// Status offers to keep reading after the person leaves: iOS 26 and files waiting.
    var canKeepReading: Bool {
        guard #available(iOS 26.0, *), let snap else { return false }
        return snap.setUp == true && snap.pending > 0 && !snap.paused && !continuing
    }

    private let fixtureDownload: (ModelDownload.State, Double)

    var download: ModelDownload.State { services?.download.state ?? fixtureDownload.0 }
    var downloadProgress: Double { services?.download.progress ?? fixtureDownload.1 }
    var setUp: Bool { snap?.setUp == true }

    @ObservationIgnored private var observation: KotlinAutoCloseable?
    @ObservationIgnored private var tokens: [NSObjectProtocol] = []
    @ObservationIgnored private var active = false
    @ObservationIgnored private var loop: Task<Void, Never>?
    @ObservationIgnored private var waiter: CheckedContinuation<Void, Never>?
    @ObservationIgnored private var woken = false
    /// A foreground batch is running; a thermal change re-checks the screen.
    @ObservationIgnored private var reading = false
    /// The app left the front while the tune ran, so its graphics-chip test failed: tune again once back.
    @ObservationIgnored private var tuneLeft = false
    /// The running tune is a Tune again (DEBUG), so it keeps the stored photo detail.
    @ObservationIgnored private var tuningAgain = false

    private static let startedKey = "started"
    private static let setupSeenKey = "setupSeen"
    /// Where mobile data was kept before it moved into Prefs.
    private static let legacyMobileDataKey = "useMobileData"
    private static let log = Logger(subsystem: "app.retriever", category: "app")

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
        observation = services.retriever.observeAll { [weak self] snapshot in
            let snap = Snap(snapshot, textOnly: textOnly, ramGb: ramGb)
            let suggestions = services.retriever.suggestions(byKindState: snapshot.counts.byKindState)
            Task { @MainActor in self?.apply(snap, starred: snapshot.starred, modelMissing: snapshot.modelMissing, suggestions: suggestions) }
        }
        migrateMobileData(services)
        services.lifecycle?.onLibraryChange = { [weak self] in self?.wake() }
        services.lifecycle?.keepsReading = { [weak self] in self?.continuing ?? false }
        tokens.append(NotificationCenter.default.addObserver(forName: IndexTask.ended, object: nil, queue: .main) { [weak self] note in
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
        for name in [ProcessInfo.thermalStateDidChangeNotification, Notification.Name.NSProcessInfoPowerStateDidChange,
                     UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification] {
            tokens.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.keepScreenOn(self?.reading ?? false) }
            })
        }
        watchDownload()
    }

    /// Fixture state; see init(fixture:).
    fileprivate init(snap: Snap, photos: PHAuthorizationStatus, download: (ModelDownload.State, Double), tune: Tune, started: Bool, skipped: [SkippedFile]) {
        services = nil
        self.snap = snap
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

    // MARK: Phase

    func setPhase(_ phase: ScenePhase) {
        guard let services else { return }
        switch phase {
        case .active:
            // Back from Control Center, a banner or the app switcher, nothing stopped, so there's nothing to redo.
            guard !active else { return }
            active = true
            photos = services.photos.status
            services.lifecycle?.observePhotos()
            // A background batch may still hold the index; it stops within a file and its task wakes the loop.
            // The person's own continued read is left to finish; its end restarts the loop.
            if !continuing { services.retriever.cancelBatch() }
            Task {
                try? await services.retriever.warmSearch()
                recent = (try? await services.retriever.recentQueries()) ?? recent
            }
            advance()
            startLoop()
        case .background:
            stopLoop()
            if tune == .running { tuneLeft = true }
            // Even when caught up: photos taken before the next open get read overnight.
            if let snap, snap.setUp == true, !snap.paused { IndexTask.schedule() }
        default:
            break
        }
    }

    // MARK: Foreground loop

    private func startLoop() {
        guard let services, active, started, !continuing, loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                self?.keepScreenOn(true)
                let next = try? await services.retriever.indexBatchSeconds(seconds: 120)
                self?.keepScreenOn(false)
                if Task.isCancelled {
                    Self.log.info("foreground reading stopped")
                    // This batch held the index until now, so a newer loop may have found it locked and gone to sleep.
                    self?.wake()
                    return
                }
                guard let self else { return }
                if next == NextRun.soon { continue }
                // .done, .whenIdle, .whenCharging: nothing to do until something changes.
                await sleepUntilWoken(timeout: next == NextRun.afterCooldown ? .seconds(15 * 60) : nil)
            }
        }
    }

    private func stopLoop() {
        active = false
        loop?.cancel()
        loop = nil
        // Swift cancellation doesn't reach the Kotlin coroutine. The continued read is the person's: it goes on.
        if !continuing { services?.retriever.cancelBatch() }
        keepScreenOn(false)
    }

    /// On while files wait to be read, on battery too: a locked iPhone sends the app to the background, which stops
    /// reading. Off when only long files' later parts are left, when the iPhone runs hot, and on a low battery or in
    /// Low Power Mode unless it is charging: reading goes on, and the screen locks as the person set it to.
    private func keepScreenOn(_ on: Bool) {
        reading = on
        let info = ProcessInfo.processInfo
        let hot = info.thermalState == .serious || info.thermalState == .critical
        let monitor = services?.monitor
        let power = monitor?.charging == true || ((monitor?.batteryPct ?? 100) > 20 && !info.isLowPowerModeEnabled)
        screenKeptOn = on && !hot && power && (snap?.pending ?? 0) > 0
        UIApplication.shared.isIdleTimerDisabled = screenKeptOn
        Self.log.info("isIdleTimerDisabled \(self.screenKeptOn, privacy: .public)")
    }

    /// Status's "Keep reading after I leave", and Setup's done button: hands reading to a task iOS lets carry on
    /// after the person leaves, with its progress on the Lock Screen.
    func keepReading() {
        guard #available(iOS 26.0, *), let services, canKeepReading, let snap else { return }
        // Submitted in the tap itself: iOS takes it only from an app in front. The task waits for this loop's batch,
        // which stops within its current file.
        do {
            try IndexTask.submitContinued(snap)
            Self.log.info("continued reading submitted")
        } catch {
            Self.log.error("couldn't keep reading after leaving: \(error, privacy: .public)")
            return
        }
        continuing = true
        loop?.cancel()
        loop = nil
        services.retriever.cancelBatch()
        keepScreenOn(false)
    }

    /// Something changed (a photo, a folder, Resume, setup finishing): run the next batch now.
    func wake() {
        if let waiter {
            self.waiter = nil
            waiter.resume()
        } else {
            woken = true
        }
    }

    private func sleepUntilWoken(timeout: Duration?) async {
        if woken {
            woken = false
            return
        }
        let timer = timeout.map { t in Task { [weak self] in
            try? await Task.sleep(for: t)
            if !Task.isCancelled { self?.wake() }
        } }
        await withTaskCancellationHandler {
            await withCheckedContinuation { waiter = $0 }
        } onCancel: {
            Task { @MainActor [weak self] in self?.wake() }
        }
        timer?.cancel()
    }

    // MARK: Setup chain: download, then tune. Lives here, so leaving the screen never stops it.

    private func watchDownload() {
        guard let download = services?.download else { return }
        withObservationTracking { _ = download.state } onChange: { [weak self] in
            Task { @MainActor in
                self?.advance()
                self?.watchDownload()
            }
        }
    }

    /// The next setup step; safe to call any time.
    func advance() {
        // Text-only has nothing to download or tune, whatever setUp says before access is answered.
        guard let services, started, !services.retriever.textOnly else { return }
        Task {
            try? await services.retriever.refreshSetUp()
            if tuneLeft, active, tune != .running { return await runTune() }
            guard (services.retriever.setUp.value as? KotlinBoolean)?.boolValue != true,
                  let build = services.retriever.requiredBuild.value as? ModelBuild else { return }
            Self.log.info("setup: download \(String(describing: services.download.state), privacy: .public), tune \(String(describing: self.tune), privacy: .public)")
            switch services.download.state {
            case .idle: services.download.start(build, allowCellular: useMobileData)
            // Only on screen: iOS refuses the graphics chip in the background, so a tune there would settle on the
            // processor for good. Coming back to the front calls advance() again.
            case .ready: if tune == .idle, active { await runTune() }
            default: break
            }
        }
    }

    func retryDownload() {
        guard let services, let build = services.retriever.requiredBuild.value as? ModelBuild else { return }
        services.download.start(build, allowCellular: useMobileData)
    }

    /// The old UserDefaults key moves into Prefs once, then goes. A write that fails leaves the key for the next launch.
    private func migrateMobileData(_ services: AppServices) {
        guard let legacy = UserDefaults.standard.object(forKey: Self.legacyMobileDataKey) as? Bool else { return }
        migratingMobileData = true
        Task {
            try? await services.retriever.refreshSetUp()
            if legacy, (services.retriever.prefs.value as? Prefs)?.allowMobileData != true {
                do { try await edit(mobileData: true) } catch {
                    migratingMobileData = false
                    return Self.log.error("mobile data setting not moved: \(error, privacy: .public)")
                }
            }
            UserDefaults.standard.removeObject(forKey: Self.legacyMobileDataKey)
            migratingMobileData = false
        }
    }

    /// Changes the settings given and leaves the rest as they are.
    private func edit(kinds: Set<Kind>? = nil, charging: Bool? = nil, mobileData: Bool? = nil, remember: Bool? = nil,
                      excluded: Set<String>? = nil) async throws {
        guard let services else { return }
        try await services.retriever.updatePrefs { p in
            p.doCopy(kinds: kinds ?? p.kinds, onlyWhileCharging: charging ?? p.onlyWhileCharging, excludedFolders: excluded ?? p.excludedFolders,
                     paused: p.paused, sampleSize: p.sampleSize, allowMobileData: mobileData ?? p.allowMobileData,
                     rememberSearches: remember ?? p.rememberSearches, dynamicColor: p.dynamicColor)
        }
    }

    func setUseMobileData(_ on: Bool) {
        useMobileData = on
        Task { try? await edit(mobileData: on) }
        // The network rule is part of the request, so a download waiting for Wi-Fi starts again under the new one.
        if on, case .waitingForWifi = download { retryDownload() }
    }

    // MARK: Settings actions. Each shows its new value at once; the next snapshot confirms it.

    func setKind(_ kind: Kind, _ on: Bool) {
        guard var kinds = snap?.kinds else { return }
        if on { kinds.insert(kind) } else { kinds.remove(kind) }
        snap?.kinds = kinds
        Task {
            try? await edit(kinds: kinds)
            services?.retriever.notifyChanged()
            wake()
        }
    }

    func setOnlyWhileCharging(_ on: Bool) {
        snap?.onlyWhileCharging = on
        Task {
            try? await edit(charging: on)
            // The request waiting with iOS keeps the old rule until it is submitted again.
            if services != nil, let snap, snap.setUp == true, !snap.paused { IndexTask.schedule() }
        }
    }

    /// Turning it off also clears what was saved (Retriever.updatePrefs).
    func setRememberSearches(_ on: Bool) {
        snap?.rememberSearches = on
        if !on { recent = [] }
        Task { try? await edit(remember: on) }
    }

    func clearRecentSearches() {
        recent = []
        guard let services else { return }
        Task { try? await services.retriever.clearRecentQueries() }
    }

    /// Settings' "Search again" on a skipped folder; the next reading round reads it.
    func restoreFolder(_ path: String) {
        guard let skipped = snap?.excludedFolders else { return }
        let rest = skipped.subtracting([path])
        snap?.excludedFolders = rest
        Task {
            try? await edit(excluded: rest)
            services?.retriever.notifyChanged()
            wake()
        }
    }

    // MARK: Storage

    func loadStorage() async {
        guard let services else { return }
        let support = services.modelsDir.deletingLastPathComponent()
        let index = support.appendingPathComponent("Retriever")
        let models = services.modelsDir
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("embed")
        storage = await Task.detached { Storage(index: Self.size(index), model: Self.size(models), cache: Self.size(cache)) }.value
    }

    nonisolated private static func size(_ dir: URL) -> Int64 {
        let files = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.totalFileAllocatedSizeKey])
        var total: Int64 = 0
        while let file = files?.nextObject() as? URL {
            total += Int64((try? file.resourceValues(forKeys: [.totalFileAllocatedSizeKey]))?.totalFileAllocatedSize ?? 0)
        }
        return total
    }

    /// Settings' Free up space: the model and what was built from it go, the index stays. Search then runs on names
    /// and words (modelMissing) until downloadAgain().
    func freeUpSpace() {
        guard let services, let build = services.retriever.requiredBuild.value as? ModelBuild else { return }
        // The reading loop stands aside, so no batch reopens the model between its unloading and the file going.
        let wasActive = active
        if wasActive { stopLoop() }
        Task {
            try? await services.retriever.release()
            services.download.deleteModel(build)
            let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("embed")
            (try? FileManager.default.contentsOfDirectory(at: cache, includingPropertiesForKeys: nil))?.forEach { try? FileManager.default.removeItem(at: $0) }
            try? await services.retriever.refreshSetUp()
            await loadStorage()
            if wasActive {
                active = true
                startLoop()
            }
        }
    }

    func downloadAgain() { retryDownload() }

    func retryTune() {
        tune = .idle
        advance()
    }

    #if DEBUG
        /// Status's testing section, as on Android: measure again, keeping the photo detail already chosen. Only in
        /// front, since iOS refuses the graphics chip in the background.
        func tuneAgain() {
            guard active, tune != .running else { return }
            tuningAgain = true
            Task { await runTune() }
        }
    #endif

    private func runTune() async {
        guard let services else { return }
        tune = .running
        tuneLeft = false
        do {
            let images = await Task.detached { Self.calibrationImages(services) }.value
            let result = try await services.retriever.calibrate(samples: RetrieverIosKt.samples(images: images, texts: Copy.calibrationTexts), tuneAgain: tuningAgain)
            let imageMs = result.backends.first { $0.backend == result.chosen }?.imageMs?.doubleValue
            tune = .done(Copy.tuneSummary(result.chosen, imageMs: imageMs))
            try? await services.retriever.refreshSetUp()
            wake()
            if tuneLeft, active { return await runTune() }
            tuningAgain = false
        } catch {
            Self.log.error("tune failed: \(error, privacy: .public)")
            tune = .failed
            tuningAgain = false
        }
    }

    /// The newest ten photos at 512 px, never fetched from iCloud; the illustrations when there are none.
    nonisolated private static func calibrationImages(_ services: AppServices) -> [Data] {
        var images: [Data] = []
        if services.photos.isReady {
            let options = PHFetchOptions()
            options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            options.fetchLimit = 10
            PHAsset.fetchAssets(with: .image, options: options).enumerateObjects { asset, _, _ in
                if let jpeg = services.extractor.imageJpeg(uri: "ph://\(asset.localIdentifier)", maxSide: 512) { images.append(jpeg) }
            }
        }
        if images.isEmpty {
            images = ["IllusWelcome", "IllusReading", "IllusEmpty"].compactMap { UIImage(named: $0)?.jpegData(compressionQuality: 0.85) }
        }
        return images
    }

    // MARK: Access

    func requestPhotos() {
        guard let services else { return }
        Task {
            photos = await services.photos.requestAccess()
            services.lifecycle?.observePhotos()
            startIfReady()
        }
    }

    func chooseMorePhotos() {
        guard services != nil,
              let root = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController
        else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: top) { [weak self] _ in
            Task { @MainActor in
                self?.services?.retriever.notifyChanged()
                self?.wake()
            }
        }
    }

    func openSettings() {
        guard services != nil, let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    /// One line per pick, for the screen to show.
    func addFolders(_ urls: [URL]) -> [FolderSource.AddResult] {
        guard let services else { return [] }
        let results = urls.map(services.folders.addFolder)
        folders = Self.pickedFolders(services)
        if results.contains(where: { if case .added = $0 { true } else { false } }) {
            started = true
            startIfReady()
        }
        return results
    }

    func finishSetup() {
        setupSeen = true
        keepReading()
    }

    private func startIfReady() {
        // A no starts too: Retriever's own folder in the Files app is always searched.
        if services?.photos.isReady == true || photos == .denied || photos == .restricted { started = true }
        guard started else { return }
        advance()
        startLoop()
        wake()
    }

    private static func pickedFolders(_ services: AppServices) -> [String] {
        services.folders.folders.filter { $0.id != FolderSource.documentsId }.map(\.name)
    }

    // MARK: Status actions

    func pause() {
        guard let services else { return }
        Task { try? await services.retriever.pause() }
    }

    func resume() {
        guard let services else { return }
        Task {
            try? await services.retriever.resume()
            wake()
        }
    }

    func loadSkipped() {
        guard let services else { return }
        Task {
            let files = (try? await services.retriever.skipped(limit: 50)) ?? []
            skippedFiles = files.map { SkippedFile(id: $0.id, name: $0.file.name, folder: $0.file.folder) }
        }
    }

    func retrySkipped() {
        guard let services else { return }
        Task {
            try? await services.retriever.retrySkipped()
            skippedFiles = []
            wake()
        }
    }

    /// Starring a file that isn't in the index does nothing; the next snapshot settles it either way.
    func setStarred(_ uri: String, _ on: Bool) {
        guard let services else { return }
        if on { starred.insert(uri) } else { starred.remove(uri) }
        Task { try? await services.retriever.setStarred(uri: uri, on: on) }
    }

    /// Home's facts, at most once every two seconds while the counts change.
    func refreshHome() async {
        guard let services else { return }
        let wait = 2 - Date.now.timeIntervalSince(homeLoadedAt)
        if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
        guard !Task.isCancelled, let facts = try? await services.retriever.home() else { return }
        homeLoadedAt = .now
        home = facts
    }

    private func apply(_ new: Snap, starred: Set<String>, modelMissing: Bool, suggestions: [String]) {
        if self.starred != starred { self.starred = starred }
        if self.modelMissing != modelMissing { self.modelMissing = modelMissing }
        if self.suggestions != suggestions { self.suggestions = suggestions }
        if !migratingMobileData { useMobileData = new.allowMobileData }
        let becameSetUp = new.setUp == true && snap?.setUp != true
        // Files waiting or not decides the screen, so re-decide when that flips (the first counts included).
        let waitingChanged = (new.pending > 0) != ((snap?.pending ?? 0) > 0)
        snap = new
        if waitingChanged { keepScreenOn(reading) }
        guard becameSetUp else { return }
        // Tuned in an earlier launch: say so rather than "starts after the download".
        if tune == .idle { tune = .done(new.backend.map { Copy.tuneSummary($0, imageMs: nil) } ?? "Tuned for this \(Copy.device)") }
        wake()
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
        allowMobileData = s.prefs.allowMobileData
        onlyWhileCharging = s.prefs.onlyWhileCharging
        rememberSearches = s.prefs.rememberSearches
        kinds = s.prefs.kinds
        indexed = state[FileState.indexed]?.intValue ?? 0
        pending = state[FileState.pending]?.intValue ?? 0
        skipped = state[FileState.skipped]?.intValue ?? 0
        tailFiles = Int(s.counts.tailFiles)
        running = s.state.running
        processed = Int(s.state.processed)
        failed = Int(s.state.failed)
        pendingAtStart = Int(s.state.pendingAtStart)
        current = s.state.current
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

// MARK: Fixtures

/// Pinned for screenshots and previews: each case is a whole-app state, no index needed.
enum AppFixture {
    case firstRun, photosLimited, photosDenied
    case downloading(Double)
    case waitingForWifi, downloadFailed, tuning
    case reading(done: Int, total: Int)
    case caughtUp, paused
    case skipped(Int)
    case noAccess
    case tailFiles(Int)
    case textOnly(ramGb: Double)
}

extension AppModel {
    convenience init(fixture: AppFixture) {
        var snap = Snap(setUp: true, byKind: [.photo: 2_140, .screenshot: 812, .pdf: 64, .doc: 31, .video: 95], indexed: 3_142,
                        lastStop: .done, downloadBytes: ModelVariant.m440.generic.sizeBytes)
        var photos = PHAuthorizationStatus.authorized
        var download: (ModelDownload.State, Double) = (.ready, 1)
        var tune = Tune.done(Copy.tuneSummary(.cpu, imageMs: 410))
        var started = true
        var skipped: [SkippedFile] = []
        func gettingReady() {
            snap.setUp = false
            snap.indexed = 0
            snap.pending = 3_142
            snap.lastStop = nil
            tune = .idle
        }
        switch fixture {
        case .firstRun:
            gettingReady()
            (photos, started, download) = (.notDetermined, false, (.idle, 0))
        case .photosLimited:
            gettingReady()
            photos = .limited
            download = (.downloading, 0.12)
        case .photosDenied:
            gettingReady()
            (photos, download) = (.denied, (.downloading, 0.05))
        case let .downloading(p):
            gettingReady()
            download = (.downloading, p)
        case .waitingForWifi:
            gettingReady()
            download = (.waitingForWifi, 0)
        case .downloadFailed:
            gettingReady()
            download = (.failed("The download stopped: The Internet connection appears to be offline. It picks up where it left off when you try again."), 0.41)
        case .tuning:
            gettingReady()
            tune = .running
        case let .reading(done, total):
            (snap.indexed, snap.pending, snap.running, snap.lastStop) = (done, total - done, true, nil)
            (snap.processed, snap.pendingAtStart, snap.filesPerMinute) = (120, total - done + 120, 38)
            snap.etaMinutes = Int((Double(total - done) / 38).rounded(.up))
            snap.pendingByKind = [.photo: total - done]
            snap.current = "Photo 2026-09-14 at 18.42.07"
            snap.tailFiles = 3
        case .caughtUp:
            break
        case let .tailFiles(n):
            snap.tailFiles = n
        case let .textOnly(ramGb):
            (snap.textOnly, snap.ramGb, snap.byKind) = (true, ramGb, [.pdf: 64, .doc: 31, .photo: 0])
            (download, tune) = ((.idle, 0), .idle)
        case .paused:
            (snap.indexed, snap.pending, snap.paused, snap.lastStop) = (1_904, 1_238, true, .paused)
            snap.pendingByKind = [.photo: 1_238]
        case let .skipped(n):
            (snap.indexed, snap.skipped) = (3_142 - n, n)
            skipped = (0..<min(n, 6)).map { i in
                SkippedFile(id: Int64(i), name: ["Scan 2019.pdf", "Locked statement.pdf", "IMG_0412.HEIC", "notes.pages", "Clip 3.mov", "export.zip"][i],
                            folder: ["Retriever", "Downloads", "Photos/Camera", "Retriever/Work", "Photos/Videos", "Downloads"][i])
            }
        case .noAccess:
            (photos, snap.byKind, snap.indexed) = (.denied, [.pdf: 12, .doc: 9], 21)
        }
        self.init(snap: snap, photos: photos, download: download, tune: tune, started: started, skipped: skipped)
        func file(_ name: String, _ kind: Kind, _ folder: String, size: Int64, daysAgo: Double) -> SourceFile {
            SourceFile(uri: "folder://fixture/\(name)", name: name, folder: folder, mime: "application/pdf", kind: kind, size: size,
                       modifiedAt: Int64((Date().timeIntervalSince1970 - daysAgo * 86400) * 1000), takenAt: nil)
        }
        home = HomeFacts(
            newDocs: [file("Lease agreement 2026.pdf", .pdf, "Retriever/Download", size: 2_100_000, daysAgo: 0),
                      file("Electricity bill October.pdf", .pdf, "Retriever/Download", size: 340_000, daysAgo: 1),
                      file("Trip itinerary.docx", .doc, "Retriever/Documents", size: 88_000, daysAgo: 14)],
            newShots: (1...6).map { file("Screenshot \($0).png", .screenshot, "Photos/Screenshots", size: 400_000, daysAgo: Double($0)) },
            folders: 12, largeCount: 4, largeBytes: 6_400_000_000, nameOnly: 0)
        storage = Storage(index: 412_000_000, model: ModelVariant.m440.generic.sizeBytes, cache: 38_000_000)
        starred = ["folder://fixture/Lease agreement 2026.pdf", "folder://fixture/Trip itinerary.docx"]
        suggestions = ["wifi password", "boarding pass", "electricity bill"]
    }
}
