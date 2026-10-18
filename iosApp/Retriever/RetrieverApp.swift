import SwiftUI
import UIKit

@main
struct RetrieverApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model: AppModel
    /// The app's phase, not one window's: on iPad, another window going to the background mustn't stop reading.
    @Environment(\.scenePhase) private var phase

    init() {
        // Hosting the unit tests: no index, no scheduled work, no services.
        if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil {
            _model = State(initialValue: AppModel(fixture: .caughtUp))
            return
        }
        #if DEBUG
            if CommandLine.arguments.contains("-selftest") {
                SelfTest.run()
                // The self-test drives the facade itself; a live loop would race it for the index.
                _model = State(initialValue: AppModel(fixture: .caughtUp))
                return
            }
            if CommandLine.arguments.contains("-eval") {
                EvalMode.run()
                _model = State(initialValue: AppModel(fixture: .caughtUp))
                return
            }
            if CommandLine.arguments.contains("-dump") {
                DumpMode.run()
                _model = State(initialValue: AppModel(fixture: .caughtUp))
                return
            }
            if let route = DebugRoute.current {
                _model = State(initialValue: AppModel(fixture: route.fixture))
                return
            }
            BackgroundDrill.armIfAsked()
            DebugRoute.starIfAsked(AppServices.shared.retriever)
        #endif
        IndexTask.register(services: .shared)
        _model = State(initialValue: AppModel(services: .shared))
    }

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                #if DEBUG
                    // `-keepreading`: taps "Keep reading after I leave" once it shows, to drill the submit with no taps.
                    .task {
                        guard CommandLine.arguments.contains("-keepreading") else { return }
                        while !model.canKeepReading { try? await Task.sleep(for: .seconds(1)) }
                        model.keepReading()
                    }
                #endif
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
