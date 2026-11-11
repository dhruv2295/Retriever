// Home's picture sections (clarity-proposal section 2): header doorways, small file tiles (Starred, Recently added) and On
// This Day. Each is its own view, so a refresh redraws only what changed.

import RetrieverCore
import SwiftUI

/// Horizontal margin of an inset-grouped list, so rows with their own layout line up with its headers.
private let gutter: CGFloat = 20

// MARK: Header

/// A section's title and a chevron: the whole header opens the full list (iOS 26 Photos).
struct SectionLink: View {
    let title: String
    let route: Route

    var body: some View {
        NavigationLink(value: route) {
            HStack(spacing: 4) {
                Text(title).font(.title3.bold())
                Image(systemName: "chevron.right").font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
            }
            .foregroundStyle(Color.primary)
            .textCase(nil)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityHint(Words.shared.strings.showsAll)
        .accessibilityAddTraits(.isHeader)
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
        Words.shared.memoryCount(photos: Int64(photos), videos: Int64(videos))
    }
}

/// A section title with a subtitle that is not a doorway (On This Day: each card is). The subtitle is primary too: secondary
/// grey fails contrast on the grouped list.
struct DateHeader: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.title3.bold())
            Text(subtitle).font(.subheadline)
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
    @ScaledMetric(relativeTo: .caption) private var width = 108.0

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
                    .hoverEffect()
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Words.shared.strings.onThisDaySpoken(year: Int32(truncatingIfNeeded: card.year), count: card.count, date: date))
                    .accessibilityHint(Words.shared.strings.showsThem)
                    .contextMenu { Button(Words.shared.strings.hideOnThisDayIos, systemImage: "eye.slash", action: hide) }
                    .accessibilityAction(named: Words.shared.strings.hideOnThisDayIos, hide)
                }
            }
            .padding(.vertical, 8)
        }
        .contentMargins(.horizontal, gutter, for: .scrollContent)
        .scrollIndicators(.hidden)
    }
}

// MARK: Tiles

/// Small square tiles, never a full-width private picture: pictures and document pages as they are, audio and other
/// files as an icon with the name. `badge` marks each one starred (the Starred section).
struct TileStrip: View {
    let files: [SourceFile]
    let starred: Set<String>
    var badge = false
    let open: (SourceFile) -> Void
    @ScaledMetric(relativeTo: .caption) private var side = 96.0

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 8) {
                ForEach(files, id: \.uri) { file in
                    // A Menu with a primary action, not .contextMenu: inside one List row every tile's context menu
                    // collapsed into the row's, so a long press on any tile offered the first tile's actions.
                    Menu {
                        FileActionButtons(file: file, starred: starred.contains(file.uri))
                    } label: {
                        tile(file)
                            .frame(width: side, height: side)
                            .clipShape(.rect(cornerRadius: 12, style: .continuous))
                            .overlay(alignment: .topTrailing) {
                                if badge {
                                    Image(systemName: "star.fill").font(.caption2).foregroundStyle(.tint)
                                        .padding(4).background(.regularMaterial, in: .circle).padding(4)
                                }
                            }
                            .contentShape(.rect(cornerRadius: 12, style: .continuous))
                    } primaryAction: {
                        open(file)
                    }
                    .menuStyle(.button)
                    .buttonStyle(.plain)
                    .hoverEffect()
                    .accessibilityLabel(
                        Words.shared.strings.homeTileSpoken(
                            kind: Words.shared.kindLabel(kind: file.kind), name: file.name, date: Format.date(file.takenAt?.int64Value ?? file.modifiedAt),
                            starred: badge)
                    )
                    .accessibilityActions { FileActionButtons(file: file, starred: starred.contains(file.uri)) }
                }
            }
            .padding(.vertical, 8)
        }
        .contentMargins(.horizontal, gutter, for: .scrollContent)
        .scrollIndicators(.hidden)
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
    }

    @ViewBuilder private func tile(_ file: SourceFile) -> some View {
        if file.kind == .audio || file.kind == .other {
            VStack(spacing: 6) {
                Image(systemName: Format.icon(file.kind)).font(.title2).foregroundStyle(.tint)
                Text(file.name).font(.caption.weight(.medium)).lineLimit(2).truncationMode(.middle).multilineTextAlignment(.center)
            }
            .padding(8)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(.background.secondary)
        } else {
            Thumb(file: file, points: side, bucketed: true)
        }
    }
}

/// People while it is off: the Settings switch, asked for where the section will appear, since off by default and only in
/// Settings, nobody found it. Turn on fills this spot with People and its faces count; Not now leaves only the switch.
struct PeopleOfferCard: View {
    let model: AppModel
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Words.shared.PEOPLE_OFFER_TITLE).font(.headline).fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(Words.shared.PEOPLE_OFFER_BODY).font(.subheadline).fixedSize(horizontal: false, vertical: true)
            // As the optional download's offer: never the strongest button on screen, stacked at accessibility sizes.
            let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 12))
            layout {
                Button(Words.shared.TURN_ON) { model.setRecognizePeople(true) }.buttonStyle(.bordered)
                Button(Words.shared.NOT_NOW, action: model.dismissPeopleOffer).buttonStyle(.borderless)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Each person a face and their name, if they have one; a tap opens their photos.
struct PeopleStrip: View {
    let people: [Person]
    @ScaledMetric(relativeTo: .caption) private var side = 72.0

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(people, id: \.id) { p in
                    let title = Words.shared.personTitle(name: p.name)
                    NavigationLink(value: Route.files(FileQuery(title: title, person: p.id))) {
                        VStack(spacing: 6) {
                            FaceAvatar(cover: p.cover, name: p.name, side: side)
                            Text(p.name ?? " ").font(.caption).lineLimit(1).frame(width: side)
                        }
                    }
                    .buttonStyle(.plain)
                    .hoverEffect()
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Words.shared.strings.personTileSpoken(name: title, photos: Int64(p.photos)))
                }
            }
            .padding(.vertical, 8)
        }
        .contentMargins(.horizontal, gutter, for: .scrollContent)
        .scrollIndicators(.hidden)
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
    }
}

/// Two people who may be one (core's ASK_SAME_PERSON), one pair at a time, closest first: both faces, and the answer
/// that merges them or never asks again. Previous and next leave a pair the person isn't sure about unanswered.
struct SamePersonCard: View {
    let pairs: [SamePerson]
    let answer: (SamePerson, Bool) -> Void
    @ScaledMetric(relativeTo: .body) private var side = 56.0
    @State private var shown = 0

    var body: some View {
        // An answered pair leaves the list, so the same place shows the next one.
        let at = min(shown, pairs.count - 1)
        let question = pairs[at]
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                FaceAvatar(cover: question.first.cover, name: question.first.name, side: side)
                FaceAvatar(cover: question.second.cover, name: question.second.name, side: side)
                VStack(alignment: .leading) {
                    Text(Words.shared.SAME_PERSON_ASK).font(.headline)
                    if pairs.count > 1 {
                        Text(Words.shared.pairOf(at: Int32(at + 1), of: Int32(pairs.count))).foregroundStyle(.secondary)
                    }
                }
                .padding(.leading, 8)
            }
            .accessibilityElement(children: .combine)
            HStack {
                if pairs.count > 1 {
                    Button {
                        shown = at - 1
                    } label: {
                        Image(systemName: "chevron.left")
                    }
                    .accessibilityLabel(Words.shared.SAME_PERSON_PREVIOUS)
                    .disabled(at == 0)
                    Button {
                        shown = at + 1
                    } label: {
                        Image(systemName: "chevron.right")
                    }
                    .accessibilityLabel(Words.shared.SAME_PERSON_NEXT)
                    .disabled(at == pairs.count - 1)
                }
                Spacer()
                Button(Words.shared.SAME_PERSON_NO) { answer(question, false) }
                Button(Words.shared.SAME_PERSON_YES) { answer(question, true) }.buttonStyle(.borderedProminent)
            }
            .buttonStyle(.bordered)
        }
        .padding(.vertical, 8)
    }
}

// MARK: The iPad sidebar

/// The iPad sidebar's rows: one per kind and per busiest folder, each with its count, then All folders.
struct HomeTile: Equatable, Identifiable {
    let id: String
    let icon: String
    let title: String
    let count: Int
    let route: Route

    static var largeFiles: Route { .files(FileQuery(sort: .largest, title: Words.shared.LARGE_FILES)) }
    @MainActor static let kinds: [Kind] = [.photo, .video, .screenshot, .pdf, .doc, .audio, .other]

    /// Every kind with files in it, the busiest folders (each counting its own files only), then All folders.
    /// `foldersAlways`: a sidebar keeps All folders even when there are none, since it is where a folder gets added.
    @MainActor static func make(
        totals: [Kind: FileTotals], folders: [FolderTotals], allFolders: Int, foldersAlways: Bool = false
    ) -> [HomeTile] {
        let byKind = kinds.compactMap { kind -> HomeTile? in
            guard let t = totals[kind], t.files > 0 else { return nil }
            let title = Words.shared.kindLabelPlural(kind: kind)
            return HomeTile(
                id: title, icon: Format.icon(kind), title: title, count: Int(t.files),
                route: .files(FileQuery(kind: kind, title: title)))
        }
        let byFolder = folders.prefix(Int(Words.shared.MORE_FOLDERS)).map { f in
            let name = f.path.split(separator: "/").last.map(String.init) ?? f.path
            return HomeTile(
                id: "folder:\(f.path)", icon: "folder", title: name, count: Int(f.totals.files),
                route: .files(FileQuery(folder: f.path, title: name)))
        }
        guard allFolders > 0 || foldersAlways else { return byKind + byFolder }
        return byKind + byFolder + [HomeTile(id: "folders", icon: "folder", title: Words.shared.ALL_FOLDERS, count: allFolders, route: .folders)]
    }
}

// MARK: Status dog and the commit choice

/// The dog beside a status: `mood` while reading or resting. With none (everything is read), CAUGHT_UP plays once ever,
/// when core has never seen it celebrated: the success haptic lands 0.2 s after the dog appears, then `done` tells the
/// model, and the dog holds its pose while this view lives.
struct StatusDog: View {
    let mood: MascotMood?
    var width: CGFloat = 160
    /// `MascotView.settles`: Home's row settles; Reading status keeps walking.
    var settles = true
    /// What shows when the dog has nothing to say; nothing if nil.
    var icon: String?
    var due = false
    var done: () -> Void = {}
    @State private var kept = false
    @State private var popped = false

    var body: some View {
        if let shown = mood ?? (due || kept ? .caughtUp : nil) {
            MascotView(mood: shown, settles: settles)
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
