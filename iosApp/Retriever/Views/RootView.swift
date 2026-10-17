import QuickLook
import RetrieverCore
import SwiftUI

/// Pages pushed on Home's stack.
enum Route: Hashable {
    case status, setup
    case browse
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
        #if DEBUG
            .onAppear { AccessibilityDump.armIfAsked() }
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
        if model.started && model.setupSeen {
            home { SearchView(model: model, search: search) }
        } else {
            SetupView(model: model, onDone: model.finishSetup).readableSetup(true)
        }
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
            if let route = DebugRoute.live { return route.path }
            if let route = DebugRoute.openOnLaunch { return [route] }
        #endif
        return []
    }
}

private extension View {
    /// Setup reads as a column, not a stretched phone screen, on an iPad.
    @ViewBuilder func readableSetup(_ on: Bool) -> some View {
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
    @State private var quickLook: URL?
    @State private var openFailed = false
    private let root: Root

    init(model: AppModel, path: Binding<[Route]>, @ViewBuilder root: () -> Root) {
        self.model = model
        _path = path
        self.root = root()
        Thumbnails.services = model.services
    }

    var body: some View {
        NavigationStack(path: $path) {
            root.navigationDestination(for: Route.self) { destination($0) }
        }
        .quickLookPreview($quickLook)
        .environment(\.openFile, OpenFile { file, locator, words in
            if let v = viewing(file, locator: locator, words: words) {
                path.append(Route.view(v))
            } else if let services = model.services {
                quickLook = services.folders.resolve(file.uri)
                openFailed = quickLook == nil
            }
        })
        .alert("Couldn't open this file", isPresented: $openFailed) {} message: {
            Text("Its folder can't be reached right now. If you moved or renamed the folder, add it again in Folders.")
        }
    }

    @ViewBuilder private func destination(_ route: Route) -> some View {
        switch route {
        case .status:
            StatusView(model: model)
        case .setup:
            SetupView(model: model, asked: true) { path = [] }
                .navigationTitle("Setup")
                .navigationBarTitleDisplayMode(.inline)
        case .browse:
            BrowseView(snap: model.snap)
        case let .files(query):
            FileListView(query: query, services: model.services)
        case .folders:
            FoldersView(model: model)
        case .duplicates:
            DuplicatesView(model: model)
        case let .view(v):
            viewer(v)
        }
    }
}
