// Ported from Retriever's Android SearchScreen.kt and SearchViewModel.kt (same project): debounced search as you
// type, kind filters run inside the engine, search by photo, recent searches, and the empty, words-only and error
// states. Native chrome follows Apple's sample "Adding a search interface to your app" (Apple sample code licence):
// searchable with scopes and suggestions in a NavigationStack. Modified for Retriever: scopes pass kinds to the
// engine, results mix a photo grid with document rows, and every state has an illustration.

import ImageIO
import Photos
import PhotosUI
@preconcurrency import RetrieverCore
import SwiftUI
import os

/// Awaits a core search handle (RetrieverIos.kt `searchCancellable` and its twins). Cancelling the task closes the
/// handle, which cancels the Kotlin job, so a replaced search stops and frees the model for the next one.
/// Runs on the caller's actor, so `start` (which captures the caller's state) never crosses an isolation boundary.
func awaitSearch(
    isolation: isolated (any Actor)? = #isolation,
    _ start: (@escaping @Sendable (SearchEngine.SearchResult?, KotlinThrowable?) -> Void) -> KotlinAutoCloseable
) async throws -> SearchEngine.SearchResult {
    let handle = OSAllocatedUnfairLock<KotlinAutoCloseable?>(initialState: nil)
    return try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { done in
            // Core calls this exactly once, also when closed before the search starts.
            let started = start { result, error in
                if let result { done.resume(returning: result) } else { done.resume(throwing: error?.asError() ?? CancellationError()) }
            }
            handle.withLock { $0 = started }
            if Task.isCancelled { started.close() }
        }
    } onCancel: {
        handle.withLock { $0 }?.close()
    }
}

@Observable @MainActor
final class SearchModel {
    enum Scope: String, CaseIterable, Identifiable {
        case all, photos, videos, documents, starred

        var id: Self { self }

        var title: String {
            switch self {
            case .all: "All"
            case .photos: "Photos"
            case .videos: "Videos"
            case .documents: "Documents"
            case .starred: "Starred"
            }
        }

        /// Filtered inside the engine, so a narrow scope still finds files beyond the mixed top results.
        var kinds: Set<Kind>? {
            switch self {
            case .all, .starred: nil
            case .photos: [.photo, .screenshot]
            case .videos: [.video]
            case .documents: [.pdf, .doc]
            }
        }

        /// One kind per scope, only to tell scopes apart in a `Question`; the engine gets `kinds`.
        var kind: Kind? {
            switch self {
            case .photos: .photo
            case .videos: .video
            case .documents: .pdf
            default: nil
            }
        }

        var needsPhotos: Bool { self == .photos || self == .videos }

        /// What a search in this scope keeps when the words' own filters are dropped, for the relaxed note; nil is everything.
        var kept: String? {
            switch self {
            case .all: nil
            case .starred: "starred files"
            default: title.lowercased()
            }
        }

        /// `-scope photo`, `-scope pdf` and the like.
        init?(arg: String) {
            switch arg {
            case "screenshot", "screenshots": self = .photos
            case "pdf", "pdfs", "doc", "docs": self = .documents
            default:
                guard let s = Self.allCases.first(where: { $0.rawValue == arg || $0.rawValue == arg + "s" }) else { return nil }
                self = s
            }
        }
    }

    struct Photo {
        let id = UUID()
        let jpeg: Data
        let thumb: UIImage
    }

    struct Key: Equatable {
        var query: String
        var scope: Scope
        var photo: UUID?
        /// The words searched as typed, without reading kinds and dates out of them.
        var literal = false
        /// First run's try-it: only these files may answer.
        var ids: [Int64]?
        var retry = 0

        /// What core's reducer compares; a retry is not part of it, so it keeps the answer it has.
        var question: Question {
            Question(
                text: query, kind: scope.kind, similarTo: photo?.uuidString, starredOnly: scope == .starred, literal: literal,
                scope: ids?.map { KotlinLong(value: $0) })
        }
    }

    struct Answer {
        let hits: [SearchEngine.Hit]
        /// The first this many hits are good matches; the rest are the closest of a weak lot.
        let strongCount: Int
        let vectorLane: Bool
        let key: Key
        /// Files read only in part, by file id; the rest were read whole.
        var extents: [Int64: ReadExtent] = [:]
        /// What the words were understood to ask for, and whether a filter left nothing so it was dropped.
        var parsed: ParsedQuery?
        var relaxed = false
        /// Names of the files found by their name, with the matched words in bold, built once per answer.
        let names: [Int64: AttributedString]

        init(_ r: SearchEngine.SearchResult, strong: Int? = nil, key: Key) {
            (hits, strongCount, vectorLane, self.key, parsed, relaxed) = (r.hits, strong ?? Int(r.strongCount), r.vectorLane, key, r.parsed, r.relaxed)
            let words = r.parsed?.text ?? key.query
            names =
                words.isEmpty
                ? [:]
                : Dictionary(
                    r.hits.filter(\.byName).map { ($0.fileId, Format.name($0.file.name, query: words)) },
                    uniquingKeysWith: { first, _ in first })
        }
    }

    /// What the results area shows, from `phase`; "empty" only for a finished answer to the question asked now.
    enum Screen: Equatable {
        case idle
        case searching(slow: Bool)
        case failed, results, empty
    }

    /// Core's reducer state (same rule as Android). `answer` is the last list to draw, which may belong to older words:
    /// it is what stays, dimmed, under a newer search.
    private(set) var phase: any SearchPhase = SearchPhaseIdle.shared
    private(set) var text = ""
    var scope = Scope.all
    private(set) var photo: Photo?
    private(set) var answer: Answer?
    /// Searching for longer than SEARCHING_BAR_MS: only then is a bar worth showing.
    private(set) var slow = false
    /// Searching for longer than SEARCHING_MASCOT_MS: the dog sniffs, when there is nothing else to show.
    private(set) var pondering = false
    private(set) var recent: [String] = []
    /// `loadRecent` has answered, so Home knows whether its chips row shows.
    private(set) var recentLoaded = false
    private(set) var literal = false
    /// The demo's files: while set, every search stays inside them.
    var fileIds: [Int64]?
    private var retries = 0
    /// Times "no matches" was shown (Prefs.noMatchSeen); after a few, the dog stops moving for it.
    private(set) var noMatchSeen: Int
    @ObservationIgnored private var wordsOnlyRetries = 0
    @ObservationIgnored private let services: AppServices?
    private let timing = SearchTiming.shared

    init(services: AppServices?) {
        self.services = services
        noMatchSeen = Int(services?.retriever.prefsNow.noMatchSeen ?? 0)
    }

    var query: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
    var active: Bool { !query.isEmpty || photo != nil }
    var key: Key { Key(query: photo == nil ? query : "", scope: scope, photo: photo?.id, literal: literal, ids: fileIds, retry: retries) }
    var question: Question { key.question }

    /// No results for the question asked now yet: whatever is on screen answers older words, another scope or photo.
    var stale: Bool { SearchPhaseKt.answerFor(phase, current: question) == nil }

    var screen: Screen {
        guard active else { return .idle }
        let q = question
        if SearchPhaseKt.showsEmpty(phase, current: q) { return .empty }
        if phase is SearchPhaseFailed, !SearchPhaseKt.isStale(phase, current: q) { return .failed }
        if let r = SearchPhaseKt.answerFor(phase, current: q), !r.hits.isEmpty { return .results }
        return .searching(slow: slow)
    }

    var noMatchMood: MascotMood { noMatchSeen > Int(timing.NO_MATCH_QUIET_AFTER) ? .noMatchQuiet : .noMatch }

    /// "Search my exact words": no kind or date is read out of them.
    func searchAsTyped() {
        literal = true
    }

    /// Typing ends a search by photo; clearing the words drops the scope with them.
    func setText(_ new: String) {
        let hadWords = !query.isEmpty
        if new != text {
            literal = false
            typed()
        }
        text = new
        wordsOnlyRetries = 0
        if !query.isEmpty {
            photo = nil
        } else if hadWords {
            scope = .all
        }
    }

    @ObservationIgnored private var typing: Task<Void, Never>?

    /// Reading steps aside for a moment after each keystroke, not for as long as the search stays open; a search
    /// running holds it off in core.
    private func typed() {
        guard let retriever = services?.retriever else { return }
        retriever.setUiBusy(busy: true)
        typing?.cancel()
        typing = Task {
            try? await Task.sleep(for: .milliseconds(SearchTiming.shared.TYPING_MS))
            if !Task.isCancelled { retriever.setUiBusy(busy: false) }
        }
    }

    /// One core search. A photo search is never scoped: during the short read only the planned files hold vectors.
    /// ponytail: core's cancellable wrappers take no `scope`, so a scoped words search (first run's try-it only) goes
    /// through the plain suspend call and can't be cancelled mid-flight; add `scope` to them and this branch goes.
    private func ask(_ key: Key, _ retriever: Retriever, limit: Int32, namesOnly: Bool, jpeg: Data? = nil) async throws -> SearchEngine.SearchResult {
        let kinds = key.scope.kinds
        let starred = key.scope == .starred
        if let ids = key.ids, jpeg == nil {
            let scope = ids.map { KotlinLong(value: $0) }
            return namesOnly
                ? try await retriever.searchNames(q: key.query, kinds: kinds, limit: limit, onlyStarred: starred, literal: key.literal, scope: scope)
                : try await retriever.search(q: key.query, kinds: kinds, limit: limit, onlyStarred: starred, literal: key.literal, scope: scope)
        }
        return try await awaitSearch {
            if namesOnly {
                retriever.searchNamesCancellable(q: key.query, kinds: kinds, limit: limit, onlyStarred: starred, literal: key.literal, done: $0)
            } else if let jpeg {
                retriever.searchImageDataCancellable(jpeg: jpeg, kinds: kinds, limit: limit, onlyStarred: starred, done: $0)
            } else {
                retriever.searchCancellable(q: key.query, kinds: kinds, limit: limit, onlyStarred: starred, literal: key.literal, done: $0)
            }
        }
    }

    func search(_ key: Key) async {
        guard let retriever = services?.retriever else { return }
        guard key.photo != nil || !key.query.isEmpty else {
            phase = SearchPhaseIdle.shared
            answer = nil
            return
        }
        let q = key.question
        // Reuse a finished meaning answer only for the same request; retries and words-only answers ask again.
        if let done = phase as? SearchPhaseResults, done.complete, answer?.key == key, done.result.vectorLane { return }
        phase = SearchPhaseKt.onAsk(phase, q: q)
        let start = ContinuousClock.now
        let limit = timing.LIMIT
        let waiting = Task {
            try await Task.sleep(for: .milliseconds(timing.SEARCHING_BAR_MS))
            slow = true
            try await Task.sleep(for: .milliseconds(timing.SEARCHING_MASCOT_MS - timing.SEARCHING_BAR_MS))
            pondering = true
        }
        defer {
            waiting.cancel()
            slow = false
            pondering = false
        }
        do {
            // Phase one: names, from memory, with no model. Nothing found yet isn't "no matches", so the reducer
            // doesn't show it; a retry of the same question keeps its full answer instead.
            if key.photo == nil, SearchPhaseKt.answerFor(phase, current: q) == nil {
                try? await Task.sleep(until: start + .milliseconds(timing.NAMES_MS))
                guard !Task.isCancelled else { return }
                let names = try await ask(key, retriever, limit: limit, namesOnly: true)
                guard !Task.isCancelled else { return }
                phase = SearchPhaseKt.onNames(phase, q: q, r: names)
                if !names.hits.isEmpty { answer = Answer(names, strong: names.hits.count, key: key) }
                // Phase two waits for the words to hold still, so typing never starts a full search.
                try? await Task.sleep(until: start + .milliseconds(timing.FULL_MS))
                guard !Task.isCancelled else { return }
            }
            let jpeg = photo.flatMap { $0.id == key.photo ? $0.jpeg : nil }
            let result = try await ask(key, retriever, limit: limit, namesOnly: false, jpeg: jpeg)
            guard !Task.isCancelled else { return }
            // Words only while the model was busy opening: ask again shortly, a few times, for results by meaning.
            // Not when it was removed in Settings: asking again can't bring it back.
            let settled = result.vectorLane || retriever.textOnly || retriever.modelMissingNow
            let willRetry = !settled && wordsOnlyRetries < Int(timing.WORDS_ONLY_RETRIES)
            withAnimation(.smooth) {
                phase = SearchPhaseKt.onFull(phase, q: q, r: result, willRetry: willRetry)
                answer = Answer(result, key: key)
            }
            if !willRetry {
                slow = false
                pondering = false
            }
            if SearchPhaseKt.showsEmpty(phase, current: q) { countNoMatch() }
            if let found = try? await retriever.readExtents(fileIds: result.hits.map { KotlinLong(value: $0.fileId) }), !Task.isCancelled,
                answer?.key.question == q
            {
                answer?.extents = Dictionary(uniqueKeysWithValues: found.map { ($0.key.int64Value, $0.value) })
            }
            if settled {
                wordsOnlyRetries = 0
            } else if willRetry {
                wordsOnlyRetries += 1
                try? await Task.sleep(for: .milliseconds(timing.WORDS_ONLY_WAIT_MS))
                if !Task.isCancelled { retries += 1 }
            }
        } catch {
            guard !Task.isCancelled else { return }
            phase = SearchPhaseKt.onError(phase, q: q)
        }
    }

    /// The person saw "no matches" once more (Prefs.noMatchSeen).
    private func countNoMatch() {
        noMatchSeen += 1
        guard let retriever = services?.retriever else { return }
        Task {
            try? await retriever.updatePrefs { @Sendable p in
                p.doCopy(
                    kinds: p.kinds, onlyWhileCharging: p.onlyWhileCharging, schedule: p.schedule, readNowUntil: p.readNowUntil,
                    excludedFolders: p.excludedFolders,
                    paused: p.paused,
                    sampleSize: p.sampleSize, allowMobileData: p.allowMobileData, rememberSearches: p.rememberSearches,
                    dynamicColor: p.dynamicColor, setupStarted: p.setupStarted, notificationsDismissed: p.notificationsDismissed,
                    noMatchSeen: p.noMatchSeen + 1, showOnThisDay: p.showOnThisDay, readingCycle: p.readingCycle,
                    dismissedReadingCycle: p.dismissedReadingCycle,
                    searchTried: p.searchTried, upgradeOfferDismissed: p.upgradeOfferDismissed, homeIntroDue: p.homeIntroDue)
            }
        }
    }

    func retry() {
        retries += 1
    }

    /// On submit, or when a result is opened.
    func record() {
        guard let retriever = services?.retriever, photo == nil, !query.isEmpty else { return }
        let q = query
        Task {
            try? await retriever.recordQuery(q: q)
            await loadRecent()
        }
    }

    func loadRecent() async {
        defer { recentLoaded = true }
        guard let retriever = services?.retriever else { return }
        recent = (try? await retriever.recentQueries()) ?? recent
    }

    func removeRecent(_ q: String) {
        recent.removeAll { $0 == q }
        guard let retriever = services?.retriever else { return }
        Task { try? await retriever.removeRecentQuery(q: q) }
    }

    func clearRecent() {
        recent = []
        guard let retriever = services?.retriever else { return }
        Task { try? await retriever.clearRecentQueries() }
    }

    /// "Find similar", or nil without search by meaning (`AppModel.meaningSearch`): no model to match a photo with.
    func similar(_ meaning: Bool) -> ((SourceFile) -> Void)? {
        meaning ? { [weak self] file in self?.findSimilar(file) } : nil
    }

    func findSimilar(_ file: SourceFile) {
        guard let extractor = services?.extractor else { return }
        let uri = file.uri
        Task {
            let jpeg = await Task.detached { extractor.imageJpeg(uri: uri, maxSide: 1024) }.value
            if let jpeg { show(jpeg) }
        }
    }

    /// A photo from the picker, at most 1024 px on its longest side.
    func pick(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self) else { return }
        let jpeg = await Task.detached { () -> Data? in
            guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                let image = CGImageSourceCreateThumbnailAtIndex(
                    source, 0,
                    [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: 1024,
                    ] as CFDictionary)
            else { return nil }
            return UIImage(cgImage: image).jpegData(compressionQuality: 0.85)
        }.value
        if let jpeg { show(jpeg) }
    }

    private func show(_ jpeg: Data) {
        guard let thumb = UIImage(data: jpeg) else { return }
        text = ""
        photo = Photo(jpeg: jpeg, thumb: thumb)
    }

    func clearPhoto() {
        photo = nil
    }

    /// Fixed state for screenshots and previews; nothing runs.
    static func fixture(
        text: String = "", scope: Scope = .all, hits: [SearchEngine.Hit]? = nil, strongCount: Int? = nil, extents: [Int64: ReadExtent] = [:],
        vectorLane: Bool = true, failed: Bool = false, slow: Bool = false, photo: UIImage? = nil, recent: [String] = []
    ) -> SearchModel {
        let m = SearchModel(services: nil)
        m.text = text
        m.scope = scope
        if let photo, let jpeg = photo.jpegData(compressionQuality: 0.8) { m.photo = Photo(jpeg: jpeg, thumb: photo) }
        if let hits {
            let result = SearchEngine.SearchResult(
                hits: hits, vectorLane: vectorLane, strongCount: Int32(strongCount ?? hits.count), parsed: nil, relaxed: false)
            m.phase = SearchPhaseResults(question: m.question, result: result, complete: true)
            m.answer = Answer(result, key: m.key)
            m.answer?.extents = extents
        }
        if failed { m.phase = SearchPhaseFailed(question: m.question) }
        m.slow = slow
        m.recent = recent
        m.recentLoaded = true
        return m
    }
}
