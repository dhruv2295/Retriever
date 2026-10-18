import Photos
@preconcurrency import RetrieverCore
import SwiftUI

// MARK: Fixtures

/// Pinned for screenshots and previews: each case is a whole-app state, no index needed.
enum AppFixture {
    case firstRun, photosLimited, photosDenied
    case downloading(Double)
    case waitingForWifi, downloadFailed, tuning
    case reading(done: Int, total: Int)
    case caughtUp, paused
    case skipped(Int)
    case noAccess
    case tailFiles(Int)
    case textOnly(ramGb: Double)
}

extension AppModel {
    convenience init(fixture: AppFixture) {
        var snap = Snap(
            setUp: true, byKind: [.photo: 2_140, .screenshot: 812, .pdf: 64, .doc: 31, .video: 95], indexed: 3_142,
            lastStop: .done, downloadBytes: ModelVariant.m440.generic.sizeBytes)
        var photos = PHAuthorizationStatus.authorized
        var download: (ModelDownload.State, Double) = (.ready, 1)
        var tune = Tune.done(Copy.tuneSummary(.cpu, imageMs: 410))
        var started = true
        var skipped: [SkippedFile] = []
        func gettingReady() {
            snap.setUp = false
            snap.indexed = 0
            snap.pending = 3_142
            snap.lastStop = nil
            tune = .idle
        }
        switch fixture {
        case .firstRun:
            gettingReady()
            (photos, started, download) = (.notDetermined, false, (.idle, 0))
        case .photosLimited:
            gettingReady()
            photos = .limited
            download = (.downloading, 0.12)
        case .photosDenied:
            gettingReady()
            (photos, download) = (.denied, (.downloading, 0.05))
        case .downloading(let p):
            gettingReady()
            download = (.downloading, p)
        case .waitingForWifi:
            gettingReady()
            download = (.waitingForWifi, 0)
        case .downloadFailed:
            gettingReady()
            download = (.failed("The download stopped: The Internet connection appears to be offline. It picks up where it left off when you try again."), 0.41)
        case .tuning:
            gettingReady()
            tune = .running
        case .reading(let done, let total):
            (snap.indexed, snap.pending, snap.running, snap.lastStop) = (done, total - done, true, nil)
            (snap.processed, snap.pendingAtStart, snap.filesPerMinute) = (120, total - done + 120, 38)
            snap.etaMinutes = Int((Double(total - done) / 38).rounded(.up))
            snap.pendingByKind = [.photo: total - done]
            snap.current = "Photo 2026-09-14 at 18.42.07"
            snap.tailFiles = 3
        case .caughtUp:
            break
        case .tailFiles(let n):
            snap.tailFiles = n
        case .textOnly(let ramGb):
            (snap.textOnly, snap.ramGb, snap.byKind) = (true, ramGb, [.pdf: 64, .doc: 31, .photo: 0])
            (download, tune) = ((.idle, 0), .idle)
        case .paused:
            (snap.indexed, snap.pending, snap.paused, snap.lastStop) = (1_904, 1_238, true, .paused)
            snap.pendingByKind = [.photo: 1_238]
        case .skipped(let n):
            (snap.indexed, snap.skipped) = (3_142 - n, n)
            skipped = (0..<min(n, 6)).map { i in
                SkippedFile(
                    id: Int64(i), name: ["Scan 2019.pdf", "Locked statement.pdf", "IMG_0412.HEIC", "notes.pages", "Clip 3.mov", "export.zip"][i],
                    folder: ["Retriever", "Downloads", "Photos/Camera", "Retriever/Work", "Photos/Videos", "Downloads"][i])
            }
        case .noAccess:
            (photos, snap.byKind, snap.indexed) = (.denied, [.pdf: 12, .doc: 9], 21)
        }
        self.init(snap: snap, photos: photos, download: download, tune: tune, started: started, skipped: skipped)
        func file(_ name: String, _ kind: Kind, _ folder: String, size: Int64, daysAgo: Double) -> SourceFile {
            SourceFile(
                uri: "folder://fixture/\(name)", name: name, folder: folder, mime: "application/pdf", kind: kind, size: size,
                modifiedAt: Int64((Date().timeIntervalSince1970 - daysAgo * 86400) * 1000), takenAt: nil)
        }
        let docs = [
            file("Lease agreement 2026.pdf", .pdf, "Retriever/Download", size: 2_100_000, daysAgo: 0),
            file("Electricity bill October.pdf", .pdf, "Retriever/Download", size: 340_000, daysAgo: 1),
            file("Trip itinerary.docx", .doc, "Retriever/Documents", size: 88_000, daysAgo: 14),
        ]
        home = HomeFacts(
            newMedia: [],
            newShots: (1...6).map { file("Screenshot \($0).png", .screenshot, "Photos/Screenshots", size: 400_000, daysAgo: Double($0)) },
            newDocs: docs,
            starred: [docs[0], docs[2]], starredCount: 2,
            folders: 12, largeCount: 4, largeBytes: 6_400_000_000, nameOnly: 0)
        storage = Storage(index: 412_000_000, model: ModelVariant.m440.generic.sizeBytes, cache: 38_000_000)
        starred = ["folder://fixture/Lease agreement 2026.pdf", "folder://fixture/Trip itinerary.docx"]
        suggestions = ["wifi password", "boarding pass", "electricity bill"]
    }
}
