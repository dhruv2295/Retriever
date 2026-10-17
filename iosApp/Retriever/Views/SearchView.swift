// Ported from Retriever's Android SearchScreen.kt and SearchViewModel.kt (same project): debounced search as you
// type, kind filters run inside the engine, search by photo, recent searches, and the empty, words-only and error
// states. Native chrome follows Apple's sample "Adding a search interface to your app" (Apple sample code licence):
// searchable with scopes and suggestions in a NavigationStack. Modified for Retriever: scopes pass kinds to the
// engine, results mix a photo grid with document rows, and every state has an illustration.

import ImageIO
import Photos
import PhotosUI
import RetrieverCore
import SwiftUI

@Observable @MainActor
final class SearchModel {
    /// Four, so every title fits the scope bar on an iPhone.
    enum Scope: String, CaseIterable, Identifiable {
        case all, photos, videos, documents

        var id: Self { self }

        var title: String {
            switch self {
            case .all: "All"
            case .photos: "Photos"
            case .videos: "Videos"
            case .documents: "Documents"
            }
        }

        /// Filtered inside the engine, so a narrow scope still finds files beyond the mixed top results.
        var kinds: Set<Kind>? {
            switch self {
            case .all: nil
            case .photos: [.photo, .screenshot]
            case .videos: [.video]
            case .documents: [.pdf, .doc]
            }
        }

        var needsPhotos: Bool { self == .photos || self == .videos }

        /// `-scope photo`, `-scope pdf` and the like.
        init?(arg: String) {
            switch arg {
            case "screenshot", "screenshots": self = .photos
            case "pdf", "pdfs", "doc", "docs": self = .documents
            default:
                guard let s = Self.allCases.first(where: { $0.rawValue == arg || $0.rawValue == arg + "s" }) else { return nil }
                self = s
            }
        }
    }

    struct Photo {
        let id = UUID()
        let jpeg: Data
        let thumb: UIImage
    }

    struct Key: Equatable {
        var query: String
        var scope: Scope
        var photo: UUID?
        var retry = 0
    }

    struct Answer {
        let hits: [SearchEngine.Hit]
        let vectorLane: Bool
        let key: Key
    }

    private(set) var text = ""
    var scope = Scope.all
    private(set) var photo: Photo?
    private(set) var answer: Answer?
    private(set) var failed = false
    /// Searching for longer than 300 ms: only then is a spinner worth showing.
    private(set) var slow = false
    private(set) var recent: [String] = []
    private(set) var recentFiles: [SourceFile] = []
    private var retries = 0
    @ObservationIgnored private var wordsOnlyRetries = 0
    @ObservationIgnored private let services: AppServices?

    init(services: AppServices?) {
        self.services = services
    }

    var query: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
    var active: Bool { !query.isEmpty || photo != nil }
    var key: Key { Key(query: photo == nil ? query : "", scope: scope, photo: photo?.id, retry: retries) }

    /// The results on screen answer older words, another scope or another photo.
    var stale: Bool { answer?.key != key }

    /// Typing ends a search by photo; clearing the words drops the scope with them.
    func setText(_ new: String) {
        let hadWords = !query.isEmpty
        text = new
        wordsOnlyRetries = 0
        if !query.isEmpty {
            photo = nil
        } else if hadWords {
            scope = .all
        }
    }

    func search(_ key: Key) async {
        guard let retriever = services?.retriever else { return }
        guard key.photo != nil || !key.query.isEmpty else {
            answer = nil
            failed = false
            return
        }
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled else { return }
        let spinner = Task {
            try await Task.sleep(for: .milliseconds(300))
            slow = true
        }
        defer {
            spinner.cancel()
            slow = false
        }
        do {
            let result = if let photo, photo.id == key.photo {
                try await retriever.searchImageData(jpeg: photo.jpeg, kinds: key.scope.kinds, limit: 60)
            } else {
                try await retriever.search(q: key.query, kinds: key.scope.kinds, limit: 60)
            }
            // Kotlin doesn't see Swift's cancellation: drop an answer to a search that was replaced.
            guard !Task.isCancelled else { return }
            answer = Answer(hits: result.hits, vectorLane: result.vectorLane, key: key)
            failed = false
            // Words only while the model was busy opening: ask again shortly, a few times, for results by meaning.
            if result.vectorLane {
                wordsOnlyRetries = 0
            } else if wordsOnlyRetries < 5 {
                wordsOnlyRetries += 1
                try? await Task.sleep(for: .seconds(2))
                if !Task.isCancelled { retries += 1 }
            }
        } catch {
            guard !Task.isCancelled else { return }
            failed = true
        }
    }

    func retry() {
        retries += 1
    }

    /// On submit, or when a result is opened.
    func record() {
        guard let retriever = services?.retriever, photo == nil, !query.isEmpty else { return }
        let q = query
        Task {
            try? await retriever.recordQuery(q: q)
            await loadRecent()
        }
    }

    func loadRecent() async {
        guard let retriever = services?.retriever else { return }
        recent = (try? await retriever.recentQueries()) ?? recent
    }

    func removeRecent(_ q: String) {
        recent.removeAll { $0 == q }
        guard let retriever = services?.retriever else { return }
        Task { try? await retriever.removeRecentQuery(q: q) }
    }

    func clearRecent() {
        recent = []
        guard let retriever = services?.retriever else { return }
        Task { try? await retriever.clearRecentQueries() }
    }

    func loadRecentFiles() async {
        guard let retriever = services?.retriever else { return }
        if let files = try? await retriever.files(kind: nil, folder: nil, sort: .newest, limit: 12, offset: 0) {
            recentFiles = files.map(\.file)
        }
    }

    func findSimilar(_ file: SourceFile) {
        guard let extractor = services?.extractor else { return }
        let uri = file.uri
        Task {
            let jpeg = await Task.detached { extractor.imageJpeg(uri: uri, maxSide: 1024) }.value
            if let jpeg { show(jpeg) }
        }
    }

    /// A photo from the picker, at most 1024 px on its longest side.
    func pick(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self) else { return }
        let jpeg = await Task.detached { () -> Data? in
            guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                      kCGImageSourceCreateThumbnailFromImageAlways: true,
                      kCGImageSourceCreateThumbnailWithTransform: true,
                      kCGImageSourceThumbnailMaxPixelSize: 1024,
                  ] as CFDictionary)
            else { return nil }
            return UIImage(cgImage: image).jpegData(compressionQuality: 0.85)
        }.value
        if let jpeg { show(jpeg) }
    }

    private func show(_ jpeg: Data) {
        guard let thumb = UIImage(data: jpeg) else { return }
        text = ""
        photo = Photo(jpeg: jpeg, thumb: thumb)
    }

    func clearPhoto() {
        photo = nil
    }

    /// Fixed state for screenshots and previews; nothing runs.
    static func fixture(text: String = "", scope: Scope = .all, hits: [SearchEngine.Hit]? = nil, vectorLane: Bool = true, failed: Bool = false,
                        slow: Bool = false, photo: UIImage? = nil, recent: [String] = [], recentFiles: [SourceFile] = []) -> SearchModel {
        let m = SearchModel(services: nil)
        m.text = text
        m.scope = scope
        if let photo, let jpeg = photo.jpegData(compressionQuality: 0.8) { m.photo = Photo(jpeg: jpeg, thumb: photo) }
        if let hits { m.answer = Answer(hits: hits, vectorLane: vectorLane, key: m.key) }
        m.failed = failed
        m.slow = slow
        m.recent = recent
        m.recentFiles = recentFiles
        return m
    }
}

// MARK: Screen

/// Home: the search field, how reading is going, recent searches and files. Typing turns it into results.
struct SearchView: View {
    let model: AppModel
    @State private var search: SearchModel
    @State private var presented: Bool
    @State private var picked: PhotosPickerItem?
    @State private var addingFolder = false
    @State private var folderNote: String?
    @Environment(\.openFile) private var openFile
    private let fixedSnap: AppModel.Snap?

    /// `search` and `snap` pin a state for screenshots; by default the search runs live.
    init(model: AppModel, search: SearchModel? = nil, snap: AppModel.Snap? = nil, presented: Bool = false) {
        self.model = model
        _search = State(initialValue: search ?? SearchModel(services: model.services))
        _presented = State(initialValue: presented)
        fixedSnap = snap
    }

    private var snap: AppModel.Snap? { fixedSnap ?? model.snap }

    var body: some View {
        Group {
            if search.active {
                ResultsView(search: search, snap: snap, photos: model.photos, model: model, open: open)
            } else {
                HomeList(search: search, snap: snap, photos: model.photos, model: model,
                         useRecent: { q in
                             search.setText(q)
                             presented = true
                             search.record()
                         },
                         addFolder: { addingFolder = true })
            }
        }
        .navigationTitle("Retriever")
        .searchable(text: Binding(get: { search.text }, set: search.setText), isPresented: $presented,
                    placement: .navigationBarDrawer(displayMode: .always), prompt: "Search photos, screenshots and files")
        .searchScopes($search.scope, activation: .onSearchPresentation) {
            ForEach(SearchModel.Scope.allCases) { Text($0.title).tag($0) }
        }
        .searchSuggestions { suggestions }
        .onSubmit(of: .search) { search.record() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                PhotosPicker(selection: $picked, matching: .images) {
                    Label("Search by photo", systemImage: "photo.badge.magnifyingglass")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: Route.browse) { Label("Browse", systemImage: "square.grid.2x2") }
            }
        }
        .task(id: search.key) { [key = search.key] in await search.search(key) }
        .task(id: snap?.total) { await search.loadRecentFiles() }
        .task { await search.loadRecent() }
        .onChange(of: picked) { _, item in
            guard let item else { return }
            picked = nil
            // Presented for the scopes, but the empty field shouldn't raise the keyboard over the photo's results.
            presented = true
            Task {
                await search.pick(item)
                try? await Task.sleep(for: .milliseconds(300))
                dismissKeyboard()
            }
        }
        .fileImporter(isPresented: $addingFolder, allowedContentTypes: [.folder]) { result in
            if case let .success(url) = result, let added = model.addFolders([url]).first {
                folderNote = Copy.addFolderResult(added)
            }
        }
        .alert(folderNote ?? "", isPresented: Binding(get: { folderNote != nil }, set: { if !$0 { folderNote = nil } })) {}
    }

    @ViewBuilder private var suggestions: some View {
        if search.text.isEmpty && search.photo == nil {
            if search.recent.isEmpty {
                Section("Try") {
                    ForEach(["wifi password", "electricity bill", "passport photo", "notes from the meeting"], id: \.self) { q in
                        Label(q, systemImage: "magnifyingglass").searchCompletion(q)
                    }
                }
            } else {
                Section("Recent searches") {
                    ForEach(search.recent, id: \.self) { q in
                        Label(q, systemImage: "clock.arrow.circlepath")
                            .searchCompletion(q)
                            .swipeActions { Button("Remove", role: .destructive) { search.removeRecent(q) } }
                            .contextMenu { Button("Remove from recent searches", systemImage: "trash", role: .destructive) { search.removeRecent(q) } }
                    }
                    Button("Clear recent searches", role: .destructive) { search.clearRecent() }
                }
            }
        }
    }

    private func open(_ hit: SearchEngine.Hit) {
        search.record()
        openFile(hit.file, locator: hit.locator, words: highlightWords(snippet: hit.snippet, query: search.photo == nil ? search.query : nil))
    }
}

// MARK: Home

private struct HomeList: View {
    let search: SearchModel
    let snap: AppModel.Snap?
    let photos: PHAuthorizationStatus
    let model: AppModel
    let useRecent: (String) -> Void
    let addFolder: () -> Void
    @Environment(\.openFile) private var openFile
    /// Grows with the text size, so a file's name still fits its card.
    @ScaledMetric(relativeTo: .caption) private var tile = 96.0

    var body: some View {
        List {
            // A header, not a row: a NavigationLink row would get a second chevron.
            if let snap, snap.total > 0 || snap.running || snap.setUp != true {
                Section {} header: {
                    StatusLine(snap: snap, download: model.download, progress: model.downloadProgress).textCase(nil).foregroundStyle(.primary).padding(.horizontal, -4)
                }
            }
            PhotosAccessCard(photos: photos, model: model)
            if let snap, snap.total == 0 {
                Section {
                    if snap.running {
                        StateView(art: "IllusReading", title: "Retriever is looking for your files", message: "This takes a minute the first time.")
                    } else {
                        StateView(art: "IllusEmpty", title: "Nothing to search yet",
                                  message: "Add a folder, or let Retriever see your photos, and it'll find what's inside them.") {
                            Button("Add a folder", action: addFolder).buttonStyle(.borderedProminent)
                            if photos == .notDetermined { Button("Allow Photos access", action: model.requestPhotos) }
                        }
                    }
                }
                .listRowBackground(Color.clear)
            }
            if !search.recent.isEmpty {
                Section("Recent searches") {
                    ForEach(search.recent, id: \.self) { q in
                        Button { useRecent(q) } label: {
                            Label(q, systemImage: "clock.arrow.circlepath").foregroundStyle(.primary)
                        }
                        .accessibilityHint("Searches again")
                        .swipeActions { Button("Remove", role: .destructive) { search.removeRecent(q) } }
                        .contextMenu { Button("Remove from recent searches", systemImage: "trash", role: .destructive) { search.removeRecent(q) } }
                    }
                    Button("Clear recent searches", role: .destructive) { search.clearRecent() }
                }
            }
            if !search.recentFiles.isEmpty {
                Section("Recent files") {
                    ScrollView(.horizontal) {
                        LazyHStack(spacing: 8) {
                            ForEach(search.recentFiles, id: \.uri) { file in
                                if file.kind == .photo || file.kind == .screenshot || file.kind == .video {
                                    ResultCell(file: file, onOpen: { openFile(file) }, onFindSimilar: search.findSimilar).frame(width: tile)
                                } else {
                                    FileCard(file: file, side: tile) { openFile(file) }
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                    }
                    .scrollIndicators(.hidden)
                    .listRowInsets(EdgeInsets())
                }
            }
        }
        .listStyle(.insetGrouped)
    }
}

/// A file with no picture: its kind's icon over its name, the size of a picture cell.
private struct FileCard: View {
    let file: SourceFile
    let side: CGFloat
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: Format.icon(file.kind)).font(.title3).foregroundStyle(.tint)
                Spacer(minLength: 0)
                // A name with no spaces would wrap mid-word ("Scan_0001.p" / "df"): one line, cut in the middle so the
                // extension stays.
                Text(file.name).font(.caption).lineLimit(file.name.contains(" ") ? 2 : 1).truncationMode(.middle)
                    .multilineTextAlignment(.leading)
            }
            .padding(8)
            .frame(width: side, height: side, alignment: .topLeading)
            .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(Format.kind(file.kind)), \(file.name), \(Format.date(file.modifiedAt))")
        .resultActions(file, nil)
    }
}

/// Photos access is off or limited: say what that means and how to change it.
private struct PhotosAccessCard: View {
    let photos: PHAuthorizationStatus
    let model: AppModel

    var body: some View {
        switch photos {
        case .denied, .restricted:
            card("Photos access is off", "Turn it on in Settings, and Retriever can search your photos and screenshots too.", "Open Settings", model.openSettings)
        case .limited:
            card("Retriever sees only some photos", "It can search just the photos you picked.", "Choose more photos", model.chooseMorePhotos)
        default:
            EmptyView()
        }
    }

    private func card(_ title: String, _ message: String, _ action: String, _ run: @escaping () -> Void) -> some View {
        Section {
            HStack(alignment: .top, spacing: 12) {
                Image("IllusWelcome").resizable().scaledToFit().frame(width: 64).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 6) {
                    Text(title).font(.headline)
                    Text(message).font(.subheadline).foregroundStyle(.secondary)
                    Button(action, action: run).buttonStyle(.bordered)
                }
            }
            .padding(.vertical, 4)
        }
    }
}

/// One line on how reading is going; opens Status. Shows once the counts load. Pinned for I5.
struct StatusLine: View {
    let snap: AppModel.Snap?
    var download = ModelDownload.State.idle
    var progress = 0.0

    var body: some View {
        if let snap, let line = Copy.statusLine(snap, download: download, progress: progress) {
            NavigationLink(value: Route.status) {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(line).font(.subheadline).monospacedDigit().multilineTextAlignment(.leading)
                        if snap.setUp == true && snap.running && snap.pendingAtStart > 0 {
                            ProgressView(value: snap.total > 0 ? Double(snap.indexed) / Double(snap.total) : 0)
                            if let eta = snap.etaMinutes { Text(Copy.eta(eta)).font(.footnote).foregroundStyle(.secondary) }
                        }
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(minHeight: 44)
                .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint("Shows details")
        }
    }
}

// MARK: Results

private struct ResultsView: View {
    let search: SearchModel
    let snap: AppModel.Snap?
    let photos: PHAuthorizationStatus
    let model: AppModel
    let open: (SearchEngine.Hit) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Group {
            if search.failed {
                StateView(art: "IllusEmpty", title: "Search hit a snag", message: "Something went wrong searching. Try again.") {
                    Button("Try again", action: search.retry).buttonStyle(.borderedProminent)
                }
            } else if let answer = search.answer, !(answer.hits.isEmpty && search.stale) {
                if answer.hits.isEmpty {
                    empty(answer)
                } else {
                    list(answer)
                }
            } else if search.slow {
                ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Color.clear
            }
        }
        .overlay(alignment: .top) {
            if search.slow, search.answer?.hits.isEmpty == false { ProgressView().padding(8) }
        }
    }

    @ViewBuilder private func empty(_ answer: SearchModel.Answer) -> some View {
        let q = search.query
        let scope = answer.key.scope
        if scope.needsPhotos && (photos == .denied || photos == .restricted) {
            StateView(art: "IllusWelcome", title: "Photos access is off", message: "Turn it on in Settings, and Retriever can search your \(scope.title.lowercased()) too.") {
                Button("Open Settings", action: model.openSettings).buttonStyle(.borderedProminent)
            }
        } else if scope.needsPhotos && photos == .limited {
            StateView(art: "IllusWelcome", title: "No matches in the photos you picked", message: "Retriever can only see the photos you chose. Pick more to search more of your library.") {
                Button("Choose more photos", action: model.chooseMorePhotos).buttonStyle(.borderedProminent)
            }
        } else if search.photo != nil && !answer.vectorLane {
            StateView(art: "IllusReading", title: "Not quite ready", message: "Search by photo works once setup finishes.")
        } else if scope != .all {
            StateView(art: "IllusEmpty", title: search.photo != nil ? "Nothing like this photo in \(scope.title)" : "No matches for “\(q)”",
                      message: search.photo != nil ? "Try all your files instead." : "Nothing in \(scope.title) matches. Try all your files.") {
                Button("Search all files") { search.scope = .all }.buttonStyle(.borderedProminent)
            }
        } else if search.photo != nil {
            StateView(art: "IllusEmpty", title: "Nothing like this yet", message: "Try another photo.")
        } else if let snap, snap.pending > 0 {
            StateView(art: "IllusReading", title: "No matches for “\(q)” yet",
                      message: "Retriever has read \(Copy.count(snap.indexed)) of \(Copy.count(snap.total)) files so far. Try again later, or try other words.") {
                ProgressView(value: snap.total > 0 ? Double(snap.indexed) / Double(snap.total) : 0).frame(width: 220)
            }
        } else {
            StateView(art: "IllusEmpty", title: "No matches for “\(q)”", message: "Try other or fewer words.")
        }
    }

    private func list(_ answer: SearchModel.Answer) -> some View {
        let media = answer.hits.filter(inGrid)
        let docs = answer.hits.filter { !inGrid($0) }
        let mediaFirst = inGrid(answer.hits[0])
        let query = answer.key.query.isEmpty ? nil : answer.key.query
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header(answer)
                if mediaFirst {
                    mediaSection(media)
                    docSection(docs, query: query)
                } else {
                    docSection(docs, query: query)
                    mediaSection(media)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.immediately)
        .opacity(search.stale ? 0.6 : 1)
    }

    @ViewBuilder private func header(_ answer: SearchModel.Answer) -> some View {
        if let photo = search.photo {
            HStack(spacing: 8) {
                Image(uiImage: photo.thumb).resizable().scaledToFill().frame(width: 28, height: 28).clipShape(Circle())
                    .accessibilityLabel("The photo you're searching with")
                Text("Like this photo").font(.subheadline.weight(.medium))
                Button { search.clearPhoto() } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    .accessibilityLabel("Stop searching by photo")
            }
            .padding(.leading, 4)
            .padding(.trailing, 10)
            .padding(.vertical, 4)
            .background(.fill.secondary, in: Capsule())
        }
        if let note = note(answer) {
            HStack(spacing: 10) {
                Image("IllusReading").resizable().scaledToFit().frame(width: 36).accessibilityHidden(true)
                Text(note).font(.footnote).foregroundStyle(.secondary)
            }
        }
        if answer.key.scope == .all && (photos == .denied || photos == .restricted) {
            Text("Photos access is off, so these results leave out your photos.").font(.footnote).foregroundStyle(.secondary)
        }
    }

    private func note(_ answer: SearchModel.Answer) -> String? {
        if snap?.setUp != true { return "Matching file names only until setup finishes." }
        if !answer.vectorLane { return "Matching exact words for now. Results by meaning will be back shortly." }
        return nil
    }

    @ViewBuilder private func mediaSection(_ hits: [SearchEngine.Hit]) -> some View {
        if !hits.isEmpty {
            SectionTitle("Photos and videos")
            LazyVGrid(columns: gridColumns(typeSize, spacing: 8), spacing: 8) {
                ForEach(hits, id: \.fileId) { hit in
                    ResultCell(file: hit.file, locator: hit.locator, onOpen: { open(hit) }, onFindSimilar: search.findSimilar)
                }
            }
        }
    }

    @ViewBuilder private func docSection(_ hits: [SearchEngine.Hit], query: String?) -> some View {
        if !hits.isEmpty {
            SectionTitle("Documents and more")
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(hits, id: \.fileId) { hit in
                    FileButton(file: hit.file, locator: hit.locator, snippet: hit.snippet, query: query, onOpen: { open(hit) }, onFindSimilar: search.findSimilar)
                    Divider()
                }
            }
        }
    }
}

/// Hides the keyboard; a presented search stays presented, with its scopes.
@MainActor func dismissKeyboard() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
}

struct SectionTitle: View {
    let title: String

    init(_ title: String) {
        self.title = title
    }

    var body: some View {
        Text(title).font(.headline).padding(.top, 8).accessibilityAddTraits(.isHeader)
    }
}

#Preview("Home") {
    NavigationStack { SearchView(model: AppModel(fixture: .caughtUp), search: .fixture(recent: ["electricity bill", "passport photo"])) }
}
