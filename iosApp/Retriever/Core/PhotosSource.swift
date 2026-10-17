import Foundation
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

    func changes(since: String?) -> Changes {
        guard isReady else {
            // Nothing readable: an empty scope, so an earlier index isn't wiped by a revoked or pending prompt.
            return Changes(upserted: [], removed: [], nextToken: since ?? "", isFullScan: since == nil, present: nil, scope: [])
        }
        // Taken before reading, so changes made during the scan show up next time.
        let next = Self.encode(PHPhotoLibrary.shared().currentChangeToken)
        if let since, let token = Self.decode(since), let changes = try? incremental(since: token, next: next) {
            return changes
        }
        let all = PHAsset.fetchAssets(with: nil)
        var files: [SourceFile] = []
        files.reserveCapacity(all.count)
        all.enumerateObjects { asset, _, _ in
            autoreleasepool {
                if let file = Self.file(asset) { files.append(file) }
            }
        }
        return Changes(upserted: files, removed: [], nextToken: next, isFullScan: true, present: nil, scope: nil)
    }

    /// Throws when the token has expired; the caller then rescans.
    private func incremental(since: PHPersistentChangeToken, next: String) throws -> Changes {
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
                    if let file = Self.file(asset) { files.append(file) }
                }
            }
            removed.formUnion(upsert.subtracting(seen)) // gone again before this sync
        }
        return Changes(upserted: files, removed: removed.map { "ph://\($0)" }, nextToken: next, isFullScan: false, present: nil, scope: nil)
    }

    private static func file(_ asset: PHAsset) -> SourceFile? {
        let kind: Kind
        let folder: String
        let label: String
        switch asset.mediaType {
        case .image where asset.mediaSubtypes.contains(.photoScreenshot):
            (kind, folder, label) = (.screenshot, "Photos/Screenshots", "Screenshot")
        case .image:
            (kind, folder, label) = (.photo, "Photos/Camera", "Photo")
        case .video:
            (kind, folder, label) = (.video, "Photos/Videos", "Video")
        default:
            return nil
        }
        // PHAsset fields only: a per-asset resource lookup makes a 50k-photo scan crawl. creationDate, not
        // modificationDate, which iOS bumps on favoriting, album changes and its own analysis; any change re-reads
        // the photo. ponytail: an edit isn't re-read either; compare pixel size or hasAdjustments if that matters.
        let date = asset.creationDate ?? Date(timeIntervalSince1970: 0)
        return SourceFile(
            uri: "ph://\(asset.localIdentifier)",
            // People never see Photos file names; the same name on every scan keeps the photo from being re-read.
            name: "\(label) \(nameFormat.string(from: date))",
            folder: folder,
            mime: kind == .video ? "video/quicktime" : "image/jpeg",
            kind: kind,
            size: 0, // not known without the resource lookup; ph:// files are never content-hashed
            modifiedAt: Int64(date.timeIntervalSince1970 * 1000)
        )
    }

    /// Fixed, not the person's locale: a locale change must not rename every photo.
    private static let nameFormat: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd 'at' HH.mm.ss"
        return f
    }()

    private static func encode(_ token: PHPersistentChangeToken) -> String {
        (try? NSKeyedArchiver.archivedData(withRootObject: token, requiringSecureCoding: true))?.base64EncodedString() ?? ""
    }

    private static func decode(_ s: String) -> PHPersistentChangeToken? {
        guard let data = Data(base64Encoded: s) else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: PHPersistentChangeToken.self, from: data)
    }
}
