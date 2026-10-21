import SwiftUI
import XCTest

@testable import Retriever

/// Every clarity state as a picture, in light and dark, and Home and Reading at the largest text size: the same fixture
/// routes the accessibility audit opens, hosted in a window and saved to iosApp/build/shots/clarity_*.png. ImageRenderer
/// draws a List (a UICollectionView) blank, so each screen is hosted and drawn from its window instead.
@MainActor
final class ClarityShotsTests: XCTestCase {
    private static let out = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("build/shots")

    private static let states: [DebugRoute] = [
        .homeCharging, .homeFirstDay, .homeUnplugged, .homeChargingUp, .homeReadNow, .homeDownloading, .homeMeaning, .homeCaughtUp,
        .homeNoAccess, .statusReading, .statusPaused, .settings, .setupChoose, .setupWelcome, .setupReading,
    ]

    func testEveryClarityStateRenders() async throws {
        try FileManager.default.createDirectory(at: Self.out, withIntermediateDirectories: true)
        for route in Self.states {
            for dark in [false, true] { try await shot(route, dark: dark) }
        }
        try await shot(.homeCharging, largest: true)
        try await shot(.statusReading, largest: true)
    }

    /// The first run as docs/features/demo-flow.md lays it out, then Home and Processing under the one count rule:
    /// iosApp/build/shots/demo_*.png. The welcome is the one with access in place, so it shows Start the demo.
    private static let demoStates: [DebugRoute] = [
        .setupFolders, .setupSamplePicking, .setupReading, .setupSampleDone, .setupChoose, .setupChooseSkipped,
        .homeCharging, .homeUnplugged, .homeNotNow, .homeCounting, .statusReading,
    ]

    func testTheDemoFlowRenders() async throws {
        try FileManager.default.createDirectory(at: Self.out, withIntermediateDirectories: true)
        for route in Self.demoStates {
            for dark in [false, true] { try await shot(route, dark: dark, prefix: "demo") }
        }
        try await shot(.setupReading, largest: true, prefix: "demo")
        try await shot(.homeCharging, largest: true, prefix: "demo")
    }

    private func shot(_ route: DebugRoute, dark: Bool = false, largest: Bool = false, prefix: String = "clarity") async throws {
        let model = AppModel(fixture: route.fixture)
        model.memoriesLoaded = true
        let screen = route.screen(model, path: .constant([]))
            .environment(model)
            .environment(\.dynamicTypeSize, largest ? .accessibility5 : .large)
            .environment(\.horizontalSizeClass, .compact)
        let host = UIHostingController(rootView: screen)
        // UIKit's own size too: the bars, the search field and @ScaledMetric tiles follow the trait, not the environment.
        if largest { host.traitOverrides.preferredContentSizeCategory = .accessibilityExtraExtraExtraLarge }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 393, height: largest ? 2_400 : 1_400)
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        // SwiftUI lays a List out over a few run-loop turns; the shade check below fails a blank picture.
        for _ in 0..<6 {
            try await Task.sleep(for: .milliseconds(100))
            host.view.layoutIfNeeded()
        }
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            _ = window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let pixels = try XCTUnwrap(image.cgImage?.dataProvider?.data) as Data
        XCTAssertGreaterThan(Set(stride(from: 0, to: pixels.count, by: 97).map { pixels[$0] }).count, 8, "\(route.rawValue) rendered blank")
        let name = "\(prefix)_\(route.rawValue.replacingOccurrences(of: ".", with: "_"))_\(largest ? "largest" : dark ? "dark" : "light").png"
        try XCTUnwrap(image.pngData()).write(to: Self.out.appendingPathComponent(name))
    }
}
