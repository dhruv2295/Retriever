import Photos
import RetrieverCore
import SwiftUI

/// First run on core's stage machine (`SetupInputs.stage`): welcome, access, the sample read with no download, Try,
/// then when to read more. Every stage follows from stored state, so each resumes after the app is closed.
struct SetupView: View {
    let model: AppModel
    /// In Home's stack (its status row leads here): back to Home once setup has nothing left to ask.
    var onDone: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let stage = model.setup?.stage ?? .welcome
        Group {
            switch stage {
            case .welcome: WelcomeStep(model: model)
            case .access: AccessStep(model: model)
            case .try_: TryStep(model: model)
            case .choose: ChooseStep(model: model)
            default: SampleStep(model: model)
            }
        }
        .id(stage)
        .transition(reduceMotion ? .opacity : .push(from: .trailing))
        .animation(reduceMotion ? nil : .snappy, value: stage)
        .onChange(of: stage, initial: true) { _, stage in
            if stage == .done { onDone?() }
        }
    }
}

/// One setup screen: its content scrolls, its actions stay at the bottom.
private struct StepPage<Content: View, Actions: View>: View {
    @ViewBuilder let content: Content
    @ViewBuilder let actions: Actions

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) { content }
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) { actions }
                .controlSize(.large)
                .padding(.horizontal, 24)
                .padding(.vertical, 12)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                // Opaque: text over a material takes on what scrolls behind it, and the previews would show through.
                .background(.background)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("step-actions")
        }
    }
}

/// A button's label filling the footer's width.
private func wide(_ title: String) -> some View {
    Text(title).frame(maxWidth: .infinity)
}

private struct WelcomeStep: View {
    let model: AppModel

    var body: some View {
        StepPage {
            MascotView(mood: .welcome, settles: false).frame(width: 260).frame(maxWidth: .infinity)
            Text(Words.shared.WELCOME_TITLE).font(.title.bold()).accessibilityAddTraits(.isHeader)
            Text(Words.shared.welcomeBody(words: Copy.words))
            if let snap = model.snap, snap.textOnly {
                Note(Words.shared.textOnlyNote(ramGb: snap.ramGb, words: Copy.words))
            }
        } actions: {
            Button {
                model.setSetupMark(.started)
            } label: {
                wide(Words.shared.GET_STARTED)
            }.buttonStyle(.primaryAction)
            Button(action: model.searchByName) { wide(Words.shared.SEARCH_BY_NAME) }
        }
    }
}

/// Photos (selected or all) and folders; Continue once either gives Retriever something to read.
private struct AccessStep: View {
    let model: AppModel
    @State private var picking = false
    @State private var folderNote: String?

    var body: some View {
        StepPage {
            MascotView(mood: .resting, settles: false).frame(width: 160).frame(maxWidth: .infinity)
            Text(Words.shared.CHOOSE_ACCESS).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            Text(Words.shared.ACCESS_BODY)
            GroupBox {
                VStack(alignment: .leading, spacing: 12) {
                    LabeledContent {
                        if let state = Copy.photosAccess(model.photos) { Text(state).foregroundStyle(.primary) }
                    } label: {
                        Label("Photos", systemImage: "photo.on.rectangle")
                    }
                    switch model.photos {
                    case .notDetermined:
                        Button("Allow access to Photos", action: model.requestPhotos).buttonStyle(.bordered)
                    case .limited:
                        Note(Words.shared.ONLY_SELECTED_PHOTOS)
                        Button(Words.shared.CHOOSE_MORE_PHOTOS, action: model.chooseMorePhotos).buttonStyle(.bordered)
                    case .denied, .restricted:
                        Button("Open Settings", action: model.openSettings).buttonStyle(.bordered)
                    default:
                        EmptyView()
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            GroupBox {
                VStack(alignment: .leading, spacing: 12) {
                    Label(Words.shared.FOLDERS, systemImage: "folder")
                    ForEach(model.folders, id: \.self) { Text($0) }
                    if let folderNote { Note(folderNote) }
                    Button(Words.shared.ADD_FOLDER) { picking = true }.buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !model.accessGiven { Note(Words.shared.NOTHING_SELECTED) }
        } actions: {
            // Shown once something is chosen; until then the note above says what to do.
            if model.accessGiven {
                Button {
                    model.setSetupMark(.accessChosen)
                } label: {
                    wide(Words.shared.CONTINUE)
                }.buttonStyle(.primaryAction)
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            folderNote = model.addFolders(urls).map(Copy.addFolderResult).joined(separator: "\n")
        }
    }
}

/// Reading a few files with no download: real previews of what is read, and Try search once enough is.
private struct SampleStep: View {
    let model: AppModel
    @State private var taste: TasteSnap
    @State private var since = Date.now
    @State private var seeingAll = false

    init(model: AppModel) {
        self.model = model
        _taste = State(initialValue: model.fixtureTaste)
    }

    var body: some View {
        let preview = model.progress.reading
        let reading = taste.pending > 0 && preview.pauseReason == nil
        StepPage {
            MascotView(mood: reading ? .walking : .resting, settles: false).frame(width: 160).frame(maxWidth: .infinity)
            Text(Words.shared.SAMPLE_TITLE).font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
                .accessibilityValue(taste.spoken)
            Text(Words.shared.readCount(read: Int64(taste.read), partlyRead: 0)).monospacedDigit()
            // Time only while reading; past the sample's batch it says so rather than repeating a stale estimate.
            TimelineView(.periodic(from: since, by: 15)) { context in
                let elapsed = Int64(context.date.timeIntervalSince(since) * 1000)
                if let time = Words.shared.sampleTime(reading: reading, elapsedMs: elapsed, words: Copy.words) { Note(time) }
            }
            if let reason = preview.pauseReason { Note(reason) }
            SampleMosaic(preview: preview)
            if taste.read > 0 {
                Button(Words.shared.seeAll(files: Int64(taste.read))) { seeingAll = true }
            }
        } actions: {
            if taste.readyToTry {
                Button {
                    model.setSetupMark(.trying)
                } label: {
                    wide(Words.shared.TRY_SEARCH)
                }.buttonStyle(.primaryAction)
            }
            Button(action: model.goHome) { wide(Words.shared.GO_TO_HOME) }
        }
        .task {
            guard let retriever = model.services?.retriever else { return }
            for await snap in retriever.tasteStream() { taste = snap }
        }
        .sheet(isPresented: $seeingAll) { SampleList(model: model) }
    }
}

/// Up to six real previews, the file being read first. Six tiles need no lazy loading, so a plain Grid: two columns,
/// one at accessibility sizes, each tile as tall as its own preview and name, so nothing is reserved for files not read yet.
struct SampleMosaic: View {
    let preview: ReadingPreview
    @Environment(\.dynamicTypeSize) private var typeSize

    /// The file being read, then the newest read; never more than six.
    static func tiles(_ preview: ReadingPreview) -> [SourceFile] {
        Array(([preview.current].compactMap { $0 } + preview.recent.filter { $0.uri != preview.current?.uri }).prefix(6))
    }

    static func columns(_ typeSize: DynamicTypeSize) -> Int { typeSize.isAccessibilitySize ? 1 : 2 }

    var body: some View {
        let tiles = Self.tiles(preview)
        if preview.recent.isEmpty { Note(Words.shared.SAMPLE_STARTING) }
        let columns = Self.columns(typeSize)
        Grid(alignment: .topLeading, horizontalSpacing: 12, verticalSpacing: 16) {
            ForEach(Array(stride(from: 0, to: tiles.count, by: columns)), id: \.self) { start in
                GridRow {
                    ForEach(tiles[start..<min(start + columns, tiles.count)], id: \.uri) { file in
                        SampleTile(file: file, readingNow: file.uri == preview.current?.uri)
                    }
                }
            }
        }
    }
}

/// A preview, the file's name and its kind; "Reading now" on the file being read.
private struct SampleTile: View {
    let file: SourceFile
    let readingNow: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Thumb(file: file, points: 200, bucketed: true)
                .aspectRatio(4 / 3, contentMode: .fit)
                .clipShape(.rect(cornerRadius: 12, style: .continuous))
                .overlay(alignment: .topLeading) {
                    if readingNow {
                        Text(Words.shared.READING_NOW).font(.caption.weight(.semibold))
                            .padding(.horizontal, 8).padding(.vertical, 4)
                            .background(.regularMaterial, in: .capsule)
                            .padding(6)
                    }
                }
            Text(file.name).font(.subheadline).lineLimit(2).truncationMode(.middle)
            Text(Words.shared.kindLabel(kind: file.kind)).font(.caption)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(file.name), \(Words.shared.kindLabel(kind: file.kind)), \(Format.date(file.modifiedAt))")
        .accessibilityValue(readingNow ? Words.shared.READING_NOW : "")
        .accessibilityIdentifier("recent:\(file.uri)")
    }
}

/// "See all": every sample file read so far, each opening in the viewer.
private struct SampleList: View {
    let model: AppModel
    @State private var files: [SourceFile] = []
    @State private var path: [Route] = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        AppStack(model: model, path: $path) {
            ScrollView { ReadyFiles(files: files).padding() }
                .navigationTitle(Words.shared.SAMPLE_TITLE)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
                .task(id: model.snap?.indexed) { files = await model.sampleFiles() }
        }
    }
}

/// The real search, names over the whole library and words inside the sample.
private struct TryStep: View {
    let model: AppModel
    @State private var search: SearchModel
    @State private var path: [Route] = []

    init(model: AppModel) {
        self.model = model
        _search = State(initialValue: SearchModel(services: model.services))
    }

    var body: some View {
        let more = model.snap?.waiting != 0
        AppStack(model: model, path: $path) {
            SearchView(model: model, search: search, trial: Trial(ids: model.sampleIds, photoSearch: model.meaningSearch))
                .safeAreaInset(edge: .bottom) {
                    VStack(spacing: 8) {
                        Button(action: model.chooseWhenToReadMore) { wide(more ? Words.shared.CHOOSE_WHEN : Words.shared.GO_TO_HOME) }
                            .buttonStyle(.primaryAction)
                        if more { Button(action: model.goHome) { wide(Words.shared.GO_TO_HOME) } }
                    }
                    .controlSize(.large)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 8)
                    .background(.background)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("step-actions")
                }
        }
        .task {
            guard let retriever = model.services?.retriever else { return }
            for await taste in retriever.tasteStream() { search.stillReading = taste.pending }
        }
    }
}

/// When to read the rest; no mascot, the decision is the focal point.
private struct ChooseStep: View {
    let model: AppModel

    var body: some View {
        ScrollView {
            ReadingChoice(
                files: model.snap?.waiting, charging: { model.commit(whileCharging: true) }, readNow: { model.commit(whileCharging: false) },
                notNow: model.decideLater
            )
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
    }
}

struct Note: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.subheadline).foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
    }
}

struct Failure: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        // An HStack, not a Label: in a List row the Label's text stopped growing with the type size and was cut off.
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
            Text(text).fixedSize(horizontal: false, vertical: true)
        }
        .font(.subheadline)
        .accessibilityElement(children: .combine)
    }
}

struct Busy: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text(text).font(.subheadline)
        }
        .accessibilityElement(children: .combine)
    }
}

#Preview("Welcome") {
    SetupView(model: AppModel(fixture: .firstRun))
}

#Preview("Access, limited Photos") {
    SetupView(model: AppModel(fixture: .photosLimited))
}

#Preview("Reading a few files") {
    SetupView(model: AppModel(fixture: .sampleReading))
}
