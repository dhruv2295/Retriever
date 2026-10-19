// Home, below the search field: how reading is going, recent searches, the newest files, and every way into the rest.
// Layout and states follow the project's home-spec (iOS parts): one inset-grouped List, a section hidden when it has
// nothing true to say, and a real NavigationLink for the status row so it is as wide as the rows below it.

import Photos
import RetrieverCore
import SwiftUI

struct HomeList: View {
    let search: SearchModel
    let snap: AppModel.Snap?
    let photos: PHAuthorizationStatus
    let model: AppModel
    let useRecent: (String) -> Void
    let addFolder: () -> Void
    /// The status row follows the model's per-file progress; a pinned `snap` (a screenshot) carries its own.
    var liveProgress = false
    @Environment(\.openFile) private var openFile
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.scenePhase) private var scenePhase

    private static let nearlyEmpty = 50

    /// What On This Day was last asked for: the date, the photo count (more turn up while reading) and the setting.
    private struct MemoryKey: Hashable {
        var day: Date
        var photos: Int
        var show: Bool
    }

    var body: some View {
        List {
            if let snap {
                HomeStatusSection(snap: snap, live: liveProgress, photos: photos, model: model)
                if snap.total == 0 { empty(snap) } else { sections(snap) }
            }
        }
        .listStyle(.insetGrouped)
        // Once per local date, again when the app comes back on another date, and as photos are found.
        .task(id: MemoryKey(day: Calendar.current.startOfDay(for: .now), photos: snap?.byKind[.photo] ?? 0, show: wantsMemories)) {
            if wantsMemories { await model.refreshOnThisDay() }
        }
    }

    private var wantsMemories: Bool {
        guard let snap else { return false }
        return snap.showOnThisDay && snap.total > 0 && !photosOff && scenePhase == .active
    }

    // MARK: Whole-screen states

    /// Until a scan has finished, "nothing yet" would be a guess: the first files turn up while it runs.
    static func isLooking(_ snap: AppModel.Snap, started: Bool) -> Bool {
        snap.running || (started && snap.lastStop == nil && !snap.paused)
    }

    @ViewBuilder private func empty(_ snap: AppModel.Snap) -> some View {
        let looking = Self.isLooking(snap, started: model.setup?.libraryChosen == true)
        let photosOff = photos == .notDetermined || photos == .denied || photos == .restricted
        Section {
            StateView(
                mood: looking ? .walking : .noMatch, title: looking ? "Looking for your files" : "Nothing to search yet",
                message: looking
                    ? "This takes a minute. You can search by name as soon as they turn up."
                    : "Add a folder, or let Retriever see your photos, and it'll find what's inside them."
            ) {
                // Never a dead end: a phone that cannot see its photos still offers the way to fix that.
                if !looking || photosOff { Button("Add a folder", action: addFolder).buttonStyle(.primaryAction) }
                if photos == .notDetermined { Button("Allow Photos access", action: model.requestPhotos) }
                if photos == .denied || photos == .restricted { Button("Turn on Photos access", action: model.openSettings) }
            }
        }
        .listRowBackground(Color.clear)
    }

    // MARK: Sections

    @ViewBuilder private func sections(_ snap: AppModel.Snap) -> some View {
        chips
        mosaic(snap)
        screenshots(snap)
        documents(snap)
        onThisDay(snap)
        starred
        // On an iPad the sidebar holds the tiles and Tidy Up.
        if sizeClass != .regular {
            moreFiles(snap)
            tidyUp
        }
        if snap.total <= Self.nearlyEmpty { addFolderNudge }
    }

    private var photosOff: Bool { photos == .denied || photos == .restricted }

    @ViewBuilder private var chips: some View {
        if !search.recent.isEmpty || !model.suggestions.isEmpty {
            Section {
                HomeChips(recent: search.recent, suggestions: model.suggestions, use: useRecent, remove: search.removeRecent)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
        }
    }

    @ViewBuilder private func mosaic(_ snap: AppModel.Snap) -> some View {
        if !photosOff, let media = model.home?.newMedia, !media.isEmpty {
            let photoCount = snap.byKind[.photo] ?? 0
            Section {
                HomeMosaic(
                    files: media, pending: snap.accessChosen ? snap.pendingByKind[.photo] ?? 0 : 0, total: photoCount, paused: snap.paused,
                    open: { openFile($0, among: media) }
                )
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            } header: {
                SectionLink(
                    title: "Photos and Videos", subtitle: Copy.sectionSubtitle(Copy.totals([.photo, .video], in: model.kindTotals)),
                    route: .files(FileQuery(kind: photoCount > 0 ? .photo : .video, title: photoCount > 0 ? "Photos" : "Videos")))
            }
        }
    }

    @ViewBuilder private func screenshots(_ snap: AppModel.Snap) -> some View {
        if !photosOff, let shots = model.home?.newShots, !shots.isEmpty {
            Section {
                ShotStrip(files: shots, starred: model.starred, similar: search.similar(model.meaningSearch), open: { openFile($0, among: shots) })
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } header: {
                SectionLink(
                    title: "Screenshots",
                    subtitle: Copy.sectionSubtitle(Copy.totals([.screenshot], in: model.kindTotals), snap.kindProgress([.screenshot])),
                    route: .files(FileQuery(kind: .screenshot, title: "Screenshots")))
            }
        }
    }

    @ViewBuilder private func documents(_ snap: AppModel.Snap) -> some View {
        if let docs = model.home?.newDocs, !docs.isEmpty {
            let pdfs = snap.byKind[.pdf] ?? 0
            Section {
                DocStrip(files: docs, starred: model.starred, open: { openFile($0, among: docs) })
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } header: {
                SectionLink(
                    title: Words.shared.documentsTitle(pdfs: Int64(pdfs), docs: Int64(snap.byKind[.doc] ?? 0)),
                    subtitle: Copy.sectionSubtitle(Copy.totals([.pdf, .doc], in: model.kindTotals), snap.kindProgress([.pdf, .doc])),
                    route: .files(FileQuery(kind: pdfs > 0 ? .pdf : .doc, title: pdfs > 0 ? "PDFs" : "Documents")))
            }
        }
    }

    @ViewBuilder private func onThisDay(_ snap: AppModel.Snap) -> some View {
        if snap.showOnThisDay, !photosOff, !model.memories.isEmpty {
            let date = Date.now.formatted(.dateTime.day().month(.wide))
            Section {
                OnThisDayStrip(cards: model.memories, date: date, hide: { model.setShowOnThisDay(false) })
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } header: {
                DateHeader(title: "On This Day", subtitle: date)
            }
        }
    }

    @ViewBuilder private var starred: some View {
        if let home = model.home, home.starredCount > 0, !home.starred.isEmpty {
            Section {
                StarredStrip(files: home.starred, starred: model.starred, open: { openFile($0, among: home.starred) })
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } header: {
                SectionLink(title: "Starred", subtitle: Copy.count(Int(home.starredCount)), route: .files(FileQuery(title: "Starred", starredOnly: true)))
            }
        }
    }

    @ViewBuilder private func moreFiles(_ snap: AppModel.Snap) -> some View {
        let tiles = HomeTile.make(snap, totals: model.kindTotals, folders: model.folderTotals, allFolders: Int(model.home?.folders ?? 0))
        if !tiles.isEmpty {
            Section {
                ForEach(tiles) { tile in NavigationLink(value: tile.route) { HomeRow(icon: tile.icon, title: tile.title, detail: tile.detail) } }
            } header: {
                VStack(alignment: .leading, spacing: 2) {
                    SectionHeader("More on your \(Copy.device)")
                    Text(Words.shared.MORE_ON_PHONE_BODY).textCase(nil)
                }
            }
        }
    }

    private var addFolderNudge: some View {
        Section {
            Button(action: addFolder) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Retriever also searches folders you add.").font(.subheadline).foregroundStyle(.secondary)
                    Text("Add a folder")
                }
            }
        }
    }

    @ViewBuilder private var tidyUp: some View {
        Section {
            if let large = model.home?.largeCount, large > 0 {
                row(
                    HomeTile.largeFiles,
                    HomeRow(icon: "externaldrive", title: "Large files", detail: Words.shared.largeFiles(n: large)))
            }
            row(Route.duplicates, HomeRow(icon: "square.on.square", title: "Duplicates", detail: "Check for exact copies"))
        } header: {
            SectionHeader("Tidy Up")
        }
    }

    private func row(_ route: Route, _ label: HomeRow) -> some View {
        NavigationLink(value: route) { label }
    }

}

/// The status row always leads, so progress, Resume and the way to Status stay; a Photos access note follows it.
/// Its own view, so the per-file progress it shows redraws it alone, not the list.
private struct HomeStatusSection: View {
    let snap: AppModel.Snap
    let live: Bool
    let photos: PHAuthorizationStatus
    let model: AppModel
    @State private var asking = false

    private var shown: AppModel.Snap {
        var s = snap
        if live { s.progress = model.progress }
        return s
    }

    var body: some View {
        let status = Copy.homeStatus(shown)
        // One remedy at a time: Resume while paused, else what Photos access needs.
        let photosNote = snap.total == 0 || status?.phase == .paused ? nil : Copy.photosNote(photos)
        if snap.libraryTotal > 0 || status != nil || photosNote != nil || snap.asksCommit || model.upgradeOnHome {
            Section {
                if snap.libraryTotal > 0 {
                    LibraryOverview(snap: snap) { reading(status) }
                    if !snap.nameSearchHintDismissed {
                        HStack {
                            Text(Words.shared.NAME_SEARCH_HINT)
                            Spacer()
                            Button(action: model.dismissNameSearchHint) { Image(systemName: "xmark").frame(minWidth: 44, minHeight: 44) }
                                .buttonStyle(.borderless).accessibilityLabel("Dismiss name search explanation")
                        }
                    }
                } else {
                    reading(status)
                }
                if model.upgradeOnHome { UpgradeCard(model: model, compact: true) }
                if snap.asksCommit {
                    HStack {
                        VStack(alignment: .leading) {
                            Text("Search inside more files")
                            Text("\(Copy.count(snap.waiting)) files still found by name only").font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Choose when") { asking = true }.buttonStyle(.borderless)
                        Button(action: model.dismissScheduleOffer) { Image(systemName: "xmark").frame(minWidth: 44, minHeight: 44) }
                            .buttonStyle(.borderless).accessibilityLabel("Dismiss reading offer")
                    }
                    .sheet(isPresented: $asking) { CommitSheet(model: model, waiting: snap.waiting) }
                }
                if let message = model.readingMessage { Text(message).foregroundStyle(.primary) }
                if let note = photosNote {
                    if note.off { card(note, "Open Settings", model.openSettings) } else { card(note, "Choose more photos", model.chooseMorePhotos) }
                }
            }
        }
    }

    private func reading(_ status: HomeStatus?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let status, status.phase != .caughtUp || !snap.caughtUpDismissed {
                HStack {
                    NavigationLink(value: status.phase == .settingUp ? Route.setup : Route.status) {
                        StatusRow(status: status, due: model.caughtUpDue, celebrate: model.celebrate)
                    }.buttonStyle(.plain)
                    if status.phase == .caughtUp {
                        Button(action: model.dismissCaughtUp) {
                            Image(systemName: "xmark").frame(width: 44, height: 44).contentShape(Rectangle())
                        }.buttonStyle(.plain).accessibilityLabel("Dismiss reading status")
                    }
                }
                if status.phase == .paused { Button("Resume reading", action: model.resume) }
            }

        }
    }

    private func card(_ status: PhotosNote, _ action: String, _ run: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(status.title).font(.headline)
            Text(status.detail).font(.subheadline).foregroundStyle(.secondary)
            Button(action, action: run).buttonStyle(.bordered)
        }
        .padding(.vertical, 4)
    }
}

/// A section's title, in the case it was written in.
private struct SectionHeader: View {
    let title: String

    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title).textCase(nil).accessibilityAddTraits(.isHeader)
    }
}

/// An icon, a name and an optional second line; one element for VoiceOver.
private struct HomeRow: View {
    let icon: String
    let title: String
    var detail: String?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(.tint).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let detail { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([title, detail].compactMap { $0 }.joined(separator: ", "))
    }
}

/// The status as one row: the dog, what is happening and what it means for search, then the way to Reading status.
private struct StatusRow: View {
    let status: HomeStatus
    var due = false
    var celebrate: () -> Void = {}

    var body: some View {
        HStack(spacing: 12) {
            StatusDog(mood: mood, width: 56, icon: "checkmark.circle.fill", due: due && status.phase == .caughtUp, done: celebrate)
            VStack(alignment: .leading, spacing: 4) {
                Text(status.title)
                    .monospacedDigit().multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                if let detail = status.detail { Note(detail) }
                // One remedy at a time, as on Android: Resume while paused, Dismiss when caught up, and setting up leads
                // back to setup's access step; the whole row still opens Reading status.
                if ![.settingUp, .paused, .caughtUp].contains(status.phase) {
                    Text(Words.shared.READING_STATUS).font(.subheadline).foregroundStyle(.tint).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    /// Walking while files are read, resting otherwise; "all caught up" plays its pose once, then keeps a check.
    private var mood: MascotMood? {
        switch status.phase {
        case .reading, .looking: .walking
        case .caughtUp: nil
        default: .resting
        }
    }
}
