import SwiftUI
import XCTest

@testable import Retriever

@MainActor
final class StarredTests: XCTestCase {
    private var services: AppServices!

    override func setUp() async throws { services = temporaryServices() }

    func testFixtureHasStars() {
        XCTAssertEqual(AppModel(fixture: .caughtUp).starred.count, 2)
    }

    func testSetStarredUpdatesStarredAtOnce() async throws {
        let model = AppModel(services: services)
        // A snapshot in flight would replace the set, so wait for the first one before acting.
        let loaded = await eventually { model.snap != nil }
        XCTAssertTrue(loaded)
        let uri = "folder://t1/not-in-the-index.pdf"
        model.setStarred(uri, true)
        XCTAssertTrue(model.starred.contains(uri))
        model.setStarred(uri, false)
        XCTAssertFalse(model.starred.contains(uri))
    }

    func testStarsCrossViewsThroughTheEnvironmentNotAGlobal() {
        // The Stars and Thumbnails globals are gone: a view reads AppModel and AppServices from the environment.
        var values = EnvironmentValues()
        XCTAssertNil(values.services, "nothing until RootView sets it, so fixtures show icons")
        values.services = services
        XCTAssertNotNil(values.services)
    }

    func testSetStarredWithoutServicesChangesNothing() {
        let model = AppModel(fixture: .caughtUp)
        let before = model.starred
        model.setStarred("folder://fixture/other.pdf", true)
        XCTAssertEqual(model.starred, before, "a fixture only holds a state")
    }

    func testStarredFilesPageIsItsOwnQuery() {
        let starred = FileQuery(title: "Starred", starredOnly: true)
        XCTAssertTrue(starred.starredOnly)
        XCTAssertNil(starred.kind)
        XCTAssertNil(starred.folder)
        XCTAssertNotEqual(starred, FileQuery(title: "Starred"))
        XCTAssertFalse(FileQuery(kind: .pdf, title: "PDFs").starredOnly)
        XCTAssertEqual(Route.files(starred), Route.files(FileQuery(title: "Starred", starredOnly: true)))
        XCTAssertNotEqual(Route.files(starred), Route.files(FileQuery(title: "Starred")))
    }

    func testStarredIsASearchFilterOverEveryKind() {
        let scope = SearchModel.Scope.starred
        XCTAssertEqual(scope.title, "Starred")
        XCTAssertNil(scope.kinds)
        XCTAssertFalse(scope.needsPhotos)
        let model = SearchModel.fixture(text: "lease", hits: [])
        model.scope = .starred
        XCTAssertEqual(model.key.scope, .starred)
    }
}
