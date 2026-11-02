import RetrieverCore
import SwiftUI

/// What a person can steer: what is read, when, what is kept on the device, and how the model downloads.
struct SettingsView: View {
    let model: AppModel
    @State private var confirmingFree = false
    @State private var confirmingClear = false
    @State private var searchesCleared = false
    @State private var confirmingForget = false
    #if DEBUG
        /// Overrides the app's own log (previews); nil reads model.services?.diagnostics.
        var diagnostics: Diagnostics? = nil
        @State private var problems: (url: URL, count: Int)?
        @State private var loadedProblems = false
    #endif

    var body: some View {
        let snap = model.snap ?? AppModel.Snap()
        Form {
            howItWorks
            reading(snap)
            // Text-only devices can't run it, so they have no section for it.
            if !snap.textOnly { searchByMeaning(snap) }
            includedFiles(snap)
            Section {
                Toggle("Save recent searches", isOn: Binding(get: { snap.rememberSearches }, set: model.setRememberSearches))
                Button(searchesCleared ? "Cleared" : "Clear recent searches", role: .destructive) { confirmingClear = true }
                    .disabled(searchesCleared)
                // Off forgets every face and name, so it asks first; on starts looking at photos.
                Toggle(
                    isOn: Binding(get: { snap.recognizePeople }, set: { on in if on { model.setRecognizePeople(true) } else { confirmingForget = true } })
                ) {
                    Text(Words.shared.RECOGNIZE_PEOPLE)
                    Text(Words.shared.RECOGNIZE_PEOPLE_DETAIL)
                }
            } header: {
                Text("Privacy")
            } footer: {
                Text("Saved on this \(Copy.device) only. Nothing is sent anywhere.")
            }
            storage(snap)
            Section("Appearance") {
                Toggle(Words.shared.SHOW_ON_THIS_DAY, isOn: Binding(get: { snap.showOnThisDay }, set: model.setShowOnThisDay))
            }
            about
            #if DEBUG
                if !snap.textOnly { Section("For testing") { testing } }
            #endif
        }
        // Prominent headers are full contrast (the default grey nearly fails), as Home's are.
        .headerProminence(.increased)
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.modelMissing) { await model.loadStorage() }
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
        .confirmationDialog("Clear recent searches?", isPresented: $confirmingClear, titleVisibility: .visible) {
            Button("Clear", role: .destructive) {
                model.clearRecentSearches()
                searchesCleared = true
            }
        }
        .confirmationDialog(Words.shared.REMOVE_DOWNLOAD_ASK, isPresented: $confirmingFree, titleVisibility: .visible) {
            Button("Remove download", role: .destructive, action: model.freeUpSpace)
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
                Text(
                    "Retriever processes your files on this \(Copy.device). Your searches, stars and what it finds inside never leave it. The only thing it downloads is search by description, if you choose it."
                )
                Text(Copy.backgroundNote)
                Text("Long files are processed a bit at a time: you can find them by their beginning first, and the rest follows.")
            }
        }
    }

    /// The status line is the way into Processing, with Process for 15 minutes, Stop and Resume as its button: a tap changes
    /// the line at once ("Processing for 15 minutes · 15 min left").
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
                    ? Words.shared.whileChargingBody(words: Copy.words)
                    : snap.automaticReading == .manual
                        ? "Only when you choose \(Words.shared.READ_FOR_15). You can always find any file by its name."
                        : "Processes your files while Retriever is open, above 20% battery. When you’re not using it, iOS waits until your \(Copy.device) is charging. Pauses when your \(Copy.device) is warm or in Low Power Mode."
            ).font(.footnote)
            if #available(iOS 26.0, *) {
                EmptyView()
            } else {
                Toggle(
                    "Keep the screen on while processing for 15 minutes",
                    isOn: Binding(
                        get: { model.keepScreenAwakeForSession },
                        set: {
                            model.keepScreenAwakeForSession = $0
                            model.keepScreenOn(model.reading)
                        }))
            }
            if model.meaningSearch {
                DisclosureGroup("Adjust processing for this \(Copy.device)") {
                    Text("Run the setup check again.")
                    Button("Run setup check", action: model.tuneAgain)
                }
            }
            if let message = model.readingMessage { Text(message).foregroundStyle(.red) }
        }
    }

    @ViewBuilder private func includedFiles(_ snap: AppModel.Snap) -> some View {
        Section {
            NavigationLink(Words.shared.WHAT_IT_CAN_SEE, value: Route.folders)
        }
        Section {
            // No platform reads audio yet, so it has no switch: only the plain fact.
            LabeledContent("Audio", value: Words.shared.FOUND_BY_NAME)
            ForEach(snap.textOnly ? AppModel.Snap.insideTextOnly : AppModel.Snap.inside, id: \.self) { kind in
                Toggle(Self.label(kind), isOn: Binding(get: { snap.kinds.contains(kind) }, set: { model.setKind(kind, $0) }))
            }
        } header: {
            Text(Words.shared.PROCESS_THESE_KINDS)
        } footer: {
            Text(snap.textOnly ? Words.shared.textOnlyNote(ramGb: snap.ramGb, words: Copy.words) : "\(Words.shared.KIND_OFF) Videos: pictures only, no sound.")
        }
        Section {
            if snap.excludedFolders.isEmpty { Text("None").foregroundStyle(.secondary) }
            ForEach(snap.excludedFolders.sorted(), id: \.self) { path in
                let name = path.split(separator: "/").last.map(String.init) ?? path
                HStack {
                    Text(name).lineLimit(1)
                    Spacer()
                    Button {
                        model.restoreFolder(path)
                    } label: {
                        Text("Include folder").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Include “\(name)”")
                }
            }
        } header: {
            Text(Words.shared.FOLDERS_TO_SKIP)
        } footer: {
            Text("Retriever doesn’t look in these. Your files stay where they are.")
        }
    }

    /// What Retriever keeps on the device: each a title, then its size. Large files and duplicates are on Home.
    @ViewBuilder private func storage(_ snap: AppModel.Snap) -> some View {
        let sizes = model.storage
        Section("Storage") {
            TitleAndDetail(title: Words.shared.RETRIEVERS_DATA, detail: Words.shared.keptOn(size: sizes.map { Copy.bytes($0.index) } ?? "…", words: Copy.words))
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
            Toggle("Use mobile data for downloads", isOn: Binding(get: { model.useMobileData }, set: model.setUseMobileData))
        } header: {
            Text(Words.shared.SEARCH_BY_MEANING)
        } footer: {
            Text(model.useMobileData ? "Downloads on Wi-Fi or mobile data." : "Downloads wait for Wi-Fi, so they won't use your data.")
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
        Section("About") {
            LabeledContent("Version", value: Self.version)
            NavigationLink("Open-source notices") { Notices() }
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
        case .photo: "Photos"
        case .screenshot: "Screenshots"
        case .pdf: "PDFs"
        case .doc: "Documents"
        case .audio: "Audio"
        case .video: "Videos"
        default: "Other files"
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
            Text(text ?? AttributedString("Loading…"))
                .font(.footnote)
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Open-source notices")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let raw =
                Bundle.main.url(forResource: "THIRD_PARTY_NOTICES", withExtension: "md").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
                ?? "Retriever is built on open-source software. Its licences couldn't be shown just now."
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
