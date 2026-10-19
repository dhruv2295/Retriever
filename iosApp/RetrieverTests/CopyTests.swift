import Photos
import RetrieverCore
import XCTest

@testable import Retriever

@MainActor
final class CopyTests: XCTestCase {
    func testHomeStatusFollowsTheIndex() {
        XCTAssertNil(Copy.homeStatus(nil))
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: false))?.title, "Finishing setup. For now Retriever matches file names.")
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 3, pending: 2, paused: true))?.phase, .paused)
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 3, pending: 2))?.title, "Waiting for iOS")
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 4))?.title, "Up to date")
    }

    func testTrialCardNeverCallsPlannedFilesNew() {
        var snap = AppModel.Snap(setUp: true, indexed: 178, pending: 12, trial: true)
        XCTAssertEqual(Copy.homeStatus(snap)?.title, "Waiting for iOS")
        snap.paused = true
        XCTAssertEqual(Copy.homeStatus(snap)?.title, "Reading paused")
    }

    func testFinishedTrialAndScanningHaveAccurateStatus() {
        let trial = AppModel.Snap(setUp: true, indexed: 24, trial: true, waiting: 200)
        XCTAssertEqual(Copy.homeStatus(trial)?.title, "Your sample is ready to try")
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 24, running: true))?.title, "Checking for new files…")
    }

    func testStatusTitle() {
        typealias Snap = AppModel.Snap
        XCTAssertEqual(Copy.reading(Snap(running: true)).title, "Checking for new files…")
        XCTAssertEqual(Copy.reading(Snap(pending: 4, running: true)).title, "Starting reading…")
        XCTAssertEqual(Copy.reading(Snap(pending: 4, paused: true)).title, "Reading paused")
        XCTAssertEqual(Copy.reading(Snap(indexed: 4)).title, "Up to date")
    }

    func testHomeAndStatusUseTheSameCoreCountsWithTrialTails() {
        var snap = AppModel.Snap(setUp: true, indexed: 58, trial: true, waiting: 26_172, tailFiles: 24, partlyRead: 24)
        XCTAssertEqual(Words.shared.readCount(read: Int64(snap.indexed), partlyRead: Int64(snap.partlyRead)), "58 files read · 24 partly read")
        XCTAssertEqual(snap.readProgress.libraryTotal, 26_230)
        for running in [false, true] {
            snap.running = running
            XCTAssertEqual(Copy.reading(snap).title, Copy.homeStatus(snap)?.title)
            XCTAssertFalse(snap.isCaughtUp)
        }
        snap.tailFiles = 0
        XCTAssertEqual(Copy.reading(snap).title, "Your sample is ready to try")
    }

    func testModelSizeUsesNonAdaptiveDownloadFormatter() {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.isAdaptive = false
        XCTAssertEqual(Copy.downloadSize(484_600_000), Words.shared.keepTogether(size: formatter.string(fromByteCount: 484_600_000)))
        XCTAssertEqual(Copy.downloadSize(484_600_000), "485\u{00A0}MB")
    }

    func testPhotosAccessIsANoteBesideTheStatusNotInItsPlace() {
        XCTAssertEqual(Copy.photosNote(.restricted)?.off, true)
        XCTAssertEqual(Copy.photosNote(.denied)?.off, true)
        let limited = Copy.photosNote(.limited)
        XCTAssertEqual(limited?.off, false)
        XCTAssertEqual(limited?.title, "Retriever sees only the photos you picked")
        XCTAssertNil(Copy.photosNote(.notDetermined))
        XCTAssertNil(Copy.photosNote(.authorized))
        // Reading and Resume stay on Home whatever Photos access is.
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 3, pending: 2, paused: true))?.phase, .paused)
    }

    func testHomeStatusReadingShowsHowFar() {
        let status = Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 5, pending: 5, running: true))
        XCTAssertEqual(status?.title, "Starting reading…", "A batch without an active file is still starting")
        XCTAssertEqual(status?.phase, .reading)
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

    func testDeepReadingAndTextOnlyNotes() {
        XCTAssertEqual(Copy.deepReading(1), "1 partly read · more meaning search to come")
        XCTAssertEqual(Copy.deepReading(12), "12 partly read · more meaning search to come")
        XCTAssertEqual(Copy.continuedSubtitle(5), "5 files read this session")
    }

    func testFirstRunWelcomeCopy() {
        XCTAssertEqual(Copy.firstRunTry, "Try it on a few files first")
        XCTAssertEqual(Copy.firstRunReadAll, "Read everything now")
        XCTAssertEqual(
            Copy.firstRunTime(size: "485 MB"),
            "A one-time 485 MB download, then a few minutes on a handful of your files. Reading everything takes hours, and you choose when."
        )
        XCTAssertTrue(Copy.firstRunTime(size: nil).hasPrefix("A one-time download, then "))
        XCTAssertEqual(Copy.firstRunMobileDataMessage(size: "485 MB"), "The download is 485 MB. On Wi-Fi it won't use your data.")
    }

    func testSetupDownloadSizeUsesWholeMegabytes() {
        XCTAssertEqual(Copy.downloadSize(484_622_336), "485\u{00A0}MB")
        XCTAssertEqual(Copy.bytes(484_622_336), "484.6\u{00A0}MB")
    }

    func testWelcomeExamplesMatchWhatThePhoneReads() {
        XCTAssertEqual(
            Copy.firstRunExamples(textOnly: false), ["“sunset at the beach”", "“the Wi-Fi password screenshot”", "“lease that mentions the deposit”"])
        XCTAssertEqual(Copy.firstRunExamples(textOnly: true), ["“the Wi-Fi password screenshot”", "“lease that mentions the deposit”"])
        XCTAssertEqual(Copy.firstRunTextOnlyTime, "Ready in a few minutes on a handful of your files. Reading everything takes hours, and you choose when.")
    }

    func testLimitedPhotosFootnote() {
        XCTAssertEqual(Copy.limitedPhotosFootnote(photos: 12, folders: 0), "Retriever can see the 12 photos you chose. You can change this in Settings.")
        XCTAssertEqual(
            Copy.limitedPhotosFootnote(photos: 1_204, folders: 2),
            "Retriever can see the \(Copy.count(1_204)) photos you chose and 2 folders. You can change this in Settings.")
        XCTAssertEqual(
            Copy.limitedPhotosFootnote(photos: 1, folders: 1), "Retriever can see the photo you chose and 1 folder. You can change this in Settings.")
    }

    func testFirstRunDownloadAndReadingLines() {
        XCTAssertEqual(Copy.firstRunDownloadLine(0.34), "Downloading · 34 %")
        XCTAssertEqual(Copy.firstRunDownloadA11y(0.34), "Downloading, 34 percent")
        XCTAssertEqual(Copy.firstRunWaitingTitle, "Getting Retriever ready to read")
        XCTAssertEqual(Copy.firstRunTuneTitle, "Getting to know this \(Copy.device)")
        XCTAssertEqual(Copy.firstRunSearchByName, "Search by name")
        XCTAssertEqual(Copy.firstRunReadyToSearch, "Files you can try")
    }

    func testTryItAndCommitCopy() {
        XCTAssertEqual(Copy.tryItWords, "Try your own words: something you remember seeing in a photo or a screenshot.")
        XCTAssertEqual(Copy.commitTitle, "Search inside more files")
        XCTAssertEqual(Copy.commitNights, "A large library can take hours of reading, spread across several sessions. You don’t need to wait.")
        let figure = /\d+(,\d+)?( to \d+)? (hours?|minutes?|nights?|days?)/
        for text in [Words.shared.moreFiles(files: KotlinLong(value: 26_214)), Words.shared.moreFiles(files: nil), Copy.commitNights] {
            XCTAssertNil(text.firstMatch(of: figure), text)
        }
    }

    func testCommitButtonsFollowTheOS() {
        XCTAssertEqual(Copy.commitWhileCharging, "While charging")
        XCTAssertEqual(Copy.commitNotYet, "Not now")
        XCTAssertEqual(Copy.commitReadNow(continued: true), "Read now for 15 minutes")
        XCTAssertEqual(Copy.commitReadNow(continued: false), "Read now for 15 minutes")
    }

    func testNoJargon() {
        let setting = AppModel.Snap(setUp: false, pending: 5)
        let reading = AppModel.Snap(setUp: true, indexed: 5, pending: 5, running: true, pendingAtStart: 5)
        func home(_ s: AppModel.Snap) -> [String?] {
            let status = Copy.homeStatus(s)
            return [status?.title, status?.detail]
        }
        let strings: [String?] =
            [
                Copy.reading(setting).title, Copy.reading(AppModel.Snap(paused: true)).title, Copy.reading(AppModel.Snap(running: true)).title,
                Copy.tuneSummary(Backend.gpu, imageMs: 900), Copy.tuneSummary(Backend.cpu, imageMs: nil),
                Copy.addFolderResult(.added("Work")), Copy.addFolderResult(.overlaps("Work")), Copy.addFolderResult(.failed),
                Copy.backgroundNote, Copy.deepReading(3),
                Copy.firstRunWelcomeBody, Copy.firstRunTime(size: nil), Copy.firstRunTextOnlyTime, Copy.firstRunMobileDataMessage(size: nil),
                Copy.firstRunPhotosNote, Copy.firstRunPhotosOff, Copy.firstRunLimitedNote, Copy.firstRunNothingAllowed,
                Copy.limitedPhotosFootnote(photos: 4, folders: 1), Copy.firstRunTuneTitle, Copy.firstRunTuneFailed,
                Copy.firstRunWaitingForWifi, Copy.firstRunNamesNow, Copy.firstRunReadyToSearch, Copy.firstRunStaysSearchable,
            ] + home(setting) + home(reading) + home(AppModel.Snap(setUp: true, indexed: 7))
        for string in strings.compactMap({ $0 }) {
            for word in ["model", "embedding", "vector", "gpu"] {
                XCTAssertFalse(string.localizedCaseInsensitiveContains(word), "“\(string)” says \(word)")
            }
        }
    }

}
