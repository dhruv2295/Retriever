import Observation
import Photos
import PhotosUI
@preconcurrency import RetrieverCore
import SwiftUI
import UIKit
import os

/// Setup runs core's stage machine (`SetupInputs`); the meaning upgrade runs the download, then the tune. Both live on
/// the model, so leaving a screen never stops them.
extension AppModel {
    func watchDownload() {
        guard let download = services?.download else { return }
        withObservationTracking {
            _ = download.state
        } onChange: { [weak self] in
            Task { @MainActor in
                // A finished download makes the model present; core says so once it looks again.
                try? await self?.services?.retriever.refreshSetUp()
                self?.advance()
                self?.watchDownload()
            }
        }
    }

    /// Runs setup's next step and the upgrade's tune; safe to call any time.
    func advance() {
        guard let services else { return }
        switch setup?.step {
        case .plan?:
            guard !planning else { break }
            planning = true
            Task {
                // Core reloads its inputs, so the snapshot after this asks for the next step.
                try? await services.retriever.planOrCommit()
                planning = false
                wake()
            }
        case .readSample?, .schedule?:
            if setup?.step == .readSample, sampleStartedAt == nil { sampleStartedAt = .now }
            startLoop()
            if !scheduled {
                scheduled = true
                IndexTask.schedule()
            }
        default: break
        }
        // Only on screen: iOS refuses the graphics chip in the background, so a tune there would settle on the
        // processor for good. Coming back to the front calls advance() again.
        guard !textOnly, active, tune != .running, tune == .idle || tuneLeft, !modelMissing, services.retriever.setUpNow?.boolValue != true
        else { return }
        Task { await runTune() }
    }

    /// Photos answered yes (all or some) or a folder added: there is something to read.
    var accessGiven: Bool { photos == .authorized || photos == .limited || !folders.isEmpty }

    // MARK: Setup answers

    /// Get started, Continue, Try search, Choose when to read more: the person's answer on a setup screen.
    func setSetupMark(_ mark: SetupMark) {
        guard let retriever = services?.retriever else { return }
        Task { try? await retriever.setSetupMark(mark: mark) }
    }

    /// Search by name: past the welcome to access, then Home; the sample still reads.
    func searchByName() {
        guard let retriever = services?.retriever else { return }
        Task {
            _ = try? await retriever.setSetupMark(mark: .started)
            _ = try? await retriever.skipFirstRun()
        }
    }

    /// Go to Home: setup ends at launch; the sample keeps reading.
    func goHome() {
        guard let retriever = services?.retriever else { return }
        Task { try? await retriever.skipFirstRun() }
    }

    /// Nothing waits beyond the sample (core's `counts.waiting`), so Try ends in Go to Home; unknown is not nothing.
    var nothingLeft: Bool { snap?.waiting == 0 }

    /// Try's "Choose when to read more", or, with nothing beyond the sample, reading everything.
    func chooseWhenToReadMore() {
        if let mark = SetupPolicy.shared.afterTry(nothingLeft: nothingLeft) { setSetupMark(mark) } else { commitScope() }
    }

    /// Back from Try to the sample, or from the choice to Try.
    func setupBack() {
        guard let stage = setup?.stage, let mark = SetupPolicy.shared.back(stage: stage) else { return }
        setSetupMark(mark)
    }

    /// The sample's planned files: Try searches inside these.
    var sampleIds: [Int64] {
        guard let services else { return (0..<Int64(fixtureTaste.planned)).map { $0 } }
        return (services.retriever.scopeIds.value as? [KotlinLong])?.map(\.int64Value) ?? []
    }

    /// The sample's files read so far, for "See all" and Try's previews.
    func sampleFiles() async -> [SourceFile] {
        guard let retriever = services?.retriever else { return progress.reading.recent }
        return (try? await retriever.readFiles(fileIds: Set(sampleIds.map { KotlinLong(value: $0) }))) ?? []
    }

    /// Search by meaning, and by photo, works: the model is here and tuned.
    var meaningSearch: Bool { !Words.shared.wordsOnly(textOnly: textOnly, modelMissing: modelMissing, setUp: setUp) }

    // MARK: The meaning upgrade

    /// Where "Search by meaning and what's in your photos" stands, by core's rule.
    var upgrade: UpgradeStage {
        UpgradePolicy.shared.stage(
            textOnly: textOnly, modelPresent: !textOnly && !modelMissing, downloading: download != .idle, setUp: setUp,
            meaningWaiting: Int64(snap?.meaningWaiting ?? 0))
    }

    /// Home's compact row: the offer after a first search until dismissed, then the download and the tune.
    var upgradeOnHome: Bool {
        snap?.prefs.map { UpgradePolicy.shared.showOnHome(stage: upgrade, prefs: $0) } ?? false
    }

    /// Download, or Try again: the platform's background download; the tune follows once it is checked.
    func downloadModel() {
        guard let services else { return }
        services.download.start(services.retriever.requiredBuildNow, allowCellular: useMobileData)
    }

    /// Cancel: the bytes so far are kept, so a later Download carries on from them.
    func cancelDownload() { services?.download.stop() }

    /// Not now: the next snapshot's prefs take the row away, on this launch and the next.
    func dismissUpgradeOffer() {
        Task { try? await services?.retriever.dismissUpgradeOffer() }
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

/// The sample's progress as Swift values: read and planned files per kind.
struct TasteSnap: Equatable, Sendable {
    struct Count: Equatable, Sendable {
        var read: Int
        var planned: Int
    }

    var counts: [Kind: Count] = [:]
    var readyToTry = false
    /// Planned files still waiting to be read. A Name only file is done without ever counting as read.
    var pending = 0

    var read: Int { counts.values.reduce(0) { $0 + $1.read } }
    var planned: Int { counts.values.reduce(0) { $0 + $1.planned } }

    /// For a screen reader only: "6 of 6 screenshots read, 4 of 8 PDFs read".
    var spoken: String {
        counts.sorted { $0.key.ordinal < $1.key.ordinal }
            .map { Words.shared.kindRead(read: Int64($0.value.read), planned: Int64($0.value.planned), kind: $0.key) }
            .joined(separator: ", ")
    }

    init(counts: [Kind: Count] = [:], readyToTry: Bool = false, pending: Int = 0) {
        (self.counts, self.readyToTry, self.pending) = (counts, readyToTry, pending)
    }

    init(_ p: TasteProgress) {
        self.init(
            counts: p.byKind.mapValues { Count(read: $0.first?.intValue ?? 0, planned: $0.second?.intValue ?? 0) }, readyToTry: p.readyToTry,
            pending: Int(p.pending))
    }
}

extension Retriever {
    /// The sample's progress, until the task that reads it is cancelled.
    func tasteStream() -> AsyncStream<TasteSnap> {
        AsyncStream { continuation in
            let handle = observeTasteProgress { @Sendable p in continuation.yield(TasteSnap(p)) }
            continuation.onTermination = { _ in handle.close() }
        }
    }
}
