import Foundation
import Photos
import RetrieverCore
import UniformTypeIdentifiers
import os

/// The photo library as ph://<localIdentifier> files. Incremental syncs come from PhotoKit's persistent change
/// history, which also reports deletions. Nothing here downloads from iCloud.
final class PhotosSource: NSObject, NativeFileSource, Sendable {
    var status: PHAuthorizationStatus { PHPhotoLibrary.authorizationStatus(for: .readWrite) }

    /// Authorized, or limited to the photos the person picked.
    var isReady: Bool { status == .authorized || status == .limited }

    @discardableResult
    func requestAccess() async -> PHAuthorizationStatus {
        await PHPhotoLibrary.requestAuthorization(for: .readWrite)
    }

    /// Assets seen, names and types looked up, and the lookups that had to list the asset's resources (none on iOS 27,
    /// which prefetches the name), by the last [changes] call, for the log line and the self-test.
    var lastAssets: Int { counts.withLock { $0.assets } }
    var lastLookups: Int { counts.withLock { $0.lookups } }
    var lastResourceLookups: Int { counts.withLock { $0.resources } }
    private let counts = OSAllocatedUnfairLock(initialState: (assets: 0, lookups: 0, resources: 0))

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
        counts.withLock { $0 = (0, 0, 0) }
        // Taken before reading, so changes made during the scan show up next time.
        let next = Self.encode(PHPhotoLibrary.shared().currentChangeToken)
        let result: Changes
        if let since, let token = Self.decode(since), let changes = try? incremental(since: token, next: next, known: known) {
            result = changes
        } else {
            let all = PHAsset.fetchAssets(with: Self.fetchOptions())
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
        Logger(subsystem: "app.retriever", category: "photos").info(
            "photo identity: looked up \(self.lastLookups) of \(self.lastAssets) assets (\(self.lastResourceLookups) through their resources) in \(ms) ms")
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
            let found = PHAsset.fetchAssets(withLocalIdentifiers: Array(upsert), options: Self.fetchOptions())
            var seen = Set<String>()
            found.enumerateObjects { asset, _, _ in
                autoreleasepool {
                    seen.insert(asset.localIdentifier)
                    if let file = self.file(asset, known, recheck: true) { files.append(file) }
                }
            }
            removed.formUnion(upsert.subtracting(seen))  // gone again before this sync
        }
        return Changes(upserted: files, removed: removed.map { "ph://\($0)" }, nextToken: next, isFullScan: false, present: nil, scope: nil)
    }

    /// `recheck`: PhotoKit reported a change, which may be an edit or its undo: look up the current rendition.
    private func file(_ asset: PHAsset, _ known: KnownFiles, recheck: Bool) -> SourceFile? {
        counts.withLock { $0.assets += 1 }
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
        // Identity is the creation date, or the modification date once edited. iOS also bumps modificationDate on
        // favoriting, album changes and its own analysis, so only an edited photo follows it: an edit or its undo
        // flips hasAdjustments, a re-edit moves the date. ponytail: favoriting an edited photo re-reads it once.
        // Size stays 0: PhotoKit has no public byte size without reading the file.
        let date = asset.hasAdjustments ? asset.modificationDate ?? asset.creationDate : asset.creationDate
        let stamp = Int64((date ?? Date(timeIntervalSince1970: 0)).timeIntervalSince1970 * 1000)
        let uri = "ph://\(asset.localIdentifier)"
        var name: String?
        var mime: String?
        if !recheck, let old = known.file(uri: uri), old.modifiedAt == stamp {
            (name, mime) = (old.name, old.mime)
        } else {
            counts.withLock { $0.lookups += 1 }
            // The asset's own metadata where iOS has it (the name prefetched with the asset), else its resources.
            if #available(iOS 27.0, *) { name = asset.extendedMetadata.originalFilename }
            if #available(iOS 26.0, *) { mime = asset.contentType.preferredMIMEType }
            if name == nil || mime == nil {
                counts.withLock { $0.resources += 1 }
                let (original, current) = Self.renditions(asset)
                name = name ?? original.flatMap(Self.filename)
                mime = mime ?? current.flatMap(Self.mimeType)
            }
        }
        let taken = asset.creationDate.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000)) }
        return SourceFile(
            uri: uri, name: name ?? String(asset.localIdentifier.prefix(36)), folder: folder,
            mime: mime ?? (kind == .video ? "video/quicktime" : "image/jpeg"), kind: kind, size: 0, modifiedAt: stamp, takenAt: taken)
    }

    /// One read of the library: Lifecycle's observer follows changes, so this fetch needs no change details. On iOS 27
    /// each asset's name comes with it, rather than costing a fetch per asset.
    private static func fetchOptions() -> PHFetchOptions {
        let options = PHFetchOptions()
        options.wantsIncrementalChangeDetails = false
        if #available(iOS 27.0, *) { options.prefetchAssetExtendedMetadata = true }
        return options
    }

    /// The resource's file name (`originalFilename` became `filename` in iOS 27).
    static func filename(_ resource: PHAssetResource) -> String? {
        if #available(iOS 27.0, *) { return resource.filename }
        return resource.originalFilename
    }

    private static func mimeType(_ resource: PHAssetResource) -> String? {
        if #available(iOS 26.0, *) { return resource.contentType.preferredMIMEType }
        return UTType(resource.uniformTypeIdentifier)?.preferredMIMEType
    }

    /// The camera's file, and what the person sees now: the edited rendition when there is one.
    static func renditions(_ asset: PHAsset) -> (original: PHAssetResource?, current: PHAssetResource?) {
        let resources = PHAssetResource.assetResources(for: asset)
        let original = resources.first { $0.type == .photo || $0.type == .video } ?? resources.first
        guard asset.hasAdjustments else { return (original, original) }
        return (original, resources.first { $0.type == .fullSizePhoto || $0.type == .fullSizeVideo } ?? original)
    }

    private static func encode(_ token: PHPersistentChangeToken) -> String {
        (try? NSKeyedArchiver.archivedData(withRootObject: token, requiringSecureCoding: true))?.base64EncodedString() ?? ""
    }

    private static func decode(_ s: String) -> PHPersistentChangeToken? {
        guard let data = Data(base64Encoded: s) else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: PHPersistentChangeToken.self, from: data)
    }
}
