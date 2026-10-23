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
            (.sampleReading, false, 25_793, .authorized),
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
        XCTAssertTrue(offered(.sampleReading), "the first search reads while the download runs")
        XCTAssertFalse(offered(.caughtUp), "nothing waits")
        XCTAssertFalse(offered(.paused), "the person paused it")
        XCTAssertFalse(offered(.firstRun), "no access chosen yet")
    }

    func testComebackSetsCoreVisibilityBeforeStartingTheLoop() async {
        let services = temporaryServices()
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

    /// The demo's flows ran for the app's whole life, so core recounted the demo on every file read long after setup
    /// ended. They are open only while the first search shows, and its chips only once its files are picked.
    func testTheFirstSearchFlowsAreOpenOnlyWhileItShows() async {
        let model = AppModel(services: temporaryServices())
        model.watchSetup(stage: .welcome)
        XCTAssertTrue(model.sampleObservations.isEmpty)
        model.watchSetup(stage: .sample)
        XCTAssertEqual(model.sampleObservations.count, 2)
        model.watchSetup(stage: .sample)
        XCTAssertEqual(model.sampleObservations.count, 2, "opened once, not again on every change")
        XCTAssertNil(model.trySuggestionsObservation, "no chips before the files are picked")
        model.watchSetup(stage: .done)
        XCTAssertTrue(model.sampleObservations.isEmpty)
        XCTAssertNil(model.trySuggestionsObservation)
    }

    /// The first search's chips are the model's, so its first frame already has them; before any file, the hint.
    func testTheFirstSearchReadsItsChipsFromTheModel() {
        XCTAssertEqual(AppModel(fixture: .sampleReading).trySuggestions.count, 3)
        XCTAssertTrue(AppModel(fixture: .sampleStart).trySuggestions.isEmpty)
    }

    // MARK: Setup on core's stage machine

    /// Each fixture's stored answers land on the stage core gives them, with Photos and folders added here.
    func testSetupStagesComeFromCoreWithAccessAdded() {
        let table: [(AppFixture, SetupStage, Bool)] = [
            (.firstRun, .welcome, true), (.textOnly(ramGb: 3), .welcome, true), (.accessNone, .access, true), (.photosLimited, .access, true),
            (.photosDenied, .access, true), (.folderAdded, .access, true), (.accessBoth, .access, true), (.sampleStart, .sample, true),
            (.sampleReading, .sample, true), (.sampleReady, .sample, true), (.sampleDone, .sample, true), (.caughtUp, .done, false),
            (.homeIntro(ready: true), .done, false),
        ]
        for (fixture, stage, shown) in table {
            let setup = AppModel(fixture: fixture).setup
            XCTAssertEqual(setup?.stage, stage, String(describing: fixture))
            XCTAssertEqual(setup?.showSetup, shown, String(describing: fixture))
        }
    }

    /// Continue waits for Photos (all or some) or a folder; a refused Photos alone gives nothing to read.
    func testContinueWaitsForPhotosOrAFolder() {
        XCTAssertFalse(AppModel(fixture: .accessNone).accessGiven)
        XCTAssertTrue(AppModel(fixture: .photosLimited).accessGiven)
        XCTAssertFalse(AppModel(fixture: .photosDenied).accessGiven)
        XCTAssertTrue(AppModel(fixture: .folderAdded).accessGiven)
        XCTAssertTrue(AppModel(fixture: .accessBoth).accessGiven)
        // Access taken away later brings back the access screen, even after setup ended.
        let model = AppModel(fixture: .caughtUp)
        model.setupInputs = SetupInputs(mark: .accessChosen, skipped: false, decided: true, planned: 30, accessGiven: false)
        XCTAssertFalse(model.setup?.showSetup ?? true)
        (model.photos, model.folders) = (.denied, [])
        XCTAssertEqual(model.setup?.stage, .access)
        XCTAssertTrue(model.setup?.showSetup ?? false)
    }

    /// Access given on the access screen never moves on by itself: the person confirms with Continue, so both rows can be
    /// filled first. Back returns to Hello.
    func testAccessWaitsForContinueAndBackReturnsToHello() async {
        let services = temporaryServices()
        let model = AppModel(services: services)
        model.startDownload = {}
        model.getStarted()
        let greeted = await eventually { model.setup?.stage == .access }
        XCTAssertTrue(greeted)
        (model.photos, model.folders) = (.authorized, ["Work"])
        model.accessChanged()
        let moved = await eventually(timeout: 2) { model.setup?.stage != .access }
        XCTAssertFalse(moved, "Photos and a folder given, still on the access screen")
        model.setupBack()
        let back = await eventually { model.setup?.stage == .welcome }
        XCTAssertTrue(back)
        model.getStarted()
        _ = await eventually { model.setup?.stage == .access }
        model.setSetupMark(.accessChosen)
        let continued = await eventually { model.setup?.stage == .sample }
        XCTAssertTrue(continued)
    }

    /// Get started downloads search by description only on an iPhone that runs it and lacks it.
    func testGetStartedDownloadsOnlyWhenTheModelRunsAndIsMissing() {
        for (fixture, downloads) in [(AppFixture.firstRun, true), (.textOnly(ramGb: 3), false), (.caughtUp, false)] {
            let model = AppModel(fixture: fixture)
            var started = 0
            model.startDownload = { started += 1 }
            XCTAssertEqual(model.downloadOnGetStarted, downloads, String(describing: fixture))
            model.getStarted()
            XCTAssertEqual(started, downloads ? 1 : 0, String(describing: fixture))
        }
    }

    /// The first search's Continue ends setup and asks Home for its card; Got it puts the card away.
    func testContinueFinishesSetupAndGotItDismissesTheCard() async {
        let services = temporaryServices()
        let model = AppModel(services: services)
        model.folders = ["Work"]
        _ = try? await services.retriever.setSetupMark(mark: .accessChosen)
        let sampling = await eventually { model.setup?.stage == .sample }
        XCTAssertTrue(sampling)
        model.finishSetup()
        let finished = await eventually { model.setup?.stage == .done && model.snap?.prefs?.homeIntroDue == true }
        XCTAssertTrue(finished)
        model.dismissHomeIntro()
        let dismissed = await eventually { model.snap?.prefs?.homeIntroDue == false }
        XCTAssertTrue(dismissed)
    }

    func testTheFirstSearchSearchesItsOwnFiles() {
        let model = AppModel(fixture: .sampleDone)
        XCTAssertEqual(model.sampleIds.count, 30, "the first search looks inside its own files")
        XCTAssertEqual(model.snap?.waiting, 25_763)
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

    func testTheOfferShowsAtOnceAndStaysDismissed() throws {
        let model = AppModel(fixture: .noModel)
        let prefs = try XCTUnwrap(model.snap?.prefs)
        func with(tried: Bool, dismissed: Bool, intro: Bool = false) -> Prefs {
            prefs.doCopy(
                kinds: prefs.kinds, onlyWhileCharging: prefs.onlyWhileCharging, schedule: prefs.schedule, readNowUntil: prefs.readNowUntil,
                excludedFolders: prefs.excludedFolders, paused: prefs.paused, sampleSize: prefs.sampleSize, allowMobileData: prefs.allowMobileData,
                rememberSearches: prefs.rememberSearches, dynamicColor: prefs.dynamicColor, setupStarted: prefs.setupStarted,
                notificationsDismissed: prefs.notificationsDismissed, noMatchSeen: prefs.noMatchSeen, showOnThisDay: prefs.showOnThisDay,
                readingCycle: prefs.readingCycle, dismissedReadingCycle: prefs.dismissedReadingCycle, searchTried: tried,
                upgradeOfferDismissed: dismissed, homeIntroDue: intro)
        }
        model.snap?.prefs = with(tried: false, dismissed: false)
        XCTAssertTrue(model.upgradeOnHome, "offered at once, before any search")
        model.snap?.prefs = with(tried: true, dismissed: false, intro: true)
        XCTAssertFalse(model.upgradeOnHome, "not while Home's one-time card says the same")
        model.snap?.prefs = with(tried: true, dismissed: true)
        XCTAssertFalse(model.upgradeOnHome, "Not now holds")
        // The search hint shows only under few results, and only while the upgrade is on offer.
        XCTAssertNotNil(Words.shared.upgradeHint(results: 1, upgrade: model.upgrade))
        XCTAssertNil(Words.shared.upgradeHint(results: 3, upgrade: model.upgrade))
        XCTAssertNil(Words.shared.upgradeHint(results: 0, upgrade: AppModel(fixture: .caughtUp).upgrade))
    }

    /// The first search and Home's card say where search by description stands in core's words, from the download's state.
    func testDescriptionStateFollowsTheDownload() {
        let table: [(AppFixture, DescriptionState)] = [
            (.sampleReading, .downloading), (.sampleWaitingForWifi, .waitingForWifi), (.sampleReady, .ready), (.downloadFailed, .failed),
            (.noModel, .notStarted), (.tuning, .preparing), (.homeIntro(ready: true), .ready), (.homeIntro(ready: false), .downloading),
            (.textOnly(ramGb: 3), .unavailable),
        ]
        for (fixture, state) in table { XCTAssertEqual(AppModel(fixture: fixture).descriptionState, state, String(describing: fixture)) }
        // Not enough space: the download never starts, and both screens say why.
        XCTAssertEqual(AppModel.phase(.noSpace("Free up 120 MB")), .noSpace)
        XCTAssertEqual(UpgradePolicy.shared.description(stage: .download, download: AppModel.phase(.noSpace(""))), .noSpace)
        // A finished download is checked until core finds the model, never "downloading" again.
        XCTAssertEqual(AppModel.phase(.ready), .checking)
        // The card's button: one where it helps, none where there is nothing to do.
        for state in [DescriptionState.waitingForWifi, .failed, .notStarted] {
            XCTAssertNotNil(Words.shared.recentFilesAction(state: state, size: "388 MB"), "\(state)")
        }
        for state in [DescriptionState.ready, .downloading, .preparing, .noSpace, .unavailable] {
            XCTAssertNil(Words.shared.recentFilesAction(state: state, size: "388 MB"), "\(state)")
        }
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
