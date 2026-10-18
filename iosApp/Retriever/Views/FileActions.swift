import AVFoundation
import ImageIO
import Photos
@preconcurrency import RetrieverCore
import SwiftUI
import UniformTypeIdentifiers

/// The same actions serve result menus, Browse, Recently read and every viewer.
enum FileAction: String, CaseIterable {
    case info = "Info", share = "Share", open = "Open with", rename = "Rename", delete = "Delete"

    static func available(_ uri: String) -> [Self] {
        if uri.hasPrefix("ph://") { return [.info, .share, .open, .delete] }
        if uri.hasPrefix("folder://") { return allCases }
        return [.info]
    }

    static func validName(_ name: String) -> Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && name != "." && name != ".."
            && !name.contains("/") && !name.contains("\\") && name.rangeOfCharacter(from: .controlCharacters) == nil
    }
}

@MainActor @Observable final class FileActions {
    var file: SourceFile?
    var action: FileAction?
    var name = ""
    var info: [(String, String)] = []
    var error: String?
    var busy = false
    var openURL: URL?
    var deleted: () -> Void = {}

    func select(_ action: FileAction, file: SourceFile, deleted: @escaping () -> Void = {}) {
        self.file = file
        self.action = action
        self.name = file.name
        self.deleted = deleted
        info = []
    }

    func perform(model: AppModel, work: @escaping () async throws -> Void) {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do { try await work() } catch {
                self.action = nil
                self.error = "Couldn't finish that. The file may have moved or access may have changed."
            }
        }
    }

    func rename(model: AppModel) {
        guard let file, let services = model.services, FileAction.validName(name) else { return }
        let wanted = name
        perform(model: model) {
            guard let changed = try await services.retriever.renameFile(uri: file.uri, operation: { old in
                try? Self.rename(old, name: wanted, folders: services.folders)
            }) else { throw CocoaError(.fileWriteUnknown) }
            self.action = nil
            await model.filesChanged()
            if changed.uri != file.uri { self.deleted() }
        }
    }

    func delete(model: AppModel) {
        guard let file, let services = model.services else { return }
        perform(model: model) {
            if file.uri.hasPrefix("ph://") {
                let assets = PHAsset.fetchAssets(withLocalIdentifiers: [String(file.uri.dropFirst(5))], options: nil)
                guard assets.count == 1 else { throw CocoaError(.fileNoSuchFile) }
                try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.deleteAssets(assets) }
            } else {
                guard let url = services.folders.resolve(file.uri) else { throw CocoaError(.fileNoSuchFile) }
                try await Self.trash(url)
            }
            try await services.retriever.deletedFiles(uris: [file.uri])
            self.action = nil
            await model.filesChanged()
            self.deleted()
        }
    }

    @concurrent private static func trash(_ url: URL) async throws {
        try FileManager.default.trashItem(at: url, resultingItemURL: nil)
    }

    nonisolated private static func rename(_ file: SourceFile, name: String, folders: FolderSource) throws -> SourceFile {
        guard let url = folders.resolve(file.uri) else { throw CocoaError(.fileNoSuchFile) }
        let next = url.deletingLastPathComponent().appendingPathComponent(name)
        try FileManager.default.moveItem(at: url, to: next)
        let modified = (try? next.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate).map { Int64($0.timeIntervalSince1970 * 1000) } ?? file.modifiedAt
        let uri = String(file.uri.prefix(upTo: file.uri.lastIndex(of: "/")!)) + "/" + name
        return SourceFile(uri: uri, name: name, folder: file.folder, mime: file.mime, kind: file.kind, size: file.size, modifiedAt: modified, takenAt: file.takenAt)
    }

    func prepare(model: AppModel) async {
        guard let file, let action, let services = model.services else { return }
        do {
            if action == .delete && file.uri.hasPrefix("ph://") {
                delete(model: model)
            } else if action == .open {
                if file.uri.hasPrefix("ph://") {
                    openURL = try await SharedAsset(id: String(file.uri.dropFirst(5)), video: file.kind == .video).exportURL()
                } else { openURL = services.folders.resolve(file.uri) }
                guard openURL != nil else { throw CocoaError(.fileNoSuchFile) }
            } else if action == .info {
                let stored = try await services.retriever.storedFile(uri: file.uri)
                info = await Self.details(file, stored: stored, folders: services.folders, readInside: services.retriever.readInside().contains(file.kind))
            }
        } catch { self.error = "Couldn't open this file. Its folder may be unavailable."; self.action = nil }
    }

    @concurrent private static func details(_ file: SourceFile, stored: Store.StoredFile?, folders: FolderSource, readInside: Bool) async -> [(String, String)] {
        var rows = [("Name", file.name), ("Folder", file.folder), ("Kind", Format.kind(file.kind)), ("Size", ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file)), ("Modified", Date(timeIntervalSince1970: Double(file.modifiedAt) / 1000).formatted())]
        if let taken = file.takenAt { rows.append(("Taken", Date(timeIntervalSince1970: Double(taken.int64Value) / 1000).formatted())) }
        rows.append(("Read status", !readInside || stored?.state != .indexed || file.kind == .other ? "Not yet read" : stored?.partial == true ? "Partly read" : "Read fully"))
        if file.uri.hasPrefix("ph://"), let asset = PHAsset.fetchAssets(withLocalIdentifiers: [String(file.uri.dropFirst(5))], options: nil).firstObject {
            rows.append(("Dimensions", "\(asset.pixelWidth) × \(asset.pixelHeight)"))
            if asset.duration > 0 { rows.append(("Duration", "\(Int(asset.duration)) seconds")) }
        } else if let url = folders.resolve(file.uri) {
            if let image = CGImageSourceCreateWithURL(url as CFURL, nil), let props = CGImageSourceCopyPropertiesAtIndex(image, 0, nil) as? [CFString: Any], let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int {
                rows.append(("Dimensions", "\(w) × \(h)"))
            } else if file.kind == .audio || file.kind == .video {
                let asset = AVURLAsset(url: url)
                if let duration = try? await asset.load(.duration), duration.seconds.isFinite { rows.append(("Duration", "\(Int(duration.seconds)) seconds")) }
                if let track = try? await asset.loadTracks(withMediaType: .video).first, let size = try? await track.load(.naturalSize) { rows.append(("Dimensions", "\(Int(size.width)) × \(Int(size.height))")) }
            }
        }
        return rows
    }
}

struct FileActionButtons: View {
    let file: SourceFile
    var deleted: () -> Void = {}
    @Environment(FileActions.self) private var actions: FileActions?

    var body: some View {
        ForEach(FileAction.available(file.uri), id: \.self) { action in
            if action == .share { ShareButton(file: file) } else {
                Button(action.rawValue, role: action == .delete ? .destructive : nil) { actions?.select(action, file: file, deleted: deleted) }
            }
        }
    }
}

struct FileOverflow: View {
    let file: SourceFile
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        Menu { FileActionButtons(file: file, deleted: { dismiss() }) } label: { Label("File actions", systemImage: "ellipsis").labelStyle(.iconOnly) }
    }
}

struct FileActionsHost: ViewModifier {
    let model: AppModel
    @State private var actions = FileActions()

    private func showing(_ action: FileAction) -> Binding<Bool> {
        Binding(get: { actions.action == action && !(action == .delete && actions.file?.uri.hasPrefix("ph://") == true) }, set: { if !$0 { actions.action = nil } })
    }

    func body(content: Content) -> some View {
        content.environment(actions)
            .task(id: actions.action) { await actions.prepare(model: model) }
            .sheet(isPresented: showing(.info)) {
                NavigationStack {
                    List {
                        ForEach(actions.info.indices, id: \.self) { index in
                            LabeledContent(actions.info[index].0, value: actions.info[index].1)
                        }
                    }.navigationTitle("File info")
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { actions.action = nil } } }
                }
            }
            .sheet(isPresented: showing(.open), onDismiss: { actions.openURL = nil }) {
                if let url = actions.openURL { OpenIn(url: url) } else { ProgressView() }
            }
            .alert("Rename file", isPresented: showing(.rename)) {
                TextField("Name", text: $actions.name)
                Button("Rename") { actions.rename(model: model) }.disabled(!FileAction.validName(actions.name) || actions.busy)
                Button("Cancel", role: .cancel) {}
            }
            .confirmationDialog("Delete \(actions.file?.name ?? "this file")?", isPresented: showing(.delete), titleVisibility: .visible) {
                Button("Delete", role: .destructive) { actions.delete(model: model) }.disabled(actions.busy)
            } message: { Text("This removes the file from its library or folder.") }
            .alert("Couldn't finish", isPresented: Binding(get: { actions.error != nil }, set: { if !$0 { actions.error = nil } })) {
                Button("OK") { actions.error = nil }
            } message: { Text(actions.error ?? "") }
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
        init(url: URL) { document = UIDocumentInteractionController(url: url); super.init(nibName: nil, bundle: nil); document.delegate = self }
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
