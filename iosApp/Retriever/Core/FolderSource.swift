import Foundation
import RetrieverCore
import UniformTypeIdentifiers

/// The app's Documents folder (people drop files there through the Files app) plus folders picked in the Files
/// app, kept as bookmarks. URIs are folder://<folderId>/<path inside the folder>. Unchecked Sendable: the mutable
/// caches are behind `lock`, and the bookmarks live in UserDefaults, which is thread-safe.
final class FolderSource: NSObject, NativeFileSource, @unchecked Sendable {
    struct Folder: Identifiable {
        let id: String
        let name: String
    }

    static let documentsId = "documents"
    private static let bookmarksKey = "folderBookmarks"
    private static let removedKey = "removedFolders"
    private static let keys: [URLResourceKey] = [
        .isRegularFileKey, .fileSizeKey, .contentModificationDateKey, .contentTypeKey, .ubiquitousItemDownloadingStatusKey,
    ]

    private let documents: URL
    private let defaults: UserDefaults
    private let lock = NSLock()
    private var resolved: [String: URL] = [:]
    private var stale: Set<String> = []

    init(documents: URL, defaults: UserDefaults = .standard) {
        self.documents = documents
        self.defaults = defaults
    }

    private var bookmarks: [String: Data] {
        get { defaults.dictionary(forKey: Self.bookmarksKey) as? [String: Data] ?? [:] }
        set { defaults.set(newValue, forKey: Self.bookmarksKey) }
    }

    /// Documents first, then picked folders by name.
    var folders: [Folder] {
        [Folder(id: Self.documentsId, name: "Retriever")]
            + bookmarks.keys.compactMap { id in
                root(id).map { Folder(id: id, name: $0.lastPathComponent) }
            }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    /// Picked folders whose bookmark no longer resolves; the person needs to remove them and pick them again.
    var unreachable: [Folder] {
        _ = folders
        let saved = bookmarks
        return lock.withLock { stale }.compactMap { id in
            saved[id].map { Folder(id: id, name: URL.resourceValues(forKeys: [.nameKey], fromBookmarkData: $0)?.name ?? "A folder") }
        }
    }

    /// Removed folders, kept in scan scope so their files are forgotten even while another folder can't be reached.
    // ponytail: never pruned; one short id per removed folder.
    private var removed: [String] {
        get { defaults.stringArray(forKey: Self.removedKey) ?? [] }
        set { defaults.set(newValue, forKey: Self.removedKey) }
    }

    enum AddResult: Equatable {
        case added(String)
        /// The pick is, holds or sits inside this folder already searched; adding it would list files twice.
        case overlaps(String)
        /// iOS won't bookmark it.
        case failed
    }

    /// Saves a folder from a document picker.
    @discardableResult
    func addFolder(_ url: URL) -> AddResult {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let path = Self.canonical(url)
        for folder in folders {
            guard let base = root(folder.id) else { continue }
            let other = Self.canonical(base)
            if path == other || path.hasPrefix(other + "/") || other.hasPrefix(path + "/") { return .overlaps(folder.name) }
        }
        guard let data = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else { return .failed }
        let id = UUID().uuidString
        bookmarks[id] = data
        return .added(id)
    }

    private static func canonical(_ url: URL) -> String {
        url.standardizedFileURL.resolvingSymlinksInPath().path
    }

    func removeFolder(_ id: String) {
        bookmarks[id] = nil
        removed.append(id)
        lock.withLock {
            if let url = resolved.removeValue(forKey: id) { url.stopAccessingSecurityScopedResource() }
            stale.remove(id)
        }
    }

    /// The file behind a folder:// URI, for the extractor.
    func resolve(_ uri: String) -> URL? {
        guard uri.hasPrefix("folder://") else { return nil }
        let rest = uri.dropFirst("folder://".count)
        guard let slash = rest.firstIndex(of: "/") else { return nil }
        let path = String(rest[rest.index(after: slash)...])
        guard !path.split(separator: "/").contains(".."), let base = root(String(rest[..<slash])) else { return nil }
        return base.appendingPathComponent(path)
    }

    /// A full scan of every folder that can be reached now. Only those are in scope, so a folder whose bookmark
    /// fails or whose provider is offline keeps its index; iCloud placeholders are reported as present, not read.
    func changes(since: String?, known: KnownFiles) -> Changes {
        var files: [SourceFile] = []
        var placeholders = Set<String>()
        var scanned: [String] = []
        for folder in folders {
            guard let base = root(folder.id), (try? base.checkResourceIsReachable()) == true,
                let (found, evicted) = scan(folderId: folder.id, label: folder.name, base: base)
            else { continue }
            files += found
            placeholders.formUnion(evicted)
            scanned.append(folder.id)
        }
        // All reachable: the whole prefix is in scope. Otherwise the reachable folders and the removed ones.
        let scope: Set<String> = scanned.count == bookmarks.count + 1 ? ["folder://"] : Set((scanned + removed).map { "folder://\($0)/" })
        // ponytail: always a full scan; fine for user-picked folders, add an mtime index if folders get huge.
        return Changes(upserted: files, removed: [], nextToken: "", isFullScan: true, present: placeholders, scope: scope)
    }

    /// nil when the folder can't be walked (a dead file provider): it then stays out of scope.
    private func scan(folderId: String, label: String, base: URL) -> ([SourceFile], [String])? {
        // Paths relative to the folder, from the walker itself: no per-file symlink resolving (on a device /var is
        // /private/var, and the walker may report either). A symlinked file is left out: isRegularFile is the link's.
        guard
            let walker = FileManager.default.enumerator(
                at: base, includingPropertiesForKeys: Self.keys, options: [.skipsHiddenFiles, .skipsPackageDescendants, .producesRelativePathURLs])
        else { return nil }
        var out: [SourceFile] = []
        var evicted: [String] = []
        for case let url as URL in walker {
            autoreleasepool {
                guard let v = try? url.resourceValues(forKeys: Set(Self.keys)), v.isRegularFile == true else { return }
                let relative = url.relativePath
                let uri = "folder://\(folderId)/\(relative)"
                // iCloud placeholders: reading one would start a download, but an indexed one stays searchable.
                if let status = v.ubiquitousItemDownloadingStatus, status != .current { return evicted.append(uri) }
                let size = Int64(v.fileSize ?? 0)
                guard size > 0 else { return }
                let name = url.lastPathComponent
                let dir = relative.contains("/") ? String(relative[..<relative.lastIndex(of: "/")!]) + "/" : ""
                if FileRules.shared.shouldSkip(relativePath: dir, name: name) { return }
                let mime = v.contentType?.preferredMIMEType ?? FileRules.shared.mimeForExtension(ext: url.pathExtension) ?? "application/octet-stream"
                out.append(
                    SourceFile(
                        uri: uri,
                        name: name,
                        folder: (label + "/" + dir).trimmingCharacters(in: CharacterSet(charactersIn: "/")),
                        mime: mime,
                        kind: FileRules.shared.classify(mime: mime, relativePath: dir, name: name),
                        size: size,
                        modifiedAt: Int64((v.contentModificationDate ?? Date(timeIntervalSince1970: 0)).timeIntervalSince1970 * 1000),
                        takenAt: nil
                    ))
            }
        }
        return (out, evicted)
    }

    /// Resolves a bookmark once and keeps its security scope open for the app's lifetime.
    private func root(_ id: String) -> URL? {
        if id == Self.documentsId { return documents }
        if let url = lock.withLock({ resolved[id] }) { return url }
        guard let data = bookmarks[id] else { return nil }
        var isStale = false
        guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &isStale),
            url.startAccessingSecurityScopedResource()
        else {
            lock.withLock { _ = stale.insert(id) }
            return nil
        }
        if isStale, let fresh = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
            bookmarks[id] = fresh
        }
        return lock.withLock {
            if let existing = resolved[id] {
                url.stopAccessingSecurityScopedResource()
                return existing
            }
            stale.remove(id)
            resolved[id] = url
            return url
        }
    }
}
