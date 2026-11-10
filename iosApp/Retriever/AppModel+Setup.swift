import Observation
import Photos
@preconcurrency import RetrieverCore
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

    /// The person's answer on a setup screen: Get started, the access screen's Continue, Back.
    func setSetupMark(_ mark: SetupMark) {
        guard let retriever = services?.retriever else { return }
        Task { try? await retriever.setSetupMark(mark: mark) }
    }

    /// Get started starts the one-time download too, on an iPhone that can run search by description and lacks it.
    var downloadOnGetStarted: Bool { SetupPolicy.shared.downloadOnGetStarted(textOnly: textOnly, modelPresent: !modelMissing) }

    /// Hello's Get started: the access screen next, and the download under way while the person answers it.
    func getStarted() {
        setSetupMark(.greeted)
        if downloadOnGetStarted { (startDownload ?? downloadModel)() }
    }

    /// The first search's Continue: setup ends, everything is processed on the one automatic schedule, and Home shows its
    /// one-time card.
    func finishSetup() {
        guard let retriever = services?.retriever else { return }
        Task {
            do {
                try await retriever.finishSetup()
                IndexTask.schedule()
                wake()
            } catch { readingMessage = Words.shared.strings.couldntSaveChange }
        }
    }

    /// Home's one-time card: Got it. The status line and the upgrade row carry on from here.
    func dismissHomeIntro() {
        Task { try? await services?.retriever.dismissHomeIntro() }
    }

    /// Back from the access screen to Hello, by core's rule.
    func setupBack() {
        guard let setup, let mark = SetupPolicy.shared.back(stage: setup.stage) else { return }
        setSetupMark(mark)
    }

    /// The first search's progress, files and chips while it shows, so they are on its first frame. Closed on every other
    /// stage: core then does none of this work.
    func watchSetup(stage: SetupStage?) {
        guard let services else { return }
        let sampling = stage == .sample
        if sampling, sampleObservations.isEmpty {
            sampleObservations = [
                services.retriever.observeTasteProgress { @Sendable [weak self] p in
                    let taste = TasteSnap(p)
                    Task { @MainActor in
                        guard let self else { return }
                        if self.taste != taste { self.taste = taste }
                        // The plan arrives here; the chips follow it.
                        self.watchSetup(stage: self.setup?.stage)
                    }
                },
                services.retriever.observeSampleFiles { @Sendable [weak self] files in
                    let plan = SamplePlan(files: files)
                    Task { @MainActor in self?.sample = plan.files }
                },
            ]
        } else if !sampling {
            for observation in sampleObservations { observation.close() }
            sampleObservations = []
        }
        let ids = stage == .sample ? sampleIds : []
        guard ids != trySuggestionsObservation?.ids ?? [] else { return }
        trySuggestionsObservation?.handle.close()
        trySuggestionsObservation = nil
        guard !ids.isEmpty else { return }
        let handle = services.retriever.observeSuggestions(fileIds: Set(ids.map { KotlinLong(value: $0) })) { @Sendable [weak self] terms in
            Task { @MainActor in if let self, self.trySuggestions != terms { self.trySuggestions = terms } }
        }
        trySuggestionsObservation = (ids, handle)
    }

    /// The demo's files: its search looks inside these.
    var sampleIds: [Int64] {
        guard let services else { return (0..<Int64(taste.planned)).map { $0 } }
        return (services.retriever.scopeIds.value as? [KotlinLong])?.map(\.int64Value) ?? []
    }

    /// Search by description, and by photo, works: the model is here and tuned.
    var meaningSearch: Bool { !Words.shared.wordsOnly(textOnly: textOnly, modelMissing: modelMissing, setUp: setUp) }

    // MARK: The meaning upgrade

    /// The download's size as the screens say it; before the first snapshot, the build core asks for.
    var downloadSize: String { Copy.downloadSize(snap?.downloadBytes ?? services?.retriever.requiredBuildNow.sizeBytes ?? 0) }

    /// The platform's download in core's words. A finished download is still being checked until core finds the model.
    static func phase(_ state: ModelDownload.State) -> DownloadPhase {
        switch state {
        case .idle: .none
        case .downloading: .running
        case .waitingForWifi: .waitingForWifi
        case .verifying, .ready: .checking
        case .failed: .failed
        case .noSpace: .noSpace
        }
    }

    /// Search by description as the first search and Home's one-time card say it.
    var descriptionState: DescriptionState { UpgradePolicy.shared.description(stage: upgrade, download: Self.phase(download)) }

    /// Where search by description stands, by core's rule.
    var upgrade: UpgradeStage {
        UpgradePolicy.shared.stage(
            textOnly: textOnly, modelPresent: !textOnly && !modelMissing, downloading: download != .idle, setUp: setUp,
            meaningWaiting: Int64(snap?.meaningWaiting ?? 0))
    }

    /// Home's compact row: the offer until dismissed, then the download and the tune; not while the one-time card shows.
    var upgradeOnHome: Bool {
        snap?.prefs.map { UpgradePolicy.shared.showOnHome(stage: upgrade, prefs: $0) } ?? false
    }

    /// The download or its tune as the status line says it (core's `line`); nil when neither is under way.
    var downloadState: DownloadState? {
        let size = downloadSize
        switch upgrade {
        case .download:
            let percent = download == .downloading ? KotlinInt(int: Int32((min(max(downloadProgress, 0), 1) * 100).rounded())) : nil
            let stopped = [.failed, .noSpace, .waitingForWifi].contains(Self.phase(download))
            return DownloadState(percent: percent, stopped: stopped, size: size, settingUp: false)
        case .tune: return DownloadState(percent: nil, stopped: tune == .failed, size: size, settingUp: true)
        default: return nil
        }
    }

    /// Photos, or a folder picked in Files: with neither, Retriever can't see any files.
    var hasAccess: Bool { ![.denied, .restricted].contains(photos) || !folders.isEmpty }

    /// The one status line, for Home, Reading status and Settings.
    func statusLine(_ s: Snap?) -> ReadingStatus? { Copy.homeStatus(s, hasAccess: hasAccess, download: downloadState) }

    /// The status line's Stop during Read for 15 minutes.
    func endReadNow() {
        Task {
            try? await services?.retriever.endReadNowSession()
            wake()
        }
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
            let images = await FileExtractor.blocking { Self.calibrationImages(services) }
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

    /// The newest ten photos at the model's size, never fetched from iCloud; the illustrations when there are none.
    nonisolated private static func calibrationImages(_ services: AppServices) -> [Data] {
        var images: [Data] = []
        if services.photos.isReady {
            let options = PHFetchOptions()
            options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            options.fetchLimit = 10
            PHAsset.fetchAssets(with: .image, options: options).enumerateObjects { asset, _, _ in
                if let jpeg = services.extractor.imageJpeg(uri: "ph://\(asset.localIdentifier)", maxSide: FileExtractor.embedSide) { images.append(jpeg) }
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
    /// Planned files still waiting. A name-only or unopenable file is done without ever counting as read.
    var pending = 0

    var planned: Int { counts.values.reduce(0) { $0 + $1.planned } }
    /// The one count rule: every planned file no longer waiting (core's `TasteProgress.done`).
    var done: Int { planned - pending }

    /// For a screen reader only: "6 of 6 screenshots read, 4 of 8 PDFs read".
    var spoken: String {
        Words.shared.strings.kindsReadSpoken(
            parts: counts.sorted { $0.key.ordinal < $1.key.ordinal }
                .map { Words.shared.kindRead(read: Int64($0.value.read), planned: Int64($0.value.planned), kind: $0.key) })
    }

    init(counts: [Kind: Count] = [:], pending: Int = 0) {
        (self.counts, self.pending) = (counts, pending)
    }

    init(_ p: TasteProgress) {
        self.init(
            counts: p.byKind.mapValues { Count(read: $0.first?.intValue ?? 0, planned: $0.second?.intValue ?? 0) },
            pending: Int(p.pending))
    }
}

/// The sample's planned files, as one delivery: Kotlin data classes of vals, immutable, so they cross to the main actor as they are.
struct SamplePlan: @unchecked Sendable {
    let files: [SampleFile]
}
