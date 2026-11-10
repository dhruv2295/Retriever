import RetrieverCore
import SwiftUI

/// Only the reading surfaces observe these per-file values.
struct ReadingPreview: Equatable {
    var current: SourceFile?
    var recent: [SourceFile] = []
    var pauseReason: String?
}

/// The file being read. Core has none between two batches, so the row keeps the last one until the next arrives: one
/// row, its height kept and its text changed in place, its picture replaced once the next is decoded.
struct ReadingNow: View {
    let file: SourceFile?
    var size: CGFloat = 88
    @State private var last: SourceFile?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let shown = file ?? last
        VStack(alignment: .leading, spacing: 8) {
            Text(shown == nil ? Words.shared.STARTING : Words.shared.PROCESSING_NOW).font(.headline).accessibilityAddTraits(.isHeader)
            HStack(spacing: 12) {
                Group {
                    if let shown { Thumb(file: shown, points: size) } else { Rectangle().fill(.fill.tertiary) }
                }
                .frame(width: size, height: size)
                .overlay { if !reduceMotion { ReadingSweep() } }
                .clipShape(RoundedRectangle(cornerRadius: 12))
                // Two lines kept for the name, so a short one never shortens the row.
                VStack(alignment: .leading, spacing: 4) {
                    Text(shown?.name ?? " ").lineLimit(2, reservesSpace: true).truncationMode(.middle)
                    Text(shown.map { Words.shared.kindLabel(kind: $0.kind) } ?? " ").font(.headline)
                    Note(shown.map { Format.date($0.modifiedAt) } ?? " ")
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityHidden(shown == nil)
            .accessibilityIdentifier("reading-now")
        }
        .onChange(of: file, initial: true) { _, file in
            if let file { last = file }
        }
    }
}

private struct ReadingSweep: View {
    @State private var moved = false

    var body: some View {
        GeometryReader { bounds in
            LinearGradient(colors: [.clear, .white.opacity(0.28), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: bounds.size.height * 0.45)
                .offset(y: moved ? bounds.size.height : -bounds.size.height * 0.45)
                .onAppear { moved = true }
                .animation(.linear(duration: 1.6).repeatForever(autoreverses: false), value: moved)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct RecentlyRead: View {
    let files: [SourceFile]
    @State private var shown: [SourceFile] = []
    @Environment(\.openFile) private var openFile

    /// Newest first when the strip first shows; after that a newly read file joins at the far end, so the tiles on screen
    /// never move as files are read (core's list is newest first, and drawing it as is shifted every tile per file).
    /// ponytail: grows by a tile per file while the screen stays open; the stack is lazy.
    static func held(_ shown: [SourceFile], _ files: [SourceFile]) -> [SourceFile] {
        guard !shown.isEmpty else { return files }
        let had = Set(shown.map(\.uri))
        return shown + files.filter { !had.contains($0.uri) }.reversed()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Words.shared.RECENTLY_PROCESSED).font(.headline).accessibilityAddTraits(.isHeader)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 8) {
                    ForEach(shown, id: \.uri) { file in
                        Button {
                            openFile(file, among: shown)
                        } label: {
                            FileTile(file: file).equatable().frame(width: 56, height: 56)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { FileActionButtons(file: file) }
                        .accessibilityActions { FileActionButtons(file: file) }
                        .accessibilityHint(Words.shared.strings.openFileHint)
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Words.shared.RECENTLY_PROCESSED)
        }
        .onChange(of: files, initial: true) { _, files in shown = Self.held(shown, files) }
    }
}

/// One square picture of a file, the same everywhere it is shown: the pile, Try, Recently read and See all.
/// Equality lets an insertion leave existing thumbnail bodies alone, including their image-loading tasks.
struct FileTile: View, @MainActor Equatable {
    let file: SourceFile

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.file == rhs.file }

    var body: some View {
        GeometryReader { bounds in
            Thumb(file: file, points: bounds.size.width, bucketed: true)
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(.rect(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Words.shared.strings.fileSpoken(name: file.name, kind: Words.shared.kindLabel(kind: file.kind), date: Format.date(file.modifiedAt)))
        .accessibilityIdentifier("recent:\(file.uri)")
    }
}
