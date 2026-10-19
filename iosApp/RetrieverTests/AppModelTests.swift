import Photos
import RetrieverCore
import XCTest

@testable import Retriever

@MainActor
final class AppModelTests: XCTestCase {
    private let keys = ["started", "setupSeen", "useMobileData"]

    // The model persists these flags; a test must not leave them for the next run on this simulator.
    override func setUp() {
        for key in keys { UserDefaults.standard.removeObject(forKey: key) }
    }

    override func tearDown() {
        for key in keys { UserDefaults.standard.removeObject(forKey: key) }
    }

    func testFixtures() {
        // fixture, setUp, started, setupSeen, total, photos
        let table: [(AppFixture, Bool, Bool, Bool, Int, PHAuthorizationStatus)] = [
            (.firstRun, false, false, false, 3_142, .notDetermined),
            (.photosLimited, false, true, true, 3_142, .limited),
            (.photosDenied, false, true, true, 3_142, .denied),
            (.downloading(0.3), false, true, true, 3_142, .authorized),
            (.waitingForWifi, false, true, true, 3_142, .authorized),
            (.downloadFailed, false, true, true, 3_142, .authorized),
            (.tuning, false, true, true, 3_142, .authorized),
            (.reading(done: 1_204, total: 5_310), true, true, true, 5_310, .authorized),
            (.caughtUp, true, true, true, 3_142, .authorized),
            (.paused, true, true, true, 3_142, .authorized),
            (.skipped(6), true, true, true, 3_142, .authorized),
            (.noAccess, true, true, true, 21, .denied),
            (.tailFiles(12), true, true, true, 3_142, .authorized),
            (.textOnly(ramGb: 3), true, true, true, 3_142, .authorized),
        ]
        for (fixture, setUp, started, setupSeen, total, photos) in table {
            let model = AppModel(fixture: fixture)
            let name = String(describing: fixture)
            XCTAssertEqual(model.setUp, setUp, name)
            XCTAssertEqual(model.started, started, name)
            XCTAssertEqual(model.setupSeen, setupSeen, name)
            XCTAssertEqual(model.snap?.total, total, name)
            XCTAssertEqual(model.photos, photos, name)
            XCTAssertNil(model.services, name)
        }
    }

    func testFixtureDetails() throws {
        let reading = try XCTUnwrap(AppModel(fixture: .reading(done: 1_204, total: 5_310)).snap)
        XCTAssertEqual(reading.tailFiles, 3)
        XCTAssertEqual(reading.pending, 4_106)
        XCTAssertEqual(reading.pendingByKind[.photo], 4_106)
        XCTAssertEqual(AppModel(fixture: .caughtUp).snap?.tailFiles, 0)
        XCTAssertEqual(AppModel(fixture: .tailFiles(12)).snap?.tailFiles, 12)
        let paused = try XCTUnwrap(AppModel(fixture: .paused).snap)
        XCTAssertTrue(paused.paused)
        XCTAssertEqual(paused.lastStop, .paused)
        let skipped = AppModel(fixture: .skipped(6))
        XCTAssertEqual(skipped.snap?.skipped, 6)
        XCTAssertEqual(skipped.skippedFiles.count, 6)
        XCTAssertEqual(AppModel(fixture: .tuning).tune, .running)
        XCTAssertEqual(AppModel(fixture: .firstRun).tune, .idle)
    }

    func testTextOnlyFixtureReadsWordsOnly() throws {
        let model = AppModel(fixture: .textOnly(ramGb: 3))
        let snap = try XCTUnwrap(model.snap)
        XCTAssertTrue(snap.textOnly)
        XCTAssertEqual(snap.ramGb, 3)
        // Nothing to download or tune on this phone.
        XCTAssertEqual(model.tune, .idle)
        XCTAssertEqual(model.download, .idle)
        XCTAssertEqual(snap.byKind[.photo], 0)
        XCTAssertFalse(AppModel(fixture: .caughtUp).snap?.textOnly ?? true)
    }

    func testTheScreenStaysOnOnlyWhileReading() {
        XCTAssertFalse(AppModel(fixture: .reading(done: 10, total: 100)).screenKeptOn)
        XCTAssertFalse(AppModel(fixture: .caughtUp).screenKeptOn)
        XCTAssertFalse(AppModel(fixture: .paused).screenKeptOn)
    }

    func testKeepReadingIsOfferedOnlyWhileFilesWait() {
        func offered(_ fixture: AppFixture) -> Bool { AppModel(fixture: fixture).canKeepReading }
        // A fixture never carries on after the person leaves.
        XCTAssertFalse(AppModel(fixture: .reading(done: 10, total: 100)).continuing)
        guard #available(iOS 26.0, *) else { return XCTAssertFalse(offered(.reading(done: 10, total: 100))) }
        XCTAssertTrue(offered(.reading(done: 10, total: 100)))
        XCTAssertFalse(offered(.caughtUp), "nothing waits")
        XCTAssertFalse(offered(.paused), "the person paused it")
        XCTAssertFalse(offered(.firstRun), "not set up yet")
    }

    func testComebackSetsCoreVisibilityBeforeStartingTheLoop() {
        let services = AppServices.temporary()
        let model = AppModel(services: services)
        model.started = true
        model.setPhase(.background)
        services.monitor.active = false
        services.retriever.uiVisible = false
        model.setPhase(.active)
        XCTAssertTrue(services.monitor.active)
        XCTAssertTrue(services.retriever.uiVisible)
        XCTAssertNotNil(model.loop)
        model.setPhase(.background)
        XCTAssertNil(model.loop)
    }

    func testBackgroundTaskIdsArePermitted() {
        let permitted = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? []
        XCTAssertTrue(permitted.contains(IndexTask.id))
        XCTAssertTrue(permitted.contains(IndexTask.continuedId))
    }

    func testFinishSetupMarksSetupSeen() {
        let model = AppModel(fixture: .firstRun)
        XCTAssertFalse(model.setupSeen)
        model.finishSetup()
        XCTAssertTrue(model.setupSeen)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: "setupSeen"))
    }

    func testRetryTuneResetsTune() {
        let model = AppModel(fixture: .tuning)
        XCTAssertEqual(model.tune, .running)
        model.retryTune()
        XCTAssertEqual(model.tune, .idle)
    }

    // MARK: First run

    /// The stored inputs a launch reads: the welcome answered, Photos, set-up, and core's answers about the short read.
    private func setup(
        asked: Bool = true, photos: PHAuthorizationStatus = .authorized, setUp: Bool = true,
        planned: Int? = 79, decided: Bool? = false, trying: Bool = false, committing: Bool = false,
        download: ModelDownload.State = .ready, tune: AppModel.Tune = .idle, stopped: Bool = false
    ) -> SetupState {
        let counts: [Kind: (read: Int, planned: Int)] = planned.map { $0 == 0 ? [:] : [.photo: (read: 3, planned: $0)] } ?? [:]
        return SetupState(
            asked: asked, photos: photos, folders: [], downloadBytes: 0, download: download, downloadStopped: stopped, progress: 0,
            useMobileData: false, tune: tune, setUp: setUp,
            taste: planned == nil ? nil : TasteSnap(counts: counts), decided: decided, trying: trying, committing: committing
        )
    }

    func testStagesFollowStoredStateAndResumeAfterRelaunch() {
        let table: [(String, SetupState, SetupStage)] = [
            ("not welcomed", setup(asked: false, photos: .notDetermined, setUp: false), .welcome),
            ("welcomed, Photos unanswered", setup(photos: .notDetermined, setUp: false), .access),
            ("Photos denied, nothing else", setup(photos: .denied, setUp: false), .access),
            ("limited Photos, downloading", setup(photos: .limited, setUp: false, download: .downloading), .waiting),
            ("tuning", setup(setUp: false, tune: .running), .waiting),
            ("set up, core has not answered", setup(planned: nil, decided: nil), .waiting),
            ("short read running", setup(), .readFew),
            ("Try it now tapped", setup(trying: true), .tryIt),
            ("Continue tapped", setup(trying: true, committing: true), .commit),
        ]
        for (name, state, stage) in table {
            XCTAssertEqual(state.stage, stage, name)
            // A relaunch rebuilds the state from the same stored inputs and lands on the same stage.
            XCTAssertEqual(
                setup(
                    asked: state.asked, photos: state.photos, setUp: state.setUp, planned: state.taste?.planned,
                    decided: state.decided, trying: state.trying, committing: state.committing,
                    download: state.download, tune: state.tune
                ).stage, stage, name)
        }
    }

    func testAReturningUserNeverSeesTheShortRead() {
        // Set up before the first run existed: core has no scope key, so it reads as decided, whatever else is stored.
        XCTAssertEqual(setup(planned: 0, decided: true).stage, .done)
        XCTAssertEqual(setup(planned: 79, decided: true).stage, .done)
        // A plan that is still empty (access not answered yet) is not a short read either.
        XCTAssertEqual(setup(planned: 0, decided: false).stage, .done)
        XCTAssertFalse(setup(planned: 0, decided: false).firstRun)
    }

    func testReadEverythingNowSkipsTryAndCommit() {
        // Read everything writes no scope, so core says decided: stale try-it and commit flags change nothing.
        XCTAssertEqual(setup(decided: true, trying: true, committing: true).stage, .done)
        // Both choices still wait on the model before anything is shown.
        XCTAssertEqual(setup(setUp: false, decided: true).stage, .waiting)
    }

    func testTheDogShowsWhatIsHappening() {
        XCTAssertEqual(setup(asked: false, photos: .notDetermined, setUp: false).mood, .welcome)
        XCTAssertEqual(setup(setUp: false, download: .downloading).mood, .walking)
        XCTAssertEqual(setup(setUp: false, download: .verifying).mood, .walking)
        XCTAssertEqual(setup(setUp: false, tune: .running).mood, .walking)
        XCTAssertEqual(setup(setUp: false, download: .waitingForWifi).mood, .resting)
        XCTAssertEqual(setup(setUp: false, download: .idle, stopped: true).mood, .resting)
        XCTAssertEqual(setup(setUp: false, download: .failed("offline")).mood, .trouble)
        XCTAssertEqual(setup(setUp: false, tune: .failed).mood, .trouble)
        XCTAssertEqual(setup().mood, .walking)
        XCTAssertNil(setup(trying: true).mood, "try-it hosts the search")
        XCTAssertNil(setup(trying: true, committing: true).mood, "the decision is the focal point")
    }

    func testTasteRowsGroupKindsInReadingOrder() {
        let taste = TasteSnap(counts: [.photo: (11, 18), .pdf: (8, 12), .doc: (4, 4), .screenshot: (6, 6)], secondsLeft: 70, readyToTry: true)
        XCTAssertEqual(taste.rows.map(\.name), ["Screenshots", "Documents and PDFs", "Photos"], "kinds with no files have no row")
        XCTAssertEqual(taste.rows.map(\.done), [true, false, false])
        XCTAssertEqual(taste.rows[1].read, 12)
        XCTAssertEqual(taste.rows[1].planned, 16)
        XCTAssertEqual(taste.read, 29)
        XCTAssertEqual(taste.planned, 40)
        XCTAssertEqual(TasteSnap().planned, 0)
    }

    func testNameOnlyFilesDoNotKeepARowOrTheSampleOpen() {
        let taste = TasteSnap(counts: [.pdf: (3, 5), .doc: (2, 2)], ready: [.pdf, .doc], pending: 0)
        XCTAssertEqual(taste.rows.map(\.done), [true], "5 planned, 3 read, none pending")
        XCTAssertEqual(taste.pending, 0)
        XCTAssertEqual(TasteSnap(counts: [.pdf: (3, 5)], ready: [], pending: 2).rows.map(\.done), [false])
    }

    func testBeginningSetupIsRemembered() {
        let model = AppModel(fixture: .firstRun)
        XCTAssertFalse(model.started)
        model.beginSetup(readEverything: false)
        XCTAssertTrue(model.started)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: "started"))
    }

    func testSkippingGoesHome() {
        let model = AppModel(fixture: .downloading(0.3))
        model.setupSeen = false
        model.skipSetup()
        XCTAssertTrue(model.setupSeen)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: "setupSeen"))
    }

    func testAccessCountsPhotosOrAFolder() {
        XCTAssertFalse(AppModel(fixture: .firstRun).accessGiven)
        XCTAssertTrue(AppModel(fixture: .photosLimited).accessGiven)
        XCTAssertTrue(AppModel(fixture: .downloading(0.1)).accessGiven)
        // Denied Photos with a folder added is still something to read.
        let denied = AppModel(fixture: .photosDenied)
        XCTAssertEqual(denied.photos, .denied)
        XCTAssertEqual(denied.accessGiven, !denied.folders.isEmpty)
    }

    func testSetUseMobileDataFlipsTheFlagAndLeavesTheOldKeyAlone() {
        let model = AppModel(fixture: .waitingForWifi)
        XCTAssertFalse(model.useMobileData)
        model.setUseMobileData(true)
        XCTAssertTrue(model.useMobileData)
        // It lives in Prefs now; the old UserDefaults key is only ever read, to be moved once.
        XCTAssertNil(UserDefaults.standard.object(forKey: "useMobileData"))
        model.setUseMobileData(false)
        XCTAssertFalse(model.useMobileData)
    }
}
