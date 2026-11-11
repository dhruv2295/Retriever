import Photos
import PhotosUI
@preconcurrency import RetrieverCore
import UIKit

extension AppModel {
    /// Changes the settings given and leaves the rest as they are.
    func edit(
        kinds: (@Sendable (Set<Kind>) -> Set<Kind>)? = nil, mobileData: Bool? = nil, remember: Bool? = nil,
        excluded: (@Sendable (Set<String>) -> Set<String>)? = nil, onThisDay: Bool? = nil, people: Bool? = nil,
        peopleOfferDismissed: Bool? = nil
    ) async throws {
        guard let services else { return }
        try await services.retriever.updatePrefs { @Sendable p in
            p.doCopy(
                kinds: kinds?(p.kinds) ?? p.kinds, onlyWhileCharging: p.onlyWhileCharging,
                schedule: p.schedule, readNowUntil: p.readNowUntil,
                excludedFolders: excluded?(p.excludedFolders) ?? p.excludedFolders,
                paused: p.paused, sampleSize: p.sampleSize, allowMobileData: mobileData ?? p.allowMobileData,
                rememberSearches: remember ?? p.rememberSearches, dynamicColor: p.dynamicColor,
                setupStarted: p.setupStarted, notificationsDismissed: p.notificationsDismissed,
                notificationsOfferDismissed: p.notificationsOfferDismissed, notificationsPermissionRequested: p.notificationsPermissionRequested,
                noMatchSeen: p.noMatchSeen, showOnThisDay: onThisDay ?? p.showOnThisDay, readingCycle: p.readingCycle,
                dismissedReadingCycle: p.dismissedReadingCycle, searchTried: p.searchTried, upgradeOfferDismissed: p.upgradeOfferDismissed,
                homeIntroDue: p.homeIntroDue, notifyWhenCaughtUp: p.notifyWhenCaughtUp, recognizePeople: people ?? p.recognizePeople,
                peopleOfferDismissed: peopleOfferDismissed ?? p.peopleOfferDismissed)
        }
    }

    func setUseMobileData(_ on: Bool) {
        useMobileData = on
        Task { try? await edit(mobileData: on) }
        // The network rule is part of the request, so a download waiting for Wi-Fi starts again under the new one.
        if on, case .waitingForWifi = download { downloadModel() }
    }

    // MARK: Settings actions. Each shows its new value at once; the next snapshot confirms it.

    func setKind(_ kind: Kind, _ on: Bool) {
        guard var kinds = snap?.kinds else { return }
        if on { kinds.insert(kind) } else { kinds.remove(kind) }
        snap?.kinds = kinds
        // Core clears a kind turned off and queues one turned on.
        Task { try? await edit(kinds: { on ? $0.union([kind]) : $0.subtracting([kind]) }) }
    }

    func setReadingSchedule(_ value: ReadingSchedule) {
        let before = snap?.automaticReading
        snap?.automaticReading = value
        Task {
            do {
                try await services?.retriever.setReadingSchedule(schedule: value)
                IndexTask.schedule()
                wake()
            } catch {
                if let before { snap?.automaticReading = before }
                readingMessage = Words.shared.strings.couldntSaveChange
            }
        }
    }

    func readNow() {
        Task {
            do {
                try await services?.retriever.readNow()
                wake()
                keepReading()
            } catch { readingMessage = Words.shared.strings.couldntSaveChange }
        }
    }

    func decideLater() {
        Task {
            do {
                try await services?.retriever.decideLater()
            } catch { readingMessage = Words.shared.strings.couldntSaveChange }
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

    /// Off forgets every face and name (Retriever.updatePrefs); on starts looking at photos.
    func setRecognizePeople(_ on: Bool) {
        snap?.recognizePeople = on
        if !on {
            people = []
            samePeople = []
        }
        Task {
            try? await edit(people: on)
            if on { wake() }
        }
    }

    /// Home's People offer answered Not now: Settings keeps the switch.
    func dismissPeopleOffer() {
        Task { try? await edit(peopleOfferDismissed: true) }
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

    /// Settings' Remove search download: the model and what was built from it go, the index stays. Search then runs on names
    /// and words (modelMissing) until the meaning upgrade downloads it again.
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
            accessChanged()
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
        if results.contains(where: { if case .added = $0 { true } else { false } }) { accessChanged() }
        return results
    }

    /// Photos answered or a folder added: setup's step follows from access. The access screen waits for its Continue, so
    /// both rows can be filled first.
    func accessChanged() {
        advance()
        wake()
    }

    static func pickedFolders(_ services: AppServices) -> [String] {
        services.folders.folders.filter { $0.id != FolderSource.documentsId }.map(\.name)
    }

    // MARK: Status actions

    func pause() {
        guard let services else { return }
        Task { do { try await services.retriever.pause() } catch { readingMessage = Words.shared.strings.couldntSaveChange } }
    }

    func resume() {
        guard let services else { return }
        Task {
            do {
                try await services.retriever.resume()
                wake()
            } catch { readingMessage = Words.shared.strings.couldntSaveChange }
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

    /// Home's facts and suggestions while Home shows: core reads its facts again when files come, go or change, and the
    /// suggestions as files are read. `unwatchHome` stops that work when Home goes.
    func watchHome() {
        guard let services, homeObservations.isEmpty else { return }
        homeObservations = [
            services.retriever.observeHome { @Sendable [weak self] facts in
                Task { @MainActor in self?.apply(home: facts) }
            },
            services.retriever.observeSuggestions(fileIds: nil) { @Sendable [weak self] terms in
                Task { @MainActor in if let self, self.suggestions != terms { self.suggestions = terms } }
            },
            services.retriever.observePeople { @Sendable [weak self] now in
                Task { @MainActor in
                    self?.people = now.people
                    self?.facesLeft = now.facesLeft
                    self?.samePeople = now.samePeople
                }
            },
        ]
    }

    // MARK: People. A person's screen sits over Home, whose observation may be closed: each change reads them again.

    func renamePerson(_ id: Int64, _ name: String) {
        changePeople { try await $0.renamePerson(id: id, name: name) }
    }

    /// `other` is this person too: their photos join `into`'s, and their name when `into` has none.
    func mergePeople(into: Int64, from other: Int64, then: @escaping @MainActor () -> Void) {
        changePeople(then: then) { try await $0.mergePeople(into: into, from: other) }
    }

    func notThisPerson(_ id: Int64, fileId: Int64, then: @escaping @MainActor () -> Void) {
        changePeople(then: then) { try await $0.notThisPerson(person: id, fileId: fileId) }
    }

    /// Home's "Same person?": that pair hidden at once, then merged or never asked again.
    func answerSamePerson(_ question: SamePerson, same: Bool) {
        samePeople.removeAll { $0 == question }
        changePeople { try await $0.answerSamePerson(question: question, same: same) }
    }

    private func changePeople(then: @escaping @MainActor () -> Void = {}, _ change: @escaping (Retriever) async throws -> Void) {
        guard let retriever = services?.retriever else { return }
        Task {
            do {
                try await change(retriever)
                people = try await retriever.peopleNow()
                samePeople = try await retriever.samePeopleNow()
                then()
            } catch {
                peopleMessage = Words.shared.strings.couldntChangePersonCurly
            }
        }
    }

    func unwatchHome() {
        for observation in homeObservations { observation.close() }
        homeObservations = []
    }

    private func apply(home facts: HomeFacts) {
        home = facts
        if kindTotals != facts.kindTotals { kindTotals = facts.kindTotals }
        if folderTotals != facts.folderTotals { folderTotals = facts.folderTotals }
    }

    /// The same date in past years, asked of core with this calendar's real day lengths. Not throttled: it is a few
    /// index probes, and Home asks again only when the date, the photo count or the setting changes.
    func refreshOnThisDay(now: Date = .now, calendar: Calendar = .current) async {
        // Answered, found or not: Home's sections wait for this so On This Day is never inserted above another.
        defer { if !Task.isCancelled, !memoriesLoaded { memoriesLoaded = true } }
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
