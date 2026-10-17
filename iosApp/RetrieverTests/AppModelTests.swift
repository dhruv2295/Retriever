import Photos
import XCTest
@testable import Retriever

@MainActor
final class AppModelTests: XCTestCase {
    private let keys = ["started", "setupSeen", "useMobileData"]

    // The model persists these flags; a test must not leave them for the next run on this simulator.
    override func setUp() {
        keys.forEach { UserDefaults.standard.removeObject(forKey: $0) }
    }

    override func tearDown() {
        keys.forEach { UserDefaults.standard.removeObject(forKey: $0) }
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
        XCTAssertTrue(AppModel(fixture: .reading(done: 10, total: 100)).screenKeptOn)
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
