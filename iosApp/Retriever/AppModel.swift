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
        var indexed = 0
        var pending = 0
        var skipped = 0
        /// Long-document text read for keywords but not yet for meaning.
        var tail = 0
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
        var downloadBytes: Int64 = 0

        var total: Int { indexed + pending + skipped }
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

    var useMobileData: Bool {
        didSet { UserDefaults.standard.set(useMobileData, forKey: Self.mobileDataKey) }
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
    /// The app left the front while the tune ran, so its graphics-chip test failed: tune again once back.
    @ObservationIgnored private var tuneLeft = false

    private static let startedKey = "started"
    private static let setupSeenKey = "setupSeen"
    private static let mobileDataKey = "useMobileData"
    private static let log = Logger(subsystem: "app.retriever", category: "app")

    init(services: AppServices) {
        self.services = services
        photos = services.photos.status
        started = UserDefaults.standard.bool(forKey: Self.startedKey) || services.photos.isReady
        setupSeen = UserDefaults.standard.bool(forKey: Self.setupSeenKey)
        useMobileData = UserDefaults.standard.bool(forKey: Self.mobileDataKey)
        fixtureDownload = (.idle, 0)
        folders = Self.pickedFolders(services)
        observation = services.retriever.observeAll { [weak self] snapshot in
            let snap = Snap(snapshot)
            Task { @MainActor in self?.apply(snap) }
        }
        services.lifecycle?.onLibraryChange = { [weak self] in self?.wake() }
        tokens.append(NotificationCenter.default.addObserver(forName: IndexTask.ended, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.wake() }
        })
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
    }

    // MARK: Phase

    func setPhase(_ phase: ScenePhase) {
        guard let services else { return }
        switch phase {
        case .active:
            active = true
            photos = services.photos.status
            services.lifecycle?.observePhotos()
            // A background batch may still hold the index; it stops within a file and its task wakes the loop.
            services.retriever.cancelBatch()
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
            stopLoop()
        }
    }

    // MARK: Foreground loop

    private func startLoop() {
        guard let services, active, started, loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                UIApplication.shared.isIdleTimerDisabled = services.monitor.charging
                let next = try? await services.retriever.indexBatchSeconds(seconds: 120)
                UIApplication.shared.isIdleTimerDisabled = false
                if Task.isCancelled {
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
        // Swift cancellation doesn't reach the Kotlin coroutine.
        services?.retriever.cancelBatch()
        UIApplication.shared.isIdleTimerDisabled = false
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
        guard let services, started else { return }
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

    func setUseMobileData(_ on: Bool) {
        useMobileData = on
        // The network rule is part of the request, so a download waiting for Wi-Fi starts again under the new one.
        if on, case .waitingForWifi = download { retryDownload() }
    }

    func retryTune() {
        tune = .idle
        advance()
    }

    private func runTune() async {
        guard let services else { return }
        tune = .running
        tuneLeft = false
        do {
            let images = await Task.detached { Self.calibrationImages(services) }.value
            let result = try await services.retriever.calibrate(samples: RetrieverIosKt.samples(images: images, texts: Copy.calibrationTexts), tuneAgain: false)
            let imageMs = result.backends.first { $0.backend == result.chosen }?.imageMs?.doubleValue
            tune = .done(Copy.tuneSummary(result.chosen, imageMs: imageMs))
            try? await services.retriever.refreshSetUp()
            wake()
            if tuneLeft, active { await runTune() }
        } catch {
            Self.log.error("tune failed: \(error, privacy: .public)")
            tune = .failed
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

    private func apply(_ new: Snap) {
        let becameSetUp = new.setUp == true && snap?.setUp != true
        snap = new
        guard becameSetUp else { return }
        // Tuned in an earlier launch: say so rather than "starts after the download".
        if tune == .idle { tune = .done(new.backend.map { Copy.tuneSummary($0, imageMs: nil) } ?? "Tuned for this iPhone") }
        wake()
    }
}

extension AppModel.Snap {
    init(_ s: RetrieverSnapshot) {
        let state = s.counts.byState
        setUp = s.setUp?.boolValue
        byKind = s.counts.byKind.mapValues { $0.intValue }
        indexed = state[FileState.indexed]?.intValue ?? 0
        pending = state[FileState.pending]?.intValue ?? 0
        skipped = state[FileState.skipped]?.intValue ?? 0
        tail = Int(s.counts.tailChunks)
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
            snap.current = "Photo 2026-09-14 at 18.42.07"
            snap.tail = 3
        case .caughtUp:
            break
        case .paused:
            (snap.indexed, snap.pending, snap.paused, snap.lastStop) = (1_904, 1_238, true, .paused)
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
    }
}
