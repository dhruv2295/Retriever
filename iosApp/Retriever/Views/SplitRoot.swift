// Sidebar and detail columns at regular width (iPad, wide Split View). The structure follows Apple's sample
// "Bringing robust navigation structure to your SwiftUI app" (Apple sample code licence): a NavigationSplitView whose
// sidebar selection roots the detail column's own stack. Modified for Retriever: the detail column is AppStack, and
// the selection is read from the one path RootView keeps, so a switch to compact width and back loses nothing.
// The sidebar holds Search, Reading, Settings, Browse and Free up space; Home keeps the pictures.

import RetrieverCore
import SwiftUI

struct SplitRoot<Home: View>: View {
    let model: AppModel
    @Binding var path: [Route]
    private let home: Home

    /// A sidebar row: Home itself, or a page that roots the detail stack.
    enum Dest: Hashable {
        case home
        case page(Route)

        var route: Route? {
            if case .page(let route) = self { route } else { nil }
        }
    }

    init(model: AppModel, path: Binding<[Route]>, @ViewBuilder home: () -> Home) {
        self.model = model
        _path = path
        self.home = home()
    }

    private var tiles: [HomeTile] {
        guard model.snap != nil else { return [] }
        return HomeTile.make(
            totals: model.kindTotals, folders: model.folderTotals, allFolders: Int(model.home?.folders ?? 0), foldersAlways: true)
    }

    private var largeFiles: Int { Int(model.home?.largeCount ?? 0) }

    /// Every page the sidebar lists, so a first page on the stack that is one of them selects its row.
    private var pages: Set<Route> {
        Set([Route.status, .settings, .duplicates, HomeTile.largeFiles] + tiles.map(\.route))
    }

    /// A page with a sidebar row first on the stack selects that row (Home's status pill selects Status); the rest of
    /// the stack is the detail column's.
    private var selection: Dest { path.first.flatMap { pages.contains($0) ? Dest.page($0) : nil } ?? .home }

    private var detail: Binding<[Route]> {
        Binding(
            get: { selection == .home ? path : Array(path.dropFirst()) },
            set: { path = (selection.route.map { [$0] } ?? []) + $0 }
        )
    }

    var body: some View {
        NavigationSplitView {
            List(selection: Binding<Dest?>(get: { selection }, set: choose)) {
                Section {
                    Label("Search", systemImage: "magnifyingglass").tag(Dest.home)
                    Label(Words.shared.READING_STATUS, systemImage: "clock.arrow.circlepath").tag(Dest.page(.status))
                    Label("Settings", systemImage: "gearshape").tag(Dest.page(.settings))
                }
                if !tiles.isEmpty {
                    Section(Words.shared.MORE_ON_PHONE) {
                        ForEach(tiles) { tile in
                            Label(tile.title, systemImage: tile.icon).badge(Copy.count(tile.count)).tag(Dest.page(tile.route))
                        }
                    }
                }
                Section(Words.shared.FREE_UP_SPACE) {
                    if largeFiles > 0 {
                        Label("Large files", systemImage: "externaldrive").badge(Copy.count(largeFiles)).tag(Dest.page(HomeTile.largeFiles))
                    }
                    Label(Words.shared.FIND_DUPLICATES, systemImage: "square.on.square").tag(Dest.page(.duplicates))
                }
            }
            .navigationTitle("Retriever")
        } detail: {
            AppStack(model: model, path: detail) { root }
                .id(selection)
        }
    }

    private func choose(_ dest: Dest?) {
        guard let dest else { return }
        path = dest.route.map { [$0] } ?? []
    }

    @ViewBuilder private var root: some View {
        switch selection {
        case .home: home
        case .page(let route):
            switch route {
            case .status: StatusView(model: model)
            case .settings: SettingsView(model: model)
            case .folders: FoldersView(model: model)
            case .duplicates: DuplicatesView(model: model)
            case .files(let query): FileListView(query: query, services: model.services)
            case .setup, .view: home
            }
        }
    }
}
