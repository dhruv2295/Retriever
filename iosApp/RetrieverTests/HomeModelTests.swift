import Photos
import SwiftUI
import XCTest
@testable import Retriever

/// Home's facts come from the Kotlin facade into the model; HomeList then shows a section only when it has something
/// true to say. The second half renders HomeList in a window and counts its sections and rows: SwiftUI exposes no
/// labels to UIKit outside VoiceOver, but a section that is hidden has no rows and no header.
@MainActor
final class HomeModelTests: XCTestCase {
    private var services: AppServices!

    override func setUp() async throws { services = AppServices.temporary() }

    func testFixtureFactsAreWhatHomeShows() throws {
        let home = try XCTUnwrap(AppModel(fixture: .caughtUp).home)
        XCTAssertEqual(home.newDocs.count, 3)
        XCTAssertEqual(home.newShots.count, 6)
        XCTAssertEqual(home.folders, 12)
        XCTAssertEqual(home.largeCount, 4)
        XCTAssertEqual(home.nameOnly, 0)
        XCTAssertEqual(Copy.largeFiles(home.largeCount), "4 files over 100 MB")
    }

    func testRefreshHomeMapsTheFacadeFactsOntoTheModel() async throws {
        let model = AppModel(services: services)
        XCTAssertNil(model.home, "nothing until the first load")
        await model.refreshHome()
        let home = try XCTUnwrap(model.home)
        // Whatever this simulator holds, the facts agree with themselves.
        XCTAssertGreaterThanOrEqual(home.folders, 0)
        XCTAssertGreaterThanOrEqual(home.largeCount, 0)
        XCTAssertGreaterThanOrEqual(home.largeBytes, 0)
        XCTAssertTrue(home.newDocs.allSatisfy { $0.kind == .pdf || $0.kind == .doc })
        XCTAssertTrue(home.newShots.allSatisfy { $0.kind == .screenshot })
    }

    func testRefreshHomeDoesNothingOnAFixture() async {
        let model = AppModel(fixture: .caughtUp)
        await model.refreshHome()
        XCTAssertEqual(model.home?.folders, 12)
    }

    // MARK: What Home shows

    func testCaughtUpHomeShowsEverySection() async throws {
        let model = AppModel(fixture: .caughtUp)
        // Status, recent searches, new documents, new screenshots, your files, tidy up.
        let layout = try await layout(model, search: .fixture(recent: ["passport scan"]))
        XCTAssertEqual(layout.headers, 5)
        // One status row; the recent search; the 3 new documents; the strip of screenshots;
        // Starred + 5 kinds + Folders; Large files + Duplicates.
        XCTAssertEqual(layout.items, [1, 1, 3, 1, 7, 2])
    }

    func testSuggestionsStandInUntilThereAreRecentSearches() async throws {
        let model = AppModel(fixture: .caughtUp)
        XCTAssertEqual(model.suggestions.count, 3)
        let layout = try await layout(model, search: .fixture())
        XCTAssertEqual(layout.items, [1, 3, 3, 1, 7, 2])
    }

    func testSectionsHideWhenTheyHaveNothingToSay() async throws {
        // The real model before its first load: no facts, no stars, no suggestions.
        let model = AppModel(services: services)
        XCTAssertNil(model.home)
        let loaded = await eventually { model.snap != nil }
        XCTAssertTrue(loaded)
        try XCTSkipUnless(model.starred.isEmpty && model.suggestions.isEmpty, "this simulator holds stars or suggestions")
        let snap = AppModel.Snap(setUp: true, byKind: [.pdf: 3, .screenshot: 2], indexed: 5)
        let layout = try await layout(model, snap: snap, search: .fixture())
        // No recent searches, suggestions, new files, folders or large files, and no stars: what is left is the status,
        // the two kinds that exist plus the nudge to add a folder (5 files is nearly empty), and Duplicates.
        XCTAssertEqual(layout.headers, 2)
        XCTAssertEqual(layout.items, [1, 3, 1])
    }

    func testKindsWithNoFilesDoNotGetARow() async throws {
        let model = AppModel(fixture: .caughtUp)
        var snap = try XCTUnwrap(model.snap)
        let all = try await layout(model, snap: snap, search: .fixture())
        snap.byKind = [.pdf: 64]
        let one = try await layout(model, snap: snap, search: .fixture())
        XCTAssertEqual(all.items[4] - one.items[4], 4, "screenshots, documents, photos and videos drop out")
    }

    func testEmptyLibraryIsOneMessageNotSections() async throws {
        let model = AppModel(fixture: .caughtUp)
        let reading = try await layout(model, snap: AppModel.Snap(setUp: true, running: true), search: .fixture())
        XCTAssertEqual(reading.headers, 0)
        XCTAssertEqual(reading.items, [1], "first run says it once, on the screen")
        let empty = try await layout(model, snap: AppModel.Snap(setUp: true), search: .fixture())
        XCTAssertEqual(empty.headers, 0)
        XCTAssertEqual(empty.items, [1], "the empty message alone, not \"All caught up\" above it (home-spec section 9)")
    }

    // MARK: Rendering

    private struct Layout: Equatable {
        var headers: Int
        var items: [Int]
    }

    /// HomeList in a window of the host app's scene (without one SwiftUI lays out nothing), then the rows in each section.
    private func layout(_ model: AppModel, snap: AppModel.Snap? = nil, search: SearchModel? = nil) async throws -> Layout {
        let list = NavigationStack {
            HomeList(search: search ?? .fixture(), snap: snap ?? model.snap, photos: .authorized, model: model, useRecent: { _ in }, addFolder: {})
        }
        let host = UIHostingController(rootView: list)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 5_000)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        // A fixed sleep raced SwiftUI's first layout pass on a loaded machine (the flake). Poll until the list has
        // sections and the same answer twice running, or give up after 15 s and let the assertions report it.
        var last: Layout?
        for _ in 0..<150 {
            try await Task.sleep(for: .milliseconds(100))
            host.view.layoutIfNeeded()
            guard let collection = Self.collectionView(in: host.view), collection.numberOfSections > 0 else { continue }
            let now = Layout(headers: collection.visibleSupplementaryViews(ofKind: UICollectionView.elementKindSectionHeader).count,
                             items: (0..<collection.numberOfSections).map { collection.numberOfItems(inSection: $0) })
            if now == last { return now }
            last = now
        }
        return try XCTUnwrap(last, "Home is not a list")
    }

    private static func collectionView(in view: UIView) -> UICollectionView? {
        (view as? UICollectionView) ?? view.subviews.lazy.compactMap(collectionView(in:)).first
    }
}
