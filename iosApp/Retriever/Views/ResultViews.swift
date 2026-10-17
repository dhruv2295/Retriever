// Ported from Retriever's Android FileRow.kt, Format.kt and Opener.kt (same project): locator parsing, snippet
// highlighting, the grid cell and row, and the empty-state copy. Layout follows Apple's sample "Adding a search
// interface to your app" (Apple sample code licence) and Queryable Queryable/Queryable/View/PhotoResult (MIT).
// Modified for Retriever: SwiftUI rows that wrap at accessibility sizes, a page or time badge, and VoiceOver
// labels, hints and actions per cell.

import Photos
import RetrieverCore
import SwiftUI
import UniformTypeIdentifiers

/// Words and numbers as the person reads them.
enum Format {
    static func kind(_ kind: Kind) -> String {
        switch kind {
        case .pdf: "PDF"
        case .doc: "Document"
        case .screenshot: "Screenshot"
        case .photo: "Photo"
        case .audio: "Audio"
        case .video: "Video"
        default: "File"
        }
    }

    static func kindPlural(_ kind: Kind) -> String {
        switch kind {
        case .pdf: "PDFs"
        case .doc: "Documents"
        case .screenshot: "Screenshots"
        case .photo: "Photos"
        case .audio: "Audio"
        case .video: "Videos"
        default: "Other files"
        }
    }

    static func icon(_ kind: Kind) -> String {
        switch kind {
        case .pdf: "doc.richtext"
        case .doc: "doc.text"
        case .screenshot: "camera.viewfinder"
        case .photo: "photo"
        case .audio: "waveform"
        case .video: "video"
        default: "doc"
        }
    }

    static func date(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .long, time: .omitted)
    }

    /// "p. 12" is page 12 (1-based); anything else is nil.
    static func page(_ locator: String) -> Int? {
        guard locator.hasPrefix("p. "), let n = Int(locator.dropFirst(3)), n >= 1 else { return nil }
        return n
    }

    /// "m:ss" or "h:mm:ss" in milliseconds; anything else is nil.
    static func ms(_ locator: String) -> Int64? {
        let parts = locator.split(separator: ":", omittingEmptySubsequences: false)
        guard (2...3).contains(parts.count), parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isASCII) }) else { return nil }
        var seconds: Int64 = 0
        for p in parts {
            guard let n = Int64(p) else { return nil }
            seconds = seconds * 60 + n
        }
        return seconds * 1000
    }

    static func badge(_ locator: String) -> String? {
        if let page = page(locator) { return "Page \(page)" }
        return ms(locator) != nil ? locator : nil
    }

    /// How VoiceOver says where the match is.
    static func spoken(_ locator: String) -> String? {
        if let page = page(locator) { return "page \(page)" }
        guard let ms = ms(locator) else { return nil }
        return "at " + Duration.milliseconds(ms).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide))
    }

    static func openHint(_ locator: String) -> String {
        if let page = page(locator) { return "Opens at page \(page)" }
        if ms(locator) != nil { return "Plays from \(locator)" }
        return "Opens"
    }

    static func files(_ n: Int) -> String {
        n == 1 ? "1 file" : "\(Copy.count(n)) files"
    }

    /// The \u{2}...\u{3} words bold on a tinted background. An unclosed span runs to the end.
    static func highlighted(_ snippet: String) -> AttributedString {
        var out = AttributedString()
        var run = ""
        var marked = false
        func flush() {
            var piece = AttributedString(run)
            if marked {
                piece.inlinePresentationIntent = .stronglyEmphasized
                piece.backgroundColor = Color.accentColor.opacity(0.2)
            }
            out += piece
            run = ""
        }
        for ch in snippet {
            switch ch {
            case "\u{2}": flush(); marked = true
            case "\u{3}": flush(); marked = false
            default: run.append(ch)
            }
        }
        flush()
        return out
    }

    /// The words a keyword match marked, for highlighting them inside the file.
    static func markedWords(_ snippet: String?) -> [String] {
        guard let snippet else { return [] }
        var words: [String] = []
        var current: String?
        for ch in snippet {
            switch ch {
            case "\u{2}": current = ""
            case "\u{3}": if let w = current, !w.isEmpty { words.append(w) }; current = nil
            default: current?.append(ch)
            }
        }
        return Array(Set(words))
    }

    /// The file name with each word of the query in bold.
    static func name(_ name: String, query: String?) -> AttributedString {
        var out = AttributedString(name)
        guard let query else { return out }
        for token in Store.Companion.shared.ftsTokens(s: query) where token.count >= 2 {
            var from = out.startIndex
            while let range = out[from...].range(of: token, options: [.caseInsensitive, .diacriticInsensitive]) {
                out[range].inlinePresentationIntent = .stronglyEmphasized
                from = range.upperBound
            }
        }
        return out
    }
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
    let onOpen: () -> Void
    var extent: ReadExtent?
    var onFindSimilar: ((SourceFile) -> Void)?

    var body: some View {
        Button(action: onOpen) {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay { Thumb(file: file, locator: locator, points: 160) }
                .overlay(alignment: .bottomLeading) { badge }
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityHint(Format.openHint(locator))
        .resultActions(file, onFindSimilar)
    }

    private var label: String {
        var parts = [Format.kind(file.kind), file.name, Format.date(file.modifiedAt)]
        if let spoken = Format.spoken(locator) { parts.append(spoken) }
        if let note = Copy.extent(extent) { parts.append(note) }
        return parts.joined(separator: ", ")
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
    var query: String?
    var subtitle: String?
    var extent: ReadExtent?
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
            .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(.separator)) // white pages on white
    }

    private var texts: some View {
        VStack(alignment: .leading, spacing: 3) {
            FileName(name: file.name, query: query, wrapLines: typeSize.isAccessibilitySize ? 3 : 1)
            Text(subtitle ?? "\(file.folder) · \(Format.date(file.modifiedAt))")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(typeSize.isAccessibilitySize ? 3 : 1)
            if let snippet, !snippet.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                snippetText(snippet).font(.subheadline).foregroundStyle(.secondary).lineLimit(typeSize.isAccessibilitySize ? 6 : 2)
            }
            if let note = Copy.extent(extent) {
                Text(note).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var badge: some View {
        if let badge = Format.badge(locator) {
            Label(badge, systemImage: Format.ms(locator) != nil ? "play.fill" : "doc.text.magnifyingglass")
                .labelStyle(.titleAndIcon)
                .font(.caption.weight(.semibold))
                .monospacedDigit()
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(.tint.opacity(0.15), in: Capsule())
                .foregroundStyle(.tint)
                .fixedSize()
                .accessibilityLabel(Format.spoken(locator) ?? badge)
        }
    }

    private func snippetText(_ snippet: String) -> Text {
        if snippet.contains("\u{2}") { return Text(Format.highlighted(snippet)) }
        return Text("Similar in meaning: ").fontWeight(.medium) + Text(snippet)
    }
}

/// A file's name. One with no spaces stays on one line and is cut in the middle so its extension shows; one with
/// spaces may wrap, between words, up to `wrapLines` lines.
struct FileName: View {
    let name: String
    var query: String?
    let wrapLines: Int

    var body: some View {
        let spaced = name.contains(" ")
        Text(Format.name(name, query: query)).lineLimit(spaced ? wrapLines : 1).truncationMode(spaced ? .tail : .middle)
    }
}

/// A row that opens its file, with Find similar and Share in its menu and VoiceOver actions.
struct FileButton: View {
    let file: SourceFile
    var locator = ""
    var snippet: String?
    var query: String?
    var subtitle: String?
    var extent: ReadExtent?
    let onOpen: () -> Void
    var onFindSimilar: ((SourceFile) -> Void)?

    var body: some View {
        Button(action: onOpen) {
            FileRowView(file: file, locator: locator, snippet: snippet, query: query, subtitle: subtitle, extent: extent)
        }
        .buttonStyle(.plain)
        .resultActions(file, onFindSimilar)
    }
}

extension View {
    func resultActions(_ file: SourceFile, _ onFindSimilar: ((SourceFile) -> Void)?) -> some View {
        modifier(ResultActions(file: file, similar: onFindSimilar.flatMap { f in file.kind == .photo || file.kind == .screenshot ? f : nil }))
    }
}

/// Star and Find similar and Share, in the long-press menu and for VoiceOver.
private struct ResultActions: ViewModifier {
    let file: SourceFile
    let similar: ((SourceFile) -> Void)?

    func body(content: Content) -> some View {
        let starred = Stars.isOn(file)
        content
            .accessibilityActions {
                starButton
                if let similar { Button("Find similar") { similar(file) } }
                ShareButton(file: file)
            }
            .contextMenu {
                starButton
                if let similar { Button { similar(file) } label: { Label("Find similar", systemImage: "photo.stack") } }
                ShareButton(file: file)
            }
            .sensoryFeedback(trigger: starred) { _, now in now ? .success : .impact(weight: .light) }
    }

    @ViewBuilder private var starButton: some View {
        if let model = Stars.model {
            let on = model.starred.contains(file.uri)
            Button { model.setStarred(file.uri, !on) } label: {
                Label(on ? Copy.removeStar : Copy.star, systemImage: on ? "star.slash" : "star")
            }
        }
    }
}

/// The app's starred files, for views that don't take the model (rows, the viewers). SearchView sets it once.
@MainActor enum Stars {
    static weak var model: AppModel?

    static func isOn(_ file: SourceFile) -> Bool { model?.starred.contains(file.uri) ?? false }
}

/// The star in a viewer's toolbar.
struct StarButton: View {
    let file: SourceFile

    var body: some View {
        if let model = Stars.model {
            let on = model.starred.contains(file.uri)
            Button { model.setStarred(file.uri, !on) } label: {
                Label(on ? Copy.removeStar : Copy.star, systemImage: on ? "star.fill" : "star")
            }
            .sensoryFeedback(trigger: on) { _, now in now ? .success : .impact(weight: .light) }
        }
    }
}

// MARK: Sharing

struct ShareButton: View {
    let file: SourceFile

    var body: some View {
        if file.uri.hasPrefix("ph://") {
            ShareLink(item: SharedAsset(id: String(file.uri.dropFirst(5)), video: file.kind == .video), preview: SharePreview(file.name))
        } else if let url = Thumbnails.services?.folders.resolve(file.uri) {
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
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
              let resource = PhotosSource.renditions(asset).current else { throw CocoaError(.fileNoSuchFile) }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(resource.originalFilename)
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        try await PHAssetResourceManager.default().writeData(for: resource, toFile: url, options: options)
        return SentTransferredFile(url)
    }
}

// MARK: States

/// One illustrated state: a picture, a title, a sentence and an optional action.
struct StateView<Actions: View>: View {
    let art: String
    let title: String
    let message: String
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        ContentUnavailableView {
            VStack(spacing: 12) {
                Image(art)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 200)
                    .accessibilityHidden(true)
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
}
