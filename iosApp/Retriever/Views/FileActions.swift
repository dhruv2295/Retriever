import AVFoundation
import ImageIO
import PDFKit
import Photos
@preconcurrency import RetrieverCore
import SwiftUI

/// The same actions serve result menus, Browse, Recently read and every viewer, in this order.
enum FileAction: CaseIterable {
    case star, share, open, info, rename, delete

    static func available(_ uri: String) -> [Self] {
        if uri.hasPrefix("ph://") { return [.star, .share, .open, .info, .delete] }
        if uri.hasPrefix("folder://") { return allCases }
        return [.star, .info]
    }

    static func validName(_ name: String) -> Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && name != "." && name != ".."
            && !name.contains("/") && !name.contains("\\") && name.rangeOfCharacter(from: .controlCharacters) == nil
    }

    @MainActor func title(starred: Bool) -> String {
        switch self {
        case .star: starred ? Copy.removeStar : Copy.star
        case .share: Words.shared.strings.share
        case .open: Words.shared.strings.openInAnotherApp
        case .info: Words.shared.strings.fileInfo
        case .rename: Words.shared.strings.rename
        case .delete: Words.shared.deleteFrom(words: Copy.words)
        }
    }

    func icon(starred: Bool) -> String {
        switch self {
        case .star: starred ? "star.slash" : "star"
        case .share: "square.and.arrow.up"
        case .open: "arrow.up.forward.app"
        case .info: "info.circle"
        case .rename: "pencil"
        case .delete: "trash"
        }
    }
}

@MainActor @Observable final class FileActions {
    var file: SourceFile?
    var action: FileAction?
    var name = ""
    var info: FileInfo?
    var error: String?
    var busy = false
    var openURL: URL?
    var deleted: () -> Void = {}

    func select(_ action: FileAction, file: SourceFile, deleted: @escaping () -> Void = {}) {
        self.file = file
        self.action = action
        self.name = file.name
        self.deleted = deleted
        info = nil
    }

    func perform(model: AppModel, work: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do { try await work() } catch {
                self.action = nil
                self.error = Words.shared.strings.couldntFinishThat
            }
        }
    }

    func rename(model: AppModel) {
        guard let file, let services = model.services, FileAction.validName(name) else { return }
        let wanted = name
        perform(model: model) {
            guard
                let changed = try await services.retriever.renameFile(
                    uri: file.uri,
                    operation: { old in
                        try? Self.rename(old, name: wanted, folders: services.folders)
                    })
            else { throw CocoaError(.fileWriteUnknown) }
            self.action = nil
            await model.filesChanged()
            if changed.uri != file.uri { self.deleted() }
        }
    }

    func delete(model: AppModel) {
        guard let file, let services = model.services else { return }
        perform(model: model) {
            guard await Self.remove([file.uri], folders: services.folders).contains(file.uri) else { throw CocoaError(.fileNoSuchFile) }
            try await services.retriever.deletedFiles(uris: [file.uri])
            self.action = nil
            await model.filesChanged()
            self.deleted()
        }
    }

    /// Deletes what it can and returns the uris it deleted: library photos in one change (iOS asks first), files in
    /// added folders to the trash, or for good with `permanently`.
    @concurrent static func remove(_ uris: [String], folders: FolderSource, permanently: Bool = false) async -> Set<String> {
        var gone = Set<String>()
        let ids = uris.filter { $0.hasPrefix("ph://") }.map { String($0.dropFirst(5)) }
        if !ids.isEmpty {
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
            let found = assets.objects(at: IndexSet(integersIn: 0..<assets.count)).map { "ph://" + $0.localIdentifier }
            if !found.isEmpty, (try? await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.deleteAssets(assets) }) != nil {
                gone.formUnion(found)
            }
        }
        for uri in uris where !uri.hasPrefix("ph://") {
            guard let url = folders.resolve(uri) else { continue }
            do {
                if permanently { try FileManager.default.removeItem(at: url) } else { try FileManager.default.trashItem(at: url, resultingItemURL: nil) }
                gone.insert(uri)
            } catch {
                continue
            }
        }
        return gone
    }

    nonisolated private static func rename(_ file: SourceFile, name: String, folders: FolderSource) throws -> SourceFile {
        guard let url = folders.resolve(file.uri) else { throw CocoaError(.fileNoSuchFile) }
        let next = url.deletingLastPathComponent().appendingPathComponent(name)
        try FileManager.default.moveItem(at: url, to: next)
        let modified =
            (try? next.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate).map { Int64($0.timeIntervalSince1970 * 1000) }
            ?? file.modifiedAt
        let uri = String(file.uri.prefix(upTo: file.uri.lastIndex(of: "/")!)) + "/" + name
        return SourceFile(
            uri: uri, name: name, folder: file.folder, mime: file.mime, kind: file.kind, size: file.size, modifiedAt: modified, takenAt: file.takenAt)
    }

    func prepare(model: AppModel) async {
        guard let file, let action, let services = model.services else { return }
        do {
            if action == .delete && file.uri.hasPrefix("ph://") {
                delete(model: model)
            } else if action == .open {
                if file.uri.hasPrefix("ph://") {
                    openURL = try await SharedAsset(id: String(file.uri.dropFirst(5)), video: file.kind == .video).exportURL()
                } else {
                    openURL = services.folders.resolve(file.uri)
                }
                guard openURL != nil else { throw CocoaError(.fileNoSuchFile) }
            } else if action == .info {
                let stored = try await services.retriever.storedFile(uri: file.uri)
                info = await Self.details(file, stored: stored, folders: services.folders, readInside: services.retriever.readInside().contains(file.kind))
            }
        } catch {
            self.error = Words.shared.strings.couldntOpenFolderUnavailable
            self.action = nil
        }
    }

    /// The friendly place a file lives: its folder's own name, or Photos.
    nonisolated static func place(_ file: SourceFile) -> String {
        file.uri.hasPrefix("ph://")
            ? Words.shared.strings.photosAppName : file.folder.split(separator: "/").last.map(String.init) ?? Words.shared.strings.topLevel
    }

    @concurrent private static func details(_ file: SourceFile, stored: Store.StoredFile?, folders: FolderSource, readInside: Bool) async -> FileInfo {
        var info = FileInfo(
            summary: file.size > 0
                ? Words.shared.strings.kindAndSize(kind: Words.shared.kindLabel(kind: file.kind), size: Copy.bytes(file.size))
                : Words.shared.kindLabel(kind: file.kind),
            location: place(file), modified: Date(timeIntervalSince1970: Double(file.modifiedAt) / 1000).formatted(),
            reading: ReadingProgressKt.readingLabel(kind: file.kind, stored: stored, readInside: readInside))
        if file.uri.hasPrefix("ph://"), let asset = PHAsset.fetchAssets(withLocalIdentifiers: [String(file.uri.dropFirst(5))], options: nil).firstObject {
            if let taken = asset.creationDate { info.facts.append(.init(Words.shared.TAKEN, taken.formatted())) }
            info.facts.append(
                .init(
                    Words.shared.strings.dimensionsLabel,
                    Words.shared.strings.dimensions(width: Int32(truncatingIfNeeded: asset.pixelWidth), height: Int32(truncatingIfNeeded: asset.pixelHeight))))
            if asset.duration > 0 { info.facts.append(.init(Words.shared.strings.durationLabel, ViewerCopy.clock(seconds: Int(asset.duration)))) }
        } else if let url = folders.resolve(file.uri) {
            info.path = url.path
            if let image = CGImageSourceCreateWithURL(url as CFURL, nil), let props = CGImageSourceCopyPropertiesAtIndex(image, 0, nil) as? [CFString: Any],
                let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int
            {
                info.facts.append(
                    .init(
                        Words.shared.strings.dimensionsLabel,
                        Words.shared.strings.dimensions(width: Int32(truncatingIfNeeded: w), height: Int32(truncatingIfNeeded: h))))
            } else if file.kind == .audio || file.kind == .video {
                let asset = AVURLAsset(url: url)
                if let duration = try? await asset.load(.duration), duration.seconds.isFinite {
                    info.facts.append(.init(Words.shared.strings.durationLabel, ViewerCopy.clock(seconds: Int(duration.seconds))))
                }
                if let track = try? await asset.loadTracks(withMediaType: .video).first, let size = try? await track.load(.naturalSize) {
                    info.facts.append(
                        .init(Words.shared.strings.dimensionsLabel, Words.shared.strings.dimensions(width: Int32(size.width), height: Int32(size.height))))
                }
            } else if file.kind == .pdf, let pages = PDFDocument(url: url)?.pageCount {
                info.facts.append(.init(Words.shared.strings.pagesLabel, "\(pages)"))
            }
        }
        return info
    }
}

/// What the File info sheet shows. The taken or created date and the measured facts appear only when known.
struct FileInfo: Equatable {
    var summary: String
    var location: String
    var path: String?
    var modified: String
    var facts: [Fact] = []
    var reading: String

    struct Fact: Equatable {
        let label: String
        let value: String
        init(_ label: String, _ value: String) { (self.label, self.value) = (label, value) }
    }
}

/// The one action list, as menu items: long-press, viewer overflow and VoiceOver actions all draw it.
/// `starred` comes from the caller, so a list row does not watch every star.
struct FileActionButtons: View {
    let file: SourceFile
    var starred = false
    var similar: ((SourceFile) -> Void)?
    var showFolder: (() -> Void)?
    var deleted: () -> Void = {}
    @Environment(FileActions.self) private var actions: FileActions?
    @Environment(AppModel.self) private var model: AppModel?

    var body: some View {
        ForEach(FileAction.available(file.uri), id: \.self) { action in
            switch action {
            case .star:
                if let model { row(action) { model.setStarred(file.uri, !starred) } }
            case .share:
                ShareButton(file: file)
            case .open:
                row(action) { actions?.select(action, file: file, deleted: deleted) }
                if let similar {
                    Button {
                        similar(file)
                    } label: {
                        Label(Words.shared.strings.findSimilar, systemImage: "photo.stack")
                    }
                }
            case .delete:
                Divider()
                row(action) { actions?.select(action, file: file, deleted: deleted) }
            case .info:
                if let showFolder { Button(action: showFolder) { Label(Words.shared.strings.showInFolder, systemImage: "folder") } }
                row(action) { actions?.select(action, file: file, deleted: deleted) }
            case .rename:
                row(action) { actions?.select(action, file: file, deleted: deleted) }
            }
        }
    }

    private func row(_ action: FileAction, perform: @escaping () -> Void) -> some View {
        Button(role: action == .delete ? .destructive : nil, action: perform) {
            Label(action.title(starred: starred), systemImage: action.icon(starred: starred))
        }
    }
}

struct FileOverflow: View {
    let file: SourceFile
    @Environment(\.dismiss) private var dismiss
    @Environment(AppModel.self) private var model: AppModel?
    var body: some View {
        Menu {
            FileActionButtons(file: file, starred: model?.starred.contains(file.uri) == true, deleted: { dismiss() })
        } label: {
            Label(Words.shared.strings.moreActions, systemImage: "ellipsis").labelStyle(.iconOnly)
        }
    }
}

/// Files picked on one list for one action, as in Photos. Kept by uri, so they stay picked while the list refreshes.
@MainActor @Observable final class FileSelection {
    var active = false
    var uris: Set<String> = []

    func start(_ uri: String? = nil) {
        active = true
        if let uri { uris.insert(uri) }
    }

    func end() {
        active = false
        uris = []
    }

    func toggle(_ uri: String) {
        if uris.remove(uri) == nil { uris.insert(uri) }
    }

    /// The count stands in for the screen's own title while picking.
    func title(_ title: String) -> String { active ? Words.shared.strings.selectedCount(n: Int64(uris.count)) : title }
}

extension EnvironmentValues {
    /// Set by a list that can select files; its cells toggle instead of opening while it is active.
    @Entry var fileSelection: FileSelection?
    @Entry var showFolder = ShowFolder()
}

/// "Show in folder" for a file; nil where there is no folder to show, or the list already is it.
struct ShowFolder {
    var action: (SourceFile) -> (() -> Void)? = { _ in nil }

    func callAsFunction(_ file: SourceFile) -> (() -> Void)? { action(file) }
}

/// The check on a picked row or cell.
struct SelectionMark: View {
    let on: Bool

    var body: some View {
        Image(systemName: on ? "checkmark.circle.fill" : "circle")
            .font(.title3)
            .foregroundStyle(on ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
    }
}

extension View {
    /// Select in the toolbar, the count as the title, and Share, Star and Delete for what is picked. `files` are the
    /// ones the list shows; once `settled` (loaded), a picked file it no longer shows is let go.
    func selectingFiles(_ selection: FileSelection, files: [SourceFile], settled: Bool, deleted: @escaping (Set<String>) -> Void = { _ in }) -> some View {
        modifier(SelectingFiles(selection: selection, files: files, settled: settled, deleted: deleted))
    }
}

private struct SelectingFiles: ViewModifier {
    let selection: FileSelection
    let files: [SourceFile]
    let settled: Bool
    let deleted: (Set<String>) -> Void
    @Environment(AppModel.self) private var model: AppModel?
    @State private var deleting: [SourceFile]?
    @State private var notDeleted = 0

    func body(content: Content) -> some View {
        let chosen = files.filter { selection.uris.contains($0.uri) }
        content
            .environment(\.fileSelection, selection)
            .toolbar {
                if selection.active {
                    ToolbarItem(placement: .topBarTrailing) { Button(Words.shared.strings.done) { selection.end() }.bold() }
                    ToolbarItemGroup(placement: .bottomBar) { bar(chosen) }
                } else if !files.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) { Button(Words.shared.strings.select) { selection.start() } }
                }
            }
            .confirmationDialog(
                deleting?.count == 1 ? Words.shared.strings.deleteThisFile : Words.shared.strings.deleteFilesAsk(n: Int64(deleting?.count ?? 0)),
                isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible, presenting: deleting
            ) { picked in
                Button(Words.shared.strings.delete_, role: .destructive) { delete(picked) }
            } message: { picked in
                if picked.count == 1 { Text(Words.shared.deleteConfirm(words: Copy.words)) }
            }
            .alert(
                Words.shared.strings.filesWerentDeleted(n: Int64(notDeleted)), isPresented: Binding(get: { notDeleted > 0 }, set: { if !$0 { notDeleted = 0 } })
            ) { Button(Words.shared.strings.ok) {} }
            .onChange(of: settled ? Set(files.map(\.uri)) : nil) { _, shown in
                guard let shown else { return }
                if shown.isEmpty { selection.end() } else { selection.uris.formIntersection(shown) }
            }
    }

    @ViewBuilder private func bar(_ chosen: [SourceFile]) -> some View {
        let shared = chosen.filter { FileAction.available($0.uri).contains(.share) }
        if shared.count == 1, let file = shared.first {
            ShareButton(file: file)
        } else {
            ShareLink(items: shared.compactMap { SharedFile($0, folders: model?.services?.folders) }) { SharePreview($0.name) }
                .disabled(shared.isEmpty)
        }
        Spacer()
        if let model {
            let starred = !chosen.isEmpty && chosen.allSatisfy { model.starred.contains($0.uri) }
            Button(
                starred ? Words.shared.strings.removeFromStarred : Words.shared.strings.addToStarred, systemImage: FileAction.star.icon(starred: starred)
            ) {
                for file in chosen { model.setStarred(file.uri, !starred) }
            }
            .disabled(chosen.isEmpty)
        }
        Spacer()
        let removable = chosen.filter { FileAction.available($0.uri).contains(.delete) }
        Button(FileAction.delete.title(starred: false), systemImage: FileAction.delete.icon(starred: false), role: .destructive) {
            // iOS asks before library photos go, as for one photo.
            if removable.allSatisfy({ $0.uri.hasPrefix("ph://") }) { delete(removable) } else { deleting = removable }
        }
        .disabled(removable.isEmpty)
    }

    private func delete(_ picked: [SourceFile]) {
        guard let model, let services = model.services else { return }
        Task {
            let gone = await FileActions.remove(picked.map(\.uri), folders: services.folders)
            if !gone.isEmpty {
                try? await services.retriever.deletedFiles(uris: Array(gone))
                await model.filesChanged()
            }
            deleted(gone)
            notDeleted = picked.count - gone.count
            if notDeleted == 0 { selection.end() } else { selection.uris.subtract(gone) }
        }
    }
}

struct FileActionsHost: ViewModifier {
    let model: AppModel
    @State private var actions = FileActions()

    private func showing(_ action: FileAction) -> Binding<Bool> {
        Binding(
            get: { actions.action == action && !(action == .delete && actions.file?.uri.hasPrefix("ph://") == true) }, set: { if !$0 { actions.action = nil } })
    }

    func body(content: Content) -> some View {
        content.environment(actions)
            .task(id: actions.action) { await actions.prepare(model: model) }
            .sheet(isPresented: showing(.info)) {
                if let file = actions.file { FileInfoSheet(file: file, info: actions.info) { actions.action = nil } }
            }
            .sheet(isPresented: showing(.open), onDismiss: { actions.openURL = nil }) {
                if let url = actions.openURL { OpenIn(url: url) } else { ProgressView() }
            }
            .alert(Words.shared.strings.renameFile, isPresented: showing(.rename)) {
                TextField(Words.shared.strings.fileNameField, text: $actions.name)
                Button(Words.shared.strings.rename) { actions.rename(model: model) }.disabled(!FileAction.validName(actions.name) || actions.busy)
                Button(Words.shared.CANCEL, role: .cancel) {}
            }
            .confirmationDialog(Words.shared.strings.deleteThisFile, isPresented: showing(.delete), titleVisibility: .visible) {
                Button(Words.shared.strings.delete_, role: .destructive) { actions.delete(model: model) }.disabled(actions.busy)
            } message: {
                Text(Words.shared.deleteConfirm(words: Copy.words))
            }
            .alert(Words.shared.strings.couldntFinish, isPresented: Binding(get: { actions.error != nil }, set: { if !$0 { actions.error = nil } })) {
                Button(Words.shared.strings.ok) { actions.error = nil }
            } message: {
                Text(actions.error ?? "")
            }
    }
}

/// File info: what it is, where it lives, when, and how much of it Retriever has read.
private struct FileInfoSheet: View {
    let file: SourceFile
    let info: FileInfo?
    let done: () -> Void

    var body: some View {
        NavigationStack {
            List {
                if let info {
                    Section {
                        HStack(spacing: 12) {
                            Thumb(file: file, points: 56).frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 8)).accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(file.name).font(.headline).textSelection(.enabled)
                                Text(info.summary).font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                    Section {
                        LabeledContent(Words.shared.strings.locationLabel, value: info.location)
                        if let path = info.path {
                            DisclosureGroup(Words.shared.strings.locationDetails) {
                                Text(path).font(.footnote).textSelection(.enabled)
                                Button(Words.shared.strings.copyPath, systemImage: "doc.on.doc") { UIPasteboard.general.string = path }
                            }
                        }
                        LabeledContent(Words.shared.MODIFIED, value: info.modified)
                        ForEach(info.facts, id: \.label) { LabeledContent($0.label, value: $0.value) }
                        LabeledContent(Words.shared.FOUND_BY, value: info.reading)
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .navigationTitle(Words.shared.strings.fileInfo)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(Words.shared.DONE, action: done) } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// UIKit's document menu supplies installed apps and the native Open In flow.
private struct OpenIn: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> Controller { Controller(url: url) }
    func updateUIViewController(_ controller: Controller, context: Context) {}

    final class Controller: UIViewController, UIDocumentInteractionControllerDelegate {
        let document: UIDocumentInteractionController
        private var presented = false
        init(url: URL) {
            document = UIDocumentInteractionController(url: url)
            super.init(nibName: nil, bundle: nil)
            document.delegate = self
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard !presented else { return }
            presented = true
            if !document.presentOpenInMenu(from: view.bounds, in: view, animated: true) {
                document.presentPreview(animated: true)
            }
        }
        func documentInteractionControllerViewControllerForPreview(_ controller: UIDocumentInteractionController) -> UIViewController { self }
    }
}
