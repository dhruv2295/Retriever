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
    enum Scope: String, CaseIterable, Identifiable {
        case all, photos, videos, documents, starred

        var id: Self { self }

        var title: String {
            switch self {
            case .all: "All"
            case .photos: "Photos"
            case .videos: "Videos"
            case .documents: "Documents"
            case .starred: "Starred"
            }
        }

        /// Filtered inside the engine, so a narrow scope still finds files beyond the mixed top results.
        var kinds: Set<Kind>? {
            switch self {
            case .all, .starred: nil
            case .photos: [.photo, .screenshot]
            case .videos: [.video]
            case .documents: [.pdf, .doc]
            }
        }

        var needsPhotos: Bool { self == .photos || self == .videos }

        /// What a search in this scope keeps when the words' own filters are dropped, for the relaxed note; nil is everything.
        var kept: String? {
            switch self {
            case .all: nil
            case .starred: "starred files"
            default: title.lowercased()
            }
        }

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
        /// The words searched as typed, without reading kinds and dates out of them.
        var literal = false
        var retry = 0

        /// The same question as `other`; only a retry differs.
        func asks(_ other: Key) -> Bool {
            query == other.query && scope == other.scope && photo == other.photo && literal == other.literal
        }
    }

    struct Answer {
        let hits: [SearchEngine.Hit]
        /// The first this many hits are good matches; the rest are the closest of a weak lot.
        let strongCount: Int
        let vectorLane: Bool
        let key: Key
        /// Files read only in part, by file id; the rest were read whole.
        var extents: [Int64: ReadExtent] = [:]
        /// What the words were understood to ask for, and whether a filter left nothing so it was dropped.
        var parsed: ParsedQuery?
        var relaxed = false
    }

    private(set) var text = ""
    var scope = Scope.all
    private(set) var photo: Photo?
    private(set) var answer: Answer?
    private(set) var failed = false
    /// Searching for longer than 300 ms: only then is a spinner worth showing.
    private(set) var slow = false
    private(set) var recent: [String] = []
    private(set) var literal = false
    private var retries = 0
    @ObservationIgnored private var wordsOnlyRetries = 0
    @ObservationIgnored private let services: AppServices?

    init(services: AppServices?) {
        self.services = services
    }

    var query: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
    var active: Bool { !query.isEmpty || photo != nil }
    var key: Key { Key(query: photo == nil ? query : "", scope: scope, photo: photo?.id, literal: literal, retry: retries) }

    /// The results on screen answer older words, another scope or another photo.
    var stale: Bool { answer?.key != key }

    /// "Search my exact words": no kind or date is read out of them.
    func searchAsTyped() {
        literal = true
    }

    /// Typing ends a search by photo; clearing the words drops the scope with them.
    func setText(_ new: String) {
        let hadWords = !query.isEmpty
        if new != text {
            literal = false
            typed()
        }
        text = new
        wordsOnlyRetries = 0
        if !query.isEmpty {
            photo = nil
        } else if hadWords {
            scope = .all
        }
    }

    @ObservationIgnored private var typing: Task<Void, Never>?

    /// Reading steps aside for a moment after each keystroke, not for as long as the search stays open; a search
    /// running holds it off in core.
    private func typed() {
        guard let retriever = services?.retriever else { return }
        retriever.setUiBusy(busy: true)
        typing?.cancel()
        typing = Task {
            try? await Task.sleep(for: .milliseconds(1500))
            if !Task.isCancelled { retriever.setUiBusy(busy: false) }
        }
    }

    func search(_ key: Key) async {
        guard let retriever = services?.retriever else { return }
        guard key.photo != nil || !key.query.isEmpty else {
            answer = nil
            failed = false
            return
        }
        let start = ContinuousClock.now
        let spinner = Task {
            try await Task.sleep(for: .milliseconds(300))
            slow = true
        }
        defer {
            spinner.cancel()
            slow = false
        }
        do {
            // Phase one: names, from memory, with no model. Nothing found yet isn't "no matches", so it isn't shown.
            // A retry of the same question keeps the full answer on screen instead.
            if key.photo == nil, answer?.key.asks(key) != true {
                let names = try await retriever.searchNames(q: key.query, kinds: key.scope.kinds, limit: 60, onlyStarred: key.scope == .starred, literal: key.literal)
                guard !Task.isCancelled else { return }
                if !names.hits.isEmpty {
                    answer = Answer(hits: names.hits, strongCount: names.hits.count, vectorLane: names.vectorLane, key: key, parsed: names.parsed, relaxed: names.relaxed)
                    failed = false
                }
                // Phase two waits for the words to hold still, so typing never starts a full search.
                try? await Task.sleep(until: start + .milliseconds(200))
                guard !Task.isCancelled else { return }
            }
            let result = if let photo, photo.id == key.photo {
                try await retriever.searchImageData(jpeg: photo.jpeg, kinds: key.scope.kinds, limit: 60, onlyStarred: key.scope == .starred)
            } else {
                try await retriever.search(q: key.query, kinds: key.scope.kinds, limit: 60, onlyStarred: key.scope == .starred, literal: key.literal)
            }
            // Kotlin doesn't see Swift's cancellation: drop an answer to a search that was replaced.
            guard !Task.isCancelled else { return }
            let hits = result.hits
            withAnimation(.easeOut(duration: 0.15)) {
                answer = Answer(hits: hits, strongCount: Int(result.strongCount), vectorLane: result.vectorLane, key: key,
                                parsed: result.parsed, relaxed: result.relaxed)
            }
            failed = false
            if let found = try? await retriever.readExtents(fileIds: hits.map { KotlinLong(value: $0.fileId) }), !Task.isCancelled, answer?.key == key {
                answer?.extents = Dictionary(uniqueKeysWithValues: found.map { ($0.key.int64Value, $0.value) })
            }
            // Words only while the model was busy opening: ask again shortly, a few times, for results by meaning.
            // Not when it was removed in Settings: asking again can't bring it back.
            if result.vectorLane || retriever.textOnly || (retriever.modelMissing.value as? KotlinBoolean)?.boolValue == true {
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

    /// "Find similar", or nil on a words-only phone, where there is no model to match a photo with.
    func similar(_ snap: AppModel.Snap?) -> ((SourceFile) -> Void)? {
        if snap?.textOnly == true { return nil }
        return { [weak self] file in self?.findSimilar(file) }
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
    static func fixture(text: String = "", scope: Scope = .all, hits: [SearchEngine.Hit]? = nil, strongCount: Int? = nil, extents: [Int64: ReadExtent] = [:],
                        vectorLane: Bool = true, failed: Bool = false, slow: Bool = false, photo: UIImage? = nil, recent: [String] = []) -> SearchModel {
        let m = SearchModel(services: nil)
        m.text = text
        m.scope = scope
        if let photo, let jpeg = photo.jpegData(compressionQuality: 0.8) { m.photo = Photo(jpeg: jpeg, thumb: photo) }
        if let hits { m.answer = Answer(hits: hits, strongCount: strongCount ?? hits.count, vectorLane: vectorLane, key: m.key, extents: extents) }
        m.failed = failed
        m.slow = slow
        m.recent = recent
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
    @Environment(\.horizontalSizeClass) private var sizeClass
    private let fixedSnap: AppModel.Snap?

    /// `search` and `snap` pin a state for screenshots; by default the search runs live.
    init(model: AppModel, search: SearchModel? = nil, snap: AppModel.Snap? = nil, presented: Bool = false) {
        self.model = model
        _search = State(initialValue: search ?? SearchModel(services: model.services))
        _presented = State(initialValue: presented)
        fixedSnap = snap
        Stars.model = model
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
        .modifier(WarmOnSearch(retriever: model.services?.retriever))
        .navigationTitle("Retriever")
        .searchable(text: Binding(get: { search.text }, set: search.setText), isPresented: $presented,
                    placement: .navigationBarDrawer(displayMode: .always), prompt: "Search photos, screenshots and files")
        .searchScopes($search.scope, activation: .onSearchPresentation) {
            // Five titles must fit the bar on an iPhone, so Documents is "Docs" there.
            ForEach(SearchModel.Scope.allCases) { Text($0 == .documents ? "Docs" : $0.title).tag($0) }
        }
        .searchSuggestions { suggestions }
        .onSubmit(of: .search) { search.record() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // Matching a photo needs the understanding that text-only leaves out.
                if snap?.textOnly != true {
                    PhotosPicker(selection: $picked, matching: .images) {
                        Label("Search by photo", systemImage: "photo.badge.magnifyingglass")
                    }
                }
            }
            // At regular width the sidebar lists Settings.
            if sizeClass != .regular {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(value: Route.settings) { Label("Settings", systemImage: "gearshape") }
                }
            }
        }
        .task(id: search.key) { [key = search.key] in await search.search(key) }
        // The newest files change as reading goes on; the facts after "all caught up" differ too.
        .task(id: [snap?.total ?? -1, snap?.pending == 0 ? 1 : 0]) { await model.refreshHome() }
        .task { await search.loadRecent() }
        // A search handed in by Shortcuts or Siri, whether the app was already open or not.
        .onChange(of: LaunchQuery.shared.pending, initial: true) { _, pending in
            guard let pending, !pending.isEmpty else { return }
            LaunchQuery.shared.pending = nil
            search.scope = .all
            search.setText(pending)
            presented = true
            search.record()
        }
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
                if !model.suggestions.isEmpty {
                    Section("Try searching") {
                        ForEach(model.suggestions, id: \.self) { q in
                            Label(q, systemImage: "magnifyingglass").searchCompletion(q)
                        }
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

/// When the search opens, the model loads ahead of the first word, so the search is quick.
private struct WarmOnSearch: ViewModifier {
    let retriever: Retriever?
    @Environment(\.isSearching) private var searching

    func body(content: Content) -> some View {
        content.onChange(of: searching) { _, on in
            if on { Task { try? await retriever?.warmSearch() } }
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
                    // What the words were understood as stays in view, with its way back to the words as typed.
                    VStack(alignment: .leading, spacing: 0) {
                        header(answer).padding(.horizontal, 16).padding(.top, 12)
                        empty(answer)
                    }
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
        } else if let parsed = answer.parsed, !parsed.understood.isEmpty, parsed.text.trimmingCharacters(in: .whitespaces).isEmpty {
            // Only filters, and even without them nothing in this scope: say what was looked for.
            StateView(art: "IllusEmpty", title: parsed.missing(), message: scope == .all ? "Nothing like that on this \(Copy.device) yet." : "Nothing in \(scope.title) fits. Try all your files.") {
                if scope != .all { Button("Search all files") { search.scope = .all }.buttonStyle(.borderedProminent) }
            }
        } else if scope == .starred && search.photo == nil {
            StateView(art: "IllusEmpty", title: "No starred file matches “\(q)”", message: "Only files you've starred show here. Try all your files.") {
                Button("Search all files") { search.scope = .all }.buttonStyle(.borderedProminent)
            }
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
        // The words left once kinds and dates were read out of them; only a hit found by its name shows them in bold.
        let words = answer.parsed?.text ?? answer.key.query
        let query = words.isEmpty ? nil : words
        // A search by photo ranks by likeness alone, so all of it is one list.
        let strong = search.photo == nil ? min(answer.strongCount, answer.hits.count) : answer.hits.count
        let weak = Array(answer.hits.dropFirst(strong))
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header(answer)
                if strong == 0 {
                    // The words these results answer, not the field's, which may already be ahead of them.
                    VStack(alignment: .leading, spacing: 4) {
                        Text(Copy.nothingWell(answer.key.query)).font(typeSize.isAccessibilitySize ? .headline : .title3.bold())
                        Text(typeSize.isAccessibilitySize ? Copy.closestFilesShort : Copy.closestFiles).font(.subheadline).foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                }
                sections(Array(answer.hits.prefix(strong)), answer: answer, query: query)
                if strong > 0 && !weak.isEmpty {
                    SectionTitle(Copy.lessLikely)
                    sections(weak, answer: answer, query: query, titled: false)
                } else if strong == 0 {
                    sections(weak, answer: answer, query: query)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
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
        if let parsed = answer.parsed, !parsed.understood.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text(answer.relaxed ? parsed.relaxedNote(scope: answer.key.scope.kept) : Copy.understood(parsed.understood))
                    .font(.footnote).foregroundStyle(.secondary)
                Button(Copy.exactWords, action: search.searchAsTyped).font(.footnote)
            }
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
        // Removed in Settings: meaning comes back only with the download, not "shortly".
        if snap?.textOnly == true || model.modelMissing { return Copy.textOnlySearch }
        if snap?.setUp != true { return "Matching file names only until setup finishes." }
        if !answer.vectorLane { return "Matching exact words for now. Results by meaning will be back shortly." }
        return nil
    }

    /// Pictures and documents in rank order as far as a grid allows: the documents ranked above the best picture, then
    /// the pictures, then the other documents, so neither one strong document nor one strong photo buries the rest.
    /// `titled: false` under a heading that already says what they are.
    @ViewBuilder private func sections(_ hits: [SearchEngine.Hit], answer: SearchModel.Answer, query: String?, titled: Bool = true) -> some View {
        let lead = Array(hits.prefix { !inGrid($0) })
        let after = hits.dropFirst(lead.count)
        docSection(lead, answer: answer, query: query, titled: titled)
        mediaSection(after.filter(inGrid), answer: answer, titled: titled)
        docSection(after.filter { !inGrid($0) }, answer: answer, query: query, titled: titled,
                   title: lead.isEmpty ? "Documents and more" : "More documents")
    }

    @ViewBuilder private func mediaSection(_ hits: [SearchEngine.Hit], answer: SearchModel.Answer, titled: Bool) -> some View {
        if !hits.isEmpty {
            if titled { SectionTitle("Photos and videos") }
            LazyVGrid(columns: gridColumns(typeSize, spacing: 8), spacing: 8) {
                ForEach(hits, id: \.fileId) { hit in
                    ResultCell(file: hit.file, locator: hit.locator, onOpen: { open(hit) }, extent: answer.extents[hit.fileId], onFindSimilar: search.similar(snap))
                }
            }
        }
    }

    @ViewBuilder private func docSection(_ hits: [SearchEngine.Hit], answer: SearchModel.Answer, query: String?, titled: Bool,
                                         title: String = "Documents and more") -> some View {
        if !hits.isEmpty {
            if titled { SectionTitle(title) }
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(hits, id: \.fileId) { hit in
                    FileButton(file: hit.file, locator: hit.locator, snippet: hit.snippet, query: hit.byName ? query : nil, extent: answer.extents[hit.fileId],
                               onOpen: { open(hit) }, onFindSimilar: search.similar(snap))
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
