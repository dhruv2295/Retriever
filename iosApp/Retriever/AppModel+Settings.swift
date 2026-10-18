import Observation
import Photos
import PhotosUI
@preconcurrency import RetrieverCore
import SwiftUI
import UIKit
import os

extension AppModel {
    /// Changes the settings given and leaves the rest as they are.
    func edit(
        kinds: (@Sendable (Set<Kind>) -> Set<Kind>)? = nil, charging: Bool? = nil, mobileData: Bool? = nil, remember: Bool? = nil,
        excluded: (@Sendable (Set<String>) -> Set<String>)? = nil, onThisDay: Bool? = nil
    ) async throws {
        guard let services else { return }
        try await services.retriever.updatePrefs { @Sendable p in
            p.doCopy(
                kinds: kinds?(p.kinds) ?? p.kinds, onlyWhileCharging: charging ?? p.onlyWhileCharging,
                excludedFolders: excluded?(p.excludedFolders) ?? p.excludedFolders,
                paused: p.paused, sampleSize: p.sampleSize, allowMobileData: mobileData ?? p.allowMobileData,
                rememberSearches: remember ?? p.rememberSearches, dynamicColor: p.dynamicColor,
                setupStarted: p.setupStarted, notificationsDismissed: p.notificationsDismissed,
                noMatchSeen: p.noMatchSeen, showOnThisDay: onThisDay ?? p.showOnThisDay)
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
        // Core clears a kind turned off and queues one turned on.
        Task { try? await edit(kinds: { on ? $0.union([kind]) : $0.subtracting([kind]) }) }
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

    /// Home's On This Day, hidden from its menu or brought back from Status.
    func setShowOnThisDay(_ on: Bool) {
        snap?.showOnThisDay = on
        Task { try? await edit(onThisDay: on) }
    }

    func clearRecentSearches() {
        recent = []
        guard let services else { return }
        Task { try? await services.retriever.clearRecentQueries() }
    }

    /// Stops or resumes searching a folder; the next reading round forgets its files or reads them again.
    func setExcluded(_ path: String, _ on: Bool) {
        guard let skipped = snap?.excludedFolders else { return }
        let rest = on ? skipped.union([path]) : skipped.subtracting([path])
        snap?.excludedFolders = rest
        Task {
            try? await edit(excluded: { on ? $0.union([path]) : $0.subtracting([path]) })
            services?.retriever.notifyChanged()
            wake()
        }
    }

    func restoreFolder(_ path: String) { setExcluded(path, false) }

    // MARK: Storage

    func loadStorage() async {
        guard let services else { return }
        let index = services.dataDir
        let models = services.modelsDir
        let cache = services.cacheDir
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
        guard let services else { return }
        let build = services.retriever.requiredBuildNow
        // The reading loop stands aside, so no batch reopens the model between its unloading and the file going.
        let wasActive = active
        if wasActive { stopLoop() }
        Task {
            try? await services.retriever.release()
            services.download.deleteModel(build)
            let cache = services.cacheDir
            for file in (try? FileManager.default.contentsOfDirectory(at: cache, includingPropertiesForKeys: nil)) ?? [] {
                try? FileManager.default.removeItem(at: file)
            }
            try? await services.retriever.refreshSetUp()
            await loadStorage()
            if wasActive {
                active = true
                startLoop()
            }
        }
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
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: top) { @Sendable [weak self] _ in
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

    static func pickedFolders(_ services: AppServices) -> [String] {
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

    /// The same date in past years, asked of core with this calendar's real day lengths. Not throttled: it is a few
    /// index probes, and Home asks again only when the date, the photo count or the setting changes.
    func refreshOnThisDay(now: Date = .now, calendar: Calendar = .current) async {
        guard let services else { return }
        let days = OnThisDay.days(on: now, calendar: calendar)
        let triples = days.map {
            KotlinTriple(first: KotlinInt(int: Int32($0.year)), second: KotlinLong(longLong: $0.from), third: KotlinLong(longLong: $0.to))
        }
        guard let found = try? await services.retriever.onThisDay(days: triples), !Task.isCancelled else { return }
        let byYear = Dictionary(uniqueKeysWithValues: days.map { ($0.year, $0) })
        let cards = found.compactMap { m in byYear[Int(m.year)].map { OnThisDay.Card(memory: m, day: $0) } }
        if cards != memories { memories = cards }
    }
}
