import XCTest

/// Runs only in verify.sh --full and at the gate: one launch per fixture route, then Apple's accessibility audit.
/// The list mirrors DebugRouteTests.fixtureRoutes (live routes need a seeded library).
final class AccessibilityAuditTests: XCTestCase {
    private static let routes = [
        "setup.welcome", "setup.photos", "setup.limited", "setup.denied", "setup.folders", "setup.download",
        "setup.waitingWifi", "setup.failed", "setup.tuning", "setup.textOnly",
        "status.reading", "status.caughtUp", "status.paused", "status.skipped", "status.textOnly", "status.tailFiles",
        "home.caughtUp", "home.reading", "home.empty", "search.empty", "search.wordsOnly", "search.lessLikely",
        "search.nothingWell", "search.firstPart", "search.error", "search.loading", "search.noAccess", "search.reading",
    ]

    func testEveryFixtureRoutePassesTheAccessibilityAudit() throws {
        continueAfterFailure = true
        for route in Self.routes {
            let app = XCUIApplication()
            app.launchArguments = ["-route", route]
            app.launch()
            XCTContext.runActivity(named: route) { _ in
                do { try app.performAccessibilityAudit() } catch { XCTFail("\(route): \(error)") }
            }
            app.terminate()
        }
    }
}
