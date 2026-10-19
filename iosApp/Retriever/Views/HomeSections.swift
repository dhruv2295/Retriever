// Home's picture sections (home-spec-v2 section 4): header doorways, the recent-search chip row, the photos mosaic,
// the screenshot strip and the document page cards. Each is its own view, so a refresh redraws only what changed.

import RetrieverCore
import SwiftUI

/// Horizontal margin of an inset-grouped list, so rows with their own layout line up with its headers.
private let gutter: CGFloat = 20

// MARK: Header

/// A section's title, a chevron and its count as a subtitle: the whole header opens the full list (iOS 26 Photos).
struct SectionLink: View {
    let title: String
    let subtitle: String
    let route: Route

    var body: some View {
        NavigationLink(value: route) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(title).font(.title3.bold())
                    Image(systemName: "chevron.right").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                }
                Text(subtitle).font(.subheadline).foregroundStyle(Color.primary).monospacedDigit()
            }
            .foregroundStyle(Color.primary)
            .textCase(nil)
        }
        .accessibilityElement(children: .ignore)
        // Spoken with commas, not the visual dot: "Screenshots, 158, 40 waiting to read".
        .accessibilityLabel("\(title), \(subtitle.replacingOccurrences(of: " · ", with: ", "))")
        .accessibilityHint("Shows all")
        .accessibilityAddTraits(.isHeader)
    }
}

// MARK: Chips

/// One scrolling row: up to five recent searches, or "Try searching" and up to six content suggestions while there is no history.
struct HomeChips: View {
    let recent: [String]
    let suggestions: [String]
    let use: (String) -> Void
    let remove: (String) -> Void

    var body: some View {
        let history = !recent.isEmpty
        let queries = history ? Array(recent.prefix(5)) : Array(suggestions.prefix(6))
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                if !history { Text("Try searching").font(.subheadline).foregroundStyle(.secondary) }
                ForEach(queries, id: \.self) { q in
                    Button {
                        use(q)
                    } label: {
                        Label {
                            Text(q).accessibilityIdentifier("recent-search")
                        } icon: {
                            Image(systemName: history ? "clock" : "magnifyingglass")
                        }.font(.body).fixedSize().foregroundStyle(Color.primary)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(history ? q : "Try searching \(q)")
                    .accessibilityHint(history ? "Searches again" : "Searches for this")
                    .contextMenu {
                        if history { Button("Remove from Recent Searches", systemImage: "trash", role: .destructive) { remove(q) } }
                    }
                }
            }
        }
        .contentMargins(.horizontal, gutter, for: .scrollContent)
        .scrollIndicators(.hidden)
    }
}

// MARK: Mosaic

/// Where the mosaic's cells go. The newest picture is the 2 x 2 cell top-left, the next two stack beside it, and the
/// next three run along the bottom; fewer pictures collapse it (3 to 5 drop the bottom row).
enum Mosaic {
    static let gap: CGFloat = 3

    /// How many of `n` newest pictures the mosaic draws: 0, 1, 2, 3 (for 3 to 5) or 6.
    static func shown(_ n: Int) -> Int { n < 3 ? n : n < 6 ? 3 : 6 }

    static func frames(count n: Int, width w: CGFloat) -> [CGRect] {
        let g = gap
        switch n {
        case ..<1: return []
        case 1: return [CGRect(x: 0, y: 0, width: w, height: w * 9 / 16)]
        case 2:
            let s = (w - g) / 2
            return [CGRect(x: 0, y: 0, width: s, height: s), CGRect(x: s + g, y: 0, width: s, height: s)]
        default:
            let c = (w - 2 * g) / 3
            let big = 2 * c + g
            var f = [
                CGRect(x: 0, y: 0, width: big, height: big), CGRect(x: big + g, y: 0, width: c, height: c),
                CGRect(x: big + g, y: c + g, width: c, height: c),
            ]
            if n >= 6 { f += (0..<3).map { CGRect(x: CGFloat($0) * (c + g), y: big + g, width: c, height: c) } }
            return f
        }
    }
}

private struct MosaicLayout: Layout {
    private func frames(_ subviews: Subviews, _ width: CGFloat) -> [CGRect] { Mosaic.frames(count: subviews.count, width: width) }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? 360
        return CGSize(width: w, height: frames(subviews, w).map(\.maxY).max() ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (view, r) in zip(subviews, frames(subviews, bounds.width)) {
            view.place(at: CGPoint(x: bounds.minX + r.minX, y: bounds.minY + r.minY), proposal: ProposedViewSize(r.size))
        }
    }
}

/// The newest photos and videos, as real pictures from the first frame; the big cell says how many photos are still
/// to be read, but nothing is greyed out.
struct HomeMosaic: View {
    let files: [SourceFile]
    /// Photos still waiting to be read and all photos; the pill shows only while reading is under way.
    let pending: Int
    let total: Int
    let paused: Bool
    let open: (SourceFile) -> Void
    @Environment(\.displayScale) private var scale

    var body: some View {
        let shown = Array(files.prefix(Mosaic.shown(files.count)))
        MosaicLayout {
            ForEach(Array(shown.enumerated()), id: \.element.uri) { i, file in
                cell(file, big: i == 0)
            }
        }
        .padding(.horizontal, gutter)
        .padding(.vertical, 8)
        // The photo cache warms the six pictures the moment they arrive, and lets go of the ones a refresh replaced.
        .task(id: shown.map(\.uri)) {
            Thumbnails.warm(shown, big: Thumbnails.bucket(240 * scale), small: Thumbnails.bucket(119 * scale))
        }
    }

    private func cell(_ file: SourceFile, big: Bool) -> some View {
        let radius: CGFloat = big ? 22 : 12
        let reading = big && pending > 0
        let kind = file.kind == .video ? "Video" : "Photo"
        let date = Format.date(file.takenAt?.int64Value ?? file.modifiedAt)
        return Button {
            open(file)
        } label: {
            Thumb(file: file, points: big ? 240 : 119, bucketed: true)
                .overlay(alignment: .bottomLeading) {
                    if reading { ReadingPill(pending: pending, total: total, paused: paused).accessibilityHidden(true) }
                }
                .overlay(alignment: .bottomLeading) {
                    if file.kind == .video && !reading { VideoBadge().padding(6) }
                }
                .clipShape(.rect(cornerRadius: radius, style: .continuous))
                .contentShape(.rect(cornerRadius: radius, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            reading ? "\(kind), \(date), \(Words.shared.waitingToRead(files: Int64(pending)))" : "\(kind), \(date)")
    }
}

private struct VideoBadge: View {
    var body: some View {
        Image(systemName: "play.fill")
            .font(.footnote)
            .foregroundStyle(.white)
            .frame(width: 28, height: 28)
            .background(.black.opacity(0.55), in: .circle)
            .shadow(color: .black.opacity(0.3), radius: 2)
            .accessibilityHidden(true)
    }
}

/// "14,700 waiting to read" over a scrim, with a thin line along the bottom edge for how many photos have been read.
private struct ReadingPill: View {
    let pending: Int
    let total: Int
    let paused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Copy.photosWaiting(pending, paused: paused))
                .font(.subheadline.weight(.medium))
                .monospacedDigit()
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(.regularMaterial, in: .capsule)
                .padding(.leading, 8)
            Rectangle().fill(.white.opacity(0.3)).frame(height: 3)
                .overlay(alignment: .leading) {
                    Rectangle().fill(Color.accentColor)
                        .scaleEffect(x: total > 0 ? Double(total - pending) / Double(total) : 0, anchor: .leading)
                }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 24)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.55)], startPoint: .top, endPoint: .bottom))
    }
}

// MARK: Strips

/// Screenshots as small phone screens: 9:16, a hairline, the fifth cut by the edge.
struct ShotStrip: View {
    let files: [SourceFile]
    let starred: Set<String>
    let similar: ((SourceFile) -> Void)?
    let open: (SourceFile) -> Void
    @ScaledMetric(relativeTo: .caption) private var width = 92.0

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 8) {
                ForEach(files, id: \.uri) { file in
                    Button {
                        open(file)
                    } label: {
                        Thumb(file: file, points: width * 16 / 9, bucketed: true)
                            .frame(width: width, height: width * 16 / 9)
                            .clipShape(.rect(cornerRadius: 16, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(.separator, lineWidth: 0.5))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Screenshot, \(Format.date(file.takenAt?.int64Value ?? file.modifiedAt))")
                    .resultActions(file, starred: starred.contains(file.uri), similar)
                }
            }
            .padding(.vertical, 8)
        }
        .contentMargins(.horizontal, gutter, for: .scrollContent)
        .scrollIndicators(.hidden)
    }
}

/// The newest PDFs and documents as pages: the first page, then the name and "Today · 2.1 MB".
struct DocStrip: View {
    let files: [SourceFile]
    let starred: Set<String>
    let open: (SourceFile) -> Void
    @ScaledMetric(relativeTo: .caption) private var width = 108.0

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(files, id: \.uri) { file in
                    Button {
                        open(file)
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Thumb(file: file, points: width * 4 / 3, bucketed: true)
                                .frame(width: width, height: width * 4 / 3)
                                .clipShape(.rect(cornerRadius: 12, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(.separator, lineWidth: 0.5))
                            Text(file.name).font(.caption).lineLimit(2).multilineTextAlignment(.leading)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier("document-name")
                            Text(Copy.cardLine(file)).font(.caption2).foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier("document-detail")
                        }
                        .frame(width: width, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(Words.shared.kindLabel(kind: file.kind)), \(file.name), \(Copy.cardLine(file))")
                    .resultActions(file, starred: starred.contains(file.uri), nil)
                }
            }
            .padding(.vertical, 8)
        }
        .contentMargins(.horizontal, gutter, for: .scrollContent)
        .scrollIndicators(.hidden)
    }
}

// MARK: On This Day

/// Past years' photos and videos taken on today's date. Core has no date library, so the platform hands it each
/// year's local-day range, computed with this calendar's real zone rules (a day is 23 or 25 hours across a clock change).
enum OnThisDay {
    struct Day: Equatable {
        let year: Int
        let from: Int64
        let to: Int64
        /// "17 October 2023"
        let title: String
    }

    /// One year's card: a cover, how many photos and videos, and the list it opens.
    struct Card: Equatable, Identifiable {
        let year: Int
        let photos: Int
        let videos: Int
        let cover: SourceFile
        let day: Day

        var id: Int { year }
        var count: String { countLine(photos: photos, videos: videos) }
        var route: Route { .files(FileQuery(title: day.title, takenRange: day.from...day.to)) }

        init(memory m: YearMemory, day: Day) {
            (year, photos, videos, cover, self.day) = (Int(m.year), Int(m.photos), Int(m.videos), m.cover, day)
        }

        init(year: Int, photos: Int, videos: Int, cover: SourceFile, day: Day) {
            (self.year, self.photos, self.videos, self.cover, self.day) = (year, photos, videos, cover, day)
        }
    }

    /// The month and day of `today` in each of the `years` before this one, newest year first, as [local midnight, next
    /// local midnight - 1 ms]. A year without that date (29 February) is left out.
    static func days(on today: Date, calendar: Calendar = .current, years: Int = 30) -> [Day] {
        let c = calendar.dateComponents([.year, .month, .day], from: today)
        guard let year = c.year, let month = c.month, let day = c.day else { return [] }
        let style = Date.FormatStyle(calendar: calendar, timeZone: calendar.timeZone).day().month(.wide).year()
        return (1...years).compactMap { back in
            guard let date = calendar.date(from: DateComponents(year: year - back, month: month, day: day)) else { return nil }
            let start = calendar.startOfDay(for: date)
            guard calendar.component(.month, from: start) == month, calendar.component(.day, from: start) == day,
                let next = calendar.date(byAdding: .day, value: 1, to: start)
            else { return nil }
            let end = calendar.startOfDay(for: next)
            return Day(year: year - back, from: ms(start), to: ms(end) - 1, title: start.formatted(style))
        }
    }

    private static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    /// "8 photos", "1 video", "5 photos and videos".
    static func countLine(photos: Int, videos: Int) -> String {
        switch (photos, videos) {
        case (_, 0): Words.shared.plural(n: Int64(photos), one: "photo", many: "photos")
        case (0, _): Words.shared.plural(n: Int64(videos), one: "video", many: "videos")
        default: "\(Copy.count(photos + videos)) photos and videos"
        }
    }
}

/// A section title with a subtitle that is not a doorway (On This Day: each card is).
struct DateHeader: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.title3.bold())
            Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
        }
        .foregroundStyle(Color.primary)
        .textCase(nil)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// Up to five year cards, newest first. Each opens that day's photos; the menu hides the section.
struct OnThisDayStrip: View {
    let cards: [OnThisDay.Card]
    /// "17 October"
    let date: String
    let hide: () -> Void
    @ScaledMetric(relativeTo: .caption) private var width = 140.0

    var body: some View {
        let height = width * 4 / 3
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 8) {
                ForEach(cards) { card in
                    NavigationLink(value: card.route) {
                        Thumb(file: card.cover, points: height, bucketed: true)
                            .frame(width: width, height: height)
                            .overlay(alignment: .bottomLeading) {
                                VStack(alignment: .leading, spacing: 0) {
                                    Text(String(card.year)).font(.title2.bold())
                                    Text(card.count).font(.subheadline)
                                }
                                .foregroundStyle(Color.primary)
                                .padding(12)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(Color(.systemBackground))
                            }
                            .clipShape(.rect(cornerRadius: 22, style: .continuous))
                            .contentShape(.rect(cornerRadius: 22, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(String(card.year)), \(card.count) from \(date)")
                    .accessibilityHint("Shows them")
                    .contextMenu { Button("Hide On This Day", systemImage: "eye.slash", action: hide) }
                    .accessibilityAction(named: "Hide On This Day", hide)
                }
            }
            .padding(.vertical, 8)
        }
        .contentMargins(.horizontal, gutter, for: .scrollContent)
        .scrollIndicators(.hidden)
    }
}

// MARK: Starred

/// The most recently starred files: pictures and PDF pages as they are, documents and audio as an icon with the name.
struct StarredStrip: View {
    let files: [SourceFile]
    let starred: Set<String>
    let open: (SourceFile) -> Void
    @ScaledMetric(relativeTo: .caption) private var side = 108.0

    private static let pictured: Set<Kind> = [.photo, .screenshot, .video, .pdf]

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 8) {
                ForEach(files, id: \.uri) { file in
                    Button {
                        open(file)
                    } label: {
                        tile(file)
                            .frame(width: side, height: side)
                            .clipShape(.rect(cornerRadius: 12, style: .continuous))
                            .overlay(alignment: .topTrailing) {
                                Image(systemName: "star.fill").font(.caption2).foregroundStyle(.tint)
                                    .padding(4).background(.regularMaterial, in: .circle).padding(4)
                            }
                            .contentShape(.rect(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(Words.shared.kindLabel(kind: file.kind)), \(file.name), starred")
                    .resultActions(file, starred: starred.contains(file.uri), nil)
                }
            }
            .padding(.vertical, 8)
        }
        .contentMargins(.horizontal, gutter, for: .scrollContent)
        .scrollIndicators(.hidden)
    }

    @ViewBuilder private func tile(_ file: SourceFile) -> some View {
        if Self.pictured.contains(file.kind) {
            Thumb(file: file, points: side, bucketed: true)
        } else {
            VStack(spacing: 6) {
                Image(systemName: Format.icon(file.kind)).font(.title2).foregroundStyle(.tint)
                Text(file.name).font(.caption.weight(.medium)).lineLimit(2).truncationMode(.middle).multilineTextAlignment(.center)
            }
            .padding(8)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(.background.secondary)
        }
    }
}

// MARK: More on your phone

/// More on your phone: one row per kind and per busiest folder, each with count and size, then All folders.
struct HomeTile: Equatable, Identifiable {
    let id: String
    let icon: String
    let title: String
    let count: Int
    /// "120 files · 640 MB", then core's reading line ("Name only", "{N} waiting to read").
    let detail: String?
    let route: Route

    static var largeFiles: Route { .files(FileQuery(sort: .largest, title: "Large files")) }
    @MainActor static let kinds: [Kind] = [.photo, .video, .screenshot, .pdf, .doc, .audio, .other]

    /// Every kind with files in it, the busiest folders (each counting its own files only), then All folders.
    /// `foldersAlways`: a sidebar keeps All folders even when there are none, since it is where a folder gets added.
    @MainActor static func make(
        _ snap: AppModel.Snap, totals: [Kind: FileTotals], folders: [FolderTotals], allFolders: Int, foldersAlways: Bool = false
    ) -> [HomeTile] {
        let byKind = kinds.compactMap { kind -> HomeTile? in
            guard let t = totals[kind], t.files > 0 else { return nil }
            let title = Words.shared.kindLabelPlural(kind: kind)
            return HomeTile(
                id: title, icon: Format.icon(kind), title: title, count: Int(t.files), detail: Copy.sectionSubtitle(t, snap.kindProgress([kind])),
                route: .files(FileQuery(kind: kind, title: title)))
        }
        let byFolder = folders.prefix(Int(Words.shared.MORE_FOLDERS)).map { f in
            let name = f.path.split(separator: "/").last.map(String.init) ?? f.path
            return HomeTile(
                id: "folder:\(f.path)", icon: "folder", title: name, count: Int(f.totals.files), detail: Copy.countAndSize(f.totals),
                route: .files(FileQuery(folder: f.path, title: name)))
        }
        guard allFolders > 0 || foldersAlways else { return byKind + byFolder }
        return byKind + byFolder + [HomeTile(id: "folders", icon: "folder", title: Words.shared.ALL_FOLDERS, count: allFolders, detail: nil, route: .folders)]
    }
}

extension AppModel.Snap {
    /// Whether this phone reads inside files of `kind` (else they are found by name).
    func reads(_ kind: Kind) -> Bool { kind != .audio && kinds.contains(kind) && (!textOnly || Self.insideTextOnly.contains(kind)) }

    /// A section's reading line, by core's rule for mixed sections such as Documents.
    func kindProgress(_ kinds: [Kind]) -> String? {
        Words.shared.kindProgress(
            kinds: kinds, inside: Set(Kind.entries.filter(reads)), pending: pendingByKind.mapValues { KotlinLong(value: Int64($0)) },
            scheduled: Int64(pending))
    }
}

// MARK: Status dog and the commit choice

/// The dog beside a status: `mood` while reading or resting. With none (everything is read), CAUGHT_UP plays once ever,
/// when core has never seen it celebrated: the success haptic lands 0.2 s after the dog appears, then `done` tells the
/// model, and the dog holds its pose while this view lives.
struct StatusDog: View {
    let mood: MascotMood?
    var width: CGFloat = 160
    /// What shows when the dog has nothing to say; nothing if nil.
    var icon: String?
    var due = false
    var done: () -> Void = {}
    @State private var kept = false
    @State private var popped = false

    var body: some View {
        if let shown = mood ?? (due || kept ? .caughtUp : nil) {
            MascotView(mood: shown)
                .frame(width: width)
                .sensoryFeedback(.success, trigger: popped)
                .task(id: due) {
                    guard due, mood == nil else { return }
                    try? await Task.sleep(for: .milliseconds(200))
                    guard !Task.isCancelled else { return }
                    kept = true
                    popped = true
                    done()
                }
        } else if let icon {
            Image(systemName: icon).font(.title2).foregroundStyle(.tint).frame(width: 28)
        }
    }
}

/// The same selection and confirmation on Home and in setup.
struct ReadingChoice: View {
    let files: Int?
    let charging: () -> Void
    let readNow: () -> Void
    let notNow: () -> Void
    @State private var selected = 0

    private var continuation: String {
        if #available(iOS 26.0, *) { "Can continue after you leave if iOS allows." } else { "Keep Retriever open for this session." }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Search inside more files").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            Text(Words.shared.moreFiles(files: files.map { KotlinLong(value: Int64($0)) }))
            Text("From your selected photos and folders").font(.subheadline)
            Note(Copy.commitNights)
            Text("When should Retriever read them?").font(.headline)
            let titles = ["While charging", "Read now for 15 minutes", "Not now"]
            let details = [
                "Recommended. Reads while charging when Retriever is open, and in the background when iOS allows.",
                "Uses battery and may warm your \(Copy.words.device). Then continues while charging. Stops after 15 minutes, or sooner if needed. \(continuation)",
                "Keep searching names and the content read so far. Choose a schedule later.",
            ]
            ForEach(0..<3, id: \.self) { index in
                Button {
                    selected = index
                } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: selected == index ? "checkmark.circle.fill" : "circle").accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(titles[index]).font(.headline)
                            Text(details[index]).font(.subheadline)
                        }
                        Spacer(minLength: 0)
                    }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).padding(.vertical, 8).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityAddTraits(selected == index ? .isSelected : [])
            }
            Button {
                switch selected {
                case 0: charging()
                case 1: readNow()
                default: notNow()
                }
            } label: {
                Text(["Use this schedule", "Start reading", "Go to Home"][selected]).frame(maxWidth: .infinity)
            }.buttonStyle(.primaryAction).controlSize(.large)
        }
    }
}

struct CommitSheet: View {
    let model: AppModel
    let waiting: Int
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            ReadingChoice(
                files: model.snap?.waiting ?? waiting,
                charging: {
                    model.commit(whileCharging: true)
                    dismiss()
                },
                readNow: {
                    model.commit(whileCharging: false)
                    dismiss()
                },
                notNow: {
                    model.decideLater()
                    dismiss()
                }
            ).padding(24)
        }.scrollBounceBehavior(.basedOnSize).presentationDetents([.large])
    }
}
