import UIKit
import XCTest
@testable import Retriever

@MainActor
final class SearchModelTests: XCTestCase {
    private typealias Scope = SearchModel.Scope

    func testScopeFromLaunchArgument() {
        let table: [(String, Scope?)] = [
            ("all", .all), ("photo", .photos), ("photos", .photos), ("screenshot", .photos), ("screenshots", .photos),
            ("video", .videos), ("videos", .videos), ("document", .documents), ("documents", .documents),
            ("pdf", .documents), ("pdfs", .documents), ("doc", .documents), ("docs", .documents),
            ("starred", .starred),
            ("", nil), ("music", nil),
        ]
        for (arg, scope) in table {
            XCTAssertEqual(Scope(arg: arg), scope, arg)
        }
    }

    func testScopesFilterInsideTheEngine() {
        XCTAssertNil(Scope.all.kinds)
        XCTAssertEqual(Scope.photos.kinds, [.photo, .screenshot])
        XCTAssertEqual(Scope.videos.kinds, [.video])
        XCTAssertEqual(Scope.documents.kinds, [.pdf, .doc])
        // Stars are not a kind: the engine filters them with onlyStarred, over every kind.
        XCTAssertNil(Scope.starred.kinds)
    }

    func testOnlyPhotosAndVideosNeedPhotosAccess() {
        XCTAssertEqual(Scope.allCases.filter(\.needsPhotos), [.photos, .videos])
    }

    func testClearingTheWordsDropsTheScope() {
        let model = SearchModel(services: nil)
        model.setText("rent")
        model.scope = .documents
        model.setText("rent receipt")
        XCTAssertEqual(model.scope, .documents)
        model.setText("   ")
        XCTAssertEqual(model.scope, .all)
        XCTAssertFalse(model.active)
    }

    func testClearingTheWordsDropsTheStarredScopeToo() {
        let model = SearchModel(services: nil)
        model.setText("rent")
        model.scope = .starred
        model.setText("")
        XCTAssertEqual(model.scope, .all)
    }

    func testScopeStaysWhenThereWereNoWords() {
        let model = SearchModel(services: nil)
        model.scope = .photos
        model.setText("")
        XCTAssertEqual(model.scope, .photos)
    }

    func testActiveNeedsWordsOrAPhoto() {
        let model = SearchModel(services: nil)
        XCTAssertFalse(model.active)
        model.setText("  lease ")
        XCTAssertTrue(model.active)
        XCTAssertEqual(model.query, "lease")
        XCTAssertTrue(SearchModel.fixture(photo: Self.pixel).active)
    }

    func testTypingEndsASearchByPhoto() {
        let model = SearchModel.fixture(photo: Self.pixel)
        XCTAssertNotNil(model.photo)
        XCTAssertEqual(model.key.query, "")
        model.setText("beach")
        XCTAssertNil(model.photo)
        XCTAssertEqual(model.key.query, "beach")
    }

    func testResultsGoStaleWhenTheQuestionChanges() {
        let model = SearchModel.fixture(text: "rent", hits: [])
        XCTAssertFalse(model.stale)
        model.setText("rent receipt")
        XCTAssertTrue(model.stale)
        model.setText("rent")
        XCTAssertFalse(model.stale)
        model.scope = .photos
        XCTAssertTrue(model.stale)
        model.scope = .all
        XCTAssertFalse(model.stale)
        model.scope = .starred
        XCTAssertTrue(model.stale)
        model.scope = .all
        model.retry()
        XCTAssertTrue(model.stale)
    }

    func testNothingAnsweredIsStaleOnceActive() {
        let model = SearchModel(services: nil)
        model.setText("rent")
        XCTAssertTrue(model.stale)
    }

    func testSearchingMyExactWordsLastsUntilTheWordsChange() {
        let model = SearchModel.fixture(text: "rent", hits: [])
        XCTAssertFalse(model.key.literal)
        model.searchAsTyped()
        XCTAssertTrue(model.key.literal)
        XCTAssertTrue(model.stale, "the answer was to the words read for kinds and dates")
        model.setText("rent")
        XCTAssertTrue(model.key.literal, "the same words, so still literal")
        model.setText("rent receipt")
        XCTAssertFalse(model.key.literal)
    }

    func testARetryAsksTheSameQuestion() {
        let model = SearchModel.fixture(text: "rent", hits: [])
        let before = model.key
        model.retry()
        XCTAssertNotEqual(model.key, before)
        XCTAssertTrue(model.key.asks(before))
        model.setText("rental")
        XCTAssertFalse(model.key.asks(before))
        model.setText("rent")
        model.scope = .videos
        XCTAssertFalse(model.key.asks(before))
    }

    func testFindSimilarNeedsTheModel() {
        let model = SearchModel(services: nil)
        XCTAssertNil(model.similar(AppModel.Snap(textOnly: true)), "no model on this phone to match a photo with")
        XCTAssertNotNil(model.similar(AppModel.Snap()))
        XCTAssertNotNil(model.similar(nil))
    }

    func testRecentSearchesCanBeRemovedOrCleared() {
        let model = SearchModel.fixture(recent: ["passport scan", "wifi password", "lease"])
        model.removeRecent("wifi password")
        XCTAssertEqual(model.recent, ["passport scan", "lease"])
        model.clearRecent()
        XCTAssertEqual(model.recent, [])
    }

    func testSearchingWithoutServicesLeavesNothing() async {
        let model = SearchModel.fixture(text: "rent", hits: [])
        await model.search(model.key)
        XCTAssertFalse(model.failed)
        XCTAssertNotNil(model.answer, "no services: the answer on screen is untouched")
    }

    private static let pixel: UIImage = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).image { context in
        UIColor.red.setFill()
        context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
    }
}
