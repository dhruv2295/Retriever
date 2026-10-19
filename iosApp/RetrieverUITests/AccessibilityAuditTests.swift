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
        "3 partly read · more meaning search to come",
        "Retriever reads while it’s open and in background time provided by iOS. Automatic reading waits for charging. A short reading session can use battery. Background timing is up to iOS; plugging in doesn’t guarantee an immediate start.",
    ]
    private static let routes = [
        "setup.welcome", "setup.photos", "setup.limited", "setup.denied", "setup.folders", "setup.textOnly", "setup.reading",
        "setup.readingPaused", "setup.sampleDone", "setup.try", "setup.tryNoSuggestions", "setup.choose",
        "upgrade.offer", "upgrade.download", "upgrade.waitingWifi", "upgrade.failed", "upgrade.tuning", "upgrade.meaning",
        "status.reading", "status.caughtUp", "status.paused", "status.skipped", "status.textOnly", "status.tailFiles",
        "home.caughtUp", "home.reading", "home.empty", "home.upgrade", "search.empty", "search.wordsOnly", "search.noModel",
        "search.lessLikely", "search.nothingWell", "search.firstPart", "search.error", "search.loading", "search.noAccess", "search.reading",
    ]

    func testEveryFixtureRoutePassesTheAccessibilityAudit() throws {
        try auditFixtures()
    }

    func testEveryFixtureRoutePassesTheAccessibilityAuditInDarkAppearance() throws {
        appearance = "dark"
        XCUIDevice.shared.appearance = .dark
        try auditFixtures()
    }

    func testDownloadCardKeepsProgressAndCancelAccessibleInBothAppearances() throws {
        for style in ["light", "dark"] {
            appearance = style
            XCUIDevice.shared.appearance = style == "dark" ? .dark : .light
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "upgrade.download"]
            launch(app)
            let progress = app.staticTexts["download-amount"]
            let cancel = app.buttons["Cancel"]
            XCTAssertTrue(progress.exists)
            // Rounded: the frame comes back in floating point, e.g. 43.99999999999997 for a 44 pt minimum.
            XCTAssertGreaterThanOrEqual(cancel.frame.height.rounded(), 44)
            XCTAssertGreaterThanOrEqual(cancel.frame.width.rounded(), 44)
            XCTAssertEqual(progress.frame.midY, cancel.frame.midY, accuracy: 14)
            try audit(app, route: "upgrade.download")
            app.terminate()
        }
    }

    /// The limited-Photos access screen shows "Choose more photos" whole, not cut by the scroll view's edge.
    func testLimitedPhotosAccessShowsChooseMorePhotosWhole() {
        let app = XCUIApplication()
        app.launchArguments = ["-mutedMedia", "-route", "setup.limited"]
        launch(app)
        defer { app.terminate() }
        let more = app.buttons["Choose more photos"]
        for _ in 0..<6 {
            if more.exists && more.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(more.isHittable)
        let continueButton = app.buttons["Continue"]
        XCTAssertTrue(continueButton.isHittable)
        XCTAssertLessThanOrEqual(more.frame.maxY, continueButton.frame.minY, "the footer never covers it")
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
                        // Six real previews at most, the file being read first; no slash counters.
                        for i in 2...6 {
                            XCTAssertTrue(app.descendants(matching: .any)["recent:folder://fixture/reading-\(i)"].firstMatch.exists)
                        }
                        XCTAssertFalse(app.descendants(matching: .any)["recent:folder://fixture/reading-8"].firstMatch.exists)
                        XCTAssertTrue(app.buttons["Try search"].exists)
                        XCTAssertTrue(app.buttons["Go to Home"].exists)
                        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS '/'")).firstMatch.exists)
                        if route == "setup.reading" {
                            XCTAssertEqual(app.descendants(matching: .any)["recent:folder://fixture/reading-1"].firstMatch.value as? String, "Reading now")
                        } else {
                            XCTAssertTrue(app.staticTexts["Paused while you use other apps. It carries on when you come back."].exists)
                        }
                    }
                    if route.hasPrefix("setup.try") {
                        XCTAssertTrue(app.staticTexts["Searching inside 54 sample files"].exists)
                        XCTAssertTrue(app.buttons["Choose when to read more"].exists)
                        XCTAssertTrue(app.buttons["Go to Home"].exists)
                    }
                    if route == "search.noModel" {
                        XCTAssertTrue(app.staticTexts["Words only"].exists)
                        XCTAssertTrue(app.buttons["Download for meaning search"].exists, "one result is few: the hint shows")
                    }
                    if route == "home.upgrade" { XCTAssertTrue(app.buttons["Not now"].exists) }
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
            ("viewer.pdf", "Document_01", "More actions"),
            ("viewer.video", "", "More actions"),
            ("viewer.photo", "IMG_1001", "More actions"),
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
        // Contrast needs complete glyphs: a button in the scrolling content can sit partly below the fold. The actions
        // pinned under it (safeAreaInset) are never clipped, so only the content's own buttons count.
        let scroll = app.scrollViews.firstMatch
        if route.hasPrefix("setup."), scroll.exists,
            let clipped = scroll.buttons.allElementsBoundByIndex.first(where: {
                $0.frame.minY >= scroll.frame.midY && $0.frame.minY < scroll.frame.maxY && $0.frame.maxY > scroll.frame.maxY - $0.frame.height
            })
        {
            let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            let distance = min(clipped.frame.maxY - scroll.frame.maxY + 2 * clipped.frame.height, scroll.frame.height / 2)
            start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -distance)))
            XCTAssertLessThanOrEqual(clipped.frame.maxY, scroll.frame.maxY - clipped.frame.height, "\(route): \(clipped.label) still clipped")
        }
        // Dynamic Type auditing resizes lazy lists. Sample contrast before it can invalidate their frames.
        try app.performAccessibilityAudit(for: .contrast) { issue in
            // A partial glyph cannot be measured; every kind is audited fully visible in testKindLegendContrastInBothAppearances.
            if self.clippedByLegend(issue.element, in: app) { return true }
            if let element = issue.element, self.underBottomBar(element, in: app) { return true }
            print("CONTRAST \(route): \(issue.compactDescription) \(String(describing: issue.element))")
            return false
        }
        // Text clipping and Dynamic Type cycle the font size. Content that fits at the smaller size resets the scroll
        // above, re-clipping the footer button, and element detection then reads its half glyphs (whether it samples
        // before or after the reset depends on load). Run the pixel checks first, on the revealed layout.
        // Font resizing can also invalidate lazy-list AX frames, so Dynamic Type runs last.
        for types in [XCUIAccessibilityAuditType.all.subtracting([.contrast, .textClipped, .dynamicType]), .textClipped, .dynamicType] {
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
                if issue.auditType != .hitRegion, self.underBottomBar(element, in: app) { return true }
                // A document preview's miniature page: hidden from VoiceOver and sized to its tile like a photo, falling
                // back to the kind's icon when its text no longer fits. The tile's own name and label carry the text.
                if [.textClipped, .dynamicType].contains(issue.auditType), element.identifier == "thumb-text" { return true }
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
                    // The status detail above moves the name-search hint the same way; its growth is checked below too.
                    if route.hasPrefix("home."), element.label == Self.nameSearchHint { return true }
                    // The upgrade row below them moves the same way; testHomeUpgradeRowGrowsAtTheLargestAccessibilitySize checks it.
                    if route == "home.upgrade", Self.upgradeText.contains(where: element.label.hasPrefix) { return true }
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
                if let normal { XCTAssertGreaterThan(element.frame.height, normal, label) } else { normal = element.frame.height }
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

    private static let nameSearchHint = "You can search every file by name"

    func testLibraryFoldersGrowAtTheLargestAccessibilitySize() {
        var normal: CGFloat?
        var normalHint: CGFloat?
        for category in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryAccessibilityXXXL"] {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "home.caughtUp", "-UIPreferredContentSizeCategoryName", category]
            launch(app)
            let hint = app.staticTexts[Self.nameSearchHint]
            for _ in 0..<12 {
                if hint.exists && hint.isHittable { break }
                app.swipeUp()
            }
            XCTAssertTrue(hint.exists)
            if let normalHint { XCTAssertGreaterThan(hint.frame.height, normalHint) } else { normalHint = hint.frame.height }
            let folders = app.buttons["All folders"]
            for _ in 0..<12 {
                if folders.exists && folders.isHittable { break }
                app.swipeUp()
            }
            XCTAssertTrue(folders.exists)
            XCTAssertTrue(folders.isHittable)
            if let normal { XCTAssertGreaterThan(folders.frame.height, normal) } else { normal = folders.frame.height }
            app.terminate()
        }
    }

    /// Label prefixes, top to bottom (the growth test only scrolls down); the body joins its size with a no-break space.
    private static let upgradeText = ["Search by meaning and what’s in your photos", "One download of", "Download", "Not now"]

    /// Home's upgrade row grows at AX5, the growth the live-resize audit loses when the taller hero scrolls the row away.
    func testHomeUpgradeRowGrowsAtTheLargestAccessibilitySize() {
        var normal: [String: CGFloat] = [:]
        for category in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryAccessibilityXXXL"] {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "home.upgrade", "-UIPreferredContentSizeCategoryName", category]
            launch(app)
            for label in Self.upgradeText {
                let element = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", label)).firstMatch
                // At AX5 the body is taller than the space above the search field, so wait only for its top to show.
                let window = app.windows.firstMatch.frame
                for _ in 0..<12 {
                    if element.exists && element.frame.minY >= window.minY && element.frame.minY < window.maxY * 0.75 { break }
                    app.swipeUp()
                }
                XCTAssertTrue(element.exists, label)
                let height = element.frame.height
                if let before = normal[label] { XCTAssertGreaterThan(height, before, label) } else { normal[label] = height }
            }
            app.terminate()
        }
    }

    /// A glyph partly under the pinned setup actions or the bottom search field cannot be measured. Scrolling brings
    /// it out, and the same tile, row or caption is audited where it is fully visible.
    private func underBottomBar(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        let midY = app.windows.firstMatch.frame.midY
        let bars = [app.otherElements["step-actions"], app.searchFields.firstMatch].filter { $0.exists && $0.frame.minY > midY }
        return bars.contains { $0.frame.intersects(element.frame) && !$0.frame.contains(element.frame) }
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
        XCTAssertTrue(app.buttons["More actions"].exists)
        let next = names.first { $0 != first }!
        app.swipeLeft()
        if !app.navigationBars.staticTexts[next].waitForExistence(timeout: 2) { app.swipeRight() }
        XCTAssertTrue(app.navigationBars.staticTexts[next].waitForExistence(timeout: 5), "opening from Browse must pass its photo neighbors")
        XCTAssertTrue(app.buttons["More actions"].exists, "the newly selected photo keeps its toolbar actions")
    }
}
