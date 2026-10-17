// Ported from Retriever's Android BrowseScreen.kt (same project): files by kind, size or folder, a page at a time.
// Modified for iOS: a sort menu in the toolbar, and paging when the last file appears.

import RetrieverCore
import SwiftUI

/// Which files a list shows and how they're sorted.
struct FileQuery: Hashable {
    enum Sort: CaseIterable {
        case newest, largest, name

        var label: String {
            switch self {
            case .newest: "Newest"
            case .largest: "Largest"
            case .name: "Name"
            }
        }

        var store: Store.Sort {
            switch self {
            case .newest: Store.Sort.newest
            case .largest: Store.Sort.largest
            case .name: Store.Sort.name
            }
        }
    }

    var kind: Kind?
    var folder: String?
    var sort = Sort.newest
    var title: String
    /// The files the person starred, newest star first; the sort menu doesn't apply.
    var starredOnly = false
}

/// Files a page at a time: pictures as a grid, everything else as rows.
struct FileListView: View {
    let query: FileQuery
    let services: AppServices?
    @State private var sort: FileQuery.Sort
    @State private var files: [Store.StoredFile] = []
    @State private var loading = true
    @State private var slow = false
    @State private var exhausted = false
    @State private var generation = 0
    @Environment(\.openFile) private var openFile
    @Environment(\.dynamicTypeSize) private var typeSize

    private static let page: Int32 = 60

    init(query: FileQuery, services: AppServices?, files: [Store.StoredFile]? = nil) {
        self.query = query
        self.services = services
        _sort = State(initialValue: query.sort)
        if let files {
            _files = State(initialValue: files)
            _loading = State(initialValue: false)
            _exhausted = State(initialValue: true)
        }
    }

    /// Large files sorted another way is just every file.
    private var title: String {
        query.kind == nil && query.folder == nil && !query.starredOnly && sort != .largest ? "All files" : query.title
    }

    private var grid: Bool {
        query.kind == .photo || query.kind == .screenshot || query.kind == .video
    }

    /// Starred follows the stars as they change (here, in a viewer), without reloading its pages.
    private var shown: [Store.StoredFile] {
        guard query.starredOnly, let starred = Stars.model?.starred else { return files }
        return files.filter { starred.contains($0.file.uri) }
    }

    var body: some View {
        let visible = shown
        Group {
            if visible.isEmpty && loading {
                // Never an empty Group: its modifiers (title, task) would vanish with it.
                ZStack { if slow { ProgressView().controlSize(.large) } }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if visible.isEmpty {
                if query.starredOnly {
                    StateView(art: "IllusEmpty", title: "Nothing starred yet", message: "Touch and hold any file and choose Star to keep it here.")
                } else {
                    StateView(art: "IllusEmpty", title: "Nothing here yet", message: "Files show up here once Retriever finds them on your \(Copy.device).")
                }
            } else if grid {
                ScrollView {
                    LazyVGrid(columns: gridColumns(typeSize, spacing: 4), spacing: 4) {
                        ForEach(visible, id: \.id) { stored in
                            ResultCell(file: stored.file, onOpen: { openFile(stored.file) }).onAppear { more(after: stored) }
                        }
                    }
                    .padding(4)
                }
            } else {
                List(visible, id: \.id) { stored in
                    let f = stored.file
                    FileButton(file: f, subtitle: "\(Copy.bytes(f.size)) · \(f.folder) · \(Format.date(f.modifiedAt))", onOpen: { openFile(f) })
                        .onAppear { more(after: stored) }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(title)
        .toolbar {
            if !query.starredOnly {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Sort by", selection: $sort) {
                            ForEach(FileQuery.Sort.allCases, id: \.self) { Text($0.label).tag($0) }
                        }
                    } label: {
                        Label("Sort", systemImage: "arrow.up.arrow.down")
                    }
                }
            }
        }
        .task(id: sort) {
            guard services != nil else { return }
            generation += 1
            files = []
            exhausted = false
            await loadPage()
        }
        .task(id: loading) {
            slow = false
            guard loading else { return }
            try? await Task.sleep(for: .milliseconds(300))
            slow = !Task.isCancelled && loading
        }
    }

    private func more(after stored: Store.StoredFile) {
        guard stored.id == files.last?.id, !loading, !exhausted else { return }
        Task { await loadPage() }
    }

    private func loadPage() async {
        guard let retriever = services?.retriever else { return }
        let gen = generation
        loading = true
        let offset = Int32(files.count)
        let page = (query.starredOnly ? try? await retriever.starredFiles(limit: Self.page, offset: offset)
            : try? await retriever.files(kind: query.kind, folder: query.folder, sort: sort.store, limit: Self.page, offset: offset)) ?? []
        guard gen == generation else { return }
        // ponytail: offset paging over a table the indexer changes, so rows can shift; drop repeats by id.
        let seen = Set(files.map(\.id))
        files += page.filter { !seen.contains($0.id) }
        exhausted = page.count < Self.page
        loading = false
    }
}
