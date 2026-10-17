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

    var body: some View {
        content
        #if DEBUG
            .onAppear { AccessibilityDump.armIfAsked() }
        #endif
    }

    @ViewBuilder private var content: some View {
        #if DEBUG
            if let route = DebugRoute.current {
                route.screen(model)
            } else if let route = DebugRoute.live {
                AppStack(model: model, path: route.path) { route.liveScreen(model) }
            } else {
                main
            }
        #else
            main
        #endif
    }

    @ViewBuilder private var main: some View {
        if model.started && model.setupSeen {
            AppStack(model: model, path: initialPath) { SearchView(model: model) }
        } else {
            SetupView(model: model, onDone: model.finishSetup)
        }
    }

    private var initialPath: [Route] {
        #if DEBUG
            if let route = DebugRoute.openOnLaunch { return [route] }
        #endif
        return []
    }
}

/// Home's navigation stack: every page, plus opening files at their match.
struct AppStack<Root: View>: View {
    let model: AppModel
    @State private var path: NavigationPath
    @State private var quickLook: URL?
    @State private var openFailed = false
    private let root: Root

    init(model: AppModel, path: [Route] = [], @ViewBuilder root: () -> Root) {
        self.model = model
        _path = State(initialValue: NavigationPath(path))
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
            SetupView(model: model, asked: true) { path = NavigationPath() }
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
