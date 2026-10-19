import RetrieverCore
import SwiftUI

/// Whole-word, case-insensitive matches of the words a search marked. Words under three letters match too much to mark.
struct WordMatcher {
    private let regex: NSRegularExpression?

    init(words: [String]) {
        let any = words.filter { $0.count >= 3 }.map(NSRegularExpression.escapedPattern(for:)).joined(separator: "|")
        regex = any.isEmpty ? nil : try? NSRegularExpression(pattern: "\\b(?:\(any))\\b", options: .caseInsensitive)
    }

    func ranges(in text: String) -> [NSRange] {
        regex?.matches(in: text, range: NSRange(text.startIndex..., in: text)).map(\.range) ?? []
    }
}

/// The paragraphs that hold the searched words, in reading order, so Previous and Next walk them like the Android reader.
struct ReaderMatches: Equatable {
    let ranges: [[NSRange]]
    let paragraphs: [Int]
    var total: Int { paragraphs.count }

    init(paragraphs: [String], words: [String]) {
        let matcher = WordMatcher(words: words)
        let found = paragraphs.map { matcher.ranges(in: $0) }
        ranges = found
        self.paragraphs = found.indices.filter { !found[$0].isEmpty }
    }

    /// Where the reader opens, as the Android reader picks it: the hit's own page or moment first, and within it the first
    /// paragraph that holds the words. `match` is that paragraph's number among the matches, 0 when it holds none.
    func opening(locators: [String], hit locator: String) -> (paragraph: Int, match: Int) {
        let here = locator.isEmpty ? [] : locators.indices.filter { locators[$0] == locator }
        let pool = here.isEmpty ? Array(locators.indices) : here
        let paragraph = pool.first { !ranges[$0].isEmpty } ?? here.first ?? 0
        return (paragraph, paragraphs.firstIndex(of: paragraph) ?? 0)
    }

    /// The paragraph holding match `n`.
    func paragraph(_ n: Int) -> Int? { paragraphs.indices.contains(n) ? paragraphs[n] : nil }

    /// The match after (or before) `current`, wrapping round the ends.
    static func step(_ current: Int, by delta: Int, total: Int) -> Int {
        total == 0 ? 0 : ((current + delta) % total + total) % total
    }
}

/// The text Retriever read from a file, as paragraphs you can select, with the searched words marked.
struct TextReader: View {
    let file: SourceFile
    /// The hit's page or moment, "" when the file was opened without one.
    let locator: String
    let words: [String]
    @Environment(\.services) private var services
    @Environment(\.dismiss) private var dismiss
    @Environment(FileActions.self) private var actions: FileActions?
    @State private var state = Phase.loading

    private enum Phase {
        case loading, waiting, empty, failed
        case text(Loaded)
    }

    private struct Loaded {
        let paragraphs: [String]
        let matches: ReaderMatches
        let opening: (paragraph: Int, match: Int)
        let partial: Bool
    }

    var body: some View {
        NavigationStack {
            Group {
                switch state {
                case .loading: ProgressView()
                case .waiting:
                    StateView(mood: .noMatch, title: "Waiting to read", message: "Retriever hasn't read this file yet. You can open the original instead.") {
                        openElsewhere
                    }
                case .empty:
                    StateView(mood: .noMatch, title: "No text to show", message: "Retriever found no readable text in this file.") { openElsewhere }
                case .failed:
                    StateView(mood: .trouble, title: "Couldn't show the text", message: "Something went wrong reading it. Try again in a moment.") {
                        openElsewhere
                    }
                case .text(let loaded): Reading(loaded: loaded)
                }
            }
            .navigationTitle("Text from this file")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.large])
        .task {
            guard let retriever = services?.retriever else {
                state = .failed
                return
            }
            do {
                guard let stored = try await retriever.storedFile(uri: file.uri) else {
                    state = .failed
                    return
                }
                // A requeued file keeps the content it already has, like Android's reader.
                guard stored.contentRead else {
                    state = stored.state == .pending ? .waiting : .empty
                    return
                }
                let chunks = try await retriever.fileText(fileId: stored.id)
                state =
                    chunks.isEmpty
                    ? .empty : .text(await Self.load(chunks.map(\.text), locators: chunks.map(\.locator), hit: locator, words: words, partial: stored.partial))
            } catch { state = .failed }
        }
    }

    private var openElsewhere: some View {
        Button("Open in another app") {
            dismiss()
            actions?.select(.open, file: file)
        }
    }

    @concurrent private static func load(_ paragraphs: [String], locators: [String], hit: String, words: [String], partial: Bool) async -> Loaded {
        let matches = ReaderMatches(paragraphs: paragraphs, words: words)
        return Loaded(paragraphs: paragraphs, matches: matches, opening: matches.opening(locators: locators, hit: hit), partial: partial)
    }

    private struct Reading: View {
        let loaded: Loaded
        @State private var current: Int
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        init(loaded: Loaded) {
            self.loaded = loaded
            _current = State(initialValue: loaded.opening.match)
        }

        var body: some View {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        if loaded.partial { Note("Only the beginning of this file is included.") }
                        ForEach(loaded.paragraphs.indices, id: \.self) { index in
                            Paragraph(text: loaded.paragraphs[index], ranges: loaded.matches.ranges[index], active: loaded.matches.paragraph(current) == index)
                                .equatable().id(index)
                        }
                    }
                    .padding()
                }
                .onChange(of: current) { _, now in
                    if let at = loaded.matches.paragraph(now) { withAnimation(reduceMotion ? nil : .default) { proxy.scrollTo(at, anchor: .center) } }
                }
                .onAppear { proxy.scrollTo(loaded.opening.paragraph, anchor: .center) }
                .safeAreaInset(edge: .bottom) { if loaded.matches.total > 0 { bar } }
            }
        }

        private var bar: some View {
            HStack {
                Button {
                    current = ReaderMatches.step(current, by: -1, total: loaded.matches.total)
                } label: {
                    Label("Previous match", systemImage: "chevron.up").labelStyle(.iconOnly)
                }
                Spacer()
                Text("Match \(current + 1) of \(loaded.matches.total)").font(.footnote).monospacedDigit()
                Spacer()
                Button {
                    current = ReaderMatches.step(current, by: 1, total: loaded.matches.total)
                } label: {
                    Label("Next match", systemImage: "chevron.down").labelStyle(.iconOnly)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(.bar)
        }
    }

    /// One paragraph. Equatable, so stepping to the next match redraws only the paragraphs whose marking changed.
    private struct Paragraph: View, Equatable {
        let text: String
        let ranges: [NSRange]
        let active: Bool

        var body: some View {
            Text(marked).textSelection(.enabled)
        }

        private var marked: AttributedString {
            var out = AttributedString(text)
            for found in ranges {
                guard let range = Range(found, in: out) else { continue }
                out[range].backgroundColor = active ? .orange : .yellow.opacity(0.6)
                out[range].foregroundColor = .black
            }
            return out
        }
    }
}
