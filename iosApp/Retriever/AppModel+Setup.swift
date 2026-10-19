import Observation
import Photos
import PhotosUI
@preconcurrency import RetrieverCore
import SwiftUI
import UIKit
import os

/// Setup: download, then tune. It lives on the model, so leaving the screen never stops it.
extension AppModel {
    func watchDownload() {
        guard let download = services?.download else { return }
        withObservationTracking {
            _ = download.state
        } onChange: { [weak self] in
            Task { @MainActor in
                self?.advance()
                self?.watchDownload()
            }
        }
    }

    /// The next setup step; safe to call any time. The download starts as soon as the welcome is answered (it needs no
    /// file access); the short read is planned once access is.
    func advance() {
        guard let services, started else { return }
        Task { if await planIfNeeded(services) { wake() } }
        guard !services.retriever.textOnly else { return }
        Task {
            try? await services.retriever.refreshSetUp()
            if tuneLeft, active, tune != .running { return await runTune() }
            guard services.retriever.setUpNow?.boolValue != true else { return }
            let build = services.retriever.requiredBuildNow
            Self.log.info(
                "setup: download \(String(describing: services.download.state), privacy: .public), tune \(String(describing: self.tune), privacy: .public)")
            switch services.download.state {
            case .idle: if !services.download.stopped { services.download.start(build, allowCellular: useMobileData) }
            // Only on screen: iOS refuses the graphics chip in the background, so a tune there would settle on the
            // processor for good. Coming back to the front calls advance() again.
            case .ready: if tune == .idle, active { await runTune() }
            default: break
            }
        }
    }

    /// The welcome tap: the download starts now, while access is asked for. Trying first writes the short read's scope
    /// at once (an empty plan until files are allowed), so the choice outlives the process; Read everything writes
    /// nothing, which core reads as everything.
    func beginSetup(readEverything: Bool) {
        started = true
        guard let services else { return }
        advance()
        startLoop()
        guard !readEverything else { return }
        Task {
            _ = try? await services.retriever.planTaste()
            // Files already allowed (a reinstall): plan them now, or read everything when there are none.
            advance()
            wake()
        }
    }

    /// Skip: Home now. The download, tune and short read carry on, and Home asks about the rest. A short read still
    /// waiting on an answer is put off rather than left half-asked.
    func skipSetup() { setupSeen = true }

    func stopDownload() { services?.download.stop() }

    /// On mobile data with a download ahead: ask before the welcome starts it.
    var asksMobileData: Bool {
        guard let services else { return false }
        return !useMobileData && !services.retriever.textOnly && services.retriever.setUpNow != true && services.download.isMetered
    }

    /// Photos answered yes (all or some) or a folder added: there is something to read.
    var accessGiven: Bool { photos == .authorized || photos == .limited || !folders.isEmpty }

    /// False once the person chose (or never had a short read); true when a plan waits on their answer.
    func tasteDecided() async -> Bool {
        guard let retriever = services?.retriever else { return true }
        return (try? await retriever.tasteDecided().boolValue) ?? true
    }

    private func tasteState(_ retriever: Retriever) async -> (decided: Bool, planned: Int) {
        let decided = (try? await retriever.tasteDecided().boolValue) ?? true
        for await taste in retriever.tasteStream() { return (decided, taste.planned) }
        return (decided, 0)
    }

    /// Plans the short read once access is answered, unless it is planned or decided already. A library with nothing in
    /// it has nothing to try, so it reads everything as files arrive. True when it changed what will be read.
    private func planIfNeeded(_ services: AppServices) async -> Bool {
        guard accessGiven else { return false }
        let taste = await tasteState(services.retriever)
        guard !taste.decided, taste.planned == 0 else { return false }
        let plan = try? await services.retriever.planTaste()
        // An empty plan means only "no eligible files".
        if plan?.ids.isEmpty == true {
            try? await services.retriever.commitScope()
        }
        return true
    }

    func retryDownload() {
        guard let services else { return }
        services.download.start(services.retriever.requiredBuildNow, allowCellular: useMobileData)
    }

    /// The old UserDefaults key moves into Prefs once, then goes. A write that fails leaves the key for the next launch.
    func migrateMobileData(_ services: AppServices) {
        guard let legacy = UserDefaults.standard.object(forKey: Self.legacyMobileDataKey) as? Bool else { return }
        migratingMobileData = true
        Task {
            try? await services.retriever.refreshSetUp()
            if legacy, !services.retriever.prefsNow.allowMobileData {
                do { try await edit(mobileData: true) } catch {
                    migratingMobileData = false
                    return Self.log.error("mobile data setting not moved: \(error, privacy: .private)")
                }
            }
            UserDefaults.standard.removeObject(forKey: Self.legacyMobileDataKey)
            migratingMobileData = false
        }
    }

    func downloadAgain() { retryDownload() }

    func retryTune() {
        tune = .idle
        advance()
    }

    /// User-requested recheck; delegate details remain in Diagnostics.
    func tuneAgain() {
        guard active, tune != .running else { return }
        tuningAgain = true
        Task { await runTune() }
    }

    private func runTune() async {
        guard let services else { return }
        tune = .running
        tuneLeft = false
        do {
            let images = await Task.detached { Self.calibrationImages(services) }.value
            let result = try await services.retriever.calibrate(samples: RetrieverIosKt.samples(images: images, texts: []), tuneAgain: tuningAgain)
            let imageMs = result.backends.first { $0.backend == result.chosen }?.imageMs?.doubleValue
            tune = .done(Copy.tuneSummary(result.chosen, imageMs: imageMs))
            try? await services.retriever.refreshSetUp()
            wake()
            if tuneLeft, active { return await runTune() }
            tuningAgain = false
        } catch {
            Self.log.error("tune failed: \(error, privacy: .private)")
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
}

/// The short read's progress as Swift values: one row per group of kinds, in reading order.
struct TasteSnap: Equatable, Sendable {
    struct Row: Equatable, Identifiable, Sendable {
        let name: String
        let read: Int
        let planned: Int
        let done: Bool
        var id: String { name }
    }

    /// Screenshots first: the cheap kinds finish first, as the reading does.
    private static let groups: [(name: String, kinds: [Kind])] = [
        ("Screenshots", [.screenshot]), ("Documents and PDFs", [.doc, .pdf]), ("Photos", [.photo]), ("Videos", [.video]),
    ]

    var rows: [Row] = []
    var secondsLeft: Int?
    var readyToTry = false
    /// Planned files still waiting to be read. A Name only file is done without ever counting as read.
    var pending = 0

    var read: Int { rows.reduce(0) { $0 + $1.read } }
    var planned: Int { rows.reduce(0) { $0 + $1.planned } }

    init(
        counts: [Kind: (read: Int, planned: Int)] = [:], ready: Set<Kind>? = nil, pending: Int = 0, secondsLeft: Int? = nil,
        readyToTry: Bool = false
    ) {
        rows = Self.groups.compactMap { group in
            let found = group.kinds.compactMap { counts[$0] }
            let planned = found.reduce(0) { $0 + $1.planned }
            guard planned > 0 else { return nil }
            let read = found.reduce(0) { $0 + $1.read }
            let done = ready.map { r in group.kinds.allSatisfy { counts[$0] == nil || r.contains($0) } } ?? (read == planned)
            return Row(name: group.name, read: read, planned: planned, done: done)
        }
        self.pending = pending
        self.secondsLeft = secondsLeft
        self.readyToTry = readyToTry
    }

    init(_ p: TasteProgress) {
        self.init(
            counts: p.byKind.mapValues { ($0.first?.intValue ?? 0, $0.second?.intValue ?? 0) },
            ready: Set(p.readyKinds), pending: Int(p.pending),
            secondsLeft: p.secondsLeft?.intValue, readyToTry: p.readyToTry)
    }
}

extension Retriever {
    /// The short read's progress, until the loop that reads it is cancelled.
    func tasteStream() -> AsyncStream<TasteSnap> {
        AsyncStream { continuation in
            let handle = observeTasteProgress { @Sendable p in continuation.yield(TasteSnap(p)) }
            continuation.onTermination = { _ in handle.close() }
        }
    }
}
