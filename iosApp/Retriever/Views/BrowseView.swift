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
    @State private var pages: FilePages
    @State private var slow = false
    @Environment(\.openFile) private var openFile
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(AppModel.self) private var model: AppModel?

    init(query: FileQuery, services: AppServices?, files: [Store.StoredFile]? = nil) {
        self.query = query
        self.services = services
        _sort = State(initialValue: query.sort)
        _pages = State(
            initialValue: FilePages(files: files) { sort, offset in
                guard let retriever = services?.retriever else { return [] }
                if let range = query.takenRange {
                    return try await retriever.filesTakenBetween(from: range.lowerBound, to: range.upperBound, limit: FilePages.pageSize, offset: offset)
                }
                if query.starredOnly { return try await retriever.starredFiles(limit: FilePages.pageSize, offset: offset) }
                return try await retriever.files(kind: query.kind, folder: query.folder, sort: sort.store, limit: FilePages.pageSize, offset: offset)
            })
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
        let visible = query.starredOnly ? pages.files.filter { starred.contains($0.file.uri) } : pages.files
        Group {
            if visible.isEmpty && pages.loading {
                // Never an empty Group: its modifiers (title, task) would vanish with it.
                ZStack { if slow { ProgressView().controlSize(.large) } }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if visible.isEmpty && pages.failed {
                StateView(mood: .trouble, title: "Couldn't load files", message: "Something went wrong reading the list.") {
                    Button("Try again") { Task { await pages.loadPage() } }.buttonStyle(.primaryAction)
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
                            ResultCell(
                                file: stored.file, starred: starred.contains(stored.file.uri), onOpen: { openFile(stored.file, among: visible.map(\.file)) }
                            )
                            .onAppear { more(after: stored) }
                        }
                    }
                    .padding(4)
                }
            } else {
                List(visible, id: \.id) { stored in
                    let f = stored.file
                    FileButton(
                        file: f, subtitle: "\(Copy.bytes(f.size)) · \(f.folder) · \(Format.date(f.modifiedAt))",
                        coverage: Format.coverage(stored, inside: services?.retriever.readInside() ?? []),
                        starred: starred.contains(f.uri), onOpen: { openFile(f, among: visible.map(\.file)) }
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
        .safeAreaInset(edge: .bottom) {
            if pages.failed && !pages.files.isEmpty {
                VStack(spacing: 8) {
                    Text("Couldn't load more files")
                    Button("Try again") { Task { await pages.loadPage() } }.buttonStyle(.bordered)
                }
                .padding().frame(maxWidth: .infinity).background(.bar)
            }
        }
        .onChange(of: model?.fileRevision) { Task { await pages.load(sort) } }
        .task(id: sort) {
            guard services != nil else { return }
            await pages.load(sort)
        }
        .task(id: pages.loading) {
            slow = false
            guard pages.loading else { return }
            try? await Task.sleep(for: .milliseconds(300))
            slow = !Task.isCancelled && pages.loading
        }
    }

    private func more(after stored: Store.StoredFile) {
        guard stored.id == pages.files.last?.id, !pages.failed else { return }
        Task { await pages.loadPage() }
    }
}

@Observable @MainActor
final class FilePages {
    static let pageSize: Int32 = 60
    private(set) var files: [Store.StoredFile]
    private(set) var loading: Bool
    private(set) var failed = false
    private(set) var exhausted: Bool
    private(set) var loadedSort: FileQuery.Sort?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var offset: Int32 = 0
    @ObservationIgnored private var seen: Set<Int64> = []
    private let fetch: (FileQuery.Sort, Int32) async throws -> [Store.StoredFile]

    init(files: [Store.StoredFile]? = nil, fetch: @escaping (FileQuery.Sort, Int32) async throws -> [Store.StoredFile]) {
        self.files = files ?? []
        loading = files == nil
        exhausted = files != nil
        self.fetch = fetch
    }

    func load(_ sort: FileQuery.Sort) async {
        guard loadedSort != sort else { return }
        generation += 1
        loadedSort = sort
        files = []
        offset = 0
        seen = []
        exhausted = false
        loading = false
        await loadPage()
    }

    func loadPage() async {
        guard let sort = loadedSort, !loading, !exhausted else { return }
        let gen = generation
        loading = true
        failed = false
        defer { if gen == generation { loading = false } }
        do {
            let count = files.count
            repeat {
                let page = try await fetch(sort, offset)
                guard gen == generation else { return }
                guard !Task.isCancelled else {
                    if files.isEmpty { loadedSort = nil }
                    return
                }
                // A fully overlapping page adds no row to trigger onAppear, so keep paging until one does.
                offset += Int32(page.count)
                files += page.filter { seen.insert($0.id).inserted }
                exhausted = page.count < Self.pageSize
            } while files.count == count && !exhausted
        } catch {
            guard gen == generation else { return }
            if Task.isCancelled {
                if files.isEmpty { loadedSort = nil }
            } else {
                failed = true
            }
        }
    }
}
