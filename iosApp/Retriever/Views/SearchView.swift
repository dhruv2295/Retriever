// Ported from Retriever's Android SearchScreen.kt and SearchViewModel.kt (same project): debounced search as you
// type, kind filters run inside the engine, search by photo, recent searches, and the empty, words-only and error
// states. Native chrome follows Apple's sample "Adding a search interface to your app" (Apple sample code licence):
// searchable with scopes and suggestions in a NavigationStack. Modified for Retriever: scopes pass kinds to the
// engine, results mix a photo grid with document rows, and every state has an illustration.

import ImageIO
import Photos
import PhotosUI
@preconcurrency import RetrieverCore
import SwiftUI
import os

// MARK: Screen

/// First run's try-it: this same screen with every search kept to the files the short read planned.
struct Trial: Equatable {
    var ids: [Int64]
    /// The field's photo button, once enough photos are read for a likeness to mean something.
    var photoSearch: Bool
}

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
    private let trial: Trial?

    /// `search` and `snap` pin a state for screenshots; by default the search runs live.
    init(model: AppModel, search: SearchModel? = nil, snap: AppModel.Snap? = nil, presented: Bool = false, trial: Trial? = nil) {
        self.model = model
        let search = search ?? SearchModel(services: model.services)
        if search.fileIds != trial?.ids { search.fileIds = trial?.ids }
        _search = State(initialValue: search)
        _presented = State(initialValue: presented)
        fixedSnap = snap
        self.trial = trial
    }

    /// Read here and not `model.snap`, so the counts that change as files are read don't redraw this screen.
    private var textOnly: Bool { fixedSnap?.textOnly ?? model.textOnly }
    private var setUp: Bool { fixedSnap.map { $0.setUp == true } ?? model.setUp }

    var body: some View {
        Group {
            if search.active {
                ResultsView(search: search, textOnly: textOnly, setUp: setUp, pinned: fixedSnap, photos: model.photos, model: model, trial: trial, open: open)
            } else if trial != nil {
                TryItIntro(search: search, textOnly: textOnly)
            } else {
                LiveHome(
                    search: search, pinned: fixedSnap, model: model,
                    useRecent: { q in
                        search.setText(q)
                        presented = true
                        search.record()
                    },
                    addFolder: { addingFolder = true })
            }
        }
        .modifier(WarmOnSearch(retriever: model.services?.retriever))
        .navigationTitle(trial == nil ? "Retriever" : Copy.tryItTitle)
        .searchable(
            text: Binding(get: { search.text }, set: search.setText), isPresented: $presented,
            placement: .navigationBarDrawer(displayMode: .always), prompt: "Search your files"
        )
        .searchScopes($search.scope, activation: .onSearchPresentation) {
            // Five titles must fit the bar on an iPhone, so Documents is "Docs" there.
            ForEach(SearchModel.Scope.allCases) { Text($0 == .documents ? "Docs" : $0.title).tag($0) }
        }
        // No recents or examples on the first run: the intro says what to try.
        .searchSuggestions { if trial == nil { suggestions } }
        .onSubmit(of: .search) { search.record() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // Matching a photo needs the understanding that text-only leaves out.
                if !textOnly, trial?.photoSearch ?? true {
                    PhotosPicker(selection: $picked, matching: .images) {
                        Label("Search by photo", systemImage: "photo.badge.magnifyingglass")
                    }
                }
            }
            // At regular width the sidebar lists Settings.
            if sizeClass != .regular, trial == nil {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(value: Route.settings) { Label("Settings", systemImage: "gearshape") }
                }
            }
        }
        .task(id: search.key) { [key = search.key] in await search.search(key) }
        .modifier(RefreshHome(model: model, pinned: fixedSnap))
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
            if case .success(let url) = result, let added = model.addFolders([url]).first {
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
        openFile(
            hit.file, locator: hit.locator, words: highlightWords(snippet: hit.snippet, query: search.photo == nil ? search.query : nil),
            among: search.answer?.hits.map(\.file) ?? [])
    }
}

/// Home under the search field. It alone reads the counts, so they redraw it and not the search field or the results.
private struct LiveHome: View {
    let search: SearchModel
    let pinned: AppModel.Snap?
    let model: AppModel
    let useRecent: (String) -> Void
    let addFolder: () -> Void

    var body: some View {
        HomeList(
            search: search, snap: pinned ?? model.snap, photos: model.photos, model: model, useRecent: useRecent, addFolder: addFolder,
            liveProgress: pinned == nil)
    }
}

/// The newest files change as reading goes on; the facts after "all caught up" differ too. Own scope, so the counts
/// that key it redraw this and not the screen.
private struct RefreshHome: ViewModifier {
    let model: AppModel
    let pinned: AppModel.Snap?

    func body(content: Content) -> some View {
        let snap = pinned ?? model.snap
        content.task(id: [snap?.total ?? -1, snap?.pending == 0 ? 1 : 0]) { await model.refreshHome() }
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
    let textOnly: Bool
    let setUp: Bool
    /// A screenshot's fixed snapshot; nil when live.
    let pinned: AppModel.Snap?
    let photos: PHAuthorizationStatus
    let model: AppModel
    let trial: Trial?
    let open: (SearchEngine.Hit) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let screen = search.screen
        Group {
            switch screen {
            case .failed:
                StateView(mood: .trouble, title: "Search hit a snag", message: "Something went wrong searching. Try again.") {
                    Button("Try again", action: search.retry).buttonStyle(.borderedProminent)
                }
            case .empty:
                if let answer = search.answer {
                    // What the words were understood as stays in view, with its way back to the words as typed.
                    VStack(alignment: .leading, spacing: 0) {
                        header(answer).padding(.horizontal, 16).padding(.top, 12)
                        empty(answer)
                    }
                }
            case .results, .searching:
                // One branch for both, so the list keeps its scroll when the new answer replaces the dimmed old one.
                if let answer = search.answer, !answer.hits.isEmpty {
                    list(answer).opacity(screen == .results ? 1 : 0.6)
                } else if search.pondering {
                    VStack(spacing: 8) {
                        MascotView(mood: .searching).frame(maxWidth: 160)
                        Text("Looking…").font(.subheadline).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityElement(children: .combine)
                }
            case .idle:
                EmptyView()
            }
        }
        .animation(.easeOut(duration: 0.15), value: screen)
        .overlay(alignment: .top) {
            if search.slow { ProgressView().progressViewStyle(.linear) }
        }
    }

    @ViewBuilder private func empty(_ answer: SearchModel.Answer) -> some View {
        let q = search.query
        let scope = answer.key.scope
        if scope.needsPhotos && (photos == .denied || photos == .restricted) {
            StateView(
                art: "IllusWelcome", title: "Photos access is off",
                message: "Turn it on in Settings, and Retriever can search your \(scope.title.lowercased()) too."
            ) {
                Button("Open Settings", action: model.openSettings).buttonStyle(.borderedProminent)
            }
        } else if scope.needsPhotos && photos == .limited {
            StateView(
                mood: .noMatch, title: "No matches in the photos you picked",
                message: "Retriever can only see the photos you chose. Pick more to search more of your library."
            ) {
                Button("Choose more photos", action: model.chooseMorePhotos).buttonStyle(.borderedProminent)
            }
        } else if search.photo != nil && !answer.vectorLane {
            StateView(mood: .walking, title: "Not quite ready", message: "Search by photo works once setup finishes.")
        } else if let parsed = answer.parsed, !parsed.understood.isEmpty, parsed.text.trimmingCharacters(in: .whitespaces).isEmpty {
            // Only filters, and even without them nothing in this scope: say what was looked for.
            StateView(
                mood: search.noMatchMood, title: parsed.missing(),
                message: scope == .all ? "Nothing like that on this \(Copy.device) yet." : "Nothing in \(scope.title) fits. Try all your files."
            ) {
                if scope != .all { Button("Search all files") { search.scope = .all }.buttonStyle(.borderedProminent) }
            }
        } else if scope == .starred && search.photo == nil {
            StateView(mood: search.noMatchMood, title: "No starred file matches “\(q)”", message: "Only files you've starred show here. Try all your files.") {
                Button("Search all files") { search.scope = .all }.buttonStyle(.borderedProminent)
            }
        } else if scope != .all {
            StateView(
                mood: search.noMatchMood, title: search.photo != nil ? "Nothing like this photo in \(scope.title)" : "No matches for “\(q)”",
                message: search.photo != nil ? "Try all your files instead." : "Nothing in \(scope.title) matches. Try all your files."
            ) {
                Button("Search all files") { search.scope = .all }.buttonStyle(.borderedProminent)
            }
        } else if search.photo != nil {
            StateView(mood: search.noMatchMood, title: "Nothing like this yet", message: "Try another photo.")
        } else {
            NoMatches(query: q, mood: search.noMatchMood, pinned: pinned, model: model)
        }
    }

    private func list(_ answer: SearchModel.Answer) -> some View {
        // One read of the stars and of "find similar" for every row.
        let rows = Rows(starred: model.starred, similar: search.similar(textOnly: textOnly))
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
                sections(Array(answer.hits.prefix(strong)), answer: answer, rows: rows)
                if strong > 0 && !weak.isEmpty {
                    SectionTitle(Copy.lessLikely)
                    sections(weak, answer: answer, rows: rows, titled: false)
                } else if strong == 0 {
                    sections(weak, answer: answer, rows: rows)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.immediately)
    }

    @ViewBuilder private func header(_ answer: SearchModel.Answer) -> some View {
        if let trial {
            // Nearest-neighbour search always returns something, so the header claims nothing about quality.
            VStack(alignment: .leading, spacing: 2) {
                Text(Copy.closestIn(trial.ids.count)).font(.subheadline.weight(.semibold))
                StillReading(search: search)
            }
            .accessibilityElement(children: .combine)
        }
        if let photo = search.photo {
            HStack(spacing: 8) {
                Image(uiImage: photo.thumb).resizable().scaledToFill().frame(width: 28, height: 28).clipShape(Circle())
                    .accessibilityLabel("The photo you're searching with")
                Text("Like this photo").font(.subheadline.weight(.medium))
                Button {
                    search.clearPhoto()
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                        .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
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
                Button(action: search.searchAsTyped) {
                    Text(Copy.exactWords).font(.footnote).frame(minHeight: 44).contentShape(Rectangle())
                }
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
        if textOnly || model.modelMissing { return Copy.textOnlySearch }
        if !setUp { return "Matching file names only until setup finishes." }
        if !answer.vectorLane { return "Matching exact words for now. Results by meaning will be back shortly." }
        return nil
    }

    /// Pictures and documents in rank order as far as a grid allows: the documents ranked above the best picture, then
    /// the pictures, then the other documents, so neither one strong document nor one strong photo buries the rest.
    /// `titled: false` under a heading that already says what they are.
    /// Recordings go last, under their own heading, as on Android.
    @ViewBuilder private func sections(_ hits: [SearchEngine.Hit], answer: SearchModel.Answer, rows: Rows, titled: Bool = true) -> some View {
        let others = hits.filter { $0.file.kind != .audio }
        let lead = Array(others.prefix { !inGrid($0) })
        let after = others.dropFirst(lead.count)
        docSection(lead, answer: answer, rows: rows, titled: titled)
        mediaSection(after.filter(inGrid), answer: answer, rows: rows, titled: titled)
        docSection(
            after.filter { !inGrid($0) }, answer: answer, rows: rows, titled: titled,
            title: lead.isEmpty ? "Documents and more" : "More documents")
        docSection(hits.filter { $0.file.kind == .audio }, answer: answer, rows: rows, titled: titled, title: "Recordings")
    }

    @ViewBuilder private func mediaSection(_ hits: [SearchEngine.Hit], answer: SearchModel.Answer, rows: Rows, titled: Bool) -> some View {
        if !hits.isEmpty {
            if titled { SectionTitle("Photos and videos") }
            LazyVGrid(columns: gridColumns(typeSize, spacing: 8), spacing: 8) {
                ForEach(hits, id: \.fileId) { hit in
                    ResultCell(
                        file: hit.file, locator: hit.locator, starred: rows.starred.contains(hit.file.uri), onOpen: { open(hit) },
                        extent: answer.extents[hit.fileId], onFindSimilar: rows.similar)
                }
            }
        }
    }

    @ViewBuilder private func docSection(
        _ hits: [SearchEngine.Hit], answer: SearchModel.Answer, rows: Rows, titled: Bool,
        title: String = "Documents and more"
    ) -> some View {
        if !hits.isEmpty {
            if titled { SectionTitle(title) }
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(hits, id: \.fileId) { hit in
                    FileButton(
                        file: hit.file, locator: hit.locator, snippet: hit.snippet, styledName: answer.names[hit.fileId], extent: answer.extents[hit.fileId],
                        starred: rows.starred.contains(hit.file.uri), onOpen: { open(hit) }, onFindSimilar: rows.similar)
                    Divider()
                }
            }
        }
    }

    /// What every row of one pass shares, read once.
    private struct Rows {
        let starred: Set<String>
        let similar: ((SourceFile) -> Void)?
    }
}

/// Under the field before the first word of the first run's try-it: what to try. The title is the navigation bar's.
private struct TryItIntro: View {
    let search: SearchModel
    let textOnly: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(textOnly ? Copy.tryItWordsTextOnly : Copy.tryItWords).font(.body).foregroundStyle(.secondary)
            StillReading(search: search)
            Spacer()
        }
        .padding(.horizontal, 24)
        .padding(.top, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// "Still reading 38 more" while the short read goes on. Own view, so each file read redraws this line alone.
private struct StillReading: View {
    let search: SearchModel

    var body: some View {
        if search.stillReading > 0 {
            Text(Copy.stillReading(search.stillReading)).font(.footnote).foregroundStyle(.secondary).monospacedDigit()
        }
    }
}

/// "Nothing yet" for a search that found nothing: while files are still being read it says how far, so it is not
/// taken for "nothing here". Own view, so the counts redraw it and not the results.
private struct NoMatches: View {
    let query: String
    let mood: MascotMood
    let pinned: AppModel.Snap?
    let model: AppModel

    var body: some View {
        if let snap = pinned ?? model.snap, snap.pending > 0 {
            StateView(
                mood: .walking, title: "No matches for “\(query)” yet",
                message: "Retriever has read \(Copy.count(snap.indexed)) of \(Copy.count(snap.total)) files so far. Try again later, or try other words."
            ) {
                ProgressView(value: snap.total > 0 ? Double(snap.indexed) / Double(snap.total) : 0).frame(width: 220)
                    .accessibilityHidden(true)  // The message already gives the same counts.
            }
        } else {
            StateView(mood: mood, title: "No matches for “\(query)”", message: "Try other or fewer words.")
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
    let model = AppModel(fixture: .caughtUp)
    NavigationStack { SearchView(model: model, search: .fixture(recent: ["electricity bill", "passport photo"])) }
        .environment(model)
}
