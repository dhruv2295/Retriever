import Photos
import RetrieverCore
import XCTest

@testable import Retriever

@MainActor
final class CopyTests: XCTestCase {
    func testHomeStatusFollowsTheIndex() {
        XCTAssertNil(Copy.homeStatus(nil))
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(accessChosen: false))?.title, Words.shared.CHOOSE_ACCESS)
        // Reading needs no download: without the model, the status is reading's own.
        XCTAssertEqual(Copy.homeStatus(AppModel.Snap(setUp: false, indexed: 4))?.title, "Up to date")
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

    func testSetupDownloadSizeUsesWholeMegabytes() {
        XCTAssertEqual(Copy.downloadSize(484_622_336), "485\u{00A0}MB")
        XCTAssertEqual(Copy.bytes(484_622_336), "484.6\u{00A0}MB")
    }

    /// Setup's sentences are core's; the shell adds only "iPhone" for "this device".
    func testSetupSaysGoToHomeAndNeverSkipOrDownload() {
        XCTAssertEqual(Words.shared.GO_TO_HOME, "Go to Home")
        XCTAssertEqual(Words.shared.searchingTry(read: 54), "Searching every file’s name, and inside the 54 files read so far.")
        XCTAssertEqual(Copy.photosAccess(.limited), Words.shared.SELECTED_PHOTOS)
        XCTAssertEqual(Copy.photosAccess(.authorized), Words.shared.ALL_PHOTOS)
        XCTAssertNil(Copy.photosAccess(.notDetermined))
        let welcome = Words.shared.welcomeBody(words: Copy.words)
        XCTAssertTrue(welcome.hasSuffix("this \(Copy.device)."), welcome)
        for text in [Words.shared.WELCOME_TITLE, welcome, Words.shared.iosAccessNote(words: Copy.words), Words.shared.SAMPLE_TITLE] {
            XCTAssertFalse(text.localizedCaseInsensitiveContains("download"), text)
            XCTAssertFalse(text.localizedCaseInsensitiveContains("skip"), text)
        }
    }

    /// The welcome's iOS ask and the pile's count line.
    func testWelcomeAskAndSampleCountWords() {
        XCTAssertEqual(Words.shared.SEARCH_ALL_PHOTOS, "Search all your photos")
        XCTAssertEqual(Words.shared.ADD_FOLDERS_FROM_FILES, "Add folders from Files")
        XCTAssertEqual(
            Words.shared.iosAccessNote(words: Copy.words),
            "iOS lets Retriever read your photos and the folders you pick in Files. Nothing leaves your \(Copy.device).")
        XCTAssertEqual(Words.shared.SAMPLE_PICKING, "Picking a few of your files…")
        XCTAssertEqual(Words.shared.readOf(read: 12, planned: 40), "12 of 40 files read")
        XCTAssertEqual(Words.shared.READ, "Read")
        XCTAssertEqual(
            Words.shared.sampleNote(reading: true, readyToTry: false, secondsLeft: nil), "You can try search once a few files are read.",
            "no time before it is measured")
        XCTAssertEqual(Words.shared.sampleNote(reading: true, readyToTry: true, secondsLeft: 170), "About 3 minutes left. You can try search now.")
        XCTAssertNil(ReadingStatus(phase: .caughtUp, title: "Up to date", detail: nil).pause, "not a pause")
    }

    func testCommitCopyHasNoFigures() {
        XCTAssertEqual(Copy.commitNights, "A large library can take hours of reading, spread across several sessions. You don’t need to wait.")
        let figure = /\d+(,\d+)?( to \d+)? (hours?|minutes?|nights?|days?)/
        for text in [Words.shared.moreFiles(files: KotlinLong(value: 26_214)), Words.shared.moreFiles(files: nil), Copy.commitNights] {
            XCTAssertNil(text.firstMatch(of: figure), text)
        }
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
                Copy.backgroundNote, Copy.deepReading(3), Words.shared.tuneFailed(words: Copy.words), Copy.filesYouCanTry,
                Words.shared.welcomeBody(words: Copy.words), Words.shared.upgradeBody(size: "388 MB", words: Copy.words),
                Words.shared.tuneTitle(words: Copy.words), Words.shared.tuneBody(words: Copy.words),
            ] + home(setting) + home(reading) + home(AppModel.Snap(setUp: true, indexed: 7))
        for string in strings.compactMap({ $0 }) {
            for word in ["model", "embedding", "vector", "gpu"] {
                XCTAssertFalse(string.localizedCaseInsensitiveContains(word), "“\(string)” says \(word)")
            }
        }
    }

}
