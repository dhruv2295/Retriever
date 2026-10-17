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
    @Environment(\.openFile) private var openFile
    /// Grows with the text size, so a screenshot's tile keeps its shape.
    @ScaledMetric(relativeTo: .caption) private var tile = 100.0

    /// Screenshots first: they are read first, so while reading their rows say Ready before Photos does.
    private static let kinds: [Kind] = [.screenshot, .pdf, .doc, .photo, .video, .audio, .other]
    private static let nearlyEmpty = 50

    var body: some View {
        List {
            if let snap {
                let status = Copy.homeStatus(snap, download: model.download, progress: model.downloadProgress, nameOnly: model.home?.nameOnly ?? 0)
                // With no files the empty state says it all, Photos access included.
                let photosNote = snap.total == 0 ? nil : Copy.photosNote(photos)
                if status != nil || photosNote != nil { statusSection(status, photosNote) }
                if snap.total == 0 { empty(snap) } else { sections(snap) }
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: Status

    /// The status row always leads, so progress, Resume and the way to Status stay; a Photos access note follows it.
    private func statusSection(_ status: HomeStatus?, _ photosNote: HomeStatus?) -> some View {
        Section {
            if let status {
                NavigationLink(value: status.phase == .settingUp ? Route.setup : Route.status) { StatusRow(status: status) }
                    .accessibilityHint("Shows reading details")
                if status.phase == .paused { Button("Resume", action: model.resume) }
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

    private func card(_ status: HomeStatus, _ action: String, _ run: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(status.title).font(.headline)
            if let detail = status.detail { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
            Button(action, action: run).buttonStyle(.bordered)
        }
        .padding(.vertical, 4)
    }

    // MARK: Whole-screen states

    @ViewBuilder private func empty(_ snap: AppModel.Snap) -> some View {
        Section {
            if snap.running {
                StateView(art: "IllusReading", title: "Looking for your files", message: "This takes a minute. You can search by name as soon as they turn up.")
            } else {
                StateView(art: "IllusEmpty", title: "Nothing to search yet",
                          message: "Add a folder, or let Retriever see your photos, and it'll find what's inside them.") {
                    Button("Add a folder", action: addFolder).buttonStyle(.borderedProminent)
                    if photos == .notDetermined { Button("Allow Photos access", action: model.requestPhotos) }
                    if photos == .denied || photos == .restricted { Button("Turn on Photos access", action: model.openSettings) }
                }
            }
        }
        .listRowBackground(Color.clear)
    }

    // MARK: Sections

    @ViewBuilder private func sections(_ snap: AppModel.Snap) -> some View {
        searches
        newDocuments
        newScreenshots
        yourFiles(snap)
        tidyUp
    }

    @ViewBuilder private var searches: some View {
        if !search.recent.isEmpty {
            Section {
                ForEach(search.recent.prefix(3), id: \.self) { q in
                    // Color.primary, not .primary: inside a tinted list button .primary resolves to the tint.
                    Button { useRecent(q) } label: {
                        Label { Text(q).foregroundStyle(Color.primary) } icon: { Image(systemName: "clock.arrow.circlepath") }
                    }
                    .accessibilityHint("Searches again")
                    .swipeActions { Button("Remove", role: .destructive) { search.removeRecent(q) } }
                    .contextMenu { Button("Remove from recent searches", systemImage: "trash", role: .destructive) { search.removeRecent(q) } }
                }
            } header: {
                SectionHeader("Recent Searches") {
                    Button("Clear") { search.clearRecent() }.accessibilityLabel("Clear recent searches")
                }
            }
        } else if !model.suggestions.isEmpty {
            Section {
                ForEach(model.suggestions, id: \.self) { q in
                    Button { useRecent(q) } label: {
                        Label { Text(q).foregroundStyle(Color.primary) } icon: { Image(systemName: "magnifyingglass") }
                    }
                    .accessibilityHint("Searches for this")
                }
            } header: {
                SectionHeader("Try Searching")
            }
        }
    }

    @ViewBuilder private var newDocuments: some View {
        if let docs = model.home?.newDocs, let first = docs.first {
            Section {
                ForEach(docs, id: \.uri) { file in
                    FileButton(file: file, subtitle: Copy.newFileLine(file), onOpen: { openFile(file) })
                }
            } header: {
                SectionHeader("New Documents") { seeAll(FileQuery(kind: first.kind, title: Format.kindPlural(first.kind))) }
            }
        }
    }

    @ViewBuilder private var newScreenshots: some View {
        if let shots = model.home?.newShots, !shots.isEmpty {
            Section {
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 8) {
                        ForEach(shots, id: \.uri) { file in
                            Button { openFile(file) } label: {
                                Thumb(file: file, points: tile * 1.5)
                                    .frame(width: tile, height: tile * 1.5)
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(.separator))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Screenshot, \(Format.date(file.modifiedAt))")
                            .resultActions(file, search.similar(snap))
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
                .scrollIndicators(.hidden)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            } header: {
                SectionHeader("New Screenshots") { seeAll(FileQuery(kind: .screenshot, title: Format.kindPlural(.screenshot))) }
            }
        }
    }

    private func yourFiles(_ snap: AppModel.Snap) -> some View {
        // Files waiting, not a quick look for new ones: that would flash "Ready" on every row.
        let reading = snap.pending > 0
        return Section {
            if !model.starred.isEmpty {
                row(Route.files(FileQuery(title: "Starred", starredOnly: true)),
                    HomeRow(icon: "star", title: "Starred", count: model.starred.count, unit: "files"))
            }
            ForEach(Self.kinds.filter { (snap.byKind[$0] ?? 0) > 0 }, id: \.self) { kind in
                let total = snap.byKind[kind] ?? 0
                row(Route.files(FileQuery(kind: kind, title: Format.kindPlural(kind))),
                    HomeRow(icon: Format.icon(kind), title: Format.kindPlural(kind), count: total, unit: "files",
                            detail: Copy.kindProgress(kind, total: total, pending: snap.pendingByKind[kind] ?? 0, reading: reading,
                                                      readInside: snap.kinds.contains(kind) && (!snap.textOnly || AppModel.Snap.insideTextOnly.contains(kind)))))
            }
            if let folders = model.home?.folders, folders > 0 {
                row(Route.folders, HomeRow(icon: "folder", title: "Folders", count: Int(folders)))
            }
            if snap.total <= Self.nearlyEmpty {
                Button(action: addFolder) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Retriever also searches folders you add.").font(.subheadline).foregroundStyle(.secondary)
                        Text("Add a folder")
                    }
                }
            }
        } header: {
            SectionHeader("Your Files")
        }
    }

    @ViewBuilder private var tidyUp: some View {
        Section {
            if let large = model.home?.largeCount, large > 0 {
                row(Route.files(FileQuery(sort: .largest, title: "Large files")),
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

    private func seeAll(_ query: FileQuery) -> some View {
        NavigationLink("See All", value: Route.files(query))
    }
}

/// A section's title, in the case it was written in, with an optional link or button at the end.
private struct SectionHeader<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: () -> Trailing

    init(_ title: String, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.title = title
        self.trailing = trailing
    }

    var body: some View {
        HStack {
            Text(title).accessibilityAddTraits(.isHeader)
            Spacer()
            trailing().textCase(nil)
        }
        .textCase(nil)
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(_ title: String) {
        self.init(title) { EmptyView() }
    }
}

/// An icon, a name, an optional second line and a trailing count; one element for VoiceOver. At accessibility sizes
/// the count moves under the name, so neither is squeezed.
private struct HomeRow: View {
    let icon: String
    let title: String
    var count: Int?
    var unit = ""
    var detail: String?
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(.tint).frame(width: 28)
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 2) {
                    label
                    countText
                }
            } else {
                label
                Spacer(minLength: 8)
                countText
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([title, count.map { "\(Copy.count($0)) \(unit)".trimmingCharacters(in: .whitespaces) }, detail].compactMap { $0 }.joined(separator: ", "))
    }

    private var label: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            if let detail { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
        }
    }

    @ViewBuilder private var countText: some View {
        if let count { Text(Copy.count(count)).monospacedDigit().foregroundStyle(.secondary) }
    }
}

/// The status as one row: an icon, what is happening, how far, and what it means for search.
private struct StatusRow: View {
    let status: HomeStatus

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.title2).foregroundStyle(.tint).frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(status.title).monospacedDigit().multilineTextAlignment(.leading)
                if let progress = status.progress { ProgressView(value: progress) }
                if let detail = status.detail { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
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
