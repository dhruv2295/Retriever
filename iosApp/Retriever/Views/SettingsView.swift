import RetrieverCore
import SwiftUI

/// What a person can steer: what is read, when, what is kept on the device, and how the model downloads.
struct SettingsView: View {
    let model: AppModel
    @State private var confirmingFree = false
    @State private var searchesCleared = false
    /// Overrides the app's own log (previews); nil reads model.services?.diagnostics.
    var diagnostics: Diagnostics? = nil
    @State private var problems: (url: URL, count: Int)?
    @State private var loadedProblems = false

    var body: some View {
        let snap = model.snap ?? AppModel.Snap()
        Form {
            reading(snap)
            readInside(snap)
            skipped(snap)
            Section {
                Toggle("Remember recent searches", isOn: Binding(get: { snap.rememberSearches }, set: model.setRememberSearches))
                Button(searchesCleared ? "Cleared" : "Clear recent searches", role: .destructive) {
                    model.clearRecentSearches()
                    searchesCleared = true
                }
                .disabled(searchesCleared)
            } header: {
                Text("Privacy")
            } footer: {
                Text("Saved on this \(Copy.device) only. Nothing is sent anywhere.")
            }
            Section("Appearance") {
                Toggle("Show On this day", isOn: Binding(get: { snap.showOnThisDay }, set: model.setShowOnThisDay))
            }
            storage(snap)
            if !snap.textOnly {
                Section {
                    Toggle("Use mobile data", isOn: Binding(get: { model.useMobileData }, set: model.setUseMobileData))
                } header: {
                    Text("Downloads")
                } footer: {
                    Text(model.useMobileData ? "Downloads on Wi-Fi or mobile data." : "Waits for Wi-Fi, so it won't use your data.")
                }
            }
            #if DEBUG
                if !snap.textOnly { Section("For testing") { testing } }
            #endif
            about
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.modelMissing) { await model.loadStorage() }
        .task {
            guard !loadedProblems, let log = diagnostics ?? model.services?.diagnostics else { return }
            loadedProblems = true
            problems = await Self.exportProblems(log)
        }
        .confirmationDialog("Remove the search model?", isPresented: $confirmingFree, titleVisibility: .visible) {
            Button("Remove", role: .destructive, action: model.freeUpSpace)
        } message: {
            Text(
                "Searching by meaning and by what is in photos stops until it downloads again (about \(Copy.downloadSize(snap.downloadBytes))). Searching by name and words keeps working. Your files and stars are not touched."
            )
        }
    }

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
            if !snap.textOnly && !model.modelMissing {
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

    private func readInside(_ snap: AppModel.Snap) -> some View {
        Section {
            Text("Audio · Name only on this device")
            ForEach(snap.textOnly ? AppModel.Snap.insideTextOnly : AppModel.Snap.inside, id: \.self) { kind in
                Toggle(Self.label(kind), isOn: Binding(get: { snap.kinds.contains(kind) }, set: { model.setKind(kind, $0) }))
            }
        } header: {
            Text("Read these file types")
        } footer: {
            Text(
                snap.textOnly
                    ? Words.shared.textOnlyNote(ramGb: snap.ramGb, words: Copy.words)
                    : "Turn a type off to search it by name only. Videos: frames only, no sound. Audio is name only on this device.")
        }
    }

    @ViewBuilder private func skipped(_ snap: AppModel.Snap) -> some View {
        if !snap.excludedFolders.isEmpty {
            Section {
                ForEach(snap.excludedFolders.sorted(), id: \.self) { path in
                    let name = path.split(separator: "/").last.map(String.init) ?? path
                    HStack {
                        Text(name).lineLimit(1)
                        Spacer()
                        Button {
                            model.restoreFolder(path)
                        } label: {
                            Text("Search again").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Search “\(name)” again")
                    }
                }
            } header: {
                Text("Folders skipped")
            } footer: {
                Text("Files in these folders don't show up in search.")
            }
        }
    }

    @ViewBuilder private func storage(_ snap: AppModel.Snap) -> some View {
        let sizes = model.storage
        Section {
            LabeledContent("Search index", value: sizes.map { Copy.bytes($0.index) } ?? "…")
            if !snap.textOnly {
                LabeledContent("Search model", value: model.modelMissing ? "Not downloaded" : sizes.map { Copy.downloadSize($0.model) } ?? "…")
                if let cache = sizes?.cache, cache > 0, !model.modelMissing {
                    LabeledContent("Temporary files", value: Copy.bytes(cache))
                }
                if model.modelMissing {
                    downloadAgain
                } else if snap.setUp == true {
                    Button("Free up space", role: .destructive) { confirmingFree = true }
                }
            }
        } header: {
            Text("Storage")
        } footer: {
            if !snap.textOnly && model.modelMissing { Text(Copy.textOnlySearch) }
        }
    }

    @ViewBuilder private var downloadAgain: some View {
        switch model.download {
        case .downloading:
            ProgressView(value: model.downloadProgress) {
                Text("Downloading").font(.subheadline)
            } currentValueLabel: {
                Text(model.downloadProgress.formatted(.percent.precision(.fractionLength(0)))).monospacedDigit()
            }
        case .verifying: Busy("Checking the download…")
        case .waitingForWifi: Note("Waiting for Wi-Fi.")
        case .failed(let reason):
            Failure(reason)
            Button("Try again", action: model.downloadAgain)
        case .ready: EmptyView()
        case .idle: Button("Download again", action: model.downloadAgain)
        }
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
            if let problems {
                ShareLink(item: problems.url) { Text("Recent problems (\(problems.count))") }
            }
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

#Preview("Settings with problems") {
    let file = FileManager.default.temporaryDirectory.appendingPathComponent("problems-fixture.json")
    try? JSONEncoder().encode(["{\"crashDiagnostics\":[]}", "{\"hangDiagnostics\":[]}"]).write(to: file)
    return NavigationStack { SettingsView(model: AppModel(fixture: .caughtUp), diagnostics: Diagnostics(file: file)) }
}
