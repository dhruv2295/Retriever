import RetrieverCore
import XCTest

@testable import Retriever

extension XCTestCase {
    /// Polls until `condition` holds; the Kotlin facade answers on its own threads, so a setting lands a moment after the tap.
    @MainActor func eventually(timeout: TimeInterval = 10, _ condition: @escaping () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline { return false }
            try? await Task.sleep(for: .milliseconds(25))
        }
        return true
    }
}

/// The settings actions on a fixture change what the screen shows; on the real facade they persist through Prefs.
/// The real-facade tests build their own AppServices under a temporary directory, so they never touch the app's index or model.
@MainActor
final class SettingsModelTests: XCTestCase {
    private static let legacyKey = "useMobileData"
    private var services: AppServices!

    override func setUp() async throws {
        UserDefaults.standard.removeObject(forKey: Self.legacyKey)
        services = temporaryServices()
        try await services.retriever.refreshSetUp()
    }

    override func tearDown() async throws {
        UserDefaults.standard.removeObject(forKey: Self.legacyKey)
    }

    private var prefs: Prefs { services.retriever.prefs.value as! Prefs }

    private func liveModel() async throws -> AppModel {
        let model = AppModel(services: services)
        let loaded = await eventually { model.snap != nil }
        XCTAssertTrue(loaded, "the first snapshot never arrived")
        return model
    }

    // MARK: On screen

    func testProblemExportUsesOneSnapshotForItsFileAndCount() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let log = Diagnostics(file: file)
        let empty = await SettingsView.exportProblems(log)
        XCTAssertNil(empty)
        try JSONEncoder().encode(["{\"crashDiagnostics\":[]}", "{\"hangDiagnostics\":[]}"]).write(to: file)
        let snapshot = await SettingsView.exportProblems(log)
        let exported = try XCTUnwrap(snapshot)
        let entries = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: exported.url)) as? [[String: Any]])
        XCTAssertEqual(exported.count, 2)
        XCTAssertEqual(entries.count, exported.count)
        XCTAssertNotNil(entries[0]["crashDiagnostics"])
        XCTAssertNotNil(entries[1]["hangDiagnostics"])
        XCTAssertNil(SettingsView(model: AppModel(fixture: .caughtUp)).diagnostics)
    }

    func testKindTogglesShowAtOnce() throws {
        let model = AppModel(fixture: .caughtUp)
        XCTAssertEqual(try XCTUnwrap(model.snap).kinds, Set(AppModel.Snap.inside))
        model.setKind(.video, false)
        XCTAssertEqual(model.snap?.kinds.contains(.video), false)
        XCTAssertEqual(model.snap?.kinds.contains(.pdf), true, "the others are left alone")
        model.setKind(.video, true)
        XCTAssertEqual(model.snap?.kinds.contains(.video), true)
    }

    func testChargingAndRememberTogglesShowAtOnce() {
        let model = AppModel(fixture: .caughtUp)
        XCTAssertEqual(model.snap?.onlyWhileCharging, false)
        model.setOnlyWhileCharging(true)
        XCTAssertEqual(model.snap?.onlyWhileCharging, true)
        XCTAssertEqual(model.snap?.rememberSearches, true)
        model.setRememberSearches(false)
        XCTAssertEqual(model.snap?.rememberSearches, false)
    }

    func testSettingExcludedShowsAtOnceAndPersistsOnce() async throws {
        let model = try await liveModel()
        model.setExcluded("DCIM/Old", true)
        XCTAssertEqual(model.snap?.excludedFolders.contains("DCIM/Old"), true, "the screen shows it before Prefs answers")
        var landed = await eventually { self.prefs.excludedFolders == ["DCIM/Old"] }
        XCTAssertTrue(landed, "excluded persists")
        model.setExcluded("DCIM/Old", false)
        XCTAssertEqual(model.snap?.excludedFolders.contains("DCIM/Old"), false)
        landed = await eventually { self.prefs.excludedFolders.isEmpty }
        XCTAssertTrue(landed, "restored persists")
    }

    func testTextOnlyPhonesOfferOnlyTheKindsTheyCanRead() {
        XCTAssertEqual(AppModel.Snap.insideTextOnly, [.pdf, .doc, .screenshot, .photo])
        XCTAssertTrue(Set(AppModel.Snap.insideTextOnly).isSubset(of: AppModel.Snap.inside))
        XCTAssertFalse(AppModel.Snap.inside.contains(.audio), "there is no audio source on iOS")
    }

    func testExcludedEditsMergeAcrossStaleSnapshots() async throws {
        let first = try await liveModel()
        let second = try await liveModel()
        first.setExcluded("DCIM/Old", true)
        second.setExcluded("DCIM/Private", true)
        var landed = await eventually { self.prefs.excludedFolders == ["DCIM/Old", "DCIM/Private"] }
        XCTAssertTrue(landed, "both additions persist even when both screens started with no exclusions")

        first.snap?.excludedFolders = ["DCIM/Old", "DCIM/Private"]
        second.snap?.excludedFolders = ["DCIM/Old", "DCIM/Private"]
        first.restoreFolder("DCIM/Old")
        second.setExcluded("DCIM/New", true)
        landed = await eventually { self.prefs.excludedFolders == ["DCIM/Private", "DCIM/New"] }
        XCTAssertTrue(landed, "adding a folder must not undo another screen's restore")
    }

    func testKindEditsMergeAcrossStaleSnapshots() async throws {
        let first = try await liveModel()
        let second = try await liveModel()
        let expected = prefs.kinds.subtracting([.video, .pdf])
        first.setKind(.video, false)
        second.setKind(.pdf, false)
        let landed = await eventually { self.prefs.kinds == expected }
        XCTAssertTrue(landed, "each toggle changes only its own kind")
    }

    // MARK: Through Prefs

    func testSettingsPersistThroughPrefsAndLeaveTheRestAlone() async throws {
        let model = try await liveModel()
        let before = prefs

        model.setOnlyWhileCharging(!before.onlyWhileCharging)
        var landed = await eventually { self.prefs.onlyWhileCharging == !before.onlyWhileCharging }
        XCTAssertTrue(landed, "only while charging")

        model.setRememberSearches(!before.rememberSearches)
        landed = await eventually { self.prefs.rememberSearches == !before.rememberSearches }
        XCTAssertTrue(landed, "remember searches")

        let wasOn = before.kinds.contains(.video)
        model.setKind(.video, !wasOn)
        landed = await eventually { self.prefs.kinds.contains(.video) == !wasOn }
        XCTAssertTrue(landed, "video")

        model.setUseMobileData(!before.allowMobileData)
        landed = await eventually { self.prefs.allowMobileData == !before.allowMobileData }
        XCTAssertTrue(landed, "mobile data")
        XCTAssertEqual(model.useMobileData, !before.allowMobileData)

        let after = prefs
        XCTAssertEqual(after.kinds.subtracting([.video]), before.kinds.subtracting([.video]), "the other kinds are untouched")
        XCTAssertEqual(after.paused, before.paused)
        XCTAssertEqual(after.excludedFolders, before.excludedFolders)
    }

    func testMobileDataMovesFromTheOldKeyIntoPrefsOnce() async throws {
        try await services.retriever.updatePrefs { @Sendable in
            $0.doCopy(
                kinds: $0.kinds, onlyWhileCharging: $0.onlyWhileCharging, schedule: $0.schedule, readNowUntil: $0.readNowUntil,
                excludedFolders: $0.excludedFolders,
                paused: $0.paused, sampleSize: $0.sampleSize, allowMobileData: false,
                rememberSearches: $0.rememberSearches, dynamicColor: $0.dynamicColor,
                setupStarted: $0.setupStarted, notificationsDismissed: $0.notificationsDismissed,
                noMatchSeen: $0.noMatchSeen, showOnThisDay: $0.showOnThisDay, readingCycle: $0.readingCycle, dismissedReadingCycle: $0.dismissedReadingCycle,
                searchTried: $0.searchTried, upgradeOfferDismissed: $0.upgradeOfferDismissed)
        }
        XCTAssertFalse(prefs.allowMobileData)
        UserDefaults.standard.set(true, forKey: Self.legacyKey)

        let model = AppModel(services: services)
        XCTAssertTrue(model.useMobileData, "until the move, the old key answers")
        let moved = await eventually { UserDefaults.standard.object(forKey: Self.legacyKey) == nil }
        XCTAssertTrue(moved, "the old key goes once the value is in Prefs")
        XCTAssertTrue(prefs.allowMobileData)
        let settled = await eventually { model.useMobileData }
        XCTAssertTrue(settled)
    }

    func testAnOldKeyThatIsOffMovesNothing() async throws {
        UserDefaults.standard.set(false, forKey: Self.legacyKey)
        let model = AppModel(services: services)
        XCTAssertFalse(model.useMobileData)
        let moved = await eventually { UserDefaults.standard.object(forKey: Self.legacyKey) == nil }
        XCTAssertTrue(moved)
        XCTAssertFalse(prefs.allowMobileData)
    }

    func testNoOldKeyLeavesPrefsAlone() async throws {
        let before = prefs.allowMobileData
        let model = try await liveModel()
        XCTAssertEqual(model.useMobileData, before)
        XCTAssertNil(UserDefaults.standard.object(forKey: Self.legacyKey))
    }

    // MARK: Free up space

    func testFreeUpSpaceRemovesTheModelAndItsTemporaryFiles() async throws {
        let model = try await liveModel()
        let build = services.retriever.requiredBuildNow
        let file = services.modelsDir.appendingPathComponent(build.fileName)
        let cache = services.cacheDir.appendingPathComponent("t1-free-up.tmp")
        try FileManager.default.createDirectory(at: services.modelsDir, withIntermediateDirectories: true)
        try Data(count: 1_024).write(to: file)
        try Data(count: 1_024).write(to: cache)

        model.freeUpSpace()
        let gone = await eventually { !FileManager.default.fileExists(atPath: file.path) && !FileManager.default.fileExists(atPath: cache.path) }
        XCTAssertTrue(gone, "the model file and the cache are removed")
        let measured = await eventually { model.storage != nil }
        XCTAssertTrue(measured, "storage is measured again")
        XCTAssertEqual(model.storage?.model, 0)
        XCTAssertEqual(model.storage?.cache, 0)
        // modelMissing (names and words only) follows from Retriever.refreshSetUp once a tune profile exists; a fresh
        // simulator has none and no model to tune with, so only the removal itself is checked here.
    }

    func testRemoveDownloadConfirmationNamesTheSizeAndWhatKeepsWorking() {
        XCTAssertEqual(
            Words.shared.removeDownloadMessage(size: "388 MB"),
            "This frees 388\u{00A0}MB. File names and text already processed still work. Search by meaning needs the download again.")
        XCTAssertEqual(
            Words.shared.removeDownloadMessage(size: nil),
            "File names and text already processed still work. Search by meaning needs the download again.")
    }

    func testClearingRecentSearchesEmptiesTheListAndKeepsTheSwitch() {
        let model = AppModel(fixture: .caughtUp)
        model.recent = ["boarding pass", "lease"]
        model.clearRecentSearches()
        XCTAssertEqual(model.recent, [])
        XCTAssertEqual(model.snap?.rememberSearches, true, "clearing leaves the switch as it was")
    }

    func testFreeUpSpaceDoesNothingOnAFixture() {
        let model = AppModel(fixture: .caughtUp)
        model.freeUpSpace()
        XCTAssertFalse(model.modelMissing)
        XCTAssertEqual(model.storage?.model, ModelVariant.m440.generic.sizeBytes)
    }
    func testReadingScheduleAndExplicitSessionPersistIndependently() async throws {
        let model = try await liveModel()
        XCTAssertEqual(prefs.automaticReading, .charging)
        model.setReadingSchedule(.manual)
        var saved = await eventually { self.prefs.automaticReading == .manual }
        XCTAssertTrue(saved)
        model.readNow()
        saved = await eventually { self.prefs.readNowUntil > 0 }
        XCTAssertTrue(saved)
        XCTAssertEqual(prefs.automaticReading, .manual)
        model.pause()
        saved = await eventually { self.prefs.paused && self.prefs.readNowUntil == 0 }
        XCTAssertTrue(saved)
        let reopened = try await liveModel()
        XCTAssertTrue(reopened.snap?.paused == true)
        reopened.resume()
        saved = await eventually { !self.prefs.paused }
        XCTAssertTrue(saved)
        XCTAssertEqual(prefs.automaticReading, .manual)
        XCTAssertEqual(prefs.readNowUntil, 0)
    }

}
