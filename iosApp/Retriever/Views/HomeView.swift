// Home, under the search field: one status line, then Starred, Recently added, On This Day and Browse, and the optional
// search-by-meaning offer last (clarity-proposal section 2). One inset-grouped List; a section with nothing in it is hidden.

import Photos
import RetrieverCore
import SwiftUI

struct HomeList: View {
    let snap: AppModel.Snap?
    let photos: PHAuthorizationStatus
    let model: AppModel
    let addFolder: () -> Void
    /// The status row follows the model's per-file progress; a pinned `snap` (a screenshot) carries its own.
    var liveProgress = false
    @Environment(\.openFile) private var openFile
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.scenePhase) private var scenePhase

    /// What On This Day was last asked for: the date, the photo count (more turn up while reading) and the setting.
    private struct MemoryKey: Hashable {
        var day: Date
        var photos: Int
        var show: Bool
    }

    var body: some View {
        List {
            if let snap {
                HomeStatusSection(snap: snap, live: liveProgress, photos: photos, model: model, addFolder: addFolder)
                if snap.total == 0 {
                    empty(snap)
                } else if Self.sectionsReady(model, memories: wantsMemories) {
                    sections(snap)
                }
            }
        }
        .listStyle(.insetGrouped)
        // Once per local date, again when the app comes back on another date, and as photos are found.
        .task(id: MemoryKey(day: Calendar.current.startOfDay(for: .now), photos: snap?.byKind[.photo] ?? 0, show: wantsMemories)) {
            if wantsMemories { await model.refreshOnThisDay() }
        }
    }

    /// Home's facts and On This Day answer in no set order; drawing as each landed put a section above one already shown,
    /// so they draw once both have answered, in their fixed order; later changes update them in place.
    static func sectionsReady(_ model: AppModel, memories: Bool) -> Bool {
        model.home != nil && (!memories || model.memoriesLoaded)
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
        starred
        recentlyAdded
        onThisDay(snap)
        // On an iPad the sidebar holds Browse.
        if sizeClass != .regular { browse(snap) }
        // Only the offer: the download and its setup show in the status line.
        if model.upgrade == .offer && model.upgradeOnHome {
            Section {
                UpgradeCard(model: model, compact: true)
            } header: {
                SectionHeader(Words.shared.OPTIONAL)
            }
        }
    }

    private var photosOff: Bool { photos == .denied || photos == .restricted }

    @ViewBuilder private var starred: some View {
        if let home = model.home, home.starredCount > 0, !home.starred.isEmpty {
            Section {
                TileStrip(files: home.starred, starred: model.starred, badge: true, open: { openFile($0, among: home.starred) })
            } header: {
                SectionLink(title: "Starred", route: .files(FileQuery(title: "Starred", starredOnly: true)))
            }
        }
    }

    @ViewBuilder private var recentlyAdded: some View {
        // Without Photos access its pictures can't be shown; files from added folders still can.
        let files = (model.home?.recentlyAdded ?? []).filter { !photosOff || !$0.uri.hasPrefix("ph://") }
        if !files.isEmpty {
            Section {
                TileStrip(files: files, starred: model.starred, open: { openFile($0, among: files) })
            } header: {
                SectionLink(title: Words.shared.RECENTLY_ADDED, route: .files(FileQuery(title: Words.shared.RECENTLY_ADDED)))
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

    /// Name and count only; sizes live in Settings. Documents opens whichever of PDFs and documents has more.
    @ViewBuilder private func browse(_ snap: AppModel.Snap) -> some View {
        if let home = model.home, !home.browse.isEmpty || home.folders > 0 {
            Section {
                ForEach(home.browse, id: \.title) { group in
                    let kind = group.kinds.max { (snap.byKind[$0] ?? 0) < (snap.byKind[$1] ?? 0) } ?? group.kinds[0]
                    NavigationLink(value: Route.files(FileQuery(kind: kind, title: group.title))) {
                        HomeRow(icon: Format.icon(kind), title: group.title, detail: Copy.count(Int(group.files)))
                    }
                }
                if home.folders > 0 {
                    NavigationLink(value: Route.folders) { HomeRow(icon: "folder", title: Words.shared.ALL_FOLDERS) }
                }
            } header: {
                SectionHeader(Words.shared.MORE_ON_PHONE)
            }
        }
    }

}

/// The status line leads; a Photos access note or a failed change follows it. Its own view, so the per-file progress it
/// shows redraws it alone, not the list.
private struct HomeStatusSection: View {
    let snap: AppModel.Snap
    let live: Bool
    let photos: PHAuthorizationStatus
    let model: AppModel
    let addFolder: () -> Void

    private static let nearlyEmpty = 50

    private var shown: AppModel.Snap {
        var s = snap
        if live { s.progress = model.progress }
        return s
    }

    var body: some View {
        let status = model.statusLine(shown)
        // One remedy at a time: Resume while paused, or no access at all, else what Photos access needs.
        let photosNote = snap.total == 0 || status.map({ [.paused, .noAccess].contains($0.phase) }) == true ? nil : Copy.photosNote(photos)
        let line = status.flatMap { $0.action != .dismiss || !snap.caughtUpDismissed ? $0 : nil }
        // A nearly empty phone is nudged to add a folder, in the line's place and only when nothing else is said.
        let nudge = line == nil && photosNote == nil && model.readingMessage == nil && snap.total > 0 && snap.total <= Self.nearlyEmpty
        if line != nil || photosNote != nil || model.readingMessage != nil || nudge {
            Section {
                if let line { StatusLine(status: line, model: model, dismissable: true) }
                if let message = model.readingMessage { Text(message).foregroundStyle(.primary) }
                if let note = photosNote {
                    if note.off { card(note, "Open Settings", model.openSettings) } else { card(note, "Choose more photos", model.chooseMorePhotos) }
                }
                if nudge {
                    Button(action: addFolder) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Retriever also searches folders you add.").font(.subheadline).foregroundStyle(Color.secondary)
                            Text("Add a folder")
                        }
                    }
                }
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

/// The one status line (clarity-proposal section 3) as list rows: the dog, what is happening, the count and its bar,
/// one plain detail, then its one button, spelled out, on a row of its own. On Home and in Settings
/// the line opens Reading status (setup while setting up); Reading status shows it without the link or the small dog.
struct StatusLine: View {
    let status: ReadingStatus
    let model: AppModel
    var link = true
    /// Home's line only: its x hides "All done" until new files come, and its dog plays the one celebration.
    var dismissable = false
    @State private var choosing = false

    var body: some View {
        HStack {
            if link {
                NavigationLink(value: status.phase == .settingUp ? Route.setup : Route.status) { row }
            } else {
                row
            }
            if dismissable && status.action == .dismiss {
                Button(action: model.dismissCaughtUp) {
                    Image(systemName: "xmark").frame(width: 44, height: 44).contentShape(Rectangle())
                }.buttonStyle(.borderless).accessibilityLabel("Dismiss")
            }
        }
        if let action = status.action, action != .dismiss {
            if action == .continue_ {
                NavigationLink(action.label, value: Route.setup)
            } else {
                Button {
                    run(action)
                } label: {
                    Text(action.label).fixedSize(horizontal: false, vertical: true)
                }
                .sheet(isPresented: $choosing) { CommitSheet(model: model) }
            }
        }
    }

    private var row: some View {
        HStack(alignment: .top, spacing: 12) {
            // Reading status has its own large dog.
            if link {
                StatusDog(
                    mood: mood, width: 28, icon: "checkmark.circle.fill", due: dismissable && model.caughtUpDue && status.action == .dismiss,
                    done: model.celebrate)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(status.title).font(.headline).monospacedDigit().fixedSize(horizontal: false, vertical: true)
                // "30 of 25,793 files processed", and a bar of exactly those numbers; core leaves both out while it counts.
                if let count = status.count { Text(count).font(.subheadline).monospacedDigit().fixedSize(horizontal: false, vertical: true) }
                if let done = status.done, let total = status.total, total.int64Value > 0 {
                    ProgressView(value: Double(done.int64Value), total: Double(total.int64Value)).accessibilityHidden(true)
                }
                if let detail = status.detail { Note(detail) }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    /// Walking while files are read, resting otherwise; "all done" plays its pose once, then keeps a check.
    private var mood: MascotMood? {
        if status.active || status.phase == .looking { return .walking }
        return status.action == .dismiss ? nil : .resting
    }

    private func run(_ action: StatusAction) {
        switch action {
        case .allowAccess: model.openSettings()
        case .chooseWhen: choosing = true
        case .readFor15: model.readNow()
        case .stop: if status.phase == .downloading { model.cancelDownload() } else { model.endReadNow() }
        case .resume: model.resume()
        case .tryAgain: if status.phase == .tuning { model.retryTune() } else { model.downloadModel() }
        default: break
        }
    }
}

/// A section's title, in the case it was written in.
struct SectionHeader: View {
    let title: String

    init(_ title: String) { self.title = title }

    var body: some View {
        // As Home's other headers: full contrast, not the list's grey.
        Text(title).font(.title3.bold()).foregroundStyle(Color.primary).textCase(nil).accessibilityAddTraits(.isHeader)
    }
}

/// An icon, a name and an optional count; one element for VoiceOver. At accessibility sizes the count goes under the
/// name, so neither breaks mid-word; Label scales the icon with the text.
private struct HomeRow: View {
    let icon: String
    let title: String
    var detail: String?
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Label {
            let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2)) : AnyLayout(HStackLayout())
            layout {
                Text(title).layoutPriority(1)
                if !typeSize.isAccessibilitySize { Spacer(minLength: 8) }
                // Primary, as the app's notes are: secondary grey fails contrast on the grouped rows.
                if let detail { Text(detail).monospacedDigit() }
            }
        } icon: {
            Image(systemName: icon).foregroundStyle(.tint)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([title, detail].compactMap { $0 }.joined(separator: ", "))
    }
}
