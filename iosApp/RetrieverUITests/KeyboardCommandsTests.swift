import XCTest

/// ⌘F and ⌘, from a hardware keyboard (an iPad's keyboard case, a Mac): the scene's commands reach the window.
@MainActor
final class KeyboardCommandsTests: XCTestCase {
    func testCommandFSearchesAndCommandCommaOpensSettings() {
        let app = XCUIApplication()
        app.launchArguments += ["-ui-testing", "-route", "home"]
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        // The debug route dismisses the keyboard 1.5 s after launch; press ⌘F after that.
        let settled = expectation(description: "Launch settled")
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { settled.fulfill() }
        wait(for: [settled], timeout: 5)

        app.typeKey("f", modifierFlags: .command)
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        let focused = NSPredicate(format: "hasKeyboardFocus == true")
        wait(for: [expectation(for: focused, evaluatedWith: field)], timeout: 5)

        app.typeKey(",", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))

        // From Settings, ⌘F goes back to Search.
        app.typeKey("f", modifierFlags: .command)
        XCTAssertTrue(app.navigationBars["Settings"].waitForNonExistence(timeout: 5))
    }
}
