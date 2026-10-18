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
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// Held above the sidebar and the size-class switch, so neither throws away the search or the open pages.
    @State private var search: SearchModel
    /// The pages over Home as one stack shows them; at regular width the first one that has a sidebar item selects it.
    @State private var path: [Route]

    init(model: AppModel) {
        self.model = model
        _search = State(initialValue: SearchModel(services: model.services))
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
        let inHome = model.started && model.setupSeen
        Group {
            if inHome {
                home { SearchView(model: model, search: search) }
                    // A search from Shortcuts or Siri shows over whatever page was open: back to Search first (on iPad
                    // the empty path selects it), where SearchView takes the words.
                    .onChange(of: LaunchQuery.shared.pending, initial: true) { _, pending in
                        if pending?.isEmpty == false { path = [] }
                    }
                    .transition(.opacity)
            } else {
                SetupView(model: model, onDone: model.finishSetup).readableSetup(true).transition(.opacity)
            }
        }
        // Setup gives way to Home with a fade.
        .animation(.easeInOut(duration: 0.3), value: inHome)
    }

    /// Sidebar and detail at regular width; today's single stack on iPhone and in narrow windows.
    @ViewBuilder private func home<Root: View>(@ViewBuilder root: () -> Root) -> some View {
        if sizeClass == .regular {
            SplitRoot(model: model, path: $path, home: root)
        } else {
            AppStack(model: model, path: $path, root: root)
        }
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
    private let root: Root

    init(model: AppModel, path: Binding<[Route]>, @ViewBuilder root: () -> Root) {
        self.model = model
        _path = path
        self.root = root()
    }

    var body: some View {
        NavigationStack(path: $path) {
            root.navigationDestination(for: Route.self) { destination($0) }
        }
        .environment(model)
        .environment(\.services, model.services)
        .environment(
            \.openFile,
            OpenFile { file, locator, words, among in
                path.append(Route.view(viewing(file, locator: locator, words: words, among: among)))
            }
        )

    }

    @ViewBuilder private func destination(_ route: Route) -> some View {
        switch route {
        case .status:
            StatusView(model: model)
        case .setup:
            SetupView(model: model, asked: true) { path = [] }
                .navigationTitle("Setup")
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
