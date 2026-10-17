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
                Section("Folders with files") {
                    ForEach(shown) { f in
                        NavigationLink(value: Route.files(FileQuery(folder: f.path, title: f.name))) {
                            VStack(alignment: .leading, spacing: 2) {
                                Label(f.name, systemImage: "folder")
                                Text([Format.files(f.count), f.parent].compactMap { $0 }.joined(separator: " · "))
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                        }
                        .swipeActions { Button("Stop searching", systemImage: "eye.slash") { excluding = f }.tint(.orange) }
                        .contextMenu { Button("Stop searching this folder", systemImage: "eye.slash") { excluding = f } }
                    }
                }
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
                Button { adding = true } label: { Label("Add a folder", systemImage: "folder.badge.plus") }
            } header: {
                Text("Folders you added")
            } footer: {
                Text("Retriever also searches its own folder in the Files app. Removing a folder here never deletes it.")
            }
            if !excluded.isEmpty {
                Section("Not searched") {
                    ForEach(excluded.sorted(), id: \.self) { path in
                        LabeledContent {
                            Button("Search again") { setExcluded(path, false) }.buttonStyle(.borderless)
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
            } else if folders.isEmpty && picked.isEmpty && unreachable.isEmpty {
                StateView(art: "IllusEmpty", title: "No folders yet", message: "Folders appear once Retriever has looked through your files.")
            }
        }
        .navigationTitle("Folders")
        .task(id: excluded) { await load() }
        .fileImporter(isPresented: $adding, allowedContentTypes: [.folder], allowsMultipleSelection: true) { result in
            guard case let .success(urls) = result else { return }
            note = model.addFolders(urls).map(Copy.addFolderResult).joined(separator: "\n")
            Task { await load() }
        }
        .alert(note ?? "", isPresented: Binding(get: { note != nil }, set: { if !$0 { note = nil } })) {}
        .alert("Stop searching \(excluding?.name ?? "")?", isPresented: Binding(get: { excluding != nil }, set: { if !$0 { excluding = nil } }), presenting: excluding) { f in
            Button("Stop searching", role: .destructive) { setExcluded(f.path, true) }
            Button("Cancel", role: .cancel) {}
        } message: { f in
            Text("Retriever will forget the \(Format.files(f.count)) in \(f.name). You can bring it back here.")
        }
        .alert("Remove \(removing?.name ?? "")?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), presenting: removing) { f in
            Button("Remove", role: .destructive) { remove(f) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Retriever will stop searching it and forget what it read there. The folder and its files stay where they are.")
        }
    }

    private func load() async {
        guard let services else { return loading = false }
        picked = services.folders.folders.filter { $0.id != FolderSource.documentsId }
        unreachable = services.folders.unreachable
        let pairs = (try? await services.retriever.folders()) ?? []
        folders = pairs.map { FolderCount(path: ($0.first as String?) ?? "", count: $0.second?.intValue ?? 0) }
        loading = false
    }

    /// The next reading round rescans, which forgets an excluded folder's files or reads a restored one again.
    private func setExcluded(_ path: String, _ on: Bool) {
        guard let services else { return }
        Task {
            try? await services.retriever.updatePrefs { p in
                p.doCopy(kinds: p.kinds, onlyWhileCharging: p.onlyWhileCharging,
                         excludedFolders: on ? p.excludedFolders.union([path]) : p.excludedFolders.subtracting([path]),
                         paused: p.paused, sampleSize: p.sampleSize)
            }
            services.retriever.notifyChanged()
            model.wake()
        }
    }

    private func remove(_ folder: FolderSource.Folder) {
        guard let services else { return }
        services.folders.removeFolder(folder.id)
        services.retriever.notifyChanged()
        model.wake()
        Task { await load() }
    }
}
