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
            Text(shown == nil ? "Starting reading…" : "Reading now").font(.headline).accessibilityAddTraits(.isHeader)
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

/// The sample's files read so far, each opening in the viewer: Try's fallback and "See all".
struct ReadyFiles: View {
    let files: [SourceFile]
    @Environment(\.openFile) private var openFile
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Copy.filesYouCanTry).font(.headline).accessibilityAddTraits(.isHeader)
            LazyVGrid(columns: gridColumns(typeSize, spacing: 8), spacing: 8) {
                ForEach(files, id: \.uri) { file in
                    Button {
                        openFile(file, among: files)
                    } label: {
                        FileTile(file: file).equatable()
                    }
                    .buttonStyle(.plain).accessibilityHint("Open file")
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Copy.filesYouCanTry)
        }
    }
}

struct RecentlyRead: View {
    let files: [SourceFile]
    @Environment(\.openFile) private var openFile

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Recently read").font(.headline).accessibilityAddTraits(.isHeader)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 8) {
                    ForEach(files, id: \.uri) { file in
                        Button {
                            openFile(file, among: files)
                        } label: {
                            FileTile(file: file).equatable().frame(width: 56, height: 56)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { FileActionButtons(file: file) }
                        .accessibilityActions { FileActionButtons(file: file) }
                        .accessibilityHint("Open file")
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Recently read")
        }
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
        .accessibilityLabel("\(file.name), \(Words.shared.kindLabel(kind: file.kind)), \(Format.date(file.modifiedAt))")
        .accessibilityIdentifier("recent:\(file.uri)")
    }
}
