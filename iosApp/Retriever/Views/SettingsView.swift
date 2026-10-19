import RetrieverCore
import SwiftUI

/// What a person can steer: what is read, when, what is kept on the device, and how the model downloads.
struct SettingsView: View {
    let model: AppModel
    @State private var confirmingFree = false
    @State private var confirmingClear = false
    @State private var searchesCleared = false
    #if DEBUG
        /// Overrides the app's own log (previews); nil reads model.services?.diagnostics.
        var diagnostics: Diagnostics? = nil
        @State private var problems: (url: URL, count: Int)?
        @State private var loadedProblems = false
    #endif

    /// The footer under Included files; shown only where the behaviour behind it is real (Retriever.updatePrefs).
    static let includedFilesFooter =
        "Turning a type off keeps name search and removes its content from search. Excluding a folder removes it from Retriever. Your original files stay where they are."

    /// The confirmation for removing the download; `size` is what is on the phone, nil when it can't be read.
    static func removeDownloadMessage(size: String?) -> String {
        (size.map { "This frees \($0). " } ?? "") + "File names and text already read still work. Search by meaning needs the download again."
    }

    var body: some View {
        let snap = model.snap ?? AppModel.Snap()
        Form {
            reading(snap)
            includedFiles(snap)
            Section {
                Toggle("Save recent searches", isOn: Binding(get: { snap.rememberSearches }, set: model.setRememberSearches))
                Button(searchesCleared ? "Cleared" : "Clear recent searches", role: .destructive) { confirmingClear = true }
                    .disabled(searchesCleared)
            } header: {
                Text("Privacy")
            } footer: {
                Text("Saved on this \(Copy.device) only. Nothing is sent anywhere.")
            }
            storage(snap)
            Section("Appearance") {
                Toggle("Show On this day", isOn: Binding(get: { snap.showOnThisDay }, set: model.setShowOnThisDay))
            }
            about
            #if DEBUG
                if !snap.textOnly { Section("For testing") { testing } }
            #endif
        }
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
        .confirmationDialog("Clear recent searches?", isPresented: $confirmingClear, titleVisibility: .visible) {
            Button("Clear", role: .destructive) {
                model.clearRecentSearches()
                searchesCleared = true
            }
        }
        .confirmationDialog("Remove the search download?", isPresented: $confirmingFree, titleVisibility: .visible) {
            Button("Remove download", role: .destructive, action: model.freeUpSpace)
        } message: {
            Text(Self.removeDownloadMessage(size: model.storage.map { Copy.downloadSize($0.model) }))
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

    private func reading(_ snap: AppModel.Snap) -> some View {
        Section("Reading") {
            Picker(
                "Automatic reading",
                selection: Binding<String>(
                    get: { snap.automaticReading.name },
                    set: { value in
                        if let schedule = ReadingSchedule.entries.first(where: { $0.name == value }) { model.setReadingSchedule(schedule) }
                    })
            ) {
                Text("While charging").tag(ReadingSchedule.charging.name)
                Text("Also on battery").tag(ReadingSchedule.battery.name)
                Text("When I start it").tag(ReadingSchedule.manual.name)
            }
            Text(
                snap.automaticReading == .charging
                    ? "Reads while charging when Retriever is open, and in the background when iOS allows."
                    : snap.automaticReading == .manual
                        ? "Choose Read now to start a short reading session."
                        : "Reads while Retriever is open or when iOS allows, above 20% battery. Pauses for power saving or heat."
            ).font(.footnote)
            Button("Read now for 15 minutes", action: model.readNow)
            Button(snap.paused ? "Resume reading" : "Pause reading") { if snap.paused { model.resume() } else { model.pause() } }
            NavigationLink("Reading status", value: Route.status)
            if #available(iOS 26.0, *) {
                EmptyView()
            } else {
                Toggle(
                    "Keep screen awake for this session",
                    isOn: Binding(
                        get: { model.keepScreenAwakeForSession },
                        set: {
                            model.keepScreenAwakeForSession = $0
                            model.keepScreenOn(model.reading)
                        }))
            }
            if model.meaningSearch {
                DisclosureGroup("Adjust reading for this device") {
                    Text("Run the setup check again.")
                    Button("Run setup check", action: model.tuneAgain)
                }
            }
            DisclosureGroup("How reading works") {
                Text(Copy.backgroundNote)
                Text(
                    "Long files are read in stages. Words already read can match now; later passes add meaning search. Some files reach a reading limit and stay partly read."
                )
            }
            if let message = model.readingMessage { Text(message).foregroundStyle(.red) }
        }
    }

    @ViewBuilder private func includedFiles(_ snap: AppModel.Snap) -> some View {
        Section("Included files") {
            NavigationLink("Manage access", value: Route.folders)
        }
        Section {
            // No platform reads audio yet, so it has no switch: only the plain fact.
            LabeledContent("Audio", value: "Name only on this device")
            ForEach(snap.textOnly ? AppModel.Snap.insideTextOnly : AppModel.Snap.inside, id: \.self) { kind in
                Toggle(Self.label(kind), isOn: Binding(get: { snap.kinds.contains(kind) }, set: { model.setKind(kind, $0) }))
            }
        } header: {
            Text("Read these file types")
        } footer: {
            Text(snap.textOnly ? Words.shared.textOnlyNote(ramGb: snap.ramGb, words: Copy.words) : "Videos: frames only, no sound.")
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
            Text("Excluded folders")
        } footer: {
            Text(Self.includedFilesFooter)
        }
    }

    @ViewBuilder private func storage(_ snap: AppModel.Snap) -> some View {
        let sizes = model.storage
        Section {
            Text("Saved search data · \(sizes.map { Copy.bytes($0.index) } ?? "…")")
            if !snap.textOnly {
                if let cache = sizes?.cache, cache > 0, !model.modelMissing {
                    Text("Temporary files · \(Copy.bytes(cache))")
                }
                // The same upgrade as Try and Home until it is done; then what it costs and how to remove it.
                if model.upgrade == .done {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(Words.shared.UPGRADE_TITLE) · \(modelSize(snap, sizes))")
                        Text("Downloaded").font(.footnote).foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                    Button("Remove search download", role: .destructive) { confirmingFree = true }
                } else {
                    UpgradeCard(model: model)
                }
                Toggle("Use mobile data for downloads", isOn: Binding(get: { model.useMobileData }, set: model.setUseMobileData))
            }
        } header: {
            Text("Storage and downloads")
        } footer: {
            if !snap.textOnly { Text(model.useMobileData ? "Downloads on Wi-Fi or mobile data." : "Downloads wait for Wi-Fi, so they won't use your data.") }
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
            NavigationLink("Privacy") {
                ScrollView {
                    Text(
                        "Retriever reads your files on this \(Copy.device). Search, reading and stars never leave it. The only network use is downloading the search model."
                    )
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .navigationTitle("Privacy")
                .navigationBarTitleDisplayMode(.inline)
            }
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
