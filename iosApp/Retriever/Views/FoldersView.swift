// Ported from Retriever's Android FoldersScreen.kt (same project): folders with file counts, and stopping
// Retriever from searching one. Modified for iOS: the folders picked in the Files app, adding and removing them,
// and bringing an excluded folder back.

import RetrieverCore
import SwiftUI

struct FoldersView: View {
    let model: AppModel
    @State private var folders: [FolderCount] = []
    @State private var picked: [FolderSource.Folder] = []
    @State private var unreachable: [FolderSource.Folder] = []
    @State private var loading = true
    @State private var failed = false
    @State private var excluding: FolderCount?
    @State private var removing: FolderSource.Folder?
    @State private var adding = false
    @State private var note: String?

    struct FolderCount: Identifiable {
        let path: String
        let count: Int
        var id: String { path }
        var name: String { path.split(separator: "/").last.map(String.init) ?? Words.shared.strings.topLevel }
        var parent: String? { path.contains("/") ? String(path[..<path.lastIndex(of: "/")!]) : nil }
    }

    private var services: AppServices? { model.services }
    private var excluded: Set<String> { model.snap?.excludedFolders ?? [] }

    var body: some View {
        List {
            let shown = folders.filter { f in !excluded.contains { f.path == $0 || f.path.hasPrefix($0 + "/") } }
            if !shown.isEmpty {
                Section {
                    ForEach(shown) { f in
                        NavigationLink(value: Route.files(FileQuery(folder: f.path, title: f.name))) {
                            VStack(alignment: .leading, spacing: 2) {
                                Label {
                                    Text(f.name).fixedSize(horizontal: false, vertical: true)
                                } icon: {
                                    Image(systemName: "folder")
                                }
                                Text(Words.shared.strings.filesInParent(n: Int64(f.count), parent: f.parent))
                                    .font(.footnote)
                                    .foregroundStyle(Color.primary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .accessibilityElement(children: .combine)
                        }
                        .swipeActions { Button(Words.shared.strings.stopSearching, systemImage: "eye.slash") { excluding = f }.tint(.orange) }
                        .contextMenu { Button(Words.shared.strings.stopSearchingThisFolder, systemImage: "eye.slash") { excluding = f } }
                    }
                } header: {
                    Text(Words.shared.strings.foldersWithFiles).foregroundStyle(Color.primary)
                }
            }
            if failed && !(picked.isEmpty && unreachable.isEmpty) {
                Section { Button(Words.shared.strings.couldntLoadFolderList) { Task { await load() } } }
            }
            Section {
                ForEach(picked) { f in
                    Label(f.name, systemImage: "folder.badge.person.crop")
                        .swipeActions { Button(Words.shared.strings.removeFolder, role: .destructive) { removing = f } }
                        .contextMenu { Button(Words.shared.strings.removeFolder, systemImage: "trash", role: .destructive) { removing = f } }
                }
                ForEach(unreachable) { f in
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.name)
                            Text(Words.shared.strings.cantOpenFolderAnyMore)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "exclamationmark.triangle").foregroundStyle(.orange)
                    }
                    .accessibilityElement(children: .combine)
                    .swipeActions { Button(Words.shared.strings.removeFolder, role: .destructive) { removing = f } }
                    .contextMenu { Button(Words.shared.strings.removeFolder, systemImage: "trash", role: .destructive) { removing = f } }
                }
                Button {
                    adding = true
                } label: {
                    Label(Words.shared.ADD_FOLDER, systemImage: "folder.badge.plus")
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
            } header: {
                Text(Words.shared.strings.foldersYouAdded).foregroundStyle(Color.primary)
            } footer: {
                Text(Words.shared.strings.alsoSearchesOwnFolder)
                    .foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
            }
            if !excluded.isEmpty {
                Section(Words.shared.FOLDERS_TO_SKIP) {
                    ForEach(excluded.sorted(), id: \.self) { path in
                        LabeledContent {
                            Button {
                                model.restoreFolder(path)
                            } label: {
                                Text(Words.shared.strings.includeFolder).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                            }.buttonStyle(.borderless)
                        } label: {
                            Label(path.split(separator: "/").last.map(String.init) ?? path, systemImage: "eye.slash")
                        }
                    }
                }
            }
        }
        .overlay {
            if loading && folders.isEmpty {
                ProgressView()
            } else if failed && picked.isEmpty && unreachable.isEmpty {
                StateView(mood: .trouble, title: Words.shared.strings.couldntLoadFolders, message: Words.shared.strings.wentWrongReadingFolders) {
                    Button(Words.shared.TRY_AGAIN) { Task { await load() } }.buttonStyle(.primaryAction)
                }
            } else if folders.isEmpty && picked.isEmpty && unreachable.isEmpty {
                StateView(mood: .noMatch, title: Words.shared.strings.noFoldersYet, message: Words.shared.strings.foldersAppearFiles)
            }
        }
        .navigationTitle(Words.shared.strings.folders)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button(Words.shared.ADD_FOLDER, systemImage: "folder.badge.plus") { adding = true }
                    .labelStyle(.iconOnly)
            }
        }
        .task(id: excluded) { await load() }
        .fileImporter(isPresented: $adding, allowedContentTypes: [.folder], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            note = model.addFolders(urls).map(Copy.addFolderResult).joined(separator: "\n")
            Task { await load() }
        }
        .alert(note ?? "", isPresented: Binding(get: { note != nil }, set: { if !$0 { note = nil } })) {}
        .alert(
            Words.shared.strings.stopSearchingAsk(name: excluding?.name ?? ""),
            isPresented: Binding(get: { excluding != nil }, set: { if !$0 { excluding = nil } }),
            presenting: excluding
        ) { f in
            Button(Words.shared.strings.stopSearching, role: .destructive) { model.setExcluded(f.path, true) }
            Button(Words.shared.CANCEL, role: .cancel) {}
        } message: { f in
            Text(Words.shared.strings.stopSearchingForget(n: Int64(f.count), name: f.name))
        }
        .alert(
            Words.shared.strings.removeFolderAsk(name: removing?.name ?? ""),
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), presenting: removing
        ) {
            f in
            Button(Words.shared.strings.removeFolder, role: .destructive) { remove(f) }
            Button(Words.shared.CANCEL, role: .cancel) {}
        } message: { _ in
            Text(Words.shared.strings.removeFolderMessage)
        }
    }

    private func load() async {
        guard let services else {
            loading = false
            return
        }
        loading = true
        picked = services.folders.folders.filter { $0.id != FolderSource.documentsId }
        unreachable = services.folders.unreachable
        do {
            let pairs = try await services.retriever.folders()
            folders = pairs.map { FolderCount(path: ($0.first as String?) ?? "", count: $0.second?.intValue ?? 0) }
            failed = false
        } catch {
            failed = true
        }
        loading = false
    }

    private func remove(_ folder: FolderSource.Folder) {
        guard let services else { return }
        services.folders.removeFolder(folder.id)
        services.retriever.notifyChanged()
        model.wake()
        Task { await load() }
    }
}
