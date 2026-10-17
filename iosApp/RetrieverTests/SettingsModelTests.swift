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
/// The real-facade tests share AppServices with the host app, and put every setting back as they found it.
@MainActor
final class SettingsModelTests: XCTestCase {
    private static let legacyKey = "useMobileData"
    private var services: AppServices!
    private var original: Prefs!

    override func setUp() async throws {
        UserDefaults.standard.removeObject(forKey: Self.legacyKey)
        services = AppServices.shared
        try await services.retriever.refreshSetUp()
        original = try XCTUnwrap(services.retriever.prefs.value as? Prefs)
    }

    override func tearDown() async throws {
        UserDefaults.standard.removeObject(forKey: Self.legacyKey)
        let saved = original!
        try await services.retriever.updatePrefs { _ in saved }
    }

    private var prefs: Prefs { services.retriever.prefs.value as! Prefs }

    private func liveModel() async throws -> AppModel {
        let model = AppModel(services: services)
        let loaded = await eventually { model.snap != nil }
        XCTAssertTrue(loaded, "the first snapshot never arrived")
        return model
    }

    // MARK: On screen

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

    func testTextOnlyPhonesOfferOnlyTheKindsTheyCanRead() {
        XCTAssertEqual(AppModel.Snap.insideTextOnly, [.pdf, .doc, .screenshot])
        XCTAssertTrue(Set(AppModel.Snap.insideTextOnly).isSubset(of: AppModel.Snap.inside))
        XCTAssertFalse(AppModel.Snap.inside.contains(.audio), "there is no audio source on iOS")
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
        try await services.retriever.updatePrefs { $0.doCopy(kinds: $0.kinds, onlyWhileCharging: $0.onlyWhileCharging, excludedFolders: $0.excludedFolders,
                                                              paused: $0.paused, sampleSize: $0.sampleSize, allowMobileData: false,
                                                              rememberSearches: $0.rememberSearches, dynamicColor: $0.dynamicColor) }
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
        let build = try XCTUnwrap(services.retriever.requiredBuild.value as? ModelBuild)
        let file = services.modelsDir.appendingPathComponent(build.fileName)
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("embed/t1-free-up.tmp")
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

    func testFreeUpSpaceDoesNothingOnAFixture() {
        let model = AppModel(fixture: .caughtUp)
        model.freeUpSpace()
        XCTAssertFalse(model.modelMissing)
        XCTAssertEqual(model.storage?.model, ModelVariant.m440.generic.sizeBytes)
    }
}
