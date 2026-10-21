import Photos
import RetrieverCore
import UIKit
import XCTest

@testable import Retriever

@MainActor
final class CopyTests: XCTestCase {
    func testHomeStatusFollowsTheIndex() {
        XCTAssertNil(Copy.homeStatus(nil))
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(accessChosen: false))?.title, "Finish setting up")
        // Reading needs no download: without the model, the status is reading's own.
        let done = "All done. Retriever has processed everything it can"
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: false, indexed: 4))?.title, done)
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 3, pending: 2, paused: true))?.phase, .paused)
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 3, pending: 2))?.title, "Taking a short break")
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 4))?.title, done)
    }

    func testTrialCardNeverCallsPlannedFilesNew() {
        var snap = AppModel.Snap(setUp: true, indexed: 178, pending: 12, trial: true)
        XCTAssertEqual(Copy.homeStatus(snap)?.title, "Taking a short break")
        snap.paused = true
        XCTAssertEqual(Copy.homeStatus(snap)?.title, "Paused")
    }

    func testFinishedTrialAndScanningHaveAccurateStatus() {
        let trial = AppModel.Snap(setUp: true, indexed: 24, trial: true, waiting: 200)
        XCTAssertEqual(Copy.homeStatus(trial)?.title, "Only names can be found for now", "the demo is done and the choice waits")
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 24, running: true))?.title, "Checking for new files…")
    }

    func testStatusTitle() {
        typealias Snap = AppModel.Snap
        XCTAssertEqual(Copy.reading(Snap(running: true)).title, "Checking for new files…")
        XCTAssertEqual(Copy.reading(Snap(pending: 4, running: true)).title, "Processing your files")
        XCTAssertEqual(Copy.reading(Snap(pending: 4, running: true)).count, "0 of 4 files processed")
        XCTAssertEqual(Copy.reading(Snap(pending: 4, paused: true)).title, "Paused")
        XCTAssertEqual(Copy.reading(Snap(indexed: 4)).title, "All done. Retriever has processed everything it can")
    }

    func testHomeAndStatusUseTheSameCoreCountsWithTrialTails() {
        var snap = AppModel.Snap(setUp: true, indexed: 58, trial: true, waiting: 26_172, tailFiles: 24, partlyRead: 24)
        XCTAssertEqual(Words.shared.readCount(read: Int64(snap.indexed), partlyRead: Int64(snap.partlyRead)), "58 files processed · 24 processed in part")
        XCTAssertEqual(snap.readProgress.libraryTotal, 26_230)
        for running in [false, true] {
            snap.running = running
            XCTAssertEqual(Copy.reading(snap).title, Copy.homeStatus(snap)?.title)
            XCTAssertFalse(snap.isCaughtUp)
        }
        snap.tailFiles = 0
        XCTAssertEqual(Copy.reading(snap).title, "Only names can be found for now")
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
        XCTAssertEqual(status?.title, "Processing your files")
        XCTAssertEqual(status?.count, "5 of 10 files processed", "A batch without an active file already says how far")
        XCTAssertEqual(status?.done?.int64Value, 5, "the bar is the count's numbers")
        XCTAssertEqual(status?.total?.int64Value, 10)
        XCTAssertEqual(status?.phase, .reading)
    }

    func testTuneSummary() {
        XCTAssertEqual(Copy.tuneSummary(Backend.gpu, imageMs: nil), "Using the graphics chip")
        XCTAssertEqual(Copy.tuneSummary(Backend.cpu, imageMs: nil), "Using the processor")
        XCTAssertEqual(Copy.tuneSummary(Backend.cpu, imageMs: 410), "Using the processor · about 0.4 s per photo")
        XCTAssertEqual(Copy.tuneSummary(Backend.gpu, imageMs: 1_250), "Using the graphics chip · about 1.2 s per photo")
    }

    func testAddFolderResult() {
        XCTAssertEqual(Copy.addFolderResult(.added("Work")), "Folder added. Retriever will process what's inside.")
        XCTAssertEqual(Copy.addFolderResult(.overlaps("Work")), "That folder overlaps “Work”, which Retriever already searches.")
        XCTAssertEqual(Copy.addFolderResult(.failed), "iOS wouldn't let Retriever keep that folder. Try another one.")
    }

    func testContinuedReadSaysNoSession() {
        XCTAssertEqual(Copy.continuedSubtitle(5), "5 files processed so far")
    }

    func testSetupDownloadSizeUsesWholeMegabytes() {
        XCTAssertEqual(Copy.downloadSize(484_622_336), "485\u{00A0}MB")
        XCTAssertEqual(Copy.bytes(484_622_336), "484.6\u{00A0}MB")
    }

    /// Setup's sentences are core's; the shell adds only "iPhone" for "this device".
    func testSetupSaysWhatComesNextAndNeverDownload() {
        XCTAssertEqual(Words.shared.GO_TO_HOME, "Go to Home")
        XCTAssertEqual(
            Words.shared.demoSearchScope(done: 12, finished: false, words: Copy.words),
            "Finds any file on your iPhone by its name. Looks inside the 12 demo files processed so far.")
        XCTAssertEqual(Copy.photosAccess(.limited), Words.shared.SELECTED_PHOTOS)
        XCTAssertEqual(Copy.photosAccess(.authorized), Words.shared.ALL_PHOTOS)
        XCTAssertNil(Copy.photosAccess(.notDetermined))
        XCTAssertTrue(Words.shared.nothingSent(words: Copy.words).contains("your \(Copy.device)"))
        XCTAssertTrue(Words.shared.welcomeNext(words: Copy.words).contains("here on your \(Copy.device)"))
        let texts = [
            Words.shared.WELCOME_TITLE, Words.shared.WELCOME_BODY, Words.shared.iosAccessNote(words: Copy.words), Words.shared.demoTitle(files: 30),
            Words.shared.welcomeNext(words: Copy.words), Words.shared.welcomeAfterDemo(words: Copy.words),
        ]
        for text in texts {
            XCTAssertFalse(text.localizedCaseInsensitiveContains("download"), text)
            XCTAssertFalse(text.localizedCaseInsensitiveContains("skip"), text)
        }
    }

    /// The demo's lines as the iPhone says them; the rest of the wording is core's (WordsTest).
    func testTheDemoNamesTheDevice() {
        XCTAssertEqual(Words.shared.demoTitle(files: 30), "A short demo on 30 of your recent files")
        XCTAssertEqual(
            Words.shared.demoDone(rest: KotlinLong(value: 25_763), words: Copy.words), "The demo is done. Next: the other 25,763 files on your iPhone.")
        XCTAssertEqual(Words.shared.chooseTitle(words: Copy.words), "Now, the rest of your iPhone")
        XCTAssertTrue(Words.shared.chargingAndNowBody(words: Copy.words).hasSuffix("Keep Retriever open."))
    }

    /// The welcome's iOS ask and the pile's count line.
    func testWelcomeAskAndDemoCountWords() {
        XCTAssertEqual(Words.shared.SEARCH_ALL_PHOTOS, "Search all your photos")
        XCTAssertEqual(Words.shared.ADD_FOLDERS_FROM_FILES, "Add folders from Files")
        XCTAssertEqual(
            Words.shared.iosAccessNote(words: Copy.words),
            "iOS lets Retriever see your photos and the folders you pick in Files.")
        XCTAssertEqual(Words.shared.DEMO_PICKING, "Picking your recent files…")
        XCTAssertEqual(Words.shared.readOf(done: 12, total: 30), "12 of 30 files processed", "always the total: it is fixed at picking")
        XCTAssertEqual(Words.shared.READ, "Found by what’s inside")
        XCTAssertEqual(Words.shared.demoNote(secondsLeft: nil), "About a minute.", "no time before it is measured")
        XCTAssertEqual(Words.shared.demoNote(secondsLeft: 170), "About 3 minutes left.")
        XCTAssertNil(ReadingStatus(phase: .caughtUp, title: "All done", detail: nil, action: nil, count: nil, done: nil, total: nil).pause, "not a pause")
    }

    func testCommitCopyHasNoFigures() {
        let figure = /\d+(,\d+)?( to \d+)? (hours?|minutes?|nights?|days?)/
        for text in [Words.shared.chooseBody(rest: KotlinLong(value: 25_763)), Words.shared.choosePace(words: Copy.words), Copy.backgroundNote] {
            // "Process for 15 minutes" is the action's name, not an estimate of how long processing takes.
            XCTAssertNil(text.replacingOccurrences(of: Words.shared.READ_FOR_15, with: "").firstMatch(of: figure), text)
        }
    }

    /// Each Home state the fixtures pin reaches the line it is for: the download, access and Read for 15 minutes come
    /// from the app's own state, the rest from core's decision.
    func testTheStatusLineCoversEveryHomeState() throws {
        func line(_ fixture: AppFixture) throws -> ReadingStatus {
            let model = AppModel(fixture: fixture)
            return try XCTUnwrap(model.statusLine(model.live))
        }
        // A live count, not a percent: 1,204 processed of 5,310, the bar the same numbers.
        let count = "1,204 of 5,310 files processed"
        XCTAssertEqual(try line(.readingWhileCharging).title, "Processing while you charge")
        XCTAssertEqual(try line(.readingWhileCharging).count, count)
        XCTAssertEqual(try line(.readingWhileCharging).done?.int64Value, 1_204)
        XCTAssertEqual(try line(.readingWhileCharging).total?.int64Value, 5_310)
        XCTAssertEqual(try line(.unplugged).title, "Waits until you charge")
        XCTAssertEqual(try line(.unplugged).count, count)
        XCTAssertEqual(try line(.unplugged).phase, .charging)
        XCTAssertEqual(try line(.unplugged).action, .readFor15)
        XCTAssertEqual(try line(.chargingUp).title, "Charging up first")
        XCTAssertEqual(try line(.readNow).title, "Processing for 15 minutes · 12 min left")
        XCTAssertEqual(try line(.readNow).action, .stop)
        XCTAssertEqual(try line(.readNow).detail, "Uses some battery. Keep Retriever open.")
        XCTAssertEqual(try line(.readNow).count, count)
        let downloading = try line(.downloading(0.34))
        XCTAssertEqual(downloading.phase, .downloading)
        XCTAssertEqual(downloading.done?.int64Value, 34)
        XCTAssertEqual(downloading.total?.int64Value, 100)
        XCTAssertNil(downloading.count)
        XCTAssertEqual(downloading.action, .stop)
        XCTAssertEqual(try line(.waitingForWifi).phase, .downloadStopped)
        XCTAssertEqual(try line(.tuning).phase, .tuning)
        XCTAssertEqual(try line(.meaningPass).phase, .meaning)
        XCTAssertEqual(try line(.meaningPass).title, "Adding search by meaning")
        XCTAssertEqual(try line(.meaningPass).count, "1,938 of 3,142 files have search by meaning")
        XCTAssertEqual(try line(.firstDay).phase, .notNow)
        XCTAssertEqual(try line(.firstDay).action, .chooseWhen)
        // The demo's 30 carry over to the whole phone's count.
        XCTAssertEqual(try line(.notNow).title, "Only names can be found for now")
        XCTAssertEqual(try line(.notNow).count, "30 of 25,793 files processed")
        XCTAssertEqual(try line(.notNow).action, .chooseWhen)
        let counting = try line(.counting)
        XCTAssertEqual(counting.title, "Counting your files…")
        XCTAssertNil(counting.count, "no count or bar until the files are all counted")
        XCTAssertNil(counting.done)
        XCTAssertEqual(try line(.caughtUp).action, .dismiss)
        XCTAssertEqual(try line(.lostAccess).phase, .noAccess)
        XCTAssertNotEqual(try line(.noAccess).phase, .noAccess, "a folder still gives files, so Photos keeps its own note")
    }

    func testNoJargon() {
        let setting = AppModel.Snap(setUp: false, pending: 5, accessChosen: false)
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
                Copy.backgroundNote, Copy.screenKeptOn, Words.shared.tuneFailed(words: Copy.words), Words.shared.DEMO_HINT,
                Words.shared.WELCOME_BODY, Words.shared.upgradeBody(size: "388 MB", words: Copy.words),
                Words.shared.tuneTitle(words: Copy.words), Words.shared.tuneBody(words: Copy.words),
            ] + home(setting) + home(reading) + home(AppModel.Snap(setUp: true, indexed: 7))
        for string in strings.compactMap({ $0 }) {
            for word in ["model", "embedding", "vector", "gpu"] {
                XCTAssertFalse(string.localizedCaseInsensitiveContains(word), "“\(string)” says \(word)")
            }
        }
    }

}
