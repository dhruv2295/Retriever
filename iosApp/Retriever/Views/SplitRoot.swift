// Sidebar and detail columns at regular width (iPad, wide Split View). The structure follows Apple's sample
// "Bringing robust navigation structure to your SwiftUI app" (Apple sample code licence): a NavigationSplitView whose
// sidebar selection roots the detail column's own stack. Modified for Retriever: the detail column is AppStack, and
// the selection is read from the one path RootView keeps, so a switch to compact width and back loses nothing.

import SwiftUI

struct SplitRoot<Home: View>: View {
    let model: AppModel
    @Binding var path: [Route]
    private let home: Home

    enum Item: Hashable, CaseIterable {
        case home, browse, folders, duplicates, status

        var title: String {
            switch self {
            case .home: "Search"
            case .browse: "Browse"
            case .folders: "Folders"
            case .duplicates: "Duplicates"
            case .status: "Status"
            }
        }

        var icon: String {
            switch self {
            case .home: "magnifyingglass"
            case .browse: "square.grid.2x2"
            case .folders: "folder"
            case .duplicates: "square.on.square"
            case .status: "clock.arrow.circlepath"
            }
        }

        /// The page that roots this item; Search is Home itself.
        var route: Route? {
            switch self {
            case .home: nil
            case .browse: .browse
            case .folders: .folders
            case .duplicates: .duplicates
            case .status: .status
            }
        }

        init?(_ route: Route) {
            guard let item = Self.allCases.first(where: { $0.route == route }) else { return nil }
            self = item
        }
    }

    init(model: AppModel, path: Binding<[Route]>, @ViewBuilder home: () -> Home) {
        self.model = model
        _path = path
        self.home = home()
    }

    /// A page with a sidebar item first on the stack selects that item (Home's status pill selects Status); the rest
    /// of the stack is the detail column's.
    private var selection: Item { path.first.flatMap(Item.init) ?? .home }

    private var detail: Binding<[Route]> {
        Binding(
            get: { selection == .home ? path : Array(path.dropFirst()) },
            set: { path = (selection.route.map { [$0] } ?? []) + $0 }
        )
    }

    var body: some View {
        NavigationSplitView {
            List(Item.allCases, id: \.self, selection: Binding(get: { selection }, set: choose)) { item in
                Label(item.title, systemImage: item.icon)
            }
            .navigationTitle("Retriever")
        } detail: {
            AppStack(model: model, path: detail) { root }
                .id(selection)
        }
    }

    private func choose(_ item: Item?) {
        guard let item else { return }
        path = item.route.map { [$0] } ?? []
    }

    @ViewBuilder private var root: some View {
        switch selection {
        case .home: home
        case .browse: BrowseView(snap: model.snap)
        case .folders: FoldersView(model: model)
        case .duplicates: DuplicatesView(model: model)
        case .status: StatusView(model: model)
        }
    }
}
