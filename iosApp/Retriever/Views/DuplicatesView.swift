// Ported from Retriever's Android DuplicatesScreen.kt (same project). Modified for iOS: photos go to Recently
// Deleted through PhotoKit, folder files are removed, and the oldest copy is kept by default.

import Photos
import RetrieverCore
import SwiftUI

struct DuplicatesView: View {
    let model: AppModel
    /// nil while looking.
    @State private var groups: [DuplicateGroup]?
    @State private var failed = false
    @State private var selected: Set<String> = []
    @State private var keepOne = false
    @State private var confirming = false
    @State private var deleting = false
    @State private var notDeleted = 0
    @Environment(\.openFile) private var openFile

    private static let minSize: Int64 = 1_000_000

    init(model: AppModel, groups: [DuplicateGroup]? = nil) {
        self.model = model
        _groups = State(initialValue: groups)
        _selected = State(initialValue: Self.extraCopies(groups ?? []))
    }

    var body: some View {
        Group {
            if failed {
                StateView(art: "IllusEmpty", title: "Couldn't look for duplicates", message: "Something went wrong. Try again.") {
                    Button("Try again") { Task { await scan() } }.buttonStyle(.borderedProminent)
                }
            } else if let groups, groups.isEmpty {
                StateView(art: "IllusEmpty", title: "No duplicates found",
                          message: "Retriever compared \(Format.files(model.snap?.total ?? 0)) and found no copies bigger than 1 MB.")
            } else if let groups {
                list(groups)
            } else {
                StateView(art: "IllusReading", title: "Looking for copies", message: "This can take a minute.") { ProgressView() }
            }
        }
        .navigationTitle("Duplicates")
        .safeAreaInset(edge: .bottom) {
            if let groups, !groups.isEmpty { bar }
        }
        .alert(selected.count == 1 ? "Delete 1 file?" : "Delete \(selected.count) files?", isPresented: $confirming) {
            Button("Delete", role: .destructive) { Task { await delete() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(deleteMessage)
        }
        .task {
            if groups == nil { await scan() }
        }
    }

    private func list(_ groups: [DuplicateGroup]) -> some View {
        List {
            Section {
                Text("\(Copy.bytes(groups.reduce(0) { $0 + $1.wastedBytes })) could be freed").font(.headline)
            } footer: {
                Text("The oldest copy of each file is kept. Tap a copy to change what goes.")
            }
            ForEach(groups, id: \.files.first!.uri) { group in
                Section("\(group.files.count) copies · \(Copy.bytes(group.size)) each") {
                    ForEach(group.files, id: \.uri) { file in row(file) }
                }
            }
        }
    }

    private func row(_ file: SourceFile) -> some View {
        let checked = selected.contains(file.uri)
        return HStack(spacing: 8) {
            Button { toggle(file.uri) } label: {
                HStack(spacing: 12) {
                    Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(checked ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    FileRowView(file: file, subtitle: "\(file.folder) · \(Format.date(file.modifiedAt))")
                }
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(checked ? .isSelected : [])
            .accessibilityHint(checked ? "Selected to delete. Double-tap to keep it." : "Kept. Double-tap to select it for deleting.")
            // Look before deleting: copies often have identical thumbnails.
            Button { openFile(file) } label: { Image(systemName: "arrow.up.forward.square") }
                .buttonStyle(.borderless)
                .accessibilityLabel("Open \(file.name)")
        }
    }

    private var bar: some View {
        VStack(spacing: 8) {
            if keepOne { Text("Keep at least one copy of each file").font(.footnote) }
            if notDeleted > 0 { Text(notDeleted == 1 ? "1 file wasn't deleted" : "\(notDeleted) files weren't deleted").font(.footnote).foregroundStyle(.red) }
            Button { confirming = true } label: {
                Text("Delete \(selected.count) selected (\(Copy.bytes(selectedBytes)))").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(selected.isEmpty || deleting)
        }
        .padding()
        .background(.bar)
    }

    private var selectedBytes: Int64 {
        (groups ?? []).reduce(0) { sum, g in sum + g.size * Int64(g.files.filter { selected.contains($0.uri) }.count) }
    }

    private var deleteMessage: String {
        let photos = selected.contains { $0.hasPrefix("ph://") }
        let others = selected.contains { !$0.hasPrefix("ph://") }
        switch (photos, others) {
        case (true, false): return "They'll go to Recently Deleted in Photos. iPhone will ask you once more."
        case (true, true): return "The photos go to Recently Deleted in Photos, and iPhone will ask you once more. The other files can't be recovered."
        default: return "They can't be recovered."
        }
    }

    /// Every copy but the oldest; files arrive oldest first.
    private static func extraCopies(_ groups: [DuplicateGroup]) -> Set<String> {
        Set(groups.flatMap { $0.files.dropFirst().map(\.uri) })
    }

    /// Never selects the last kept copy in a group: deleting every copy would lose the file.
    private func toggle(_ uri: String) {
        if selected.remove(uri) != nil {
            keepOne = false
            return
        }
        let group = groups?.first { $0.files.contains { $0.uri == uri } }
        if let group, group.files.allSatisfy({ $0.uri == uri || selected.contains($0.uri) }) {
            keepOne = true
        } else {
            selected.insert(uri)
            keepOne = false
        }
    }

    private func scan() async {
        guard let retriever = model.services?.retriever else { return groups = [] }
        failed = false
        groups = nil
        do {
            let found = try await retriever.duplicates(minSize: Self.minSize)
            selected = Self.extraCopies(found)
            groups = found
        } catch {
            failed = true
        }
    }

    private func delete() async {
        guard let services = model.services else { return }
        deleting = true
        let uris = Array(selected)
        var gone = Set<String>()
        let ids = uris.filter { $0.hasPrefix("ph://") }.map { String($0.dropFirst(5)) }
        if !ids.isEmpty {
            do {
                try await PHPhotoLibrary.shared().performChanges {
                    PHAssetChangeRequest.deleteAssets(PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil))
                }
                gone.formUnion(ids.map { "ph://" + $0 })
            } catch {} // the person said no in iOS's own prompt
        }
        for uri in uris where !uri.hasPrefix("ph://") {
            if let url = services.folders.resolve(uri), (try? FileManager.default.removeItem(at: url)) != nil { gone.insert(uri) }
        }
        // The next reading round drops the deleted files from the index.
        services.retriever.notifyChanged()
        model.wake()
        groups = groups?
            .map { DuplicateGroup(size: $0.size, files: $0.files.filter { !gone.contains($0.uri) }) }
            .filter { $0.files.count >= 2 }
        selected.subtract(gone)
        notDeleted = uris.count - gone.count
        deleting = false
    }
}
