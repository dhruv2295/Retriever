// Ported from Retriever's Android DuplicatesScreen.kt (same project). Modified for iOS: only files in added
// folders are compared (PhotoKit has no public byte size), they are removed, and the oldest copy is kept by default.

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
                StateView(mood: .trouble, title: Words.shared.strings.couldntLookForDuplicates, message: Words.shared.strings.somethingWentWrongTryAgain) {
                    Button(Words.shared.TRY_AGAIN) { Task { await scan() } }.buttonStyle(.primaryAction)
                }
            } else if let groups, groups.isEmpty {
                StateView(
                    mood: .noMatch, title: Words.shared.strings.noDuplicatesFound,
                    message: Words.shared.strings.noDuplicatesMessage)
            } else if let groups {
                list(groups)
            } else {
                StateView(mood: .walking, title: Words.shared.strings.lookingForCopies, message: Words.shared.strings.thisCanTakeAMinute) { ProgressView() }
            }
        }
        .navigationTitle(Words.shared.strings.duplicates)
        .safeAreaInset(edge: .bottom) {
            if let groups, !groups.isEmpty { bar }
        }
        .alert(Words.shared.strings.deleteFilesAsk(n: Int64(selected.count)), isPresented: $confirming) {
            Button(Words.shared.strings.delete_, role: .destructive) { Task { await delete() } }
            Button(Words.shared.CANCEL, role: .cancel) {}
        } message: {
            Text(Words.shared.strings.cantBeRecovered)
        }
        .task {
            if groups == nil { await scan() }
        }
    }

    private func list(_ groups: [DuplicateGroup]) -> some View {
        List {
            Section {
                Text(Words.shared.strings.couldBeFreed(size: Copy.bytes(groups.reduce(0) { $0 + $1.wastedBytes }))).font(.headline)
            } footer: {
                Text(Words.shared.strings.comparesFilesNote)
                    .foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(groups, id: \.files.first!.uri) { group in
                Section {
                    ForEach(group.files, id: \.uri) { file in row(file) }
                } header: {
                    Text(Words.shared.strings.copiesEach(n: Int64(group.files.count), size: Copy.bytes(group.size)))
                        .foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func row(_ file: SourceFile) -> some View {
        let checked = selected.contains(file.uri)
        return HStack(spacing: 8) {
            Button {
                toggle(file.uri)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(checked ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    FileRowView(file: file, subtitle: Words.shared.strings.folderAndDate(folder: file.folder, date: Format.date(file.modifiedAt)))
                }
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(checked ? .isSelected : [])
            .accessibilityHint(checked ? Words.shared.strings.selectedToDeleteHint : Words.shared.strings.keptHint)
            // Look before deleting: copies often have identical thumbnails.
            Button {
                openFile(file)
            } label: {
                Image(systemName: "arrow.up.forward.square")
                    .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(Words.shared.strings.openNamed(name: file.name))
        }
    }

    private var bar: some View {
        VStack(spacing: 8) {
            if keepOne { Text(Words.shared.strings.keepOneCopy).font(.footnote) }
            if notDeleted > 0 { Text(Words.shared.strings.filesWerentDeleted(n: Int64(notDeleted))).font(.footnote).foregroundStyle(.red) }
            Button {
                confirming = true
            } label: {
                Text(Words.shared.strings.deleteSelected(n: Int64(selected.count), size: Copy.bytes(selectedBytes))).frame(maxWidth: .infinity)
            }
            .buttonStyle(.primaryAction)
            .controlSize(.large)
            .disabled(selected.isEmpty || deleting)
        }
        .padding()
        .background(.bar)
    }

    private var selectedBytes: Int64 {
        (groups ?? []).reduce(0) { sum, g in sum + g.size * Int64(g.files.filter { selected.contains($0.uri) }.count) }
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
        guard let retriever = model.services?.retriever else {
            groups = []
            return
        }
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
        // Only copies that still match the kept file go; the rest count as not deleted.
        let copies = (try? await services.retriever.verifiedCopies(groups: groups ?? [], selected: selected)) ?? []
        var gone = Set<String>()
        for uri in copies {
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
