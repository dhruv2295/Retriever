// Home's one navigation structure at every width. It follows Apple's sample "Bringing robust navigation structure to
// your SwiftUI app" (Apple sample code licence): a NavigationSplitView whose sidebar selection drives the detail
// column's stack. Modified for Retriever: the detail column is always Home's own stack, and a sidebar page is the first
// page pushed over Home, so the selection is read from that one path. Nothing in the tree depends on the width:
// folding an iPhone Duo, resizing an iPad window or rotating collapses or expands the same views and keeps their state.
// At compact width the split view collapses into that stack with Home on top, as before.
// The sidebar holds Search, Reading, Settings, Browse and Free up space (as Home lists it); Home keeps the pictures.

import RetrieverCore
import SwiftUI

struct SplitRoot<Home: View>: View {
    let model: AppModel
    @Binding var path: [Route]
    private let home: Home
    /// Compact width opens on Home, not on the sidebar's list.
    @State private var column = NavigationSplitViewColumn.detail
    /// What the person last chose for the sidebar; a photo or video on top hides it without forgetting this.
    @State private var columns = NavigationSplitViewVisibility.automatic

    /// Photos and videos take the whole window, as in Photos; documents and PDFs read beside the sidebar.
    private var mediaOnTop: Bool {
        guard case .view(let viewing) = path.last else { return false }
        switch viewing {
        case .photos, .media: return true
        case .pdf, .document: return false
        }
    }

    /// A sidebar row: Home itself, or a page pushed first over Home.
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
    private var possibleCopies: Bool { (model.home?.possibleCopies ?? 0) > 0 }

    /// Every page the sidebar lists, so a first page on the stack that is one of them selects its row.
    private var pages: Set<Route> {
        Set([Route.status, .settings, .duplicates, HomeTile.largeFiles] + tiles.map(\.route))
    }

    /// A page with a sidebar row first on the stack selects that row (Home's status pill selects Status).
    private var selection: Dest { path.first.flatMap { pages.contains($0) ? Dest.page($0) : nil } ?? .home }

    var body: some View {
        NavigationSplitView(
            columnVisibility: Binding(get: { mediaOnTop ? .detailOnly : columns }, set: { if !mediaOnTop { columns = $0 } }),
            preferredCompactColumn: $column
        ) {
            // Rows, not List(selection:): the split view pops the detail stack whenever a list selection changes, and
            // this selection changes as pages are pushed over Home.
            List {
                Section {
                    row(Words.shared.strings.searchTab, "magnifyingglass", .home)
                    row(Words.shared.READING_STATUS, "clock.arrow.circlepath", .page(.status))
                    row(Words.shared.strings.appSettings, "gearshape", .page(.settings))
                }
                if !tiles.isEmpty {
                    Section(Words.shared.MORE_ON_PHONE) {
                        ForEach(tiles) { tile in row(tile.title, tile.icon, .page(tile.route), count: tile.count) }
                    }
                }
                // As on Home: a row only when it has something, and no section when neither has.
                if largeFiles > 0 || possibleCopies {
                    Section(Words.shared.FREE_UP_SPACE) {
                        if largeFiles > 0 {
                            row(Words.shared.LARGE_FILES, "externaldrive", .page(HomeTile.largeFiles), count: largeFiles)
                        }
                        if possibleCopies { row(Words.shared.FIND_DUPLICATES, "doc.on.doc", .page(.duplicates)) }
                    }
                }
            }
            .navigationTitle("Retriever")
            // The sidebar stays hidden under a photo or video, so its toggle would do nothing there.
            .toolbar(removing: mediaOnTop ? .sidebarToggle : nil)
        } detail: {
            // Collapsed, the sidebar sits under Home; its Back would leave the person on a bare list of pages.
            AppStack(model: model, path: $path, sidebarPage: selection.route) { home.navigationBarBackButtonHidden() }
        }
    }

    private func row(_ title: String, _ icon: String, _ dest: Dest, count: Int? = nil) -> some View {
        let selected = dest == selection
        return Button {
            path = dest.route.map { [$0] } ?? []
        } label: {
            Label {
                Text(title).foregroundStyle(Color.primary)
            } icon: {
                Image(systemName: icon)
            }
        }
        .badge(count.map { Text(Copy.count($0)) })
        .listRowBackground(selected ? Color(.systemFill) : Color.clear)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
