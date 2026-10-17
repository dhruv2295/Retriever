// Ported from Retriever's Android BrowseScreen.kt (same project): files by kind, size or folder, a page at a time.
// Modified for iOS: a List hub, a sort menu in the toolbar, and paging when the last file appears.

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
}

/// The kinds Browse lists, in order; iOS reads no audio.
private let browseKinds: [Kind] = [.photo, .screenshot, .video, .pdf, .doc, .other]

/// Every way into the files: by size, by kind, by folder, and copies.
struct BrowseView: View {
    let snap: AppModel.Snap?

    var body: some View {
        List {
            Section {
                NavigationLink(value: Route.files(FileQuery(sort: .largest, title: "Large files"))) {
                    Label("Large files", systemImage: "externaldrive")
                }
            }
            let kinds = browseKinds.filter { (snap?.byKind[$0] ?? 0) > 0 }
            if !kinds.isEmpty {
                Section("By kind") {
                    ForEach(kinds, id: \.self) { kind in
                        NavigationLink(value: Route.files(FileQuery(kind: kind, title: Format.kindPlural(kind)))) {
                            LabeledContent {
                                Text(Copy.count(snap?.byKind[kind] ?? 0)).monospacedDigit()
                            } label: {
                                Label(Format.kindPlural(kind), systemImage: Format.icon(kind))
                            }
                        }
                    }
                }
            }
            Section("Tidy up") {
                NavigationLink(value: Route.folders) { Label("Folders", systemImage: "folder") }
                NavigationLink(value: Route.duplicates) { Label("Duplicates", systemImage: "square.on.square") }
            }
        }
        .navigationTitle("Browse")
    }
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
        query.kind == nil && query.folder == nil && sort != .largest ? "All files" : query.title
    }

    private var grid: Bool {
        query.kind == .photo || query.kind == .screenshot || query.kind == .video
    }

    var body: some View {
        Group {
            if files.isEmpty && loading {
                // Never an empty Group: its modifiers (title, task) would vanish with it.
                ZStack { if slow { ProgressView().controlSize(.large) } }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if files.isEmpty {
                StateView(art: "IllusEmpty", title: "Nothing here yet", message: "Files show up here once Retriever finds them on your \(Copy.device).")
            } else if grid {
                ScrollView {
                    LazyVGrid(columns: gridColumns(typeSize, spacing: 4), spacing: 4) {
                        ForEach(files, id: \.id) { stored in
                            ResultCell(file: stored.file, onOpen: { openFile(stored.file) }).onAppear { more(after: stored) }
                        }
                    }
                    .padding(4)
                }
            } else {
                List(files, id: \.id) { stored in
                    let f = stored.file
                    FileButton(file: f, subtitle: "\(Copy.bytes(f.size)) · \(f.folder) · \(Format.date(f.modifiedAt))", onOpen: { openFile(f) })
                        .onAppear { more(after: stored) }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(title)
        .toolbar {
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
        let page = (try? await retriever.files(kind: query.kind, folder: query.folder, sort: sort.store, limit: Self.page, offset: Int32(files.count))) ?? []
        guard gen == generation else { return }
        // ponytail: offset paging over a table the indexer changes, so rows can shift; drop repeats by id.
        let seen = Set(files.map(\.id))
        files += page.filter { !seen.contains($0.id) }
        exhausted = page.count < Self.page
        loading = false
    }
}
