@preconcurrency import RetrieverCore
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

    func testScopedQuestionsCarryThePlannedIds() {
        let model = SearchModel(services: nil)
        model.setText("rent")
        XCTAssertNil(model.question.scope, "Home's search is unscoped")
        model.fileIds = [7, 3, 9]
        XCTAssertEqual(model.question.scope?.map(\.int64Value), [7, 3, 9])
        XCTAssertEqual(model.key.ids, [7, 3, 9])
        // Another scope or kind keeps the ids; the same words with other ids are another question.
        model.scope = .documents
        XCTAssertEqual(model.question.scope?.map(\.int64Value), [7, 3, 9])
        let before = model.question
        model.fileIds = [7, 3]
        XCTAssertNotEqual(model.question, before)
        model.fileIds = nil
        XCTAssertNil(model.question.scope)
    }

    func testANewQueryIsSearchingNeverEmpty() {
        let model = SearchModel(services: nil)
        XCTAssertEqual(model.screen, .idle)
        model.setText("rent")
        XCTAssertEqual(model.screen, .searching(slow: false))
    }

    func testAnEmptyAnswerToAnOlderQuestionIsSearching() {
        let model = SearchModel.fixture(text: "rent", hits: [])
        XCTAssertEqual(model.screen, .empty)
        model.setText("rent receipt")
        XCTAssertEqual(model.screen, .searching(slow: false))
        model.setText("rent")
        XCTAssertEqual(model.screen, .empty, "back to the question that was answered")
        model.scope = .photos
        XCTAssertEqual(model.screen, .searching(slow: false))
    }

    func testARetryOfTheSameQuestionKeepsItsEmptyAnswer() {
        let model = SearchModel.fixture(text: "rent", hits: [])
        model.retry()
        XCTAssertEqual(model.screen, .empty)
    }

    func testARetryKeepsVisibleWordsOnlyResults() {
        let file = SourceFile(
            uri: "folder://test/rent.pdf", name: "rent.pdf", folder: "test", mime: "application/pdf", kind: .pdf, size: 1, modifiedAt: 0, takenAt: nil)
        let hit = SearchEngine.Hit(file: file, fileId: 1, chunkId: 1, locator: "", snippet: nil, score: 1)
        let model = SearchModel.fixture(text: "rent", hits: [hit], vectorLane: false)
        model.retry()
        XCTAssertEqual(model.screen, .results)
        model.setText("receipt")
        XCTAssertEqual(model.screen, .searching(slow: false))
    }

    func testAFailureBelongsToItsQuestion() {
        let model = SearchModel.fixture(text: "rent", failed: true)
        XCTAssertEqual(model.screen, .failed)
        model.retry()
        XCTAssertEqual(model.screen, .failed, "stays until the search answers")
        model.setText("rental")
        XCTAssertEqual(model.screen, .searching(slow: false))
    }

    func testResultsAreResultsAndSlowShowsOnlyWhileSearching() {
        let slow = SearchModel.fixture(text: "rent", slow: true)
        XCTAssertEqual(slow.screen, .searching(slow: true))
        XCTAssertEqual(SearchModel.fixture(text: "rent", hits: []).screen, .empty)
    }

    func testTheDogMovesForTheFirstNoMatches() {
        let model = SearchModel(services: nil)
        XCTAssertEqual(model.noMatchMood, .noMatch)
    }

    func testSearchingMyExactWordsLastsUntilTheWordsChange() {
        let model = SearchModel.fixture(text: "rent", hits: [])
        XCTAssertFalse(model.key.literal)
        model.searchAsTyped()
        XCTAssertTrue(model.key.literal)
        XCTAssertEqual(model.screen, .searching(slow: false), "the answer was to the words read for kinds and dates")
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
        XCTAssertEqual(model.question, before.question)
        model.setText("rental")
        XCTAssertNotEqual(model.question, before.question)
        model.setText("rent")
        model.scope = .videos
        XCTAssertNotEqual(model.question, before.question)
        model.scope = .photos
        XCTAssertNotEqual(model.question, SearchModel.fixture(text: "rent", hits: []).question)
    }

    func testFindSimilarNeedsTheModel() {
        let model = SearchModel(services: nil)
        XCTAssertNil(model.similar(false), "no model to match a photo with")
        XCTAssertNotNil(model.similar(true))
        // Search by photo and Find similar wait for the meaning upgrade, which a sample read never needs.
        XCTAssertFalse(AppModel(fixture: .noModel).meaningSearch)
        XCTAssertFalse(AppModel(fixture: .tuning).meaningSearch, "downloaded, not tuned yet")
        XCTAssertFalse(AppModel(fixture: .textOnly(ramGb: 3)).meaningSearch)
        XCTAssertTrue(AppModel(fixture: .caughtUp).meaningSearch)
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
        XCTAssertEqual(model.screen, .empty)
        XCTAssertNotNil(model.answer, "no services: the answer on screen is untouched")
    }

    func testReturningReusesACompleteAnswerButAnExplicitRetryRuns() async throws {
        let model = SearchModel(services: temporaryServices())
        model.setText("videos")
        await model.search(model.key)
        let before = try XCTUnwrap(model.phase as? SearchPhaseResults)
        XCTAssertTrue(before.complete)
        XCTAssertTrue(before.result.vectorLane)

        await model.search(model.key)
        XCTAssertTrue((model.phase as? SearchPhaseResults) === before)
        model.retry()
        await model.search(model.key)
        XCTAssertEqual(model.answer?.key, model.key, "retry must not be swallowed by the same-question guard")
        XCTAssertFalse((model.phase as? SearchPhaseResults) === before)
    }

    func testReturningToCompleteWordsOnlyResultsSearchesAgain() async throws {
        let model = SearchModel(services: temporaryServices())
        model.setText("rent")
        for _ in 0...Int(SearchTiming.shared.WORDS_ONLY_RETRIES) {
            await model.search(model.key)
            if (model.phase as? SearchPhaseResults)?.complete == true { break }
        }
        let before = try XCTUnwrap(model.phase as? SearchPhaseResults)
        XCTAssertTrue(before.complete)
        XCTAssertFalse(before.result.vectorLane)
        let key = model.key
        await model.search(key)
        XCTAssertEqual(model.answer?.key, key)
        XCTAssertFalse((model.phase as? SearchPhaseResults) === before, "returning must give meaning search another chance")
    }

    /// A replaced search closes its core handle at once instead of running on with the model, and the next search
    /// isn't queued behind it. Without a model the embedder mutex isn't held; the self-test covers that path.
    func testACancelledSearchStopsAtOnceAndTheNextRuns() async throws {
        let stuck = Stuck()
        let first = Task {
            try await awaitSearch {
                stuck.done = $0
                return stuck
            }
        }
        try await Task.sleep(for: .milliseconds(50))
        let clock = ContinuousClock()
        let cancelled = clock.now
        first.cancel()
        let outcome = await first.result
        XCTAssertLessThan(clock.now - cancelled, .milliseconds(500))
        XCTAssertTrue(stuck.closed)
        XCTAssertThrowsError(try outcome.get())

        let retriever = temporaryServices().retriever
        let replaced = Task {
            try await awaitSearch { retriever.searchCancellable(q: "rent", kinds: nil, limit: 20, onlyStarred: false, literal: false, done: $0) }
        }
        replaced.cancel()
        _ = await replaced.result
        let next = try await awaitSearch {
            retriever.searchCancellable(q: "rent", kinds: nil, limit: 20, onlyStarred: false, literal: false, done: $0)
        }
        XCTAssertTrue(next.hits.isEmpty, "an empty index answers, not waits")
    }

    /// A core handle whose search never finishes until closed; closing answers the way core does.
    private final class Stuck: NSObject, KotlinAutoCloseable, @unchecked Sendable {
        var done: ((SearchEngine.SearchResult?, KotlinThrowable?) -> Void)?
        private(set) var closed = false

        func close() {
            closed = true
            done?(nil, nil)
            done = nil
        }
    }

    private static let pixel: UIImage = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).image { context in
        UIColor.red.setFill()
        context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
    }
}
