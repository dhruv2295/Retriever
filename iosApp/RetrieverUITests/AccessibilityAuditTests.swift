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
        "Processing now", "Photo", "9 October 2025", "Recently processed", "Pause",
        "Retriever processes your files while it’s open, and sometimes while you’re not using it, when iOS allows. Automatic processing waits until your iPhone is charging. Process for 15 minutes uses some battery.",
    ]
    private static let routes = [
        "setup.welcome", "setup.textOnly", "setup.access", "setup.limited", "setup.denied", "setup.folders", "setup.accessBoth",
        "setup.samplePicking", "setup.sampleStart", "setup.reading", "setup.waitingWifi", "setup.ready", "setup.paused", "setup.sampleDone",
        "upgrade.offer", "upgrade.download", "upgrade.waitingWifi", "upgrade.failed", "upgrade.tuning", "upgrade.meaning",
        "status.reading", "status.caughtUp", "status.paused", "status.skipped", "status.textOnly", "status.tailFiles",
        "home.caughtUp", "home.reading", "home.empty", "home.upgrade", "home.people", "home.charging", "home.firstDay", "home.unplugged", "home.chargingUp",
        "home.readNow", "home.downloading", "home.meaning", "home.noAccess", "home.notNow", "home.counting", "home.introReady", "home.introOnItsWay",
        "settings", "search.empty", "search.wordsOnly",
        "search.noModel",
        "search.lessLikely", "search.nothingWell", "search.firstPart", "search.error", "search.loading", "search.noAccess", "search.reading",
    ]

    /// The fixture routes by screen, light and dark each, so a screen's change reruns only its own audits and the parallel
    /// runner can spread them. Every route sits in exactly one area (testEveryFixtureRouteIsInExactlyOneAuditArea).
    private static let setupRoutes = [
        "setup.welcome", "setup.textOnly", "setup.access", "setup.limited", "setup.denied", "setup.folders", "setup.accessBoth",
    ]
    private static let upgradeRoutes = ["upgrade.offer", "upgrade.download", "upgrade.waitingWifi", "upgrade.failed", "upgrade.tuning", "upgrade.meaning"]
    private static let statusAndSettingsRoutes = [
        "status.reading", "status.caughtUp", "status.paused", "status.skipped", "status.textOnly", "status.tailFiles", "settings",
    ]
    private static let homeRoutes = [
        "home.caughtUp", "home.reading", "home.empty", "home.upgrade", "home.people", "home.introReady", "home.introOnItsWay",
    ]
    private static let homeStatusLineRoutes = [
        "home.charging", "home.firstDay", "home.unplugged", "home.chargingUp", "home.readNow", "home.downloading", "home.meaning", "home.noAccess",
        "home.notNow", "home.counting",
    ]
    private static let searchResultRoutes = ["search.wordsOnly", "search.noModel", "search.lessLikely", "search.nothingWell", "search.firstPart"]
    private static let searchStateRoutes = ["search.empty", "search.error", "search.loading", "search.noAccess", "search.reading"]
    private static let areas = [
        setupRoutes, sampleRoutes, upgradeRoutes, statusAndSettingsRoutes, homeRoutes, homeStatusLineRoutes, searchResultRoutes, searchStateRoutes,
    ]

    func testEveryFixtureRouteIsInExactlyOneAuditArea() {
        let audited = Self.areas.flatMap { $0 }
        XCTAssertEqual(Set(audited), Set(Self.routes), "a fixture route is missing from the audit areas, or an area names an unknown route")
        XCTAssertEqual(audited.count, Self.routes.count, "a fixture route is audited in two areas")
    }

    func testSetupRoutesPassTheAccessibilityAudit() throws { try auditFixtures(Self.setupRoutes) }
    func testSetupRoutesPassTheAccessibilityAuditInDarkAppearance() throws { try auditDark(Self.setupRoutes) }
    func testSetupSampleRoutesPassTheAccessibilityAudit() throws { try auditFixtures(Self.sampleRoutes) }
    func testSetupSampleRoutesPassTheAccessibilityAuditInDarkAppearance() throws { try auditDark(Self.sampleRoutes) }
    func testUpgradeRoutesPassTheAccessibilityAudit() throws { try auditFixtures(Self.upgradeRoutes) }
    func testUpgradeRoutesPassTheAccessibilityAuditInDarkAppearance() throws { try auditDark(Self.upgradeRoutes) }
    func testStatusAndSettingsRoutesPassTheAccessibilityAudit() throws { try auditFixtures(Self.statusAndSettingsRoutes) }
    func testStatusAndSettingsRoutesPassTheAccessibilityAuditInDarkAppearance() throws { try auditDark(Self.statusAndSettingsRoutes) }
    func testHomeRoutesPassTheAccessibilityAudit() throws { try auditFixtures(Self.homeRoutes) }
    func testHomeRoutesPassTheAccessibilityAuditInDarkAppearance() throws { try auditDark(Self.homeRoutes) }
    func testHomeStatusLineRoutesPassTheAccessibilityAudit() throws { try auditFixtures(Self.homeStatusLineRoutes) }
    func testHomeStatusLineRoutesPassTheAccessibilityAuditInDarkAppearance() throws { try auditDark(Self.homeStatusLineRoutes) }
    func testSearchResultRoutesPassTheAccessibilityAudit() throws { try auditFixtures(Self.searchResultRoutes) }
    func testSearchResultRoutesPassTheAccessibilityAuditInDarkAppearance() throws { try auditDark(Self.searchResultRoutes) }
    func testSearchStateRoutesPassTheAccessibilityAudit() throws { try auditFixtures(Self.searchStateRoutes) }
    func testSearchStateRoutesPassTheAccessibilityAuditInDarkAppearance() throws { try auditDark(Self.searchStateRoutes) }

    private func auditDark(_ routes: [String]) throws {
        appearance = "dark"
        XCUIDevice.shared.appearance = .dark
        try auditFixtures(routes)
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

    /// The limited-Photos access screen shows "Choose more" whole, not cut by the scroll view's edge.
    func testLimitedPhotosAccessShowsChooseMoreWhole() {
        let app = XCUIApplication()
        app.launchArguments = ["-mutedMedia", "-route", "setup.limited"]
        launch(app)
        defer { app.terminate() }
        let more = app.buttons["Choose more"]
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
        for route in ["status.reading"] {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", route]
            launch(app)
            let cell = app.descendants(matching: .any)["recent:folder://fixture/reading-2"].firstMatch
            XCTAssertTrue(cell.exists)
            XCTAssertEqual(cell.label, "Photo 2.jpg, Photo, 9 October 2025")
            app.terminate()
        }
    }

    private func auditFixtures(_ routes: [String]) throws {
        continueAfterFailure = true
        for route in routes {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", route]
            launch(app)
            XCTContext.runActivity(named: route) { _ in
                do {
                    if Self.sampleRoutes.contains(route) {
                        // One pile element saying the count, with See the files as its action; its cards are not separate elements.
                        let pile = app.descendants(matching: .any)["sample-pile"].firstMatch
                        let counts = [
                            "setup.samplePicking": "Picking your recent files…", "setup.sampleStart": "0 of 30 files processed",
                            "setup.sampleDone": "30 of 30 files processed", "setup.ready": "18 of 30 files processed",
                        ]
                        // Always "of 30": the total is fixed once the files are picked.
                        let count = counts[route] ?? "12 of 30 files processed"
                        XCTAssertEqual(pile.label, count)
                        XCTAssertTrue(app.staticTexts[count].exists)
                        XCTAssertFalse(app.descendants(matching: .any)["recent:folder://fixture/reading-2"].firstMatch.exists)
                        // The search works from the first frame, and Continue is the one way on, enabled throughout. The
                        // field asks for a description only once search by description is ready.
                        let hint = route == "setup.ready" ? "Describe a photo or file" : "Search by name or words"
                        XCTAssertTrue(app.searchFields[hint].exists, "\(route): \(hint)")
                        XCTAssertEqual(app.staticTexts["Search by description is ready"].exists, route == "setup.ready")
                        let lines = [
                            "setup.waitingWifi": "Waiting for Wi-Fi to download search by description.",
                            "setup.reading": "Downloading search by description · 64%",
                        ]
                        if let line = lines[route] {
                            let shown = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", line)).firstMatch
                            XCTAssertTrue(shown.exists, "\(route): \(line)")
                        }
                        XCTAssertEqual(app.buttons["Use mobile data"].exists, route == "setup.waitingWifi")
                        // A pause takes the description line's place: it says why the count stands still.
                        let paused = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Paused, battery is low'")).firstMatch
                        XCTAssertEqual(paused.exists, route == "setup.paused", route)
                        XCTAssertTrue(app.buttons["Continue"].isEnabled)
                        XCTAssertFalse(app.buttons["Try search"].exists)
                        XCTAssertFalse(app.buttons["Skip for now"].exists)
                        // Chips once the demo has three suggestions; before, the hint in their place.
                        let chips = !["setup.samplePicking", "setup.sampleStart"].contains(route)
                        XCTAssertEqual(app.buttons["wifi password"].exists, chips)
                        XCTAssertEqual(app.staticTexts["Type a word you remember from a recent photo, screenshot or document."].exists, !chips)
                        XCTAssertFalse(app.staticTexts["Taking a short break"].exists, "no status leaks in before the plan")
                        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS '/'")).firstMatch.exists)
                    }
                    // Continue waits for Photos or a folder; given either, it goes on. Nothing on Hello asks for access.
                    if Self.setupRoutes.contains(route) {
                        let hello = ["setup.welcome", "setup.textOnly"].contains(route)
                        XCTAssertEqual(app.buttons["Get started"].exists, hello, route)
                        if !hello {
                            XCTAssertEqual(app.buttons["Continue"].isEnabled, !["setup.access", "setup.denied"].contains(route), route)
                            XCTAssertEqual(app.buttons["Allow"].exists, route == "setup.access" || route == "setup.folders", route)
                            XCTAssertEqual(app.buttons["Settings"].exists, route == "setup.denied", route)
                        }
                        XCTAssertEqual(app.staticTexts["Includes a one-time 388\u{00A0}MB download, on Wi-Fi."].exists, route == "setup.welcome", route)
                    }
                    // Home's one-time card: the recent files' state, and its button only where it helps.
                    if route.hasPrefix("home.intro") {
                        XCTAssertTrue(app.staticTexts["Retriever keeps going on its own"].exists)
                        XCTAssertTrue(app.buttons["Got it"].exists)
                        let body =
                            route == "home.introReady"
                            ? "Find them by describing them." : "Find them by name or words now. Search by description arrives when setup finishes."
                        let shown = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", body)).firstMatch
                        XCTAssertTrue(shown.exists, "\(route): \(body)")
                    }
                    if route == "search.noModel" {
                        XCTAssertFalse(app.staticTexts["Words only"].exists, "retired: nothing is broken")
                        XCTAssertTrue(app.buttons["Download for search by description"].exists, "one result is few: the hint shows")
                    }
                    // home.upgrade's Not now is Home's last row, below the fold: testHomeUpgradeRowGrowsAtTheLargestAccessibilitySize
                    // scrolls to it (scrolling here would change what the audit below sees).
                    if route == "status.reading" { XCTAssertTrue(app.staticTexts["Recently processed"].exists) }
                    if route == "status.paused" { XCTAssertFalse(app.staticTexts["Recently processed"].exists) }
                    // Home's one status line, its button spelled out (clarity-proposal section 3).
                    let lines =
                        [
                            "home.charging": ("Processing while you charge", nil),
                            "home.firstDay": ("Only names can be found for now", "Choose when to process"),
                            "home.notNow": ("Only names can be found for now", "Choose when to process"),
                            "home.counting": ("Counting your files…", nil),
                            "home.unplugged": ("Waits until you charge", "Process for 15 minutes"), "home.chargingUp": ("Charging up first", nil),
                            "home.readNow": ("Processing for 15 minutes · 12 min left", "Stop"),
                            "home.downloading": ("Downloading search by description · 34%", "Stop"), "home.meaning": ("Adding search by description", nil),
                            "home.noAccess": ("Retriever can’t see your files", "Allow access"),
                        ] as [String: (String, String?)]
                    if let line = lines[route] {
                        XCTAssertTrue(app.staticTexts[line.0].exists, "\(route): \(line.0)")
                        if let button = line.1 { XCTAssertTrue(app.buttons[button].exists, "\(route): \(button)") }
                        XCTAssertFalse(app.staticTexts["Your files"].exists, "no counts on Home")
                    }
                    // The line's live count, not a percent: 1,204 processed of 5,310 (1,938 of 3,142 with meaning); the
                    // demo's 30 carry over to Not now's whole-phone count. Counting has no count and no bar.
                    let liveCounts = [
                        "home.charging": "1,204 of 5,310 files processed", "home.unplugged": "1,204 of 5,310 files processed",
                        "home.meaning": "1,938 of 3,142 files have search by description", "home.notNow": "30 of 25,793 files processed",
                        "status.reading": "1,204 of 5,310 files processed",
                    ]
                    if let count = liveCounts[route] {
                        let shown = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", count)).firstMatch
                        XCTAssertTrue(shown.exists, "\(route): \(count)")
                    }
                    if route == "home.counting" {
                        XCTAssertFalse(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS ' files processed'")).firstMatch.exists)
                    }
                    try audit(app, route: route)
                } catch { XCTFail("\(route): \(error)") }
            }
            app.terminate()
        }
    }

    private static let sampleRoutes = [
        "setup.samplePicking", "setup.sampleStart", "setup.reading", "setup.waitingWifi", "setup.ready", "setup.paused", "setup.sampleDone",
    ]

    /// The demo's group was left-aligned under a centred dog and pile, with the spare height in one band above the
    /// footer, and rows came and went between states. Now it is centred both ways, and every state lays out the same rows.
    func testTheDemoGroupSitsCentredAndHoldsItsPlaceInEveryState() {
        var frames: [String: (title: CGRect, count: CGRect, bar: CGRect)] = [:]
        for route in Self.sampleRoutes {
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", route]
            launch(app)
            defer { app.terminate() }
            let title = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'A first look'")).firstMatch
            let group = app.descendants(matching: .any)["step-content"].firstMatch
            let actions = app.descendants(matching: .any)["step-actions"].firstMatch
            let count = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH 'processed' OR label BEGINSWITH 'Picking'")).firstMatch
            let bar = app.progressIndicators.firstMatch
            XCTAssertTrue(title.exists && count.exists && group.exists && actions.exists && bar.exists, route)
            // The pile's own frame follows its turned cards, so the group's centre line stands in for it.
            XCTAssertEqual(title.frame.midX, group.frame.midX, accuracy: 1, "\(route): the title is centred")
            XCTAssertEqual(count.frame.midX, group.frame.midX, accuracy: 1, "\(route): the count is centred")
            // Centred between the bar above (the navigation bar, with the search field under it before iOS 26) and the
            // pinned actions. A group pinned to the top, with the spare height in one band above the actions, sits well
            // above that. The group's test frame spans only its accessible rows, and before the chips arrive their held
            // place is hidden, so its frame ends short there; the rows' equal places below carry the centring over.
            let top = app.navigationBars.firstMatch.frame.maxY
            if !["setup.samplePicking", "setup.sampleStart"].contains(route), group.frame.height + 32 < actions.frame.minY - top {
                XCTAssertEqual(group.frame.midY, (top + actions.frame.minY) / 2, accuracy: 12, "\(route): spare height split above and below")
            }
            frames[route] = (title.frame, count.frame, bar.frame)
        }
        let first = frames["setup.samplePicking"]
        for (route, f) in frames {
            // Its height is held by the longest title, so picking's shorter one sits on the same centre line.
            XCTAssertEqual(f.title.midY, first?.title.midY ?? 0, accuracy: 0.5, "\(route): the title never moves between states")
            XCTAssertEqual(f.title.midX, first?.title.midX ?? 0, accuracy: 0.5, "\(route): the title never moves between states")
            // The count sits on the bar: the long picking sentence and a short count end at the same line.
            XCTAssertEqual(f.count.maxY, first?.count.maxY ?? 0, accuracy: 0.5, "\(route): the count never moves between states")
            XCTAssertEqual(f.bar, first?.bar, "\(route): the bar never moves between states")
        }
    }

    /// Reading now left the screen between two files and came back with the next, so the rows under it jumped on every
    /// file. It keeps its last file, its header and its height while the next one starts.
    func testReadingNowHoldsItsRowAsFilesTurnOver() {
        let app = XCUIApplication()
        app.launchArguments = ["-mutedMedia", "-route", "status.readingTurnover"]
        launch(app)
        defer { app.terminate() }
        let row = app.descendants(matching: .any)["reading-now"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        let height = row.frame.height
        var names: Set<String> = []
        for _ in 0..<12 {
            XCTAssertTrue(app.staticTexts["Processing now"].exists)
            XCTAssertTrue(row.exists)
            XCTAssertEqual(row.frame.height, height, accuracy: 0.5)
            names.insert(row.label)
            Thread.sleep(forTimeInterval: 0.15)
        }
        XCTAssertGreaterThan(names.count, 1, "the files did turn over")
    }

    /// After a chip's results, Back took two presses on Android. Results open the full search; one Back returns to the
    /// demo, which has no Back of its own.
    func testOneBackLeavesAChipsResults() {
        let app = XCUIApplication()
        app.launchArguments = ["-mutedMedia", "-route", "setup.reading"]
        launch(app)
        defer { app.terminate() }
        let suggestion = app.buttons["wifi password"]
        XCTAssertTrue(suggestion.waitForExistence(timeout: 5), "the chips are there on the first frame")
        XCTAssertFalse(app.navigationBars.buttons["Back"].exists, "the demo has no Back")
        suggestion.tap()
        XCTAssertTrue(suggestion.waitForNonExistence(timeout: 5), "the results replace the demo")
        XCTAssertEqual(app.keyboards.count, 0, "a chip searches without the keyboard")
        app.navigationBars.buttons["Back"].tap()
        XCTAssertTrue(suggestion.waitForExistence(timeout: 5), "one Back, and the demo is back")
        XCTAssertTrue(app.buttons["Continue"].exists, "still on the demo")
    }

    /// Home's tiles offer the file actions every other file has on a long press.
    func testHomeTilesOfferTheFileActions() {
        let app = XCUIApplication()
        app.launchArguments = ["-mutedMedia", "-route", "home.caughtUp"]
        launch(app)
        defer { app.terminate() }
        // Not the strip's first tile, which is starred: each tile's menu must be its own file's.
        let cell = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Photo, '")).firstMatch
        XCTAssertTrue(cell.waitForExistence(timeout: 5))
        cell.press(forDuration: 1.2)
        XCTAssertTrue(app.buttons["Add to Starred"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Remove from Starred"].exists)
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
            if let element = issue.element, self.underABar(element, in: app) { return true }
            // WCAG 1.4.3 exempts inactive controls.
            if issue.element?.isEnabled == false { return true }
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
                if issue.auditType != .hitRegion, self.underABar(element, in: app) { return true }
                // These literal source filenames are the correct viewer titles, not missing app-authored labels.
                if route.hasPrefix("viewer."), issue.auditType == .sufficientElementDescription,
                    ["Document_01.pdf", "talk-and-show.mp4", "IMG_1001.jpg"].contains(element.label),
                    app.navigationBars.firstMatch.frame.contains(element.frame)
                {
                    return true
                }
                // The native field grows and its entire placeholder fits at AX5 (screenshot and typing test below).
                if issue.auditType == .textClipped, element.elementType == .searchField, element.label == "What are you looking for?" { return true }
                // These folder Labels are reported as clipped, but the AX5 screenshots below show both full labels.
                if route == "browse.folders", issue.auditType == .textClipped, ["audit", "Add a folder"].contains(element.label) { return true }
                // The audit loses these lazy-list elements when resizing scrolls them off screen. The tests below
                // relaunch at AX5, scroll them into view, and assert that each label actually grows.
                if issue.auditType == .dynamicType {
                    if route.hasPrefix("status."), Self.statusText.contains(element.label) { return true }
                    // The cold-launch test below verifies these labels grow and remain fully visible at AX5.
                    if Self.grown.contains(where: { $0 == (route, element.label) }) { return true }
                    // Every Home route has the same Starred header; home.charging's is checked to grow.
                    if route.hasPrefix("home."), element.label == "Starred" { return true }
                    // Live resizing moves the folders row off-screen; its launch-size growth is checked directly below.
                    if route.hasPrefix("home."), element.label == "All folders" { return true }
                    // The upgrade row below them moves the same way; testHomeUpgradeRowGrowsAtTheLargestAccessibilitySize checks it.
                    if route == "home.upgrade", Self.upgradeText.contains(where: element.label.hasPrefix) { return true }
                    if route == "browse.folders", Self.folderText.contains(element.label) { return true }
                    // Home's one-time card pushes the status line below it off screen as the text grows; that line is
                    // audited whole on its own routes (home.charging, home.downloading). The card's own rows still count.
                    if route.hasPrefix("home.intro"), element.frame.minY > app.buttons["Got it"].frame.maxY { return true }
                }
                print("AUDIT \(route): \(issue.auditType) \(issue.compactDescription) \(element.debugDescription)")
                return false
            }
        }
    }

    /// Status lines the live resize loses; each is relaunched at AX5 below and checked to grow.
    private static let grown = [
        ("status.paused", "Resume"), ("status.tailFiles", "Taking a short break"), ("status.reading", "Photo 1.jpg"),
        ("home.charging", "Starred"), ("home.firstDay", "Choose when to process"), ("home.noAccess", "Allow access"),
        // The live count's sentence pushes these buttons lower, where the resize loses them.
        ("home.unplugged", "Process for 15 minutes"), ("home.readNow", "Stop"),
        ("settings", "When to process"), ("settings", "Process these file types"), ("settings", "Adjust processing for this iPhone"),
        ("settings", "When your iPhone is charging and you’re not using it, usually overnight. You can pause any time."),
        // Search by description sits just under When to process, at the first screen's fold.
        ("settings", "Search by description"), ("settings", "Find files by describing them"), ("settings", "Downloaded · 388\u{00A0}MB"),
    ]

    func testPausedAndPartialStatusTextGrowsAtTheLargestAccessibilitySize() {
        for (route, label) in Self.grown {
            var normal: CGFloat?
            for category in ["UICTContentSizeCategoryL", "UICTContentSizeCategoryAccessibilityXXXL"] {
                let app = XCUIApplication()
                app.launchArguments = ["-mutedMedia", "-route", route, "-UIPreferredContentSizeCategoryName", category]
                launch(app)
                let element = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
                // Settings at AX5, with its prominent headers, is many screens tall.
                let window = app.windows.firstMatch
                for _ in 0..<30 {
                    if element.exists && element.isHittable && window.frame.contains(element.frame) { break }
                    guard element.exists else {
                        app.swipeUp()
                        continue
                    }
                    // A full swipe carries a tall AX5 paragraph from below the fold past the top; nudge it in instead.
                    let from = window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                    let up = element.frame.maxY > window.frame.maxY
                    from.press(forDuration: 0.1, thenDragTo: from.withOffset(CGVector(dx: 0, dy: up ? -120 : 120)))
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
                // Not an image: a thumbnail's kind icon (hidden from VoiceOver, still in the test tree) is also "Photo",
                // and its fixed 56 pt frame never grows.
                let element = app.descendants(matching: .any).matching(
                    NSPredicate(format: "label == %@ AND elementType != %d", label, XCUIElement.ElementType.image.rawValue)
                ).firstMatch
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

    private func clippedByLegend(_ element: XCUIElement?, in app: XCUIApplication) -> Bool {
        let legend = app.scrollViews["home.library"]
        guard let element, legend.exists else { return false }
        let viewport = legend.frame
        let frame = element.frame
        return viewport.intersects(frame) && (frame.minX < viewport.minX || frame.maxX > viewport.maxX)
    }

    func testStatusLineContrastInBothAppearances() throws {
        for style in ["light", "dark"] {
            appearance = style
            XCUIDevice.shared.appearance = style == "dark" ? .dark : .light
            let app = XCUIApplication()
            app.launchArguments = ["-mutedMedia", "-route", "home.caughtUp"]
            launch(app)
            XCTAssertTrue(app.staticTexts["All done. Retriever has processed everything it can"].exists)
            // The status line sits at the top; what scrolled under the bottom search bar is audited elsewhere.
            try app.performAccessibilityAudit(for: .contrast) { issue in issue.element.map { self.underABar($0, in: app) } ?? false }
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
            if let normal { XCTAssertGreaterThan(folders.frame.height, normal) } else { normal = folders.frame.height }
            app.terminate()
        }
    }

    /// Label prefixes, top to bottom (the growth test only scrolls down); the body joins its size with a no-break space.
    private static let upgradeText = ["Find files by describing them", "One download of", "Download", "Not now"]

    /// Home's optional upgrade row grows at AX5, the growth the live-resize audit loses when the rows above scroll it away.
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

    /// A glyph cut by the screen's bottom edge, or partly or wholly under the pinned setup actions or the bottom search
    /// field, cannot be measured: the bar's own text is inside its frame and hittable, content scrolled behind it is not.
    /// Scrolling brings it out, and the same tile, row or caption is audited where it is fully visible.
    private func underABar(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        let window = app.windows.firstMatch.frame
        let frame = element.frame
        if frame.minY < window.maxY && frame.maxY > window.maxY { return true }
        let bars = [app.otherElements["step-actions"], app.searchFields.firstMatch].filter { $0.exists && $0.frame.minY > window.midY }
        // The floating search field's glass reaches past the field's own frame; a row scrolled behind it is faded by design.
        if bars.contains(where: { $0.frame.insetBy(dx: 0, dy: -12).intersects(frame) && (!$0.frame.contains(frame) || !element.isHittable) }) {
            return true
        }
        // The same at the top: scrolling a clipped tile into view can push a header or tile partly under the navigation
        // bar or off the top edge; each is audited fully visible at launch.
        if frame.minY < window.minY && frame.maxY > window.minY { return true }
        let bar = app.navigationBars.firstMatch
        return bar.exists && frame.minY < bar.frame.maxY && frame.maxY > bar.frame.maxY
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
