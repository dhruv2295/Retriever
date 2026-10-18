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
        let looking = Self.isLooking(snap, started: model.started)
        let photosOff = photos == .notDetermined || photos == .denied || photos == .restricted
        Section {
            StateView(
                mood: looking ? .walking : .noMatch, title: looking ? "Looking for your files" : "Nothing to search yet",
                message: looking
                    ? "This takes a minute. You can search by name as soon as they turn up."
                    : "Add a folder, or let Retriever see your photos, and it'll find what's inside them."
            ) {
                // Never a dead end: a phone that cannot see its photos still offers the way to fix that.
                if !looking || photosOff { Button("Add a folder", action: addFolder).buttonStyle(.borderedProminent) }
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

    /// Files waiting, not a quick look for new ones: that would flash "Ready" on every header.
    private func progress(_ snap: AppModel.Snap, _ kinds: [Kind]) -> String? {
        let total = kinds.reduce(0) { $0 + (snap.byKind[$1] ?? 0) }
        let pending = kinds.reduce(0) { $0 + (snap.pendingByKind[$1] ?? 0) }
        guard snap.pending > 0, let first = kinds.first else { return nil }
        return Copy.kindProgress(first, total: total, pending: pending, reading: true, readInside: kinds.allSatisfy(snap.reads))
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
            let videoCount = snap.byKind[.video] ?? 0
            Section {
                HomeMosaic(
                    files: media, pending: snap.setUp == true ? snap.pendingByKind[.photo] ?? 0 : 0, total: photoCount, paused: snap.paused,
                    open: { openFile($0, among: media) }
                )
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            } header: {
                SectionLink(
                    title: "Photos and Videos", subtitle: Copy.mediaSubtitle(photos: photoCount, videos: videoCount),
                    route: .files(FileQuery(kind: photoCount > 0 ? .photo : .video, title: photoCount > 0 ? "Photos" : "Videos")))
            }
        }
    }

    @ViewBuilder private func screenshots(_ snap: AppModel.Snap) -> some View {
        if !photosOff, let shots = model.home?.newShots, !shots.isEmpty {
            Section {
                ShotStrip(files: shots, starred: model.starred, similar: search.similar(textOnly: snap.textOnly), open: { openFile($0, among: shots) })
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } header: {
                SectionLink(
                    title: "Screenshots", subtitle: Copy.sectionSubtitle(snap.byKind[.screenshot] ?? 0, progress(snap, [.screenshot])),
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
                    title: "Documents", subtitle: Copy.documentsSubtitle(pdfs: pdfs, docs: snap.byKind[.doc] ?? 0, detail: progress(snap, [.pdf, .doc])),
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
        let tiles = HomeTile.make(snap, folders: Int(model.home?.folders ?? 0))
        if !tiles.isEmpty {
            Section {
                HomeTiles(tiles: tiles)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } header: {
                SectionHeader("More on Your \(Copy.device)")
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
                    HomeRow(icon: "externaldrive", title: "Large files", detail: Copy.largeFiles(large)))
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
        let status = Copy.homeStatus(shown, download: model.download, progress: model.downloadProgress, nameOnly: model.home?.nameOnly ?? 0)
        // With no files the empty state says it all, Photos access included.
        let photosNote = snap.total == 0 ? nil : Copy.photosNote(photos)
        if status != nil || photosNote != nil || snap.asksCommit {
            Section {
                if let status {
                    NavigationLink(value: status.phase == .settingUp ? Route.setup : Route.status) {
                        StatusRow(status: status, due: model.caughtUpDue, celebrate: model.celebrate)
                    }
                    .accessibilityHint("Shows reading details")
                    if status.phase == .paused { Button("Resume", action: model.resume) }
                }
                // The short read is done and the rest waits on the person. No hours until the pace is measured on this phone.
                if snap.asksCommit {
                    card(
                        HomeStatus(phase: .waiting, title: "Read the other \(Copy.count(snap.waiting))?", detail: Copy.commitRest(files: nil)),
                        "Read everything"
                    ) { asking = true }
                    .sheet(isPresented: $asking) { CommitSheet(model: model, waiting: snap.waiting) }
                }
                if let note = photosNote {
                    if note.phase == .photosOff {
                        card(note, "Open Settings", model.openSettings)
                    } else {
                        card(note, "Choose more photos", model.chooseMorePhotos)
                    }
                }
            }
        }
    }

    private func card(_ status: HomeStatus, _ action: String, _ run: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(status.title).font(.headline)
            if let detail = status.detail { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
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

/// The status as one row: an icon, what is happening, how far, and what it means for search.
private struct StatusRow: View {
    let status: HomeStatus
    var due = false
    var celebrate: () -> Void = {}

    var body: some View {
        HStack(spacing: 12) {
            StatusDog(mood: mood, width: 56, icon: icon, due: due && status.phase == .caughtUp, done: celebrate)
            VStack(alignment: .leading, spacing: 4) {
                Text(status.title).monospacedDigit().multilineTextAlignment(.leading)
                if let progress = status.progress { ProgressView(value: progress) }
                if let detail = status.detail { Text(detail).font(.subheadline).foregroundStyle(Color.primary) }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    /// Walking while files are read, resting while that waits; setup and "all caught up" keep their icon.
    private var mood: MascotMood? {
        switch status.phase {
        case .reading, .looking: .walking
        case .paused, .waiting: .resting
        default: nil
        }
    }

    private var icon: String {
        switch status.phase {
        case .caughtUp: "checkmark.circle.fill"
        case .paused: "pause.circle.fill"
        case .waiting: "clock.fill"
        case .settingUp: "arrow.down.circle.fill"
        default: "doc.text.magnifyingglass"
        }
    }
}
