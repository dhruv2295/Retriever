import SwiftUI
import UIKit

@main
struct RetrieverApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        #if DEBUG
            if CommandLine.arguments.contains("-selftest") { SelfTest.run() }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

/// iOS relaunches the app to hand over a finished model download; the session's delegate takes it from there.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == ModelDownload.sessionId else { return completionHandler() }
        AppServices.shared.download.backgroundCompletion = completionHandler
    }
}
