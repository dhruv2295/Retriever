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
        var name: String { path.split(separator: "/").last.map(String.init) ?? "Top level" }
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
                                Text([Words.shared.files(n: Int64(f.count)), f.parent].compactMap { $0 }.joined(separator: " · "))
                                    .font(.footnote)
                                    .foregroundStyle(Color.primary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .accessibilityElement(children: .combine)
                        }
                        .swipeActions { Button("Stop searching", systemImage: "eye.slash") { excluding = f }.tint(.orange) }
                        .contextMenu { Button("Stop searching this folder", systemImage: "eye.slash") { excluding = f } }
                    }
                } header: {
                    Text("Folders with files").foregroundStyle(Color.primary)
                }
            }
            if failed && !(picked.isEmpty && unreachable.isEmpty) {
                Section { Button("Couldn't load the folder list. Try again") { Task { await load() } } }
            }
            Section {
                ForEach(picked) { f in
                    Label(f.name, systemImage: "folder.badge.person.crop")
                        .swipeActions { Button("Remove", role: .destructive) { removing = f } }
                        .contextMenu { Button("Remove", systemImage: "trash", role: .destructive) { removing = f } }
                }
                ForEach(unreachable) { f in
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.name)
                            Text("Retriever can't open this folder any more. Remove it, then add it again.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "exclamationmark.triangle").foregroundStyle(.orange)
                    }
                    .accessibilityElement(children: .combine)
                    .swipeActions { Button("Remove", role: .destructive) { removing = f } }
                    .contextMenu { Button("Remove", systemImage: "trash", role: .destructive) { removing = f } }
                }
                Button {
                    adding = true
                } label: {
                    Label("Add a folder", systemImage: "folder.badge.plus")
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
            } header: {
                Text("Folders you added").foregroundStyle(Color.primary)
            } footer: {
                Text("Retriever also searches its own folder in the Files app. Removing a folder here never deletes it.")
                    .foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
            }
            if !excluded.isEmpty {
                Section("Excluded folders") {
                    ForEach(excluded.sorted(), id: \.self) { path in
                        LabeledContent {
                            Button {
                                model.restoreFolder(path)
                            } label: {
                                Text("Include folder").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
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
                StateView(mood: .trouble, title: "Couldn't load folders", message: "Something went wrong reading your folders.") {
                    Button("Try again") { Task { await load() } }.buttonStyle(.primaryAction)
                }
            } else if folders.isEmpty && picked.isEmpty && unreachable.isEmpty {
                StateView(mood: .noMatch, title: "No folders yet", message: "Folders appear once Retriever has looked through your files.")
            }
        }
        .navigationTitle("Folders")
        .task(id: excluded) { await load() }
        .fileImporter(isPresented: $adding, allowedContentTypes: [.folder], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            note = model.addFolders(urls).map(Copy.addFolderResult).joined(separator: "\n")
            Task { await load() }
        }
        .alert(note ?? "", isPresented: Binding(get: { note != nil }, set: { if !$0 { note = nil } })) {}
        .alert(
            "Stop searching \(excluding?.name ?? "")?", isPresented: Binding(get: { excluding != nil }, set: { if !$0 { excluding = nil } }),
            presenting: excluding
        ) { f in
            Button("Stop searching", role: .destructive) { model.setExcluded(f.path, true) }
            Button("Cancel", role: .cancel) {}
        } message: { f in
            Text("Retriever will forget the \(Words.shared.files(n: Int64(f.count))) in \(f.name). You can bring it back here.")
        }
        .alert("Remove \(removing?.name ?? "")?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), presenting: removing) {
            f in
            Button("Remove", role: .destructive) { remove(f) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Retriever will stop searching it and forget what it read there. The folder and its files stay where they are.")
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
