import RetrieverCore
import SwiftUI

/// Pages pushed on Home's stack.
enum Route: Hashable {
    case status, setup, settings
    case files(FileQuery)
    case folders, duplicates
    case view(Viewing)
}

/// Setup until the person has given Retriever something to read and chosen to go on, then Home.
struct RootView: View {
    let model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The pages over Home; the first one that has a sidebar item selects it.
    @State private var path: [Route]
    /// Home was shown this session; setup is decided once, not again when access changes.
    @State private var homeShown = false
    /// ⌘F asked for the search field and Home hasn't raised it yet.
    @State private var searchRequested = false

    init(model: AppModel) {
        self.model = model
        _path = State(initialValue: Self.initialPath)
    }

    var body: some View {
        content
            .modifier(FileActionsHost(model: model))
            .environment(model)
            .environment(\.services, model.services)
            #if DEBUG
                .preferredColorScheme(
                    CommandLine.arguments.contains("-ui-testing") ? (DebugRoute.arg("-ui-appearance") == "dark" ? .dark : .light) : nil
                )
                .transaction {
                    if CommandLine.arguments.contains("-ui-testing") {
                        $0.animation = nil
                        $0.disablesAnimations = true
                    }
                }
            #endif
    }

    @ViewBuilder private var content: some View {
        #if DEBUG
            if let route = DebugRoute.current {
                route.screen(model, path: $path).readableSetup(route.rawValue.hasPrefix("setup."))
            } else if let route = DebugRoute.live {
                home { route.liveScreen(model) }
            } else {
                main
            }
        #else
            main
        #endif
    }

    @ViewBuilder private var main: some View {
        // Nil for the moment before core has read setup's state: neither screen is known yet. Once Home shows it stays
        // for the session, as on Android: access lost meanwhile is Home's status to explain, not a return to setup.
        let inHome = homeShown ? true : model.setup.map { !$0.showSetup }
        Group {
            if inHome == true {
                home { SearchView(model: model) }
                    // A search from Shortcuts or Siri shows over whatever page was open: back to Search first (on iPad
                    // the empty path selects it), where SearchView takes the words.
                    .onChange(of: LaunchQuery.shared.pending, initial: true) { _, pending in
                        if pending?.isEmpty == false { path = [] }
                    }
                    .transition(.opacity)
            } else if inHome == false {
                SetupView(model: model).readableSetup(true).transition(.opacity)
            } else {
                Color(.systemBackground).ignoresSafeArea()
            }
        }
        // Setup gives way to Home with a fade, or at once with Reduce Motion.
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: inHome)
        .onChange(of: inHome, initial: true) { _, now in
            if now == true { homeShown = true }
        }
    }

    /// Sidebar and detail when there is room; collapsed to Home's single stack on iPhone and in narrow windows.
    private func home<Root: View>(@ViewBuilder root: () -> Root) -> some View {
        SplitRoot(model: model, path: $path, home: root)
            .environment(\.searchRequest, $searchRequested)
            // The menu bar's and the keyboard's Search and Settings act on this window (RetrieverCommands).
            .focusedSceneValue(
                \.windowCommands,
                WindowCommands(
                    search: {
                        path = []
                        searchRequested = true
                    }, settings: { path = [.settings] }))
    }

    private static var initialPath: [Route] {
        #if DEBUG
            if let route = DebugRoute.live, !route.path.isEmpty { return route.path }
            if let route = DebugRoute.openOnLaunch { return [route] }
        #endif
        return []
    }
}

extension View {
    /// Setup reads as a column, not a stretched phone screen, on an iPad.
    @ViewBuilder fileprivate func readableSetup(_ on: Bool) -> some View {
        if on {
            frame(maxWidth: 640).frame(maxWidth: .infinity).background(Color(.systemBackground))
        } else {
            self
        }
    }
}

/// Home's navigation stack: every page, plus opening files at their match.
struct AppStack<Root: View>: View {
    let model: AppModel
    @Binding var path: [Route]
    /// The first page, when the sidebar lists it: at regular width the sidebar is its way back, so it has no Back.
    private let sidebarPage: Route?
    @Environment(\.horizontalSizeClass) private var sizeClass
    private let root: Root

    init(model: AppModel, path: Binding<[Route]>, sidebarPage: Route? = nil, @ViewBuilder root: () -> Root) {
        self.model = model
        _path = path
        self.sidebarPage = sidebarPage
        self.root = root()
    }

    var body: some View {
        NavigationStack(path: $path) {
            pageEnvironment(root).navigationDestination(for: Route.self) { route in
                pageEnvironment(destination(route)).navigationBarBackButtonHidden(sizeClass == .regular && route == sidebarPage)
            }
        }
    }

    /// Set on each page, not on the stack: in a split view's detail column a pushed page doesn't inherit what is set
    /// around the stack, so a list pushed from the sidebar on iPad had the default, do-nothing openFile.
    private func pageEnvironment(_ page: some View) -> some View {
        page
            .environment(model)
            .environment(\.services, model.services)
            .environment(
                \.openFile,
                OpenFile { file, locator, words, among in
                    path.append(Route.view(viewing(file, locator: locator, words: words, among: among)))
                }
            )
            .environment(\.showFolder, ShowFolder { file in { path.append(.files(.folder(file.folder))) } })
    }

    @ViewBuilder private func destination(_ route: Route) -> some View {
        switch route {
        case .status:
            StatusView(model: model)
        case .setup:
            SetupView(model: model) { path = [] }
                .navigationTitle(Words.shared.strings.setupTitle)
                .navigationBarTitleDisplayMode(.inline)
        case .settings:
            SettingsView(model: model)
        case .files(let query):
            FileListView(query: query, services: model.services)
        case .folders:
            FoldersView(model: model)
        case .duplicates:
            DuplicatesView(model: model)
        case .view(let v):
            viewer(v)
        }
    }
}
