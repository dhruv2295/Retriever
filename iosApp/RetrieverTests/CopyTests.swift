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
        let done = "All done. Retriever has read everything it can"
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
        XCTAssertEqual(Copy.homeStatus(trial)?.title, "Ready to try")
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: true, indexed: 24, running: true))?.title, "Checking for new files…")
    }

    func testStatusTitle() {
        typealias Snap = AppModel.Snap
        XCTAssertEqual(Copy.reading(Snap(running: true)).title, "Checking for new files…")
        XCTAssertEqual(Copy.reading(Snap(pending: 4, running: true)).title, "Reading your files · 0% read")
        XCTAssertEqual(Copy.reading(Snap(pending: 4, paused: true)).title, "Paused")
        XCTAssertEqual(Copy.reading(Snap(indexed: 4)).title, "All done. Retriever has read everything it can")
    }

    func testHomeAndStatusUseTheSameCoreCountsWithTrialTails() {
        var snap = AppModel.Snap(setUp: true, indexed: 58, trial: true, waiting: 26_172, tailFiles: 24, partlyRead: 24)
        XCTAssertEqual(Words.shared.readCount(read: Int64(snap.indexed), partlyRead: Int64(snap.partlyRead)), "58 files read · 24 read in part")
        XCTAssertEqual(snap.readProgress.libraryTotal, 26_230)
        for running in [false, true] {
            snap.running = running
            XCTAssertEqual(Copy.reading(snap).title, Copy.homeStatus(snap)?.title)
            XCTAssertFalse(snap.isCaughtUp)
        }
        snap.tailFiles = 0
        XCTAssertEqual(Copy.reading(snap).title, "Ready to try")
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
        XCTAssertEqual(status?.title, "Reading your files · 50% read", "A batch without an active file already says how far")
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

    func testContinuedReadSaysNoSession() {
        XCTAssertEqual(Copy.continuedSubtitle(5), "5 files read so far")
    }

    func testSetupDownloadSizeUsesWholeMegabytes() {
        XCTAssertEqual(Copy.downloadSize(484_622_336), "485\u{00A0}MB")
        XCTAssertEqual(Copy.bytes(484_622_336), "484.6\u{00A0}MB")
    }

    /// Setup's sentences are core's; the shell adds only "iPhone" for "this device".
    func testSetupSaysGoToHomeAndNeverSkipOrDownload() {
        XCTAssertEqual(Words.shared.GO_TO_HOME, "Skip for now")
        XCTAssertEqual(Words.shared.searchingTry(read: 54), "Searching every file’s name, and inside the 54 files read so far.")
        XCTAssertEqual(Copy.photosAccess(.limited), Words.shared.SELECTED_PHOTOS)
        XCTAssertEqual(Copy.photosAccess(.authorized), Words.shared.ALL_PHOTOS)
        XCTAssertNil(Copy.photosAccess(.notDetermined))
        let welcome = Words.shared.WELCOME_BODY
        XCTAssertTrue(Words.shared.nothingSent(words: Copy.words).contains("your \(Copy.device)"))
        for text in [Words.shared.WELCOME_TITLE, welcome, Words.shared.iosAccessNote(words: Copy.words), Words.shared.SAMPLE_TITLE] {
            XCTAssertFalse(text.localizedCaseInsensitiveContains("download"), text)
            XCTAssertFalse(text.localizedCaseInsensitiveContains("skip"), text)
        }
    }

    /// Try names the next step; "Try searching inside" left "inside what?".
    func testTryWords() {
        XCTAssertEqual(Words.shared.TRY_TITLE, "Find a file you know")
        XCTAssertEqual(Words.shared.TRY_BODY, "Type something you remember from it, like a name, a place or an amount, or tap a suggestion.")
        XCTAssertEqual(Words.shared.TRY_WITHOUT_SUGGESTIONS, "Open one of these files, then type something you remember from it.")
        XCTAssertEqual(Words.shared.FILES_TO_TRY, "Files you can try")
    }

    /// The navigation bar's large title, on one line on the narrowest iPhone (375 pt) less its 20 pt margins.
    func testTryTitleFitsALargeTitle() {
        let width = (Words.shared.TRY_TITLE as NSString).size(withAttributes: [.font: UIFont.systemFont(ofSize: 34, weight: .bold)]).width
        XCTAssertLessThanOrEqual(width, 375 - 40)
    }

    /// The welcome's iOS ask and the pile's count line.
    func testWelcomeAskAndSampleCountWords() {
        XCTAssertEqual(Words.shared.SEARCH_ALL_PHOTOS, "Search all your photos")
        XCTAssertEqual(Words.shared.ADD_FOLDERS_FROM_FILES, "Add folders from Files")
        XCTAssertEqual(
            Words.shared.iosAccessNote(words: Copy.words),
            "iOS lets Retriever read your photos and the folders you pick in Files.")
        XCTAssertEqual(
            Words.shared.SAMPLE_PICKING, "Trying a few of your recent files to show you how it works. About a minute. Nothing is changed or sent.")
        XCTAssertEqual(Words.shared.readOf(read: 12, planned: 40, pending: 0), "12 of 40 files read")
        XCTAssertEqual(Words.shared.readOf(read: 12, planned: 40, pending: 28), "12 files read", "no total while the plan can shrink")
        XCTAssertEqual(Words.shared.READ, "Found by what’s inside")
        XCTAssertEqual(
            Words.shared.sampleNote(reading: true, readyToTry: false, secondsLeft: nil), "You can try search once a few files are read.",
            "no time before it is measured")
        XCTAssertEqual(Words.shared.sampleNote(reading: true, readyToTry: true, secondsLeft: 170), "About 3 minutes left. Ready to try.")
        XCTAssertNil(ReadingStatus(phase: .caughtUp, title: "All done", detail: nil, action: nil, percent: nil).pause, "not a pause")
    }

    func testCommitCopyHasNoFigures() {
        let figure = /\d+(,\d+)?( to \d+)? (hours?|minutes?|nights?|days?)/
        for text in [Words.shared.MORE_FILES, Words.shared.CHOOSE_PACE, Copy.backgroundNote] {
            // "Reading for 15 minutes" is the action's name, not an estimate of how long reading takes.
            XCTAssertNil(text.replacingOccurrences(of: "Reading for 15 minutes", with: "").firstMatch(of: figure), text)
        }
    }

    /// Each Home state the fixtures pin reaches the line it is for: the download, access and Read for 15 minutes come
    /// from the app's own state, the rest from core's decision.
    func testTheStatusLineCoversEveryHomeState() throws {
        func line(_ fixture: AppFixture) throws -> ReadingStatus {
            let model = AppModel(fixture: fixture)
            return try XCTUnwrap(model.statusLine(model.live))
        }
        XCTAssertEqual(try line(.readingWhileCharging).title, "Reading while you charge · 22% read")
        XCTAssertEqual(try line(.unplugged).phase, .charging)
        XCTAssertEqual(try line(.unplugged).action, .readFor15)
        XCTAssertEqual(try line(.chargingUp).title, "Charging up first")
        XCTAssertEqual(try line(.readNow).title, "Reading for 15 minutes · 12 min left")
        XCTAssertEqual(try line(.readNow).action, .stop)
        XCTAssertEqual(try line(.readNow).detail, "Uses some battery. Keep Retriever open.")
        let downloading = try line(.downloading(0.34))
        XCTAssertEqual(downloading.phase, .downloading)
        XCTAssertEqual(downloading.percent?.intValue, 34)
        XCTAssertEqual(downloading.action, .stop)
        XCTAssertEqual(try line(.waitingForWifi).phase, .downloadStopped)
        XCTAssertEqual(try line(.tuning).phase, .tuning)
        XCTAssertEqual(try line(.meaningPass).phase, .meaning)
        XCTAssertEqual(try line(.firstDay).phase, .noContent)
        XCTAssertEqual(try line(.firstDay).action, .chooseWhen)
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
                Copy.backgroundNote, Copy.screenKeptOn, Words.shared.tuneFailed(words: Copy.words), Words.shared.FILES_TO_TRY,
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
