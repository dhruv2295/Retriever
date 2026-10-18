#if DEBUG
    import RetrieverCore
    import XCTest
    @testable import Retriever

    @MainActor
    final class DebugRouteTests: XCTestCase {
        private static let fixtureRoutes: [(raw: String, fixture: String)] = [
            ("setup.welcome", "firstRun"), ("setup.photos", "firstRun"), ("setup.limited", "photosLimited"),
            ("setup.denied", "photosDenied"), ("setup.folders", "downloading(0.08)"), ("setup.download", "downloading(0.34)"),
            ("setup.waitingWifi", "waitingForWifi"), ("setup.failed", "downloadFailed"), ("setup.tuning", "tuning"),
            ("setup.textOnly", "textOnly(ramGb: 3.0)"),
            ("status.reading", "reading(done: 1204, total: 5310)"), ("status.caughtUp", "caughtUp"),
            ("status.paused", "paused"), ("status.skipped", "skipped(6)"),
            ("status.textOnly", "textOnly(ramGb: 3.0)"), ("status.tailFiles", "tailFiles(12)"),
            ("home.caughtUp", "caughtUp"), ("home.reading", "caughtUp"), ("home.empty", "caughtUp"), ("search.empty", "caughtUp"),
            ("search.wordsOnly", "caughtUp"), ("search.lessLikely", "caughtUp"), ("search.nothingWell", "caughtUp"),
            ("search.firstPart", "caughtUp"), ("search.error", "caughtUp"), ("search.loading", "caughtUp"),
            ("search.noAccess", "noAccess"), ("search.reading", "reading(done: 1204, total: 5310)"),
        ]

        private static let liveRoutes: [(raw: String, path: [Route])] = [
            ("home", []), ("search", []), ("search.photo", []),
            ("browse.large", [.files(FileQuery(sort: .largest, title: "Large files"))]),
            ("browse.folders", [.folders]),
            ("browse.duplicates", [.duplicates]),
            ("viewer.pdf", []), ("viewer.video", []), ("viewer.photo", []), ("viewer.file", []),
        ]

        func testFixtureRoutesMapToTheirFixture() throws {
            for (raw, fixture) in Self.fixtureRoutes {
                let route = try XCTUnwrap(DebugRoute(rawValue: raw), raw)
                XCTAssertEqual(String(describing: route.fixture), fixture, raw)
                XCTAssertEqual(route.path, [], raw)
            }
        }

        func testLiveRoutesGiveTheirPath() throws {
            for (raw, path) in Self.liveRoutes {
                let route = try XCTUnwrap(DebugRoute(rawValue: raw), raw)
                XCTAssertEqual(route.path, path, raw)
            }
        }

        /// The locator and snippet parsing the results and viewers depend on (was `-selfcheck`).
        func testLocatorAndSnippetParsing() {
            XCTAssertEqual(Locator.shared.pageOf(l: "p. 12")?.intValue, 12)
            XCTAssertNil(Locator.shared.pageOf(l: "p. 0"))
            XCTAssertEqual(Locator.shared.msOf(l: "1:02:03")?.int64Value, 3_723_000)
            XCTAssertNil(Locator.shared.msOf(l: "1:"))
            XCTAssertEqual(Format.badge("p. 3"), "Page 3")
            XCTAssertEqual(Format.badge("0:37"), "0:37")
            XCTAssertNil(Format.badge(""))
            XCTAssertEqual(Set(Format.markedWords("a \u{2}rent\u{3} and \u{2}deposit\u{3} b")), ["rent", "deposit"])
            XCTAssertEqual(String(Format.highlighted("x \u{2}y\u{3} z").characters), "x y z")
        }

        func testUnknownRouteDoesNotParse() {
            XCTAssertNil(DebugRoute(rawValue: "setup.nowhere"))
            // Browse was folded into Home; its old route names are gone.
            XCTAssertNil(DebugRoute(rawValue: "browse"))
        }
    }
#endif
