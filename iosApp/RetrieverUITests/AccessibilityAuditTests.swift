// Xcode also runs App Intents metadata extraction for this test bundle.
import AppIntents
import UIKit
import XCTest

/// Runs only in verify.sh --full and at the gate: one launch per fixture route, then Apple's accessibility audit.
/// The list mirrors DebugRouteTests.fixtureRoutes (live routes need a seeded library).
@MainActor
final class AccessibilityAuditTests: XCTestCase {
    private var appearance = "light"

    override func setUp() async throws {
        await MainActor.run {
            XCUIDevice.shared.appearance = .light
            XCUIDevice.shared.orientation = .portrait
            XCTAssertTrue(UIAccessibility.isReduceMotionEnabled, "ios-unit-tests.sh must enable Reduce Motion")
        }
    }

    private func launch(_ app: XCUIApplication) {
        app.launchArguments += ["-ui-testing", "-ui-appearance", appearance]
        if !app.launchArguments.contains("-UIPreferredContentSizeCategoryName") {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL"]
        }
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        // Debug routes dismiss the keyboard after 1.5 s; wait through that and the native search-bar layout.
        let settled = expectation(description: "Launch layout settled")
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { settled.fulfill() }
        wait(for: [settled], timeout: 5)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
    }

    private static let folderText = [
        "audit", "Add a folder",
        "Retriever also searches its own folder in the Files app. Removing a folder here never deletes it.",
    ]
    private static let statusText = [
        "Retriever keeps your screen on while it reads. Lock your iPhone any time to stop.",
        "Pause", "Keep reading after I leave", "Your iPhone shows how it's going and lets you stop it any time.",
        "3 long files still being read in depth", "Show On This Day on Home", "Photos and videos from this date in earlier years.",
        "Retriever reads your files while it's open, on battery too, and keeps the screen on as it goes. After you leave, it carries on where iPhone allows, mostly overnight on the charger.",
    ]
    private static let routes = [
        "setup.welcome", "setup.photos", "setup.limited", "setup.denied", "setup.folders", "setup.download",
        "setup.waitingWifi", "setup.failed", "setup.tuning", "setup.textOnly",
        "status.reading", "status.caughtUp", "status.paused", "status.skipped", "status.textOnly", "status.tailFiles",
        "home.caughtUp", "home.reading", "home.empty", "search.empty", "search.wordsOnly", "search.lessLikely",
        "search.nothingWell", "search.firstPart", "search.error", "search.loading", "search.noAccess", "search.reading",
    ]

    func testEveryFixtureRoutePassesTheAccessibilityAudit() throws {
        try auditFixtures()
    }

    func testEveryFixtureRoutePassesTheAccessibilityAuditInDarkAppearance() throws {
        appearance = "dark"
        XCUIDevice.shared.appearance = .dark
        try auditFixtures()
    }

    func testPhotoCaptionsAndUnavailablePhotoContrastInBothAppearances() throws {
        for style in ["light", "dark"] {
            appearance = style
            XCUIDevice.shared.appearance = style == "dark" ? .dark : .light
            for route in ["home.memories", "viewer.photoMissing"] {
                let app = XCUIApplication()
                app.launchArguments = ["-mutedMedia", "-route", route]
                launch(app)
                defer { app.terminate() }
                if route == "viewer.photoMissing" {
                    XCTAssertTrue(app.staticTexts["Couldn't show this photo"].waitForExistence(timeout: 5))
                }
                try XCTContext.runActivity(named: "\(route) \(appearance)") { _ in
                    let screenshot = XCTAttachment(screenshot: app.screenshot())
                    screenshot.name = "\(route) \(appearance)"
                    screenshot.lifetime = .keepAlways
                    add(screenshot)
                    try app.performAccessibilityAudit(for: .contrast)
                }
            }
        }
    }

    private func auditFixtures() throws {
        continueAfterFailure = true
        for route in Self.routes {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", route]
            launch(app)
            XCTContext.runActivity(named: route) { _ in
                do {
                    try audit(app, route: route)
                } catch { XCTFail("\(route): \(error)") }
            }
            app.terminate()
        }
    }

    func testHomeAuditAfterAnAccessibilitySizeLaunch() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-mutedMedia", "-route", "home.caughtUp", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        launch(app)
        app.terminate()
        app.launchArguments = ["-mutedMedia", "-route", "home.caughtUp"]
        launch(app)
        defer { app.terminate() }
        try audit(app, route: "home.caughtUp")
    }

    func testSeededViewerAndBrowseRoutesPassTheAccessibilityAudit() throws {
        let routes = [
            ("viewer.pdf", "Document_01", "Star"),
            ("viewer.video", "", "Star"),
            ("viewer.photo", "IMG_1001", "Star"),
            ("browse.large", "", "Sort"),
            ("browse.folders", "", "Add a folder"),
            ("browse.duplicates", "", "Duplicates"),
        ]
        for (route, query, label) in routes {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", route, "-q", query]
            launch(app)
            defer { app.terminate() }
            XCTAssertTrue(app.descendants(matching: .any)[label].firstMatch.waitForExistence(timeout: 15), "\(route) never opened")
            if route.hasPrefix("viewer.") {
                XCTAssertTrue(app.descendants(matching: .any)[route].firstMatch.waitForExistence(timeout: 15), "\(route) did not load its file")
            }
            XCTAssertFalse(app.staticTexts["Couldn't play this video"].exists)
            XCTAssertFalse(app.staticTexts["Couldn't open this file"].exists)
            XCTAssertFalse(app.staticTexts["Couldn't show this photo"].exists)
            try XCTContext.runActivity(named: route) { _ in try audit(app, route: route) }
        }
    }

    private func audit(_ app: XCUIApplication, route: String) throws {
        // Dynamic Type auditing resizes lazy lists. Sample contrast before it can invalidate their frames.
        try app.performAccessibilityAudit(for: .contrast)
        try app.performAccessibilityAudit(for: .all.subtracting(.contrast)) { issue in
            guard let element = issue.element else {
                print("AUDIT \(route): \(issue.auditType) \(issue.compactDescription) (no element)")
                return false
            }
            // PDFKit paragraphs are document content, not tappable controls. Keep links and app buttons in the audit.
            if route == "viewer.pdf", issue.auditType == .hitRegion, element.elementType == .other,
                app.descendants(matching: .any)["viewer.pdf"].firstMatch.frame.contains(element.frame)
            {
                return true
            }
            // These literal source filenames are the correct viewer titles, not missing app-authored labels.
            if route.hasPrefix("viewer."), issue.auditType == .sufficientElementDescription,
                ["Document_01.pdf", "talk-and-show.mp4", "IMG_1001.jpg"].contains(element.label),
                app.navigationBars.firstMatch.frame.contains(element.frame)
            {
                return true
            }
            // The native field grows and its entire placeholder fits at AX5 (screenshot and typing test below).
            if issue.auditType == .textClipped, element.elementType == .searchField, element.label == "Search your files" { return true }
            // These folder Labels are reported as clipped, but the AX5 screenshots below show both full labels.
            if route == "browse.folders", issue.auditType == .textClipped, ["audit", "Add a folder"].contains(element.label) { return true }
            // The audit loses these lazy-list elements when resizing scrolls them off screen. The tests below
            // relaunch at AX5, scroll them into view, and assert that each label actually grows.
            if issue.auditType == .dynamicType {
                if route.hasPrefix("status."), Self.statusText.contains(element.label) { return true }
                if route == "home.caughtUp", ["document-name", "document-detail"].contains(element.identifier) { return true }
                if route == "browse.folders", Self.folderText.contains(element.label) { return true }
            }
            print("AUDIT \(route): \(issue.auditType) \(issue.compactDescription) \(element.debugDescription)")
            return false
        }
    }

    func testStatusTextGrowsAtTheLargestAccessibilitySize() {
        var normal: [String: CGFloat] = [:]
        for category in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryAccessibilityXXXL"] {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "status.reading", "-UIPreferredContentSizeCategoryName", category]
            launch(app)
            for label in Self.statusText {
                let element = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
                for _ in 0..<12 {
                    if element.exists && element.isHittable { break }
                    app.swipeUp()
                }
                XCTAssertTrue(element.exists, label)
                let height = element.frame.height
                if let before = normal[label] { XCTAssertGreaterThan(height, before, label) } else { normal[label] = height }
            }
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "Status \(category)"
            shot.lifetime = .keepAlways
            add(shot)
            app.terminate()
        }
    }

    func testDocumentCardsGrowAtTheLargestAccessibilitySize() {
        var normal: [String: CGFloat] = [:]
        for category in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryAccessibilityXXXL"] {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "home.caughtUp", "-UIPreferredContentSizeCategoryName", category]
            launch(app)
            for id in ["document-name", "document-detail"] {
                let element = app.staticTexts[id].firstMatch
                for _ in 0..<12 {
                    if element.exists && element.isHittable { break }
                    app.swipeUp()
                }
                XCTAssertTrue(element.exists, id)
                let height = element.frame.height
                if let before = normal[id] { XCTAssertGreaterThan(height, before, id) } else { normal[id] = height }
            }
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "Document cards \(category)"
            shot.lifetime = .keepAlways
            add(shot)
            app.terminate()
        }
    }

    func testNativeSearchFieldAtTheLargestAccessibilitySize() {
        let app = XCUIApplication()
        app.launchArguments = ["-mutedMedia", "-route", "home.reading", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        launch(app)
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertTrue(field.isHittable)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "Search placeholder at AX5"
        shot.lifetime = .keepAlways
        add(shot)
        field.tap()
        field.typeText("receipt")
        XCTAssertEqual(field.value as? String, "receipt")
        app.terminate()
    }

    func testFolderLabelsGrowAtTheLargestAccessibilitySize() {
        var normal: [String: CGFloat] = [:]
        for category in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryAccessibilityXXXL"] {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "browse.folders", "-UIPreferredContentSizeCategoryName", category]
            launch(app)
            for label in Self.folderText {
                let element = app.staticTexts[label].firstMatch
                for _ in 0..<12 {
                    if element.exists && element.isHittable { break }
                    app.swipeUp()
                }
                XCTAssertTrue(element.exists, label)
                let height = element.frame.height
                if let before = normal[label] { XCTAssertGreaterThan(height, before, label) } else { normal[label] = height }
                let shot = XCTAttachment(screenshot: app.screenshot())
                shot.name = "Folder \(label) \(category)"
                shot.lifetime = .keepAlways
                add(shot)
            }
            app.terminate()
        }
    }

    func testBrowsePhotosSwipeThroughVisibleFilesAndUpdateTheToolbar() {
        let app = XCUIApplication()
        app.launchArguments = ["-mutedMedia", "-route", "browse.large"]
        launch(app)
        defer { app.terminate() }
        let photo = app.buttons.matching(NSPredicate(format: "label CONTAINS 'IMG_' AND label CONTAINS '.jpg'")).firstMatch
        for _ in 0..<8 {
            if photo.exists && photo.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(photo.exists)
        photo.tap()
        let image = app.descendants(matching: .any)["viewer.photo"].firstMatch
        XCTAssertTrue(image.waitForExistence(timeout: 10))
        let names = ["IMG_1001.jpg", "IMG_1003.jpg"]
        let first = names.first { app.navigationBars.staticTexts[$0].exists }
        XCTAssertNotNil(first)
        XCTAssertTrue(app.buttons["Star"].exists)
        let next = names.first { $0 != first }!
        app.swipeLeft()
        if !app.navigationBars.staticTexts[next].waitForExistence(timeout: 2) { app.swipeRight() }
        XCTAssertTrue(app.navigationBars.staticTexts[next].waitForExistence(timeout: 5), "opening from Browse must pass its photo neighbors")
        XCTAssertTrue(app.buttons["Star"].exists, "the newly selected photo keeps its toolbar actions")
    }
}
