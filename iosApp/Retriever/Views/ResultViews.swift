// Ported from Retriever's Android FileRow.kt, Format.kt and Opener.kt (same project): locator parsing, snippet
// highlighting, the grid cell and row, and the empty-state copy. Layout follows Apple's sample "Adding a search
// interface to your app" (Apple sample code licence) and Queryable Queryable/Queryable/View/PhotoResult (MIT).
// Modified for Retriever: SwiftUI rows that wrap at accessibility sizes, a page or time badge, and VoiceOver
// labels, hints and actions per cell.

import Photos
import RetrieverCore
import SwiftUI
import UniformTypeIdentifiers

/// Keep the native prominent button, with a light label on the dark accent and a dark label on the light accent.
struct PrimaryActionStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button(configuration).buttonStyle(.borderedProminent).foregroundStyle(Color(.systemBackground))
    }
}

extension PrimitiveButtonStyle where Self == PrimaryActionStyle {
    static var primaryAction: PrimaryActionStyle { PrimaryActionStyle() }
}

/// Pictures go in the grid, except a screenshot matched by its words: its row shows them.
func inGrid(_ hit: SearchEngine.Hit) -> Bool {
    let kind = hit.file.kind
    guard kind == .photo || kind == .screenshot || kind == .video else { return false }
    return !(kind == .screenshot && !(hit.snippet ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
}

// MARK: Cells and rows

/// Picture grid columns; bigger cells at accessibility text sizes, so their badges stay readable.
func gridColumns(_ size: DynamicTypeSize, spacing: CGFloat) -> [GridItem] {
    [GridItem(.adaptive(minimum: size.isAccessibilitySize ? 160 : 110), spacing: spacing)]
}

/// A square picture of the file, at the matched page or moment, with a small badge saying where.
struct ResultCell: View {
    let file: SourceFile
    var locator = ""
    var starred = false
    let onOpen: () -> Void
    var extent: ReadExtent?
    var onFindSimilar: ((SourceFile) -> Void)?
    var matchReason: String?
    var copies: [SourceFile] = []
    var onOpenCopy: (SourceFile) -> Void = { _ in }
    var notThisPerson: (() -> Void)?

    var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 4) {
                Color.clear
                    .aspectRatio(1, contentMode: .fit)
                    .overlay { Thumb(file: file, locator: locator, points: 160) }
                    .overlay(alignment: .bottomLeading) { badge }
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                if let matchReason { Text(matchReason).font(.caption).foregroundStyle(.secondary) }
            }
        }
        .buttonStyle(.plain)
        .hoverEffect()
        .accessibilityLabel(label)
        .accessibilityHint(Format.openHint(locator))
        .resultActions(file, starred: starred, onFindSimilar, copies: copies, onOpenCopy: onOpenCopy, notThisPerson: notThisPerson)
    }

    private var label: String {
        Words.shared.strings.resultRowSpoken(
            kind: Words.shared.kindLabel(kind: file.kind), name: file.name, date: Format.date(file.modifiedAt),
            position: Words.shared.spokenPosition(locator: locator), extent: Words.shared.extent(extent: extent), reason: matchReason)
    }

    @ViewBuilder private var badge: some View {
        let text = Format.badge(locator)
        if file.kind == .video || text != nil {
            HStack(spacing: 3) {
                if file.kind == .video { Image(systemName: "play.fill").imageScale(.small) }
                if let text { Text(text).monospacedDigit() }
            }
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(.black.opacity(0.6), in: Capsule())
            .fixedSize()
            // It sits on a picture of fixed size, so it stops growing where it would cover it; VoiceOver reads it in full.
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            .padding(6)
        }
    }
}

/// A file as a row: picture or icon, name, where it lives and when, the matched words, and where in the file.
struct FileRowView: View {
    let file: SourceFile
    var locator = ""
    var snippet: String?
    /// The name with the words a search matched in bold; nil is the name as it is.
    var styledName: AttributedString?
    var subtitle: String?
    var extent: ReadExtent?
    /// How much of the file is searchable, for a list that is not a search.
    var coverage: String?
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Group {
            // At accessibility sizes the text gets the full width; the picture and badge share the line above it.
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 12) {
                        thumb
                        badge
                    }
                    texts
                }
            } else {
                HStack(spacing: 12) {
                    thumb
                    texts
                    badge
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(Format.openHint(locator))
    }

    private var thumb: some View {
        Thumb(file: file, locator: locator, points: 52)
            .frame(width: 52, height: 52)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(.separator))  // white pages on white
    }

    private var texts: some View {
        VStack(alignment: .leading, spacing: 3) {
            FileName(name: file.name, styled: styledName, wrapLines: typeSize.isAccessibilitySize ? 3 : 2)
            Text(subtitle ?? Words.shared.strings.folderAndDate(folder: file.folder, date: Format.date(file.modifiedAt)))
                .font(.footnote)
                .foregroundStyle(Color.primary)
                .fixedSize(horizontal: false, vertical: true)
            if let snippet, !snippet.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                snippetText(snippet).font(.subheadline).foregroundStyle(.secondary).lineLimit(typeSize.isAccessibilitySize ? 3 : 1)
            }
            if let note = Words.shared.extent(extent: extent) ?? coverage {
                Text(note).font(.footnote).foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var badge: some View {
        if let badge = Format.badge(locator) {
            Label(badge, systemImage: Locator.shared.msOf(l: locator) != nil ? "play.fill" : "doc.text.magnifyingglass")
                .labelStyle(.titleAndIcon)
                .font(.caption.weight(.semibold))
                .monospacedDigit()
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(.tint.opacity(0.15), in: Capsule())
                .foregroundStyle(.tint)
                .fixedSize()
                .accessibilityLabel(Words.shared.spokenPosition(locator: locator) ?? badge)
        }
    }

    private func snippetText(_ snippet: String) -> Text {
        Text(Format.highlighted(snippet))
    }
}

/// A file's name: up to `wrapLines` lines, cut in the middle when it is longer, so the extension still shows.
struct FileName: View {
    let name: String
    var styled: AttributedString?
    let wrapLines: Int

    var body: some View {
        Text(styled ?? AttributedString(name)).lineLimit(wrapLines).truncationMode(.middle)
    }
}

/// A row that opens its file, with Find similar and Share in its menu and VoiceOver actions.
struct FileButton: View {
    let file: SourceFile
    var locator = ""
    var snippet: String?
    var styledName: AttributedString?
    var subtitle: String?
    var extent: ReadExtent?
    var coverage: String?
    var starred = false
    let onOpen: () -> Void
    var onFindSimilar: ((SourceFile) -> Void)?
    var copies: [SourceFile] = []
    var onOpenCopy: (SourceFile) -> Void = { _ in }

    var body: some View {
        Button(action: onOpen) {
            FileRowView(file: file, locator: locator, snippet: snippet, styledName: styledName, subtitle: subtitle, extent: extent, coverage: coverage)
        }
        .buttonStyle(.plain)
        .hoverEffect()
        .resultActions(file, starred: starred, onFindSimilar, copies: copies, onOpenCopy: onOpenCopy)
    }
}

extension View {
    /// `starred` comes from the list's own read of the stars, so starring one file redraws that row, not every one.
    /// `copies` are a search result's other copies (`SearchEngine.Hit.copies`), listed first, each opened by `onOpenCopy`.
    func resultActions(
        _ file: SourceFile, starred: Bool, _ onFindSimilar: ((SourceFile) -> Void)?, copies: [SourceFile] = [],
        onOpenCopy: @escaping (SourceFile) -> Void = { _ in }, notThisPerson: (() -> Void)? = nil
    ) -> some View {
        modifier(
            ResultActions(
                file: file, starred: starred, similar: onFindSimilar.flatMap { f in file.kind == .photo || file.kind == .screenshot ? f : nil },
                copies: copies, openCopy: onOpenCopy, notThisPerson: notThisPerson))
    }
}

/// The shared action list, in the long-press menu and for VoiceOver.
private struct ResultActions: ViewModifier {
    let file: SourceFile
    let starred: Bool
    let similar: ((SourceFile) -> Void)?
    let copies: [SourceFile]
    let openCopy: (SourceFile) -> Void
    /// On a person's photos: their face in this one isn't them.
    let notThisPerson: (() -> Void)?

    @ViewBuilder private var items: some View {
        if let notThisPerson {
            Button(action: notThisPerson) { Label(Words.shared.NOT_THIS_PERSON, systemImage: "person.badge.minus") }
            Divider()
        }
        ForEach(copies, id: \.uri) { copy in
            Button {
                openCopy(copy)
            } label: {
                Label(Words.shared.openCopyIn(folder: FileActions.place(copy)), systemImage: "arrow.up.forward.square")
            }
        }
        if !copies.isEmpty { Divider() }
        FileActionButtons(file: file, starred: starred, similar: similar)
    }

    func body(content: Content) -> some View {
        content
            .accessibilityActions { items }
            .contextMenu { items }
            .sensoryFeedback(trigger: starred) { _, now in now ? .success : .impact(weight: .light) }
    }
}

/// The star in a viewer's toolbar.
struct StarButton: View {
    let file: SourceFile
    @Environment(AppModel.self) private var model: AppModel?

    var body: some View {
        if let model {
            let on = model.starred.contains(file.uri)
            Button {
                model.setStarred(file.uri, !on)
            } label: {
                Label(on ? Copy.removeStar : Copy.star, systemImage: on ? "star.fill" : "star")
            }
            .sensoryFeedback(trigger: on) { _, now in now ? .success : .impact(weight: .light) }
        }
    }
}

// MARK: Sharing

struct ShareButton: View {
    let file: SourceFile
    @Environment(\.services) private var services

    var body: some View {
        if file.uri.hasPrefix("ph://") {
            ShareLink(item: SharedAsset(id: String(file.uri.dropFirst(5)), video: file.kind == .video), preview: SharePreview(file.name))
        } else if let url = services?.folders.resolve(file.uri) {
            ShareLink(item: url)
        }
    }
}

/// A photo or video from the library, exported as its file when a share target asks for it.
struct SharedAsset: Transferable {
    let id: String
    let video: Bool

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .movie) { try await $0.export() }.exportingCondition { $0.video }
        FileRepresentation(exportedContentType: .image) { try await $0.export() }.exportingCondition { !$0.video }
    }

    private func export() async throws -> SentTransferredFile {
        SentTransferredFile(try await exportURL())
    }

    func exportURL() async throws -> URL {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
            let resource = PhotosSource.renditions(asset).current
        else { throw CocoaError(.fileNoSuchFile) }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(PhotosSource.filename(resource) ?? Self.unnamed(resource))
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        try await PHAssetResourceManager.default().writeData(for: resource, toFile: url, options: options)
        return url
    }

    /// iOS 27's resource may have no file name: one with its type's extension, so the receiver can open it.
    private static func unnamed(_ resource: PHAssetResource) -> String {
        guard #available(iOS 26.0, *), let ext = resource.contentType.preferredFilenameExtension else { return Words.shared.strings.sharedPhotoName(ext: nil) }
        return Words.shared.strings.sharedPhotoName(ext: ext)
    }
}

// MARK: States

/// One illustrated state: a picture, a title, a sentence and an optional action.
struct StateView<Actions: View>: View {
    let art: String?
    let mood: MascotMood?
    let title: String
    let message: String
    @ViewBuilder var actions: () -> Actions

    init(art: String, title: String, message: String, @ViewBuilder actions: @escaping () -> Actions) {
        (self.art, self.mood, self.title, self.message, self.actions) = (art, nil, title, message, actions)
    }

    init(mood: MascotMood, title: String, message: String, @ViewBuilder actions: @escaping () -> Actions) {
        (self.art, self.mood, self.title, self.message, self.actions) = (nil, mood, title, message, actions)
    }

    var body: some View {
        ContentUnavailableView {
            VStack(spacing: 12) {
                if let mood {
                    MascotView(mood: mood).frame(maxWidth: 160)
                } else if let art {
                    Image(art)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 200)
                        .accessibilityHidden(true)
                }
                Text(title).font(.title2.bold())
            }
        } description: {
            Text(message)
        } actions: {
            actions()
        }
    }
}

extension StateView where Actions == EmptyView {
    init(art: String, title: String, message: String) {
        self.init(art: art, title: title, message: message) { EmptyView() }
    }

    init(mood: MascotMood, title: String, message: String) {
        self.init(mood: mood, title: title, message: message) { EmptyView() }
    }
}
