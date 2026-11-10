// Ported from Retriever's Android SearchScreen.kt and SearchViewModel.kt (same project): debounced search as you
// type, kind filters run inside the engine, search by photo, recent searches, and the empty, words-only and error
// states. Native chrome follows Apple's sample "Adding a search interface to your app" (Apple sample code licence):
// searchable with scopes and suggestions in a NavigationStack. Modified for Retriever: scopes pass kinds to the
// engine, results mix a photo grid with document rows, and every state has an illustration.

import Photos
import PhotosUI
@preconcurrency import RetrieverCore
import SwiftUI

// MARK: Screen

/// First run's first search: this same screen, words inside the recent files and names everywhere.
struct Trial: Equatable {
    var ids: [Int64]
    /// The field's photo button: search by photo needs the meaning upgrade.
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
    @Environment(\.searchRequest) private var searchRequest
    @FocusState private var fieldFocused: Bool
    /// Not covered by a pushed page: a field raised while covered is dropped, so ⌘F waits for Home to show.
    @State private var visible = false
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

    /// The system's bottom search field on iOS 26, within reach of a thumb; the bar's drawer before it.
    private static var placement: SearchFieldPlacement {
        if #available(iOS 26.0, *) { .automatic } else { .navigationBarDrawer(displayMode: .always) }
    }

    var body: some View {
        Group {
            if search.active {
                ResultsView(search: search, textOnly: textOnly, pinned: fixedSnap, photos: model.photos, model: model, trial: trial, open: open)
            } else if trial != nil {
                DemoIntro(model: model, search: search)
            } else {
                LiveHome(pinned: fixedSnap, model: model, addFolder: { addingFolder = true })
            }
        }
        .modifier(WarmOnSearch(retriever: model.services?.retriever))
        // The demo's title is its content's first line, so its bar says nothing.
        .navigationTitle(trial == nil ? "Retriever" : "")
        .navigationBarTitleDisplayMode(trial == nil ? .automatic : .inline)
        .searchable(
            text: Binding(get: { search.text }, set: search.setText), isPresented: $presented, placement: Self.placement,
            prompt: trial == nil ? Words.shared.SEARCH_FIELD : Words.shared.firstSearchHint(state: model.descriptionState)
        )
        .searchScopes($search.scope, activation: .onSearchPresentation) {
            // Five titles must fit the bar on an iPhone, so Documents is "Docs" there.
            ForEach(SearchModel.Scope.allCases) { Text($0 == .documents ? Words.shared.strings.docsShort : $0.title).tag($0) }
        }
        // No recents or examples on the first run: the demo offers its own words.
        .searchSuggestions { if trial == nil { suggestions } }
        .onSubmit(of: .search) { search.record() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // Matching a photo needs the meaning upgrade.
                if trial?.photoSearch ?? model.meaningSearch {
                    PhotosPicker(selection: $picked, matching: .images) {
                        Label(Words.shared.strings.searchByPhoto, systemImage: "photo.badge.magnifyingglass")
                    }
                }
            }
            // At regular width the sidebar lists Settings.
            if sizeClass != .regular, trial == nil {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(value: Route.settings) { Label(Words.shared.strings.appSettings, systemImage: "gearshape") }
                }
            }
        }
        .onChange(of: model.fileRevision) { search.retry() }
        .modifier(FocusSearchField(focused: $fieldFocused))
        .onChange(of: searchRequest.wrappedValue) { takeSearchRequest() }
        .onAppear {
            visible = true
            takeSearchRequest()
        }
        .onDisappear { visible = false }
        .task(id: search.key) { [key = search.key] in await search.search(key) }
        .modifier(RefreshHome(model: model, home: trial == nil))
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
                    Section(Words.shared.TRY_LABEL) {
                        ForEach(model.suggestions, id: \.self) { q in
                            Label(q, systemImage: "magnifyingglass").searchCompletion(q)
                        }
                    }
                }
            } else {
                Section(Words.shared.strings.recentSearches) {
                    ForEach(search.recent, id: \.self) { q in
                        Label(q, systemImage: "clock.arrow.circlepath")
                            .searchCompletion(q)
                            .swipeActions { Button(Words.shared.strings.removeSearch, role: .destructive) { search.removeRecent(q) } }
                            .contextMenu { Button(Words.shared.strings.removeFromRecentSearches, systemImage: "trash", role: .destructive) { search.removeRecent(q) } }
                    }
                    Button(Words.shared.strings.clearRecentSearches, role: .destructive) { search.clearRecent() }
                }
            }
        }
    }

    /// isPresented alone leaves an already presented field unfocused; iOS 18 can focus it.
    private func takeSearchRequest() {
        guard visible, searchRequest.wrappedValue else { return }
        searchRequest.wrappedValue = false
        presented = true
        // Focus asked for while Home is still appearing is dropped, and the state can still read true from the last ⌘F
        // after a pushed page took the focus.
        fieldFocused = false
        Task { fieldFocused = true }
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
    let pinned: AppModel.Snap?
    let model: AppModel
    let addFolder: () -> Void

    var body: some View {
        HomeList(snap: pinned ?? model.snap, photos: model.photos, model: model, addFolder: addFolder, liveProgress: pinned == nil)
    }
}

/// Home's facts and suggestions from core while Home shows in the foreground; core reads them again as files come, go
/// and are read. SwiftUI calls no `onDisappear` when the app leaves the foreground, so the scene's phase closes them too.
private struct RefreshHome: ViewModifier {
    let model: AppModel
    /// The demo is this screen too, but shows none of Home's facts.
    let home: Bool
    @Environment(\.scenePhase) private var phase
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .onAppear {
                shown = true
                sync()
            }
            .onDisappear {
                shown = false
                sync()
            }
            .onChange(of: phase) { sync() }
    }

    private func sync() {
        guard home else { return }
        if shown && phase == .active { model.watchHome() } else { model.unwatchHome() }
    }
}

private struct FocusSearchField: ViewModifier {
    let focused: FocusState<Bool>.Binding

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) { content.searchFocused(focused) } else { content }
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
    /// A screenshot's fixed snapshot; nil when live.
    let pinned: AppModel.Snap?
    let photos: PHAuthorizationStatus
    let model: AppModel
    let trial: Trial?
    let open: (SearchEngine.Hit) -> Void
    @State private var expanded = false
    @State private var upgrading = false
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let screen = search.screen
        Group {
            switch screen {
            case .failed:
                // Name matches already found for these words stay, with the way to try the rest again.
                if let answer = search.answer, answer.key.query == search.query, !answer.hits.isEmpty {
                    list(answer)
                } else {
                    StateView(mood: .trouble, title: Words.shared.SEARCH_FAILED, message: Words.shared.strings.somethingWentWrongSearching) {
                        Button(Words.shared.TRY_AGAIN, action: search.retry).buttonStyle(.primaryAction)
                    }
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
                } else if let answer = search.answer, answer.key.question != search.question {
                    // Earlier words' "Nothing found" stays, dimmed, while these are searched: typing never blinks it.
                    empty(answer).opacity(0.6)
                } else if search.pondering {
                    VStack(spacing: 8) {
                        MascotView(mood: .searching).frame(maxWidth: 160)
                        Text(Words.shared.strings.lookingEllipsis).font(.subheadline).foregroundStyle(.secondary)
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
        .sheet(isPresented: $upgrading) {
            UpgradeCard(model: model).padding(24).presentationDetents([.medium])
        }
    }

    @ViewBuilder private func empty(_ answer: SearchModel.Answer) -> some View {
        // The answer's own words: an earlier answer stays up while newer ones are searched.
        let q = answer.key.query
        let scope = answer.key.scope
        if scope.needsPhotos && (photos == .denied || photos == .restricted) {
            StateView(
                art: "IllusWelcome", title: Words.shared.strings.photosAccessOff,
                message: Words.shared.strings.turnOnToSearchScope(scope: scope.kept ?? scope.title)
            ) {
                Button(Words.shared.strings.openSettings, action: model.openSettings).buttonStyle(.primaryAction)
            }
        } else if scope.needsPhotos && photos == .limited {
            StateView(
                mood: .noMatch, title: Words.shared.strings.noMatchesInPickedPhotos,
                message: Words.shared.strings.onlySeesChosenPhotos
            ) {
                Button(Words.shared.strings.chooseMorePhotos, action: model.chooseMorePhotos).buttonStyle(.primaryAction)
            }
        } else if search.photo != nil && !answer.vectorLane {
            StateView(mood: .walking, title: Words.shared.strings.notQuiteReady, message: Words.shared.strings.photoSearchAfterSetup)
        } else if let parsed = answer.parsed, !parsed.understood.isEmpty, parsed.text.trimmingCharacters(in: .whitespaces).isEmpty {
            // Only filters, and even without them nothing in this scope: say what was looked for.
            StateView(
                mood: search.noMatchMood, title: parsed.missing(),
                message: scope == .all ? Words.shared.strings.nothingLikeThatOn(words: Copy.words) : Words.shared.strings.nothingInScopeFits(scope: scope.title)
            ) {
                if scope != .all { Button(Words.shared.strings.searchAllFiles) { search.scope = .all }.buttonStyle(.primaryAction) }
            }
        } else if scope == .starred && search.photo == nil {
            StateView(mood: search.noMatchMood, title: Words.shared.strings.noStarredMatch(query: q), message: Words.shared.strings.onlyStarredShowHere) {
                Button(Words.shared.strings.searchAllFiles) { search.scope = .all }.buttonStyle(.primaryAction)
            }
        } else if scope != .all {
            StateView(
                mood: search.noMatchMood, title: search.photo != nil ? Words.shared.strings.nothingLikePhotoIn(scope: scope.title) : Words.shared.strings.noMatchesFor(query: q),
                message: search.photo != nil ? Words.shared.strings.tryAllFilesInstead : Words.shared.strings.nothingInScopeMatches(scope: scope.title)
            ) {
                Button(Words.shared.strings.searchAllFiles) { search.scope = .all }.buttonStyle(.primaryAction)
            }
        } else if search.photo != nil {
            StateView(mood: search.noMatchMood, title: Words.shared.strings.nothingLikeThisYet, message: Words.shared.strings.tryAnotherPhoto)
        } else {
            NoMatches(mood: search.noMatchMood, pinned: pinned, model: model)
        }
    }

    private func list(_ answer: SearchModel.Answer) -> some View {
        // One read of the stars and of "find similar" for every row.
        let rows = Rows(starred: model.starred, similar: search.similar(model.meaningSearch))
        // A search by photo ranks by likeness alone, so all of it is one list.
        let strong = min(max(answer.strongCount, 1), answer.hits.count)
        let weak = Array(answer.hits.dropFirst(strong))
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header(answer)
                sections(Array(answer.hits.prefix(strong)), answer: answer, rows: rows)
                if !weak.isEmpty {
                    DisclosureGroup(Words.shared.strings.lessLikelyMatches(n: Int64(weak.count)), isExpanded: $expanded) {
                        sections(weak, answer: answer, rows: rows, titled: false)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.immediately)
        .onChange(of: answer.key) { expanded = false }
    }

    @ViewBuilder private func header(_ answer: SearchModel.Answer) -> some View {
        if trial != nil {
            // Names come from the whole phone; this says where the words inside are looked for.
            let taste = model.taste
            Text(Words.shared.demoSearchScope(done: Int64(taste.done), finished: taste.planned > 0 && taste.pending == 0, words: Copy.words))
                .font(.subheadline).fixedSize(horizontal: false, vertical: true)
        }
        switch search.screen {
        case .searching: Text(Words.shared.SEARCHING_CONTENTS).font(.footnote).foregroundStyle(.secondary)
        case .failed:
            HStack {
                Text(Words.shared.SHOWING_NAME_MATCHES).font(.footnote).foregroundStyle(.secondary)
                Button(Words.shared.TRY_AGAIN, action: search.retry).font(.footnote)
            }
        default: EmptyView()
        }
        if let photo = search.photo {
            HStack(spacing: 8) {
                Image(uiImage: photo.thumb).resizable().scaledToFill().frame(width: 28, height: 28).clipShape(Circle())
                    .accessibilityLabel(Words.shared.strings.photoYoureSearchingWith)
                Text(Words.shared.strings.likeThisPhoto).font(.subheadline.weight(.medium))
                Button {
                    search.clearPhoto()
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                        .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                .accessibilityLabel(Words.shared.strings.stopSearchingByPhoto)
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
        if textOnly || !model.meaningSearch {
            // Core's words-only rule: no label (nothing is broken), the upgrade offered only under few results.
            if let hint = Words.shared.upgradeHint(results: Int32(answer.hits.count), upgrade: model.upgrade) {
                Button {
                    upgrading = true
                } label: {
                    Text(hint).frame(minHeight: 44)
                }.font(.footnote)
            }
        } else if !answer.vectorLane {
            HStack(spacing: 10) {
                Image("IllusReading").resizable().scaledToFit().frame(width: 36).accessibilityHidden(true)
                Text(Words.shared.strings.matchingExactWordsForNow).font(.footnote).foregroundStyle(.secondary)
            }
        }
        if answer.key.scope == .all && (photos == .denied || photos == .restricted) {
            Text(Words.shared.strings.photosOffResults).font(.footnote).foregroundStyle(.secondary)
        }
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
            title: lead.isEmpty ? Words.shared.strings.documentsAndMore : Words.shared.strings.moreDocuments)
        docSection(hits.filter { $0.file.kind == .audio }, answer: answer, rows: rows, titled: titled, title: Words.shared.strings.recordings)
    }

    @ViewBuilder private func mediaSection(_ hits: [SearchEngine.Hit], answer: SearchModel.Answer, rows: Rows, titled: Bool) -> some View {
        if !hits.isEmpty {
            if titled { SectionTitle(Words.shared.PHOTOS_AND_VIDEOS) }
            LazyVGrid(columns: gridColumns(typeSize, spacing: 8), spacing: 8) {
                ForEach(hits, id: \.fileId) { hit in
                    ResultCell(
                        file: hit.file, locator: hit.locator, starred: rows.starred.contains(hit.file.uri), onOpen: { open(hit) },
                        extent: answer.extents[hit.fileId], onFindSimilar: rows.similar,
                        matchReason: Words.shared.strings.reasonLine(parts: [hit.matchReason, copiesNote(hit)].compactMap { $0 }).nonEmpty,
                        copies: hit.copies.map(\.file), onOpenCopy: { copy in openCopy(copy, of: hit) })
                }
            }
        }
    }

    @ViewBuilder private func docSection(
        _ hits: [SearchEngine.Hit], answer: SearchModel.Answer, rows: Rows, titled: Bool,
        title: String = Words.shared.strings.documentsAndMore
    ) -> some View {
        if !hits.isEmpty {
            if titled { SectionTitle(title) }
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(hits, id: \.fileId) { hit in
                    FileButton(
                        file: hit.file, locator: hit.locator, snippet: hit.snippet, styledName: answer.names[hit.fileId], extent: answer.extents[hit.fileId],
                        starred: rows.starred.contains(hit.file.uri), onOpen: { open(hit) }, onFindSimilar: rows.similar,
                        copies: hit.copies.map(\.file), onOpenCopy: { copy in openCopy(copy, of: hit) })
                    if let reason = Words.shared.strings.reasonLine(parts: [hit.matchReason, copiesNote(hit)].compactMap { $0 }).nonEmpty {
                        Text(reason).font(.caption).foregroundStyle(.secondary)
                    }
                    Divider()
                }
            }
        }
    }

    /// "2 copies" on a result that stands for the same file in several folders; nil for one file.
    private func copiesNote(_ hit: SearchEngine.Hit) -> String? {
        hit.copies.isEmpty ? nil : Words.shared.copies(n: Int32(hit.copies.count + 1))
    }

    /// The menu's "Open the copy in …": the other copy opens as its own hit.
    private func openCopy(_ copy: SourceFile, of hit: SearchEngine.Hit) {
        if let other = hit.copies.first(where: { $0.file.uri == copy.uri }) { open(other) }
    }

    /// What every row of one pass shares, read once.
    private struct Rows {
        let starred: Set<String>
        let similar: ((SourceFile) -> Void)?
    }
}

/// "No matches yet" for a search that found nothing; while files wait to be read it says so, so it is not taken for
/// "nothing here". Own view, so the counts redraw it and not the results.
private struct NoMatches: View {
    let mood: MascotMood
    let pinned: AppModel.Snap?
    let model: AppModel

    var body: some View {
        let waiting = ((pinned ?? model.snap)?.pending ?? 0) > 0
        StateView(
            mood: waiting ? .walking : mood, title: Words.shared.NO_MATCHES,
            message: Words.shared.strings.noMatchesMessage(byMeaning: model.meaningSearch, waiting: waiting, wordsOnly: false))
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

extension String {
    /// Nil for the empty string, so joined optional notes show no empty line.
    fileprivate var nonEmpty: String? { isEmpty ? nil : self }
}
