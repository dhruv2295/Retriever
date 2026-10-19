import RetrieverCore
import SwiftUI

/// Only the reading surfaces observe these per-file values.
struct ReadingPreview: Equatable {
    var current: SourceFile?
    var recent: [SourceFile] = []
    var pauseReason: String?

    func more(read: Int) -> String? {
        let count = read - recent.count
        return count > 0 ? "+\(count) more" : nil
    }
}

struct ReadingNow: View {
    let file: SourceFile?
    var pauseReason: String?
    var size: CGFloat = 88
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(file == nil ? pauseReason ?? "Starting reading…" : "Reading now").font(.headline).accessibilityAddTraits(.isHeader)
            ZStack(alignment: .leading) {
                if let file {
                    HStack(spacing: 12) {
                        Thumb(file: file, points: size)
                            .frame(width: size, height: size)
                            .overlay { if !reduceMotion { ReadingSweep() } }
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(file.name).lineLimit(2).truncationMode(.middle)
                            Text(Words.shared.kindLabel(kind: file.kind)).font(.headline)
                            Note(Format.date(file.modifiedAt))
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .id(file.uri)
                    .transition(reduceMotion ? .identity : .opacity)
                } else {
                    EmptyView()
                }
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: file?.uri)
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

struct SetupReadingFiles: View {
    let reading: () -> ReadingPreview
    let read: Int

    var body: some View {
        let preview = reading()
        ReadingNow(file: preview.current, pauseReason: preview.pauseReason)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 16))
        ReadyFiles(files: preview.recent, read: read)
    }
}

struct ReadyFiles: View {
    let files: [SourceFile]
    let read: Int
    var interactive = false
    @Environment(\.openFile) private var openFile

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Copy.firstRunReadyToSearch).font(.headline).accessibilityAddTraits(.isHeader)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                ForEach(files, id: \.uri) { file in
                    if interactive {
                        Button {
                            openFile(file, among: files)
                        } label: {
                            RecentFileCell(file: file).equatable()
                        }
                        .buttonStyle(.plain).accessibilityHint("Open file")
                    } else {
                        RecentFileCell(file: file).equatable()
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Copy.firstRunReadyToSearch)
            if read > files.count { Note("+\(read - files.count) more") }
        }
    }
}

/// SwiftUI has no flow container; each pill is measured at its actual font and available width.
struct WrappingLayout: Layout {
    var spacing: CGFloat = 8

    private func frames(width: CGFloat, subviews: Subviews) -> [CGRect] {
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        return subviews.map { view in
            let size = view.sizeThatFits(ProposedViewSize(width: width, height: nil))
            if x > 0 && x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            let frame = CGRect(origin: CGPoint(x: x, y: y), size: size)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            return frame
        }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? subviews.map { $0.sizeThatFits(.unspecified).width }.reduce(0, +)
        let frames = frames(width: width, subviews: subviews)
        return CGSize(width: width, height: frames.map(\.maxY).max() ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (view, frame) in zip(subviews, frames(width: bounds.width, subviews: subviews)) {
            view.place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY), proposal: ProposedViewSize(frame.size))
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
                            RecentFileCell(file: file).equatable().frame(width: 56, height: 56)
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

/// Equality lets an insertion leave existing thumbnail bodies alone, including their image-loading tasks.
struct RecentFileCell: View, @MainActor Equatable {
    let file: SourceFile
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.file == rhs.file }

    var body: some View {
        GeometryReader { bounds in
            Thumb(file: file, points: bounds.size.width, bucketed: true)
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .opacity(reduceMotion || appeared ? 1 : 0)
        .scaleEffect(reduceMotion || appeared ? 1 : 0.92)
        .onAppear { withAnimation(reduceMotion ? nil : .easeOut(duration: 0.22)) { appeared = true } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(file.name), \(Words.shared.kindLabel(kind: file.kind)), \(Format.date(file.modifiedAt))")
        .accessibilityIdentifier("recent:\(file.uri)")
    }
}
