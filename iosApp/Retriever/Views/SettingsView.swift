import Photos
import RetrieverCore
import SwiftUI

/// What a person can steer: what is read, when, what is kept on the device, and how the model downloads.
struct SettingsView: View {
    let model: AppModel
    @State private var confirmingFree = false
    @State private var confirmingClear = false
    @State private var searchesCleared = false
    @State private var confirmingForget = false
    @State private var addingFolder = false
    @State private var folderNote: String?
    #if DEBUG
        /// Overrides the app's own log (previews); nil reads model.services?.diagnostics.
        var diagnostics: Diagnostics? = nil
        @State private var problems: (url: URL, count: Int)?
        @State private var loadedProblems = false
    #endif

    var body: some View {
        let snap = model.snap ?? AppModel.Snap()
        // Text-only devices can't run it, so they have no section for it. Until it's downloaded it leads the screen.
        let meaningFirst = !snap.textOnly && model.upgrade != .done
        Form {
            if meaningFirst { searchByMeaning(snap) }
            howItWorks
            reading(snap)
            if !snap.textOnly && !meaningFirst { searchByMeaning(snap) }
            includedFiles(snap)
            Section {
                Toggle(Words.shared.strings.saveRecentSearches, isOn: Binding(get: { snap.rememberSearches }, set: model.setRememberSearches))
                Button(searchesCleared ? Words.shared.strings.cleared : Words.shared.strings.clearRecentSearches, role: .destructive) { confirmingClear = true }
                    .disabled(searchesCleared)
                // Off forgets every face and name, so it asks first; on starts looking at photos.
                Toggle(
                    isOn: Binding(get: { snap.recognizePeople }, set: { on in if on { model.setRecognizePeople(true) } else { confirmingForget = true } })
                ) {
                    Text(Words.shared.RECOGNIZE_PEOPLE)
                    Text(Words.shared.RECOGNIZE_PEOPLE_DETAIL)
                }
            } header: {
                Text(Words.shared.strings.privacy)
            } footer: {
                Text(Words.shared.strings.savedOnDeviceOnly(words: Copy.words))
            }
            storage(snap)
            Section(Words.shared.strings.appearance) {
                Toggle(Words.shared.SHOW_ON_THIS_DAY, isOn: Binding(get: { snap.showOnThisDay }, set: model.setShowOnThisDay))
            }
            about
            #if DEBUG
                if !snap.textOnly { Section("For testing") { testing } }
            #endif
        }
        // Prominent headers are full contrast (the default grey nearly fails), as Home's are.
        .headerProminence(.increased)
        .navigationTitle(Words.shared.strings.appSettings)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.modelMissing) { await model.loadStorage() }
        // Back from Folders: a folder removed there leaves model.folders as it was.
        .onAppear { if let services = model.services { model.folders = AppModel.pickedFolders(services) } }
        .fileImporter(isPresented: $addingFolder, allowedContentTypes: [.folder], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            // An added folder shows in its section; only what went wrong needs saying.
            let problems = model.addFolders(urls).filter { if case .added = $0 { false } else { true } }
            folderNote = problems.isEmpty ? nil : problems.map(Copy.addFolderResult).joined(separator: "\n")
        }
        .alert(folderNote ?? "", isPresented: Binding(get: { folderNote != nil }, set: { if !$0 { folderNote = nil } })) {}
        #if DEBUG
            .task {
                guard !loadedProblems, let log = diagnostics ?? model.services?.diagnostics else { return }
                loadedProblems = true
                problems = await Self.exportProblems(log)
            }
        #endif
        .alert(Words.shared.FORGET_PEOPLE_TITLE, isPresented: $confirmingForget) {
            Button(Words.shared.FORGET, role: .destructive) { model.setRecognizePeople(false) }
            Button(Words.shared.CANCEL, role: .cancel) {}
        } message: {
            Text(Words.shared.FORGET_PEOPLE_BODY)
        }
        .confirmationDialog(Words.shared.strings.clearRecentSearchesAsk, isPresented: $confirmingClear, titleVisibility: .visible) {
            Button(Words.shared.strings.clear, role: .destructive) {
                model.clearRecentSearches()
                searchesCleared = true
            }
        }
        .confirmationDialog(Words.shared.REMOVE_DOWNLOAD_ASK, isPresented: $confirmingFree, titleVisibility: .visible) {
            Button(Words.shared.strings.removeDownloadConfirm, role: .destructive, action: model.freeUpSpace)
        } message: {
            Text(Words.shared.removeDownloadMessage(size: model.storage.map { Copy.downloadSize($0.model) }))
        }
    }

    #if DEBUG
        /// The share file and its count describe the same read, even if MetricKit adds another report meanwhile.
        @concurrent static func exportProblems(_ log: Diagnostics) async -> (url: URL, count: Int)? {
            let entries = log.entries
            guard !entries.isEmpty else { return nil }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("Retriever problems.json")
            do {
                try "[\(entries.joined(separator: ","))]".write(to: url, atomically: true, encoding: .utf8)
                return (url, entries.count)
            } catch {
                return nil
            }
        }
    #endif

    /// First, so the person reads what Retriever does with their files before any switch.
    private var howItWorks: some View {
        Section {
            DisclosureGroup(Words.shared.HOW_IT_WORKS) {
                Text(Words.shared.strings.privacyBody(words: Copy.words))
                Text(Copy.backgroundNote)
                Text(Words.shared.strings.longFilesBitAtATime)
            }
        }
    }

    /// Processing uses the same Start, Pause and Resume controls as Home; the saved automatic schedule stays below.
    private func reading(_ snap: AppModel.Snap) -> some View {
        Section(Words.shared.READING_STATUS) {
            if let status = model.statusLine(model.snap) { StatusLine(status: status, model: model) }
            Picker(
                Words.shared.WHEN_TO_READ,
                selection: Binding<String>(
                    get: { snap.automaticReading.name },
                    set: { value in
                        if let schedule = ReadingSchedule.entries.first(where: { $0.name == value }) { model.setReadingSchedule(schedule) }
                    })
            ) {
                ForEach(ReadingSchedule.entries, id: \.name) { Text($0.title).tag($0.name) }
            }
            // The three choices as rows with a tick: nothing hidden in a menu, and no value cut short at large text.
            .pickerStyle(.inline)
            Text(
                snap.automaticReading == .charging
                    ? Words.shared.strings.automaticChargingPreferenceDetail
                    : snap.automaticReading == .manual
                        ? Words.shared.strings.onlyWhenYouChooseAnyFile
                        : Words.shared.strings.automaticProcessingPreferenceDetail
            ).font(.footnote)
            if #available(iOS 26.0, *) {
                EmptyView()
            } else {
                Toggle(
                    Words.shared.strings.keepScreenOnToggle,
                    isOn: Binding(
                        get: { model.keepScreenAwakeForSession },
                        set: {
                            model.keepScreenAwakeForSession = $0
                            model.keepScreenOn(model.reading)
                        }))
            }
            if model.meaningSearch {
                DisclosureGroup(Words.shared.strings.adjustProcessingFor(words: Copy.words)) {
                    Text(Words.shared.strings.runSetupCheckAgain)
                    Button(Words.shared.strings.runSetupCheck, action: model.tuneAgain)
                }
            }
            if let message = model.readingMessage { Text(message).foregroundStyle(.red) }
        }
    }

    /// iOS reads two places: the Photos library, under its permission, and the folders picked in the Files app.
    /// Each section offers only the kinds that place holds, and its own way in while it gives nothing.
    @ViewBuilder private func includedFiles(_ snap: AppModel.Snap) -> some View {
        let kinds = snap.textOnly ? AppModel.Snap.insideTextOnly : AppModel.Snap.inside
        Section {
            switch model.photos {
            case .authorized, .limited:
                kindToggles([.photo, .screenshot, .video], of: kinds, snap)
                if model.photos == .limited { Button(Words.shared.strings.chooseMorePhotos, action: model.chooseMorePhotos) }
            case .denied, .restricted:
                Button(Words.shared.strings.openSettings, action: model.openSettings)
            default:
                Button(Words.shared.strings.allowPhotosAccess, action: model.requestPhotos)
            }
        } header: {
            Text(Words.shared.strings.photosLibrary)
        } footer: {
            switch model.photos {
            case .authorized, .limited:
                VStack(alignment: .leading, spacing: 4) {
                    if model.photos == .limited { Text(Words.shared.strings.onlySeesPickedPhotos) }
                    Text(snap.textOnly ? Words.shared.textOnlyNote(ramGb: snap.ramGb, words: Copy.words) : Words.shared.strings.kindsFooterVideos)
                }
            case .denied, .restricted: Text(Words.shared.strings.photosOffCantSearch)
            default: EmptyView()
            }
        }
        // Retriever's own Files folder is searched even with none picked, so skipped folders keep their way back.
        let hasFolders = !model.folders.isEmpty || !snap.excludedFolders.isEmpty
        Section {
            if hasFolders {
                NavigationLink(value: Route.folders) {
                    LabeledContent(Words.shared.strings.folders, value: Copy.count(model.folders.count))
                }
                kindToggles([.pdf, .doc], of: kinds, snap)
                // No platform reads audio yet, so it has no switch: only the plain fact.
                LabeledContent(Words.shared.kindLabelPlural(kind: .audio), value: Words.shared.FOUND_BY_NAME)
            } else {
                Button {
                    addingFolder = true
                } label: {
                    Label(Words.shared.ADD_FOLDER, systemImage: "folder.badge.plus")
                }
            }
        } header: {
            Text(Words.shared.strings.foldersFromFiles)
        } footer: {
            Text(hasFolders ? Words.shared.KIND_OFF : Words.shared.strings.addFoldersFooter)
        }
    }

    /// The `wanted` kinds this device reads (`offered`), in the order given.
    private func kindToggles(_ wanted: [Kind], of offered: [Kind], _ snap: AppModel.Snap) -> some View {
        ForEach(wanted.filter { offered.contains($0) }, id: \.self) { kind in
            Toggle(Self.label(kind), isOn: Binding(get: { snap.kinds.contains(kind) }, set: { model.setKind(kind, $0) }))
        }
    }

    /// What Retriever keeps on the device: each a title, then its size. Large files and duplicates are on Home.
    @ViewBuilder private func storage(_ snap: AppModel.Snap) -> some View {
        let sizes = model.storage
        Section(Words.shared.strings.storage) {
            TitleAndDetail(
                title: Words.shared.RETRIEVERS_DATA,
                detail: Words.shared.keptOn(size: sizes.map { Copy.bytes($0.index) } ?? Words.shared.strings.sizePending, words: Copy.words))
            if !snap.textOnly, let cache = sizes?.cache, cache > 0, !model.modelMissing {
                TitleAndDetail(title: Words.shared.TEMPORARY_FILES, detail: Words.shared.keptOn(size: Copy.bytes(cache), words: Copy.words))
            }
        }
    }

    /// The optional download: the same upgrade as Try and Home until it is done; then its size and how to remove it.
    private func searchByMeaning(_ snap: AppModel.Snap) -> some View {
        Section {
            if model.upgrade == .done {
                TitleAndDetail(title: Words.shared.UPGRADE_TITLE, detail: Words.shared.downloaded(size: modelSize(snap, model.storage)))
                Button(Words.shared.REMOVE_DOWNLOAD, role: .destructive) { confirmingFree = true }
            } else {
                UpgradeCard(model: model)
            }
            Toggle(Words.shared.strings.useMobileDataForDownloads, isOn: Binding(get: { model.useMobileData }, set: model.setUseMobileData))
        } header: {
            Text(Words.shared.SEARCH_BY_MEANING)
        } footer: {
            Text(model.useMobileData ? Words.shared.strings.downloadsOnWifiOrData : Words.shared.strings.downloadsWaitForWifi)
        }
    }

    private func modelSize(_ snap: AppModel.Snap, _ sizes: AppModel.Storage?) -> String {
        let onDisk = sizes?.model ?? 0
        return Copy.downloadSize(onDisk > 0 ? onDisk : snap.downloadBytes)
    }

    #if DEBUG
        @ViewBuilder private var testing: some View {
            switch model.tune {
            case .running: Busy("Tuning… about a minute")
            case .done(let summary): Note(summary)
            case .failed: Failure("Tune failed")
            case .idle: EmptyView()
            }
            Button("Tune again", action: model.tuneAgain).disabled(model.tune == .running)
        }
    #endif

    private var about: some View {
        Section(Words.shared.strings.about) {
            LabeledContent(Words.shared.strings.version, value: Self.version)
            NavigationLink(Words.shared.strings.openSourceNotices) { Notices() }
            #if DEBUG
                if let problems {
                    ShareLink(item: problems.url) { Text("Diagnostics (\(problems.count))") }
                }
            #endif
        }
    }

    private static let version: String = {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        return (info?["CFBundleVersion"] as? String).map { "\(version) (\($0))" } ?? version
    }()

    private static func label(_ kind: Kind) -> String {
        switch kind {
        case .photo: Words.shared.kindLabelPlural(kind: .photo)
        case .screenshot: Words.shared.kindLabelPlural(kind: .screenshot)
        case .pdf: Words.shared.kindLabelPlural(kind: .pdf)
        case .doc: Words.shared.kindLabelPlural(kind: .doc)
        case .audio: Words.shared.kindLabelPlural(kind: .audio)
        case .video: Words.shared.kindLabelPlural(kind: .video)
        default: Words.shared.kindLabelPlural(kind: .other)
        }
    }
}

/// A row's title with its detail beneath, read as one.
private struct TitleAndDetail: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            // Never compressed by the row: a two-line title clipped at the larger text sizes.
            Text(title).fixedSize(horizontal: false, vertical: true)
            // Primary, as the app's notes are: secondary grey nearly fails contrast on the grouped rows.
            Text(detail).font(.subheadline).monospacedDigit().fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// THIRD_PARTY_NOTICES.md, shipped in the app bundle.
private struct Notices: View {
    @State private var text: AttributedString?

    var body: some View {
        ScrollView {
            Text(text ?? AttributedString(Words.shared.strings.loading))
                .font(.footnote)
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(Words.shared.strings.openSourceNotices)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let raw =
                Bundle.main.url(forResource: "THIRD_PARTY_NOTICES", withExtension: "md").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
                ?? Words.shared.strings.noticesUnavailable
            text = (try? AttributedString(markdown: raw, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(raw)
        }
    }
}

#Preview("Settings") {
    NavigationStack { SettingsView(model: AppModel(fixture: .caughtUp)) }
}

#if DEBUG
    #Preview("Settings with problems") {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("problems-fixture.json")
        try? JSONEncoder().encode(["{\"crashDiagnostics\":[]}", "{\"hangDiagnostics\":[]}"]).write(to: file)
        return NavigationStack { SettingsView(model: AppModel(fixture: .caughtUp), diagnostics: Diagnostics(file: file)) }
    }
#endif
