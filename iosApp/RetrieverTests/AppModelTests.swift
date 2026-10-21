import Photos
import RetrieverCore
import XCTest

@testable import Retriever

@MainActor
final class AppModelTests: XCTestCase {
    private let keys = ["useMobileData"]

    // The model persists these flags; a test must not leave them for the next run on this simulator.
    override func setUp() {
        for key in keys { UserDefaults.standard.removeObject(forKey: key) }
    }

    override func tearDown() {
        for key in keys { UserDefaults.standard.removeObject(forKey: key) }
    }

    func testFixtures() {
        // fixture, setUp, total, photos
        let table: [(AppFixture, Bool, Int, PHAuthorizationStatus)] = [
            (.firstRun, false, 0, .notDetermined),
            (.photosLimited, false, 0, .limited),
            (.photosDenied, false, 0, .denied),
            (.sampleReading, false, 3_142, .authorized),
            (.downloading(0.3), false, 3_142, .authorized),
            (.tuning, false, 3_142, .authorized),
            (.reading(done: 1_204, total: 5_310), true, 5_310, .authorized),
            (.caughtUp, true, 3_142, .authorized),
            (.paused, true, 3_142, .authorized),
            (.skipped(6), true, 3_142, .authorized),
            (.noAccess, true, 21, .denied),
            (.tailFiles(12), true, 3_142, .authorized),
            (.textOnly(ramGb: 3), true, 3_142, .authorized),
        ]
        for (fixture, setUp, total, photos) in table {
            let model = AppModel(fixture: fixture)
            let name = String(describing: fixture)
            XCTAssertEqual(model.setUp, setUp, name)
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
        XCTAssertEqual(model.upgrade, .unavailable)
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
        XCTAssertTrue(offered(.sampleReading), "the sample reads with no download")
        XCTAssertFalse(offered(.caughtUp), "nothing waits")
        XCTAssertFalse(offered(.paused), "the person paused it")
        XCTAssertFalse(offered(.firstRun), "no access chosen yet")
    }

    func testComebackSetsCoreVisibilityBeforeStartingTheLoop() async {
        let services = AppServices.temporary()
        let model = AppModel(services: services)
        // Past access with a folder: core plans (an empty library reads everything), then reading runs.
        model.folders = ["Work"]
        _ = try? await services.retriever.setSetupMark(mark: .accessChosen)
        let reading = await eventually { model.setup.map { $0.step == .readSample || $0.step == .schedule } == true }
        XCTAssertTrue(reading)
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

    func testRetryTuneResetsTune() {
        let model = AppModel(fixture: .tuning)
        XCTAssertEqual(model.tune, .running)
        model.retryTune()
        XCTAssertEqual(model.tune, .idle)
    }

    /// The sample's flows ran for the app's whole life, so core recounted the sample on every file read long after
    /// setup ended. They are open only on the stages that show them, and Try's suggestions only once Try can open.
    func testTheSampleFlowsAreOpenOnlyWhileTheSampleOrTryShows() async {
        let model = AppModel(services: AppServices.temporary())
        model.watchSetup(stage: .welcome)
        XCTAssertTrue(model.sampleObservations.isEmpty)
        model.watchSetup(stage: .sample)
        XCTAssertEqual(model.sampleObservations.count, 2)
        model.watchSetup(stage: .sample)
        XCTAssertEqual(model.sampleObservations.count, 2, "opened once, not again on every change")
        XCTAssertNil(model.trySuggestionsObservation, "Try can't open yet: nothing is planned or ready")
        model.watchSetup(stage: .try_)
        XCTAssertEqual(model.sampleObservations.count, 2)
        for stage in [SetupStage.choose, .done] {
            model.watchSetup(stage: stage)
            XCTAssertTrue(model.sampleObservations.isEmpty, "\(stage)")
            XCTAssertNil(model.trySuggestionsObservation, "\(stage)")
        }
    }

    /// Try's suggestions are the model's from before Try opens, so Try's first frame already has them.
    func testTryReadsItsSuggestionsFromTheModel() {
        XCTAssertEqual(AppModel(fixture: .trying(suggestions: true)).trySuggestions.count, 3)
        XCTAssertTrue(AppModel(fixture: .trying(suggestions: false)).trySuggestions.isEmpty)
    }

    // MARK: Setup on core's stage machine

    /// Each fixture's stored answers land on the stage core gives them, with Photos and folders added here.
    func testSetupStagesComeFromCoreWithAccessAdded() {
        let table: [(AppFixture, SetupStage, Bool)] = [
            (.firstRun, .welcome, true), (.photosLimited, .welcome, true), (.photosDenied, .welcome, true),
            (.folderAdded, .welcome, true), (.sampleStart, .sample, true), (.sampleReading, .sample, true), (.sampleDone, .sample, true),
            (.trying(suggestions: true), .try_, true), (.choosing, .choose, true), (.caughtUp, .done, false),
        ]
        for (fixture, stage, shown) in table {
            let setup = AppModel(fixture: fixture).setup
            XCTAssertEqual(setup?.stage, stage, String(describing: fixture))
            XCTAssertEqual(setup?.showSetup, shown, String(describing: fixture))
        }
    }

    /// Continue waits for Photos (all or some) or a folder; a denied Photos alone gives nothing to read.
    func testAccessCountsPhotosOrAFolder() {
        XCTAssertFalse(AppModel(fixture: .firstRun).accessGiven)
        XCTAssertTrue(AppModel(fixture: .photosLimited).accessGiven)
        XCTAssertFalse(AppModel(fixture: .photosDenied).accessGiven)
        XCTAssertTrue(AppModel(fixture: .folderAdded).accessGiven)
        // Access taken away later brings back the welcome's ask, even after Go to Home.
        let model = AppModel(fixture: .caughtUp)
        model.setupInputs = SetupInputs(mark: .accessChosen, skipped: true, decided: false, planned: 54, accessGiven: false)
        XCTAssertFalse(model.setup?.showSetup ?? true)
        (model.photos, model.folders) = (.denied, [])
        XCTAssertEqual(model.setup?.stage, .welcome)
        XCTAssertTrue(model.setup?.showSetup ?? false)
    }

    /// On the welcome, all photos or a folder added chooses access at once; selected photos wait for Continue.
    func testAllPhotosOrAFolderChoosesAccessOnTheWelcome() async {
        for (photos, folders, chosen) in [(PHAuthorizationStatus.authorized, [String](), true), (.notDetermined, ["Work"], true), (.limited, [], false)] {
            let services = AppServices.temporary()
            let model = AppModel(services: services)
            let loaded = await eventually { model.setup?.stage == .welcome }
            XCTAssertTrue(loaded)
            (model.photos, model.folders) = (photos, folders)
            model.accessChanged()
            let marked = await eventually(timeout: 3) { model.setupInputs?.mark == .accessChosen }
            XCTAssertEqual(marked, chosen, "\(photos.rawValue) \(folders)")
            if !chosen {
                // Continue is the answer for selected photos.
                model.setSetupMark(.accessChosen)
                let continued = await eventually { model.setup?.stage == .sample }
                XCTAssertTrue(continued)
            }
        }
    }

    /// A later stage keeps its mark when Photos is answered from Home or Search.
    func testAccessAnsweredAfterSetupLeavesTheMarkAlone() async throws {
        let services = AppServices.temporary()
        let model = AppModel(services: services)
        try await services.retriever.setSetupMark(mark: .trying)
        let trying = await eventually { model.setupInputs?.mark == .trying }
        XCTAssertTrue(trying)
        model.photos = .authorized
        model.accessChanged()
        let regressed = await eventually(timeout: 2) { model.setupInputs?.mark == .accessChosen }
        XCTAssertFalse(regressed)
    }

    /// Search by name skips the sample's screens, never access; the sample still reads.
    func testGoToHomeKeepsReadingTheSample() {
        let skipped = SetupInputs(mark: .accessChosen, skipped: true, decided: false, planned: 54, accessGiven: true)
        XCTAssertEqual(skipped.stage, .done)
        XCTAssertFalse(skipped.showSetup)
        XCTAssertEqual(skipped.step, .readSample)
    }

    func testTryOffersTheNextStepOrHomeWhenNothingIsLeft() {
        let model = AppModel(fixture: .trying(suggestions: true))
        XCTAssertEqual(model.sampleIds.count, 54, "Try searches the sample's planned files")
        XCTAssertEqual(model.snap?.waiting, 3_088)
    }

    // MARK: The meaning upgrade

    func testUpgradeStagesFollowTheDownloadAndTune() {
        let table: [(AppFixture, UpgradeStage, Bool)] = [
            (.noModel, .offer, true), (.downloading(0.3), .download, true), (.waitingForWifi, .download, true),
            (.downloadFailed, .download, true), (.tuning, .tune, true), (.meaning(1_204), .meaning, false), (.caughtUp, .done, false),
        ]
        for (fixture, stage, onHome) in table {
            let model = AppModel(fixture: fixture)
            XCTAssertEqual(model.upgrade, stage, String(describing: fixture))
            XCTAssertEqual(model.upgradeOnHome, onHome, String(describing: fixture))
        }
    }

    func testTheOfferWaitsForAFirstSearchAndStaysDismissed() throws {
        let model = AppModel(fixture: .noModel)
        let prefs = try XCTUnwrap(model.snap?.prefs)
        func with(tried: Bool, dismissed: Bool) -> Prefs {
            prefs.doCopy(
                kinds: prefs.kinds, onlyWhileCharging: prefs.onlyWhileCharging, schedule: prefs.schedule, readNowUntil: prefs.readNowUntil,
                excludedFolders: prefs.excludedFolders, paused: prefs.paused, sampleSize: prefs.sampleSize, allowMobileData: prefs.allowMobileData,
                rememberSearches: prefs.rememberSearches, dynamicColor: prefs.dynamicColor, setupStarted: prefs.setupStarted,
                notificationsDismissed: prefs.notificationsDismissed, noMatchSeen: prefs.noMatchSeen, showOnThisDay: prefs.showOnThisDay,
                readingCycle: prefs.readingCycle, dismissedReadingCycle: prefs.dismissedReadingCycle, searchTried: tried,
                upgradeOfferDismissed: dismissed)
        }
        model.snap?.prefs = with(tried: false, dismissed: false)
        XCTAssertFalse(model.upgradeOnHome, "not before a first search")
        model.snap?.prefs = with(tried: true, dismissed: true)
        XCTAssertFalse(model.upgradeOnHome, "Not now holds")
        // The search hint shows only under few results, and only while the upgrade is on offer.
        XCTAssertNotNil(Words.shared.upgradeHint(results: 1, upgrade: model.upgrade))
        XCTAssertNil(Words.shared.upgradeHint(results: 3, upgrade: model.upgrade))
        XCTAssertNil(Words.shared.upgradeHint(results: 0, upgrade: AppModel(fixture: .caughtUp).upgrade))
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
