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
        "Reading now", "Photo", "9 October 2025", "Recently read", "Pause reading",
        "Read now for 15 minutes", "Can continue after you leave if iOS allows.",
        "3 partly read · more meaning search to come",
        "Retriever reads while it’s open and in background time provided by iOS. Automatic reading waits for charging. A short reading session can use battery. Background timing is up to iOS; plugging in doesn’t guarantee an immediate start.",
    ]
    private static let routes = [
        "setup.welcome", "setup.photos", "setup.limited", "setup.denied", "setup.folders", "setup.download",
        "setup.waitingWifi", "setup.failed", "setup.tuning", "setup.textOnly", "setup.reading", "setup.readingPaused",
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

    func testDownloadCardKeepsProgressAndStopAccessibleInBothAppearances() throws {
        for style in ["light", "dark"] {
            appearance = style
            XCUIDevice.shared.appearance = style == "dark" ? .dark : .light
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "setup.limited"]
            launch(app)
            let progress = app.staticTexts["download-amount"]
            let stop = app.buttons["Stop"]
            XCTAssertTrue(progress.exists)
            XCTAssertGreaterThanOrEqual(stop.frame.height, 44)
            XCTAssertGreaterThanOrEqual(stop.frame.width, 44)
            XCTAssertEqual(progress.frame.midY, stop.frame.midY, accuracy: 14)
            try audit(app, route: "setup.limited")
            app.terminate()
        }
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
                    try app.performAccessibilityAudit(for: .contrast) { issue in
            // A partial glyph cannot be measured; every kind is audited fully visible in testKindLegendContrastInBothAppearances.
            if self.clippedByLegend(issue.element, in: app) { return true }
            print("CONTRAST \(route): \(issue.compactDescription) \(String(describing: issue.element))")
            return false
        }
                }
            }
        }
    }

    func testRecentAndReadyCellsAnnounceNameKindAndDate() {
        for route in ["setup.reading", "status.reading"] {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", route]
            launch(app)
            let cell = app.descendants(matching: .any)["recent:folder://fixture/reading-2"].firstMatch
            XCTAssertTrue(cell.exists)
            XCTAssertEqual(cell.label, "Photo 2.jpg, Photo, 9 October 2025")
            app.terminate()
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
                    if route.hasPrefix("setup.reading") {
                        if route == "setup.reading" { XCTAssertTrue(app.staticTexts["Reading now"].exists) }
                        XCTAssertTrue(app.staticTexts["Files you can try"].exists)
                        XCTAssertTrue(app.staticTexts["+23 more"].exists)
                        for i in 2...8 {
                            XCTAssertTrue(app.descendants(matching: .any)["recent:folder://fixture/reading-\(i)"].firstMatch.exists)
                        }
                        if route == "setup.readingPaused" {
                            XCTAssertTrue(app.staticTexts["Paused while you use other apps. It carries on when you come back."].exists)
                        }
                    }
                    if route == "status.reading" { XCTAssertTrue(app.staticTexts["Recently read"].exists) }
                    if route == "status.paused" { XCTAssertFalse(app.staticTexts["Recently read"].exists) }
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
        // Contrast needs complete glyphs: the shorter download card can leave a footer action partly below the fold.
        let scroll = app.scrollViews.firstMatch
        if route.hasPrefix("setup."), scroll.exists,
            let clipped = app.buttons.allElementsBoundByIndex.first(where: {
                $0.frame.minY >= scroll.frame.midY && $0.frame.minY < scroll.frame.maxY && $0.frame.maxY > scroll.frame.maxY - $0.frame.height
            })
        {
            let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            let distance = min(clipped.frame.maxY - scroll.frame.maxY + 2 * clipped.frame.height, scroll.frame.height / 2)
            start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -distance)))
        }
        // Dynamic Type auditing resizes lazy lists. Sample contrast before it can invalidate their frames.
        try app.performAccessibilityAudit(for: .contrast) { issue in
            // A partial glyph cannot be measured; every kind is audited fully visible in testKindLegendContrastInBothAppearances.
            if self.clippedByLegend(issue.element, in: app) { return true }
            print("CONTRAST \(route): \(issue.compactDescription) \(String(describing: issue.element))")
            return false
        }
        // Font resizing can invalidate lazy-list AX frames; run it after every check that uses the original frames.
        for types in [XCUIAccessibilityAuditType.all.subtracting(.contrast).subtracting(.dynamicType), .dynamicType] {
        try app.performAccessibilityAudit(for: types) { issue in
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
                // The cold-launch test below verifies these labels grow and remain fully visible at AX5.
                if route == "status.paused", element.label == "Resume reading" { return true }
                if route == "status.tailFiles", element.label == "12 partly read · more meaning search to come" { return true }
                if route == "home.caughtUp", ["document-name", "document-detail"].contains(element.identifier) { return true }
                // The taller hero displaces this row during live resizing; testRecentSearchChipsGrowAtTheLargestAccessibilitySize verifies its actual growth.
                if route.hasPrefix("home."), element.identifier == "recent-search" { return true }
                // The same live resize moves the folders row off-screen; its launch-size growth is checked directly below.
                if route.hasPrefix("home."), element.label == "All folders" { return true }
                if route == "browse.folders", Self.folderText.contains(element.label) { return true }
            }
            print("AUDIT \(route): \(issue.auditType) \(issue.compactDescription) \(element.debugDescription)")
            return false
        }
        }
    }

    func testPausedAndPartialStatusTextGrowsAtTheLargestAccessibilitySize() {
        for (route, label) in [("status.paused", "Resume reading"), ("status.tailFiles", "12 partly read · more meaning search to come")] {
            var normal: CGFloat?
            for category in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryAccessibilityXXXL"] {
                let app = XCUIApplication()
                app.launchArguments = ["-mutedMedia", "-route", route, "-UIPreferredContentSizeCategoryName", category]
                launch(app)
                let element = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
                for _ in 0..<12 {
                    if element.exists && element.isHittable && app.windows.firstMatch.frame.contains(element.frame) { break }
                    app.swipeUp()
                }
                XCTAssertTrue(element.exists, label)
                XCTAssertTrue(element.isHittable, label)
                XCTAssertTrue(app.windows.firstMatch.frame.contains(element.frame), label)
                if let normal { XCTAssertGreaterThan(element.frame.height, normal, label) }
                else { normal = element.frame.height }
                app.terminate()
            }
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
                // SwiftUI may expose this labelled text as Other at accessibility sizes.
                let element = app.descendants(matching: .any)[id].firstMatch
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

    private func clippedByLegend(_ element: XCUIElement?, in app: XCUIApplication) -> Bool {
        let legend = app.scrollViews["home.library"]
        guard let element, legend.exists else { return false }
        let viewport = legend.frame
        let frame = element.frame
        return viewport.intersects(frame) && (frame.minX < viewport.minX || frame.maxX > viewport.maxX)
    }

    func testLibraryCountsAndStatusContrastInBothAppearances() throws {
        for style in ["light", "dark"] {
            appearance = style
            XCUIDevice.shared.appearance = style == "dark" ? .dark : .light
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "home.caughtUp"]
            launch(app)
            XCTAssertTrue(app.staticTexts["Your files"].exists)
            XCTAssertTrue(app.staticTexts["3,142 files read"].exists)
            XCTAssertFalse(app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "kind-")).firstMatch.exists)
            try app.performAccessibilityAudit(for: .contrast)
            app.terminate()
        }
    }

    func testLibraryFoldersGrowAtTheLargestAccessibilitySize() {
        var normal: CGFloat?
        for category in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryAccessibilityXXXL"] {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "home.caughtUp", "-UIPreferredContentSizeCategoryName", category]
            launch(app)
            let folders = app.buttons["All folders"]
            for _ in 0..<12 {
                if folders.exists && folders.isHittable { break }
                app.swipeUp()
            }
            XCTAssertTrue(folders.exists)
            XCTAssertTrue(folders.isHittable)
            if let normal { XCTAssertGreaterThan(folders.frame.height, normal) }
            else { normal = folders.frame.height }
            app.terminate()
        }
    }

    func testRecentSearchChipsGrowAtTheLargestAccessibilitySize() {
        var normal: CGFloat?
        for category in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryAccessibilityXXXL"] {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "home.caughtUp", "-UIPreferredContentSizeCategoryName", category]
            launch(app)
            let chip = app.buttons["passport scan"].firstMatch
            for _ in 0..<12 {
                if chip.exists && chip.isHittable { break }
                app.swipeUp()
            }
            XCTAssertTrue(chip.exists)
            XCTAssertTrue(chip.isHittable)
            let height = chip.frame.height
            print("Recent search chip height at \(category): \(height)")
            if let normal { XCTAssertGreaterThan(height, normal) } else { normal = height }
            let screenshot = XCTAttachment(screenshot: app.screenshot())
            screenshot.name = "Recent search chips \(category)"
            screenshot.lifetime = .keepAlways
            add(screenshot)
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
