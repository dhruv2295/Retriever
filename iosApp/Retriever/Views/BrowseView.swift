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
    /// Photos and videos taken within these times (ms), oldest first; the sort menu doesn't apply.
    var takenRange: ClosedRange<Int64>?
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
    @State private var failed = false
    /// The sort the list was last loaded for, so coming back to the screen doesn't throw the pages away.
    @State private var loadedSort: FileQuery.Sort?
    @Environment(\.openFile) private var openFile
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(AppModel.self) private var model: AppModel?

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
        query.kind == nil && query.folder == nil && !query.starredOnly && query.takenRange == nil && sort != .largest ? "All files" : query.title
    }

    private var grid: Bool {
        query.kind == .photo || query.kind == .screenshot || query.kind == .video || query.takenRange != nil
    }

    var body: some View {
        // One read of the stars for every row. Starred follows them as they change (here, in a viewer), without reloading its pages.
        let starred = model?.starred ?? []
        let visible = query.starredOnly ? files.filter { starred.contains($0.file.uri) } : files
        Group {
            if visible.isEmpty && loading {
                // Never an empty Group: its modifiers (title, task) would vanish with it.
                ZStack { if slow { ProgressView().controlSize(.large) } }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if visible.isEmpty && failed {
                StateView(mood: .trouble, title: "Couldn't load files", message: "Something went wrong reading the list.") {
                    Button("Try again") { Task { await reload() } }.buttonStyle(.borderedProminent)
                }
            } else if visible.isEmpty {
                if query.starredOnly {
                    StateView(mood: .noMatch, title: "Nothing starred yet", message: "Touch and hold any file and choose Star to keep it here.")
                } else {
                    StateView(mood: .noMatch, title: "Nothing here yet", message: "Files show up here once Retriever finds them on your \(Copy.device).")
                }
            } else if grid {
                ScrollView {
                    LazyVGrid(columns: gridColumns(typeSize, spacing: 4), spacing: 4) {
                        ForEach(visible, id: \.id) { stored in
                            ResultCell(file: stored.file, starred: starred.contains(stored.file.uri), onOpen: { openFile(stored.file) })
                                .onAppear { more(after: stored) }
                        }
                    }
                    .padding(4)
                }
            } else {
                List(visible, id: \.id) { stored in
                    let f = stored.file
                    FileButton(
                        file: f, subtitle: "\(Copy.bytes(f.size)) · \(f.folder) · \(Format.date(f.modifiedAt))", coverage: Format.coverage(stored),
                        starred: starred.contains(f.uri), onOpen: { openFile(f) }
                    )
                    .onAppear { more(after: stored) }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(title)
        .toolbar {
            if !query.starredOnly && query.takenRange == nil {
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
            guard loadedSort != sort else { return }
            await reload()
        }
        .task(id: loading) {
            slow = false
            guard loading else { return }
            try? await Task.sleep(for: .milliseconds(300))
            slow = !Task.isCancelled && loading
        }
    }

    private func reload() async {
        guard services != nil else { return }
        generation += 1
        files = []
        exhausted = false
        loadedSort = nil
        await loadPage()
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
        let page: [Store.StoredFile]
        do {
            if let range = query.takenRange {
                page = try await retriever.filesTakenBetween(from: range.lowerBound, to: range.upperBound, limit: Self.page, offset: offset)
            } else if query.starredOnly {
                page = try await retriever.starredFiles(limit: Self.page, offset: offset)
            } else {
                page = try await retriever.files(kind: query.kind, folder: query.folder, sort: sort.store, limit: Self.page, offset: offset)
            }
        } catch {
            guard gen == generation else { return }
            failed = true
            loading = false
            return
        }
        guard gen == generation else { return }
        failed = false
        loadedSort = sort
        // ponytail: offset paging over a table the indexer changes, so rows can shift; drop repeats by id.
        let seen = Set(files.map(\.id))
        files += page.filter { !seen.contains($0.id) }
        exhausted = page.count < Self.page
        loading = false
    }
}
