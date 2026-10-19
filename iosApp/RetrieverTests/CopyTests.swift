import Photos
import RetrieverCore
import XCTest

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
        XCTAssertEqual(Copy.stop(.hot), "Paused to cool down")
        XCTAssertEqual(Copy.stop(.unplugged), "Waiting to charge")
        XCTAssertEqual(Copy.stop(.budget), "Waiting for iOS")
        XCTAssertEqual(Copy.stop(.paused), "Reading paused")
        XCTAssertEqual(Copy.stop(.powerSave), "Paused for Low Power Mode")
        XCTAssertEqual(Copy.stop(.lowBattery), "Paused for low battery")
        XCTAssertNil(Copy.stop(nil))
    }

    func testStatusTitle() {
        typealias Snap = AppModel.Snap
        XCTAssertEqual(Copy.statusTitle(Snap(running: true)), "Checking for new files…")
        XCTAssertEqual(Copy.statusTitle(Snap(pending: 4, running: true)), "Starting reading…")
        XCTAssertEqual(Copy.statusTitle(Snap(pending: 4, paused: true)), "Reading paused")
        XCTAssertEqual(Copy.statusTitle(Snap(indexed: 4)), "Up to date")
    }

    func testHomeAndStatusUseTheSameCoreCountsWithTrialTails() {
        var snap = AppModel.Snap(setUp: true, indexed: 58, trial: true, waiting: 26_172, tailFiles: 24, partlyRead: 24)
        XCTAssertEqual(Copy.readCount(snap), "58 files read · 24 partly read")
        XCTAssertEqual(snap.readProgress.libraryTotal, 26_230)
        for running in [false, true] {
            snap.running = running
            XCTAssertEqual(Copy.statusTitle(snap), Copy.homeStatus(snap)?.title)
            XCTAssertFalse(snap.isCaughtUp)
        }
        snap.tailFiles = 0
        XCTAssertEqual(Copy.statusTitle(snap), "Your sample is ready to try")
    }

    func testModelSizeUsesNonAdaptiveDownloadFormatter() {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.isAdaptive = false
        XCTAssertEqual(Copy.downloadSize(484_600_000), formatter.string(fromByteCount: 484_600_000))
        XCTAssertEqual(Copy.downloadSize(484_600_000), "485 MB")
    }

    func testHomeStatusWhileGettingReadyNamesTheStep() {
        for state in [ModelDownload.State.idle, .verifying, .ready, .waitingForWifi, .failed("offline")] {
            XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: false), download: state)?.title, "Finishing setup. For now Retriever matches file names.")
        }
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
        let status = Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 5, pending: 5, running: true))
        XCTAssertEqual(status?.title, "Starting reading…", "A batch without an active file is still starting")
        XCTAssertNil(status?.progress, "There is no whole-library percentage")
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
    }

    func testDeepReadingAndTextOnlyNotes() {
        XCTAssertEqual(Copy.deepReading(1), "1 partly read · more meaning search to come")
        XCTAssertEqual(Copy.deepReading(12), "12 partly read · more meaning search to come")
        XCTAssertTrue(Copy.textOnlyNote(ramGb: 3.4).contains("3 GB of memory"))
        XCTAssertEqual(Copy.continuedSubtitle(5), "5 files read this session")
    }

    func testExtentOnlySpeaksForPartialReads() {
        XCTAssertEqual(Copy.extent(ReadExtent.firstPart), "Partly read · only the beginning is included")
        XCTAssertEqual(Copy.extent(ReadExtent.deepening), "Partly read · more meaning search to come")
        XCTAssertNil(Copy.extent(nil))
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
        XCTAssertEqual(Copy.downloadSize(484_622_336), "485 MB")
        XCTAssertEqual(Copy.bytes(484_622_336), "484.6 MB")
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
        XCTAssertEqual(Copy.firstRunReadingLine(files: 79, secondsLeft: 70), "79 files read")
        XCTAssertEqual(Copy.firstRunReadingLine(files: 1, secondsLeft: nil), "1 file read")
        XCTAssertEqual(Copy.firstRunReadyToSearch, "Files you can try")
    }

    func testTimeLeftRoundsToPlainWords() {
        XCTAssertNil(Copy.timeLeft(nil))
        XCTAssertNil(Copy.timeLeft(0))
        XCTAssertEqual(Copy.timeLeft(10), "almost done")
        XCTAssertEqual(Copy.timeLeft(45), "less than a minute left")
        XCTAssertEqual(Copy.timeLeft(89), "about a minute left")
        XCTAssertEqual(Copy.timeLeft(150), "about 3 minutes left")
    }

    func testTryItAndCommitCopy() {
        XCTAssertEqual(Copy.closestIn(79), "Closest in these 79 files")
        XCTAssertEqual(Copy.stillReading(1200), "Still reading 1,200 more")
        XCTAssertEqual(Copy.tryItWords, "Try your own words: something you remember seeing in a photo or a screenshot.")
        XCTAssertEqual(Copy.commitTitle, "Search inside more files")
        XCTAssertEqual(Copy.commitNights, "A large library can take hours of reading, spread across several sessions. You don’t need to wait.")
        // Uncalibrated: no hours, no night count.
        XCTAssertEqual(Copy.commitRest(files: 26_214), "26,214 more files can be read. You can already find them by name.")
        XCTAssertEqual(Copy.commitRest(files: nil), "You can already find your files by name.")
        let figure = /\d+(,\d+)?( to \d+)? (hours?|minutes?|nights?|days?)/
        for text in [Copy.commitRest(files: 26_214), Copy.commitRest(files: nil), Copy.commitNights] {
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
        func home(_ s: AppModel.Snap, _ download: ModelDownload.State = .idle) -> [String?] {
            let status = Copy.homeStatus(s, download: download, progress: 0.5)
            return [status?.title, status?.detail]
        }
        let strings: [String?] =
            [
                Copy.stop(StopReason.done), Copy.stop(StopReason.hot), Copy.stop(StopReason.unplugged), Copy.stop(StopReason.budget),
                Copy.stop(StopReason.busy), Copy.stop(StopReason.paused), Copy.waiting(StopReason.done),
                Copy.statusTitle(setting), Copy.statusTitle(AppModel.Snap(paused: true)), Copy.statusTitle(AppModel.Snap(running: true)),
                Copy.eta(0), Copy.eta(1), Copy.eta(30), Copy.eta(60), Copy.eta(120), Copy.eta(3_000),
                Copy.tuneSummary(Backend.gpu, imageMs: 900), Copy.tuneSummary(Backend.cpu, imageMs: nil),
                Copy.addFolderResult(.added("Work")), Copy.addFolderResult(.overlaps("Work")), Copy.addFolderResult(.failed),
                Copy.backgroundNote, Copy.largeFiles(4), Copy.deepReading(3),
                Copy.firstRunWelcomeBody, Copy.firstRunTime(size: nil), Copy.firstRunTextOnlyTime, Copy.firstRunMobileDataMessage(size: nil),
                Copy.firstRunPhotosNote, Copy.firstRunPhotosOff, Copy.firstRunLimitedNote, Copy.firstRunNothingAllowed,
                Copy.limitedPhotosFootnote(photos: 4, folders: 1), Copy.firstRunTuneTitle, Copy.firstRunTuneFailed,
                Copy.firstRunWaitingForWifi, Copy.firstRunNamesNow, Copy.firstRunReadyToSearch, Copy.firstRunStaysSearchable,
                Copy.firstRunReadingLine(files: 79, secondsLeft: 70),
            ] + home(setting) + home(setting, .downloading) + home(setting, .waitingForWifi) + home(setting, .failed("offline"))
            + home(reading) + home(AppModel.Snap(setUp: true, indexed: 7))
        for string in strings.compactMap({ $0 }) {
            for word in ["model", "embedding", "vector", "gpu"] {
                XCTAssertFalse(string.localizedCaseInsensitiveContains(word), "“\(string)” says \(word)")
            }
        }
    }
    func testFailedMeaningSearchKeepsThePartialCoverageExplanation() {
        XCTAssertEqual(Copy.extent(.meaningLimited), "Partly read · couldn’t finish meaning search")
        XCTAssertEqual(Copy.extent(.firstPartMeaningLimited), "Partly read · only the beginning is included; couldn’t finish meaning search")
    }

}
