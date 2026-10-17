import Photos
import XCTest
import RetrieverCore
@testable import Retriever

@MainActor
final class CopyTests: XCTestCase {
    func testEtaRoundsToPlainUnits() {
        XCTAssertEqual(Copy.eta(0), "Less than a minute left")
        XCTAssertEqual(Copy.eta(1), "About a minute left")
        XCTAssertEqual(Copy.eta(45), "About 45 minutes left")
        XCTAssertEqual(Copy.eta(75), "About an hour left")
        XCTAssertEqual(Copy.eta(150), "About 3 hours left")
        XCTAssertEqual(Copy.eta(3 * 24 * 60), "About 3 days left")
    }

    func testHomeStatusFollowsTheIndex() {
        func status(_ s: AppModel.Snap?, nameOnly: Int64 = 0) -> HomeStatus? {
            Copy.homeStatus(s, nameOnly: nameOnly)
        }
        XCTAssertNil(status(nil))
        XCTAssertNil(status(AppModel.Snap(setUp: true, running: true)), "first run says it once, on the screen")
        XCTAssertNil(status(AppModel.Snap(setUp: true)), "no files: the empty state alone, never \"caught up\" over it")
        XCTAssertEqual(status(AppModel.Snap(setUp: false))?.title, "Getting ready. For now Retriever finds files by name.")
        XCTAssertEqual(status(AppModel.Snap(setUp: true, indexed: 3, running: true, pendingAtStart: 0))?.phase, .looking)
        XCTAssertEqual(status(AppModel.Snap(setUp: true, indexed: 3, pending: 2, running: true, pendingAtStart: 2, etaMinutes: 150))?.detail, "About 3 hours left")
        XCTAssertEqual(status(AppModel.Snap(setUp: true, indexed: 3, pending: 2, paused: true))?.phase, .paused)
        XCTAssertEqual(
            status(AppModel.Snap(setUp: true, indexed: 3, pending: 2, lastStop: StopReason.done))?.title,
            "Will read the new files soon · 3 of 5 searchable"
        )
        XCTAssertEqual(status(AppModel.Snap(setUp: true, indexed: 4))?.title, "All caught up · 4 files searchable")
        XCTAssertEqual(status(AppModel.Snap(setUp: true, indexed: 4), nameOnly: 1)?.detail, "1 of them by name only")
    }

    func testKindProgressSaysHowFarEachKindIs() {
        XCTAssertEqual(Copy.kindProgress(.pdf, total: 10, pending: 0, reading: true), "Ready")
        XCTAssertEqual(Copy.kindProgress(.photo, total: 10, pending: 4, reading: true), "4 to go")
        XCTAssertEqual(Copy.kindProgress(.photo, total: 10, pending: 10, reading: true), "Waiting")
        XCTAssertNil(Copy.kindProgress(.photo, total: 10, pending: 0, reading: false))
        XCTAssertEqual(Copy.kindProgress(.other, total: 10, pending: 0, reading: false), "Found by name")
        // Switched off in Read inside: found by its name, never "Ready".
        XCTAssertEqual(Copy.kindProgress(.photo, total: 10, pending: 0, reading: true, readInside: false), "Found by name")
    }

    func testStopReasonsReadAsSentences() {
        let device = Copy.device
        XCTAssertEqual(Copy.stop(StopReason.done), "All caught up")
        XCTAssertEqual(Copy.stop(StopReason.hot), "Resting while your \(device) cools down")
        XCTAssertEqual(Copy.stop(StopReason.unplugged), "Waiting for the charger")
        XCTAssertEqual(Copy.stop(StopReason.budget), "Taking a short break")
        XCTAssertEqual(Copy.stop(StopReason.busy), "Paused while you use your \(device)")
        XCTAssertEqual(Copy.stop(StopReason.paused), "Paused")
        XCTAssertNil(Copy.stop(nil))
        // A batch that ended done finished before the newer files arrived.
        XCTAssertEqual(Copy.waiting(StopReason.done), "Will read the new files soon")
        XCTAssertEqual(Copy.waiting(StopReason.hot), Copy.stop(StopReason.hot))
        XCTAssertNil(Copy.waiting(nil))
    }

    func testStatusTitle() {
        typealias Snap = AppModel.Snap
        XCTAssertEqual(Copy.statusTitle(Snap(running: true, pendingAtStart: 0)), "Looking for your files")
        XCTAssertEqual(Copy.statusTitle(Snap(pending: 4, running: true, pendingAtStart: 4)), "Reading your files")
        XCTAssertEqual(Copy.statusTitle(Snap(pending: 4, paused: true)), "Paused")
        XCTAssertEqual(Copy.statusTitle(Snap(pending: 4)), "Reading your files")
        XCTAssertEqual(Copy.statusTitle(Snap(indexed: 4)), "All caught up")
    }

    func testHomeStatusWhileGettingReadyNamesTheStep() {
        let snap = AppModel.Snap(setUp: false, pending: 5)
        let tail = ". For now Retriever finds files by name."
        func title(_ download: ModelDownload.State, _ progress: Double = 0) -> String? {
            Copy.homeStatus(snap, download: download, progress: progress)?.title
        }
        XCTAssertEqual(title(.idle), "Getting ready" + tail)
        XCTAssertEqual(title(.verifying), "Getting ready" + tail)
        XCTAssertEqual(title(.ready), "Getting ready" + tail)
        XCTAssertEqual(title(.waitingForWifi), "Waiting for Wi-Fi to download" + tail)
        XCTAssertEqual(title(.failed("offline")), "The download stopped" + tail)
        let downloading = title(.downloading, 0.34)
        XCTAssertTrue(downloading?.hasPrefix("Downloading · 34") == true, downloading ?? "nil")
        XCTAssertTrue(downloading?.hasSuffix(tail) == true)
    }

    func testPhotosAccessIsANoteBesideTheStatusNotInItsPlace() {
        XCTAssertEqual(Copy.photosNote(.restricted)?.phase, .photosOff)
        XCTAssertEqual(Copy.photosNote(.denied)?.phase, .photosOff)
        let limited = Copy.photosNote(.limited)
        XCTAssertEqual(limited?.phase, .photosLimited)
        XCTAssertEqual(limited?.title, "Retriever sees only the photos you picked")
        XCTAssertNil(Copy.photosNote(.notDetermined))
        XCTAssertNil(Copy.photosNote(.authorized))
        // Reading and Resume stay on Home whatever Photos access is.
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 3, pending: 2, paused: true))?.phase, .paused)
    }

    func testHomeStatusReadingShowsHowFar() {
        let status = Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 5, pending: 5, running: true, pendingAtStart: 5))
        XCTAssertEqual(status?.title, "Reading your files · 5 of 10")
        XCTAssertEqual(status?.progress, 0.5)
    }

    func testEtaBoundaries() {
        XCTAssertEqual(Copy.eta(-3), "Less than a minute left")
        XCTAssertEqual(Copy.eta(0), "Less than a minute left")
        XCTAssertEqual(Copy.eta(1), "About a minute left")
        XCTAssertEqual(Copy.eta(2), "About 2 minutes left")
        XCTAssertEqual(Copy.eta(59), "About 59 minutes left")
        XCTAssertEqual(Copy.eta(60), "About an hour left")
        XCTAssertEqual(Copy.eta(89), "About an hour left")
        XCTAssertEqual(Copy.eta(90), "About 2 hours left")
        XCTAssertEqual(Copy.eta(48 * 60 - 1), "About 48 hours left")
        XCTAssertEqual(Copy.eta(48 * 60), "About 2 days left")
        XCTAssertEqual(Copy.eta(5 * 24 * 60), "About 5 days left")
    }

    func testTuneSummary() {
        XCTAssertEqual(Copy.tuneSummary(Backend.gpu, imageMs: nil), "Using the graphics chip")
        XCTAssertEqual(Copy.tuneSummary(Backend.cpu, imageMs: nil), "Using the processor")
        XCTAssertEqual(Copy.tuneSummary(Backend.cpu, imageMs: 410), "Using the processor · about 0.4 s per photo")
        XCTAssertEqual(Copy.tuneSummary(Backend.gpu, imageMs: 1_250), "Using the graphics chip · about 1.2 s per photo")
    }

    func testAddFolderResult() {
        XCTAssertEqual(Copy.addFolderResult(.added("Work")), "Folder added. Retriever will read what's inside.")
        XCTAssertEqual(Copy.addFolderResult(.overlaps("Work")), "That folder overlaps “Work”, which Retriever already searches.")
        XCTAssertEqual(Copy.addFolderResult(.failed), "iOS wouldn't let Retriever keep that folder. Try another one.")
    }

    func testHomeFactsReadAsPlainLines() {
        XCTAssertEqual(Copy.largeFiles(1), "1 file over 100 MB")
        XCTAssertEqual(Copy.largeFiles(4), "4 files over 100 MB")
        XCTAssertEqual(Copy.largeFiles(1_200), "\(Copy.count(1_200)) files over 100 MB")
        let file = SourceFile(uri: "folder://x/Lease.pdf", name: "Lease.pdf", folder: "Retriever/Download", mime: "application/pdf", kind: .pdf,
                              size: 2_100_000, modifiedAt: Int64(Date().timeIntervalSince1970 * 1000), takenAt: nil)
        XCTAssertEqual(Copy.newFileLine(file), ["Download", Copy.day(file.modifiedAt), Copy.bytes(2_100_000)].joined(separator: " · "))
        // A file at the top of a folder has no parent to name; an empty size is left out rather than shown as "Zero KB".
        let loose = SourceFile(uri: "folder://x/a", name: "a", folder: "Downloads", mime: "text/plain", kind: .other, size: 0, modifiedAt: file.modifiedAt, takenAt: nil)
        XCTAssertTrue(Copy.newFileLine(loose).hasPrefix("Downloads · "))
    }

    func testDeepReadingAndTextOnlyNotes() {
        XCTAssertEqual(Copy.deepReading(1), "1 long file still being read in depth")
        XCTAssertEqual(Copy.deepReading(12), "12 long files still being read in depth")
        XCTAssertTrue(Copy.textOnlyNote(ramGb: 3.4).contains("3 GB of memory"))
        XCTAssertEqual(Copy.continuedSubtitle(AppModel.Snap(indexed: 5, pending: 5)), "5 of 10")
    }

    func testExtentOnlySpeaksForPartialReads() {
        XCTAssertEqual(Copy.extent(ReadExtent.firstPart), "Only the first part of this file was read")
        XCTAssertEqual(Copy.extent(ReadExtent.deepening), "Still reading the rest of this file")
        XCTAssertNil(Copy.extent(nil))
    }

    func testNoJargon() {
        let setting = AppModel.Snap(setUp: false, pending: 5)
        let reading = AppModel.Snap(setUp: true, indexed: 5, pending: 5, running: true, pendingAtStart: 5)
        func home(_ s: AppModel.Snap, _ download: ModelDownload.State = .idle) -> [String?] {
            let status = Copy.homeStatus(s, download: download, progress: 0.5)
            return [status?.title, status?.detail]
        }
        let strings: [String?] = [
            Copy.stop(StopReason.done), Copy.stop(StopReason.hot), Copy.stop(StopReason.unplugged), Copy.stop(StopReason.budget),
            Copy.stop(StopReason.busy), Copy.stop(StopReason.paused), Copy.waiting(StopReason.done),
            Copy.statusTitle(setting), Copy.statusTitle(AppModel.Snap(paused: true)), Copy.statusTitle(AppModel.Snap(running: true)),
            Copy.eta(0), Copy.eta(1), Copy.eta(30), Copy.eta(60), Copy.eta(120), Copy.eta(3_000),
            Copy.tuneSummary(Backend.gpu, imageMs: 900), Copy.tuneSummary(Backend.cpu, imageMs: nil),
            Copy.addFolderResult(.added("Work")), Copy.addFolderResult(.overlaps("Work")), Copy.addFolderResult(.failed),
            Copy.backgroundNote, Copy.largeFiles(4), Copy.deepReading(3),
        ] + home(setting) + home(setting, .downloading) + home(setting, .waitingForWifi) + home(setting, .failed("offline"))
            + home(reading) + home(AppModel.Snap(setUp: true, indexed: 7))
        for string in strings.compactMap({ $0 }) {
            for word in ["model", "embedding", "vector", "gpu"] {
                XCTAssertFalse(string.localizedCaseInsensitiveContains(word), "“\(string)” says \(word)")
            }
        }
    }
}
