import SwiftUI
import UIKit

@main
struct RetrieverApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model: AppModel
    /// The app's phase, not one window's: on iPad, another window going to the background mustn't stop reading.
    @Environment(\.scenePhase) private var phase

    init() {
        #if DEBUG
            if CommandLine.arguments.contains("-selftest") {
                SelfTest.run()
                // The self-test drives the facade itself; a live loop would race it for the index.
                _model = State(initialValue: AppModel(fixture: .caughtUp))
                return
            }
            if CommandLine.arguments.contains("-eval") { EvalMode.run(); _model = State(initialValue: AppModel(fixture: .caughtUp)); return }
            if let route = DebugRoute.current {
                _model = State(initialValue: AppModel(fixture: route.fixture))
                return
            }
            BackgroundDrill.armIfAsked()
        #endif
        IndexTask.register()
        _model = State(initialValue: AppModel(services: .shared))
    }

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
        }
        .onChange(of: phase, initial: true) { _, phase in model.setPhase(phase) }
    }
}

/// iOS relaunches the app to hand over a finished model download; the session's delegate takes it from there.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == ModelDownload.sessionId else { return completionHandler() }
        AppServices.shared.download.backgroundCompletion = completionHandler
    }
}
