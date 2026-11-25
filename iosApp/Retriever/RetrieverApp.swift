import RetrieverCore
import SwiftUI
import UIKit

@main
struct RetrieverApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model: AppModel
    /// The app's phase, not one window's: on iPad, another window going to the background mustn't stop reading.
    @Environment(\.scenePhase) private var phase

    init() {
        // Hosting the unit tests or Xcode's previews: no index, no scheduled work, no services. Each preview builds its own
        // fixture model; the services' start crashed every preview.
        let env = ProcessInfo.processInfo.environment
        if env["XCTestConfigurationFilePath"] != nil || env["XCODE_RUNNING_FOR_PREVIEWS"] == "1" {
            _model = State(initialValue: AppModel(fixture: .caughtUp))
            return
        }
        // The bundle's own pick from the person's languages, so core's sentences match the app's (iOS restarts the app on a change).
        Words.shared.language = Language.companion.of(tag: Bundle.main.preferredLocalizations.first)
        #if DEBUG
            if CommandLine.arguments.contains("-ui-testing") { UIView.setAnimationsEnabled(false) }
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
        .commands { RetrieverCommands() }
    }
}

/// What the focused window does for the menu bar's and the keyboard's commands.
struct WindowCommands {
    let search: () -> Void
    let settings: () -> Void
}

extension FocusedValues {
    @Entry var windowCommands: WindowCommands?
}

extension EnvironmentValues {
    /// ⌘F's request in a window, pending until Home shows and raises its search field.
    @Entry var searchRequest: Binding<Bool> = .constant(false)
}

/// ⌘F and ⌘, with an iPad's keyboard or on a Mac, listed when ⌘ is held and in iPadOS's menu bar. The commands act on
/// the focused window through its focusedSceneValue, as Apple's FocusedValues documentation sets out.
struct RetrieverCommands: Commands {
    @FocusedValue(\.windowCommands) private var window

    var body: some Commands {
        CommandGroup(replacing: .appSettings) {
            Button(Words.shared.strings.appSettings + "…") { window?.settings() }.keyboardShortcut(",").disabled(window == nil)
        }
        CommandMenu(Words.shared.strings.searchAction) {
            Button(Words.shared.strings.searchYourFiles) { window?.search() }.keyboardShortcut("f").disabled(window == nil)
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
