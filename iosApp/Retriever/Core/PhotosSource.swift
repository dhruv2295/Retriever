import Foundation
import os
import Photos
import RetrieverCore
import UniformTypeIdentifiers

/// The photo library as ph://<localIdentifier> files. Incremental syncs come from PhotoKit's persistent change
/// history, which also reports deletions. Nothing here downloads from iCloud.
final class PhotosSource: NSObject, NativeFileSource {
    var status: PHAuthorizationStatus { PHPhotoLibrary.authorizationStatus(for: .readWrite) }

    /// Authorized, or limited to the photos the person picked.
    var isReady: Bool { status == .authorized || status == .limited }

    @discardableResult
    func requestAccess() async -> PHAuthorizationStatus {
        await PHPhotoLibrary.requestAuthorization(for: .readWrite)
    }

    /// Assets seen and resource lookups made by the last [changes] call, for the log line and the self-test.
    private(set) var lastAssets = 0
    private(set) var lastLookups = 0

    func changes(since: String?, known: KnownFiles) -> Changes {
        switch status {
        case .denied, .restricted:
            // Access turned off: forget every photo, so what the person withheld stops turning up in results. The
            // empty token rescans when access comes back.
            return Changes(upserted: [], removed: [], nextToken: "", isFullScan: true, present: nil, scope: ["ph://"])
        case .notDetermined:
            // Not asked yet: an empty scope, so a pending prompt never wipes the index.
            return Changes(upserted: [], removed: [], nextToken: since ?? "", isFullScan: since == nil, present: nil, scope: [])
        default:
            break
        }
        let started = Date()
        lastLookups = 0
        lastAssets = 0
        // Taken before reading, so changes made during the scan show up next time.
        let next = Self.encode(PHPhotoLibrary.shared().currentChangeToken)
        let result: Changes
        if let since, let token = Self.decode(since), let changes = try? incremental(since: token, next: next, known: known) {
            result = changes
        } else {
            let all = PHAsset.fetchAssets(with: nil)
            var files: [SourceFile] = []
            files.reserveCapacity(all.count)
            all.enumerateObjects { asset, _, _ in
                autoreleasepool {
                    if let file = self.file(asset, known, recheck: false) { files.append(file) }
                }
            }
            result = Changes(upserted: files, removed: [], nextToken: next, isFullScan: true, present: nil, scope: nil)
        }
        let ms = Int(Date().timeIntervalSince(started) * 1000)
        Logger(subsystem: "app.retriever", category: "photos").info("photo identity: looked up \(self.lastLookups) of \(self.lastAssets) assets in \(ms) ms")
        return result
    }

    /// Throws when the token has expired; the caller then rescans.
    private func incremental(since: PHPersistentChangeToken, next: String, known: KnownFiles) throws -> Changes {
        var upsert = Set<String>()
        var removed = Set<String>()
        for change in try PHPhotoLibrary.shared().fetchPersistentChanges(since: since) {
            let details = try change.changeDetails(for: .asset)
            let touched = details.insertedLocalIdentifiers.union(details.updatedLocalIdentifiers)
            upsert.formUnion(touched)
            removed.subtract(touched)
            upsert.subtract(details.deletedLocalIdentifiers)
            removed.formUnion(details.deletedLocalIdentifiers)
        }
        var files: [SourceFile] = []
        if !upsert.isEmpty {
            let found = PHAsset.fetchAssets(withLocalIdentifiers: Array(upsert), options: nil)
            var seen = Set<String>()
            found.enumerateObjects { asset, _, _ in
                autoreleasepool {
                    seen.insert(asset.localIdentifier)
                    if let file = self.file(asset, known, recheck: true) { files.append(file) }
                }
            }
            removed.formUnion(upsert.subtracting(seen)) // gone again before this sync
        }
        return Changes(upserted: files, removed: removed.map { "ph://\($0)" }, nextToken: next, isFullScan: false, present: nil, scope: nil)
    }

    /// `recheck`: PhotoKit reported a change, which may be an edit or its undo: look up the current rendition's size.
    private func file(_ asset: PHAsset, _ known: KnownFiles, recheck: Bool) -> SourceFile? {
        lastAssets += 1
        let kind: Kind
        let folder: String
        switch asset.mediaType {
        case .image where asset.mediaSubtypes.contains(.photoScreenshot):
            (kind, folder) = (.screenshot, "Photos/Screenshots")
        case .image:
            (kind, folder) = (.photo, "Photos/Camera")
        case .video:
            (kind, folder) = (.video, "Photos/Videos")
        default:
            return nil
        }
        // Not modificationDate: iOS also bumps it on favoriting, album changes and its own analysis, none of which
        // change what the photo shows. An edit shows as a new size of the current rendition instead.
        let stamp = Int64((asset.creationDate ?? Date(timeIntervalSince1970: 0)).timeIntervalSince1970 * 1000)
        let uri = "ph://\(asset.localIdentifier)"
        var name: String, size: Int64, mime: String
        if !recheck, let old = known.file(uri: uri), old.modifiedAt == stamp, old.size > 0 {
            (name, size, mime) = (old.name, old.size, old.mime)
        } else {
            lastLookups += 1
            let (original, current) = Self.renditions(asset)
            name = original?.originalFilename ?? String(asset.localIdentifier.prefix(36))
            size = current.map(Self.fileSize) ?? 0
            mime = current.flatMap { UTType($0.uniformTypeIdentifier)?.preferredMIMEType } ?? (kind == .video ? "video/quicktime" : "image/jpeg")
        }
        return SourceFile(uri: uri, name: name, folder: folder, mime: mime, kind: kind, size: size, modifiedAt: stamp)
    }

    /// The camera's file, and what the person sees now: the edited rendition when there is one.
    static func renditions(_ asset: PHAsset) -> (original: PHAssetResource?, current: PHAssetResource?) {
        let resources = PHAssetResource.assetResources(for: asset)
        let original = resources.first { $0.type == .photo || $0.type == .video } ?? resources.first
        guard asset.hasAdjustments else { return (original, original) }
        return (original, resources.first { $0.type == .fullSizePhoto || $0.type == .fullSizeVideo } ?? original)
    }

    /// Private key, the only size source that doesn't read the file; an unknown KVC key raises, hence the guard.
    static func fileSize(_ resource: PHAssetResource) -> Int64 {
        guard resource.responds(to: Selector(("fileSize"))) else { return 0 }
        return (resource.value(forKey: "fileSize") as? NSNumber)?.int64Value ?? 0
    }

    private static func encode(_ token: PHPersistentChangeToken) -> String {
        (try? NSKeyedArchiver.archivedData(withRootObject: token, requiringSecureCoding: true))?.base64EncodedString() ?? ""
    }

    private static func decode(_ s: String) -> PHPersistentChangeToken? {
        guard let data = Data(base64Encoded: s) else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: PHPersistentChangeToken.self, from: data)
    }
}
