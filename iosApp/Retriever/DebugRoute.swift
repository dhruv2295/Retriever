#if DEBUG
    @preconcurrency import RetrieverCore
    import SwiftUI
    import os

    /// `-bgdrill <seconds>` (0: when iOS's background time runs out): the simulator has no BGTaskScheduler (submit
    /// fails with "not available on this platform" and lldb's `_simulateLaunchForTaskWithIdentifier:` never
    /// returns), so once the app is in the background this runs the task's own handler pieces: runBatch, then
    /// expire() after that long. The BGDRILL line gives the time from expiry to completion.
    enum BackgroundDrill {
        static func armIfAsked() {
            let args = CommandLine.arguments
            guard let i = args.firstIndex(of: "-bgdrill"), i + 1 < args.count, let expireAfter = Double(args[i + 1]) else { return }
            _ = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated {
                    let hold = OSAllocatedUnfairLock(initialState: UIBackgroundTaskIdentifier.invalid)
                    let expiredAt = OSAllocatedUnfairLock<ContinuousClock.Instant?>(initialState: nil)
                    let complete = IndexTask.Completion { ok in
                        let after = expiredAt.withLock { $0 }.map { " \(ContinuousClock.now - $0) after expiry" } ?? ""
                        print("BGDRILL setTaskCompleted(success: \(ok))\(after)")
                        let id = hold.withLock { $0 }
                        DispatchQueue.main.async { UIApplication.shared.endBackgroundTask(id) }
                    }
                    let expire: @Sendable () -> Void = {
                        expiredAt.withLock { $0 = $0 ?? .now }
                        IndexTask.expire(complete)
                    }
                    let id = UIApplication.shared.beginBackgroundTask(withName: "bgdrill", expirationHandler: expire)
                    hold.withLock { $0 = id }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { IndexTask.runBatch { complete($0) } }
                    if expireAfter > 0 { DispatchQueue.main.asyncAfter(deadline: .now() + 2 + expireAfter, execute: expire) }
                }
            }
        }
    }

    /// `-route <name>` opens one screen, so screenshots need no taps. Fixture routes pin a state and need no index;
    /// live routes run the real engine on the seeded library (`-q <words>`, `-scope pdf`).
    enum DebugRoute: String {
        /// Hello, then the access screen in each state.
        case setupWelcome = "setup.welcome"
        case setupAccess = "setup.access"
        case setupLimited = "setup.limited"
        case setupDenied = "setup.denied"
        case setupFolders = "setup.folders"
        case setupAccessBoth = "setup.accessBoth"
        case statusReading = "status.reading"
        /// Reading status as files come and go: the current file turns over, with a gap between two files.
        case statusReadingTurnover = "status.readingTurnover"
        case statusCaughtUp = "status.caughtUp"
        case statusPaused = "status.paused"
        case statusReadNow = "status.readNow"
        case statusSkipped = "status.skipped"
        case setupSamplePicking = "setup.samplePicking"
        case setupSampleStart = "setup.sampleStart"
        /// The first search while its download runs, waits for Wi-Fi, or once search by description is ready.
        case setupReading = "setup.reading"
        case setupWaitingWifi = "setup.waitingWifi"
        case setupReady = "setup.ready"
        case setupPaused = "setup.paused"
        case setupSampleDone = "setup.sampleDone"
        case statusTextOnly = "status.textOnly"
        case statusTailFiles = "status.tailFiles"
        case setupTextOnly = "setup.textOnly"
        case upgradeOffer = "upgrade.offer"
        case upgradeDownload = "upgrade.download"
        case upgradeWaitingWifi = "upgrade.waitingWifi"
        case upgradeFailed = "upgrade.failed"
        case upgradeTuning = "upgrade.tuning"
        case upgradeMeaning = "upgrade.meaning"
        case homeUpgrade = "home.upgrade"
        case searchNoModel = "search.noModel"
        case searchLessLikely = "search.lessLikely"
        case searchNothingWell = "search.nothingWell"
        case searchFirstPart = "search.firstPart"
        case searchCopies = "search.copies"
        case homeCaughtUp = "home.caughtUp"
        case homeReading = "home.reading"
        case homeEmpty = "home.empty"
        case homeMemories = "home.memories"
        // Home's status line in each state (clarity-proposal section 3), plus Settings, where the same line leads.
        case homeCharging = "home.charging"
        case homeFirstDay = "home.firstDay"
        case homeUnplugged = "home.unplugged"
        case homeChargingUp = "home.chargingUp"
        case homeReadNow = "home.readNow"
        case homeDownloading = "home.downloading"
        case homeMeaning = "home.meaning"
        case homeNoAccess = "home.noAccess"
        case homeNotNow = "home.notNow"
        case homeCounting = "home.counting"
        /// Home's one-time card after setup.
        case homeIntroReady = "home.introReady"
        case homeIntroOnItsWay = "home.introOnItsWay"
        case settings
        /// Home with its photo mosaic.
        case homePhotos = "home.photos"
        /// Home's People strip, a named and an unnamed person, while photos still wait for faces.
        case homePeople = "home.people"
        case viewerPhotoMissing = "viewer.photoMissing"
        case searchEmpty = "search.empty"
        case searchWordsOnly = "search.wordsOnly"
        case searchError = "search.error"
        case searchLoading = "search.loading"
        case searchNoAccess = "search.noAccess"
        case searchReading = "search.reading"
        case mascotGallery = "mascot.gallery"
        // Live.
        case home
        case search
        case searchPhoto = "search.photo"
        case browseLarge = "browse.large"
        case browseFolders = "browse.folders"
        case browseDuplicates = "browse.duplicates"
        case viewerPdf = "viewer.pdf"
        case viewerVideo = "viewer.video"
        case viewerPhoto = "viewer.photo"
        case viewerFile = "viewer.file"

        private static let parsed: DebugRoute? = arg("-route").flatMap(DebugRoute.init(rawValue:))

        private var isLive: Bool {
            switch self {
            case .home, .search, .searchPhoto, .browseLarge, .browseFolders, .browseDuplicates, .viewerPdf, .viewerVideo, .viewerPhoto, .viewerFile: true
            default: false
            }
        }

        /// A fixture route; the app then runs on a fixture model.
        static let current: DebugRoute? = parsed.flatMap { $0.isLive ? nil : $0 }

        /// A live route; the app runs on the real model.
        static let live: DebugRoute? = parsed.flatMap { $0.isLive ? $0 : nil }

        /// `-open status|setup|settings|starred` pushes that page over the live Home, for screenshots of a real run.
        @MainActor static let openOnLaunch: Route? = arg("-open").flatMap {
            [
                "status": Route.status, "setup": .setup, "settings": .settings,
                "starred": .files(FileQuery(title: "Starred", starredOnly: true)),
            ][$0]
        }

        /// `-star <words>` stars the best hit for the words at launch, so starred screens need no taps.
        static func starIfAsked(_ retriever: Retriever) {
            guard let q = arg("-star") else { return }
            Task {
                if let hit = try? await retriever.search(q: q, kinds: nil, limit: 1).hits.first {
                    try? await retriever.setStarred(uri: hit.file.uri, on: true)
                }
            }
        }

        static func arg(_ name: String) -> String? {
            let args = CommandLine.arguments
            guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
            return args[i + 1]
        }

        var fixture: AppFixture {
            switch self {
            case .setupWelcome: .firstRun
            case .setupAccess: .accessNone
            case .setupLimited: .photosLimited
            case .setupDenied: .photosDenied
            case .setupFolders: .folderAdded
            case .setupAccessBoth: .accessBoth
            case .setupSamplePicking: .samplePicking
            case .setupSampleStart: .sampleStart
            case .setupReading: .sampleReading
            case .setupWaitingWifi: .sampleWaitingForWifi
            case .setupReady: .sampleReady
            case .setupPaused: .samplePaused
            case .setupSampleDone: .sampleDone
            case .homeIntroReady: .homeIntro(ready: true)
            case .homeIntroOnItsWay: .homeIntro(ready: false)
            case .upgradeOffer, .homeUpgrade, .searchNoModel: .noModel
            case .upgradeDownload, .homeDownloading: .downloading(0.34)
            case .homeCharging: .readingWhileCharging
            case .homeFirstDay: .firstDay
            case .homeUnplugged: .unplugged
            case .homeChargingUp: .chargingUp
            case .homeReadNow, .statusReadNow: .readNow
            case .homeMeaning: .meaningPass
            case .homeNoAccess: .lostAccess
            case .homeNotNow: .notNow
            case .homeCounting: .counting
            case .upgradeWaitingWifi: .waitingForWifi
            case .upgradeFailed: .downloadFailed
            case .upgradeTuning: .tuning
            case .upgradeMeaning: .meaning(1_204)
            case .statusReading, .statusReadingTurnover, .searchReading: .reading(done: 1_204, total: 5_310)
            case .statusPaused: .paused
            case .statusSkipped: .skipped(6)
            case .statusTailFiles: .tailFiles(12)
            case .setupTextOnly, .statusTextOnly: .textOnly(ramGb: 3)
            case .searchNoAccess: .noAccess
            default: .caughtUp
            }
        }

        @MainActor @ViewBuilder func screen(_ model: AppModel, path: Binding<[Route]>) -> some View {
            switch self {
            case .setupWelcome, .setupAccess, .setupLimited, .setupDenied, .setupFolders, .setupAccessBoth, .setupSamplePicking, .setupSampleStart,
                .setupReading, .setupWaitingWifi, .setupReady, .setupPaused, .setupSampleDone, .setupTextOnly:
                SetupView(model: model)
            case .upgradeOffer, .upgradeDownload, .upgradeWaitingWifi, .upgradeFailed, .upgradeTuning, .upgradeMeaning:
                // Settings' Search by description entry, alone: the same card as Home's.
                NavigationStack { List { UpgradeCard(model: model) }.navigationTitle("Settings") }
            case .statusReadingTurnover:
                NavigationStack { StatusView(model: model) }.task { await Self.turnOver(model) }
            case .statusReading, .statusCaughtUp, .statusPaused, .statusReadNow, .statusSkipped, .statusTextOnly, .statusTailFiles:
                NavigationStack { StatusView(model: model) }
            case .mascotGallery:
                MascotGallery()
            case .settings:
                NavigationStack { SettingsView(model: model) }
            case .homeMemories:
                NavigationStack {
                    OnThisDayStrip(
                        cards: [
                            OnThisDay.Card(
                                year: 2025, photos: 4, videos: 1,
                                cover: Fixtures.file("memory.jpg", .photo, "Photos", daysAgo: 365),
                                day: OnThisDay.Day(year: 2025, from: 0, to: 1, title: "18 October 2025"))
                        ],
                        date: "18 October", hide: {})
                }
            case .viewerPhotoMissing:
                NavigationStack {
                    PhotoViewer(
                        files: [
                            SourceFile(
                                uri: "ph://missing-audit-photo", name: "Missing photo", folder: "Photos", mime: "image/jpeg", kind: .photo,
                                size: 0, modifiedAt: 0, takenAt: nil)
                        ], index: 0)
                }
            default:
                AppStack(model: model, path: path) { fixtureSearch(model).task { await Self.hideKeyboard() } }
            }
        }

        @MainActor @ViewBuilder private func fixtureSearch(_ model: AppModel) -> some View {
            switch self {
            case .homeCaughtUp, .homeUpgrade:
                SearchView(model: model, search: .fixture(recent: ["passport scan", "wifi password", "lease agreement"]))
            case .homeCharging, .homeFirstDay, .homeUnplugged, .homeChargingUp, .homeReadNow, .homeDownloading, .homeMeaning, .homeNoAccess,
                .homeNotNow, .homeCounting, .homeIntroReady, .homeIntroOnItsWay:
                SearchView(model: model, search: .fixture())
            case .homePhotos:
                SearchView(model: model, search: .fixture()).onAppear {
                    guard let h = model.home, h.newMedia.isEmpty else { return }
                    model.home = h.doCopy(
                        newMedia: (1...6).map { Fixtures.file("Photo \($0).jpg", .photo, "Photos", daysAgo: Double($0)) }, newShots: h.newShots,
                        newDocs: h.newDocs, starred: h.starred, starredCount: h.starredCount, folders: h.folders, largeCount: h.largeCount,
                        largeBytes: h.largeBytes, possibleCopies: h.possibleCopies, kindTotals: h.kindTotals, folderTotals: h.folderTotals)
                }
            case .homePeople:
                SearchView(model: model, search: .fixture()).onAppear {
                    model.people = [Person(id: 1, name: "Asha", photos: 12, cover: nil), Person(id: 2, name: nil, photos: 4, cover: nil)]
                    model.facesLeft = 1_250
                }
            case .searchNoModel:
                SearchView(model: model, search: .fixture(text: "security deposit", hits: Array(Fixtures.hits.prefix(1)), vectorLane: false), presented: true)
            case .homeReading:
                SearchView(model: model, search: .fixture(), snap: AppModel.Snap(setUp: true, running: true))
            case .homeEmpty:
                SearchView(model: model, search: .fixture(), snap: AppModel.Snap(setUp: true))
            case .searchEmpty:
                SearchView(model: model, search: .fixture(text: "boarding pass", scope: .documents, hits: []), presented: true)
            case .searchWordsOnly:
                SearchView(model: model, search: .fixture(text: "security deposit", hits: Fixtures.hits, vectorLane: false), presented: true)
            case .searchLessLikely:
                SearchView(
                    model: model, search: .fixture(text: "security deposit", hits: Fixtures.hits + Fixtures.closest.prefix(2), strongCount: 2), presented: true)
            case .searchNothingWell:
                SearchView(model: model, search: .fixture(text: "car insurance renewal", hits: Fixtures.closest, strongCount: 0), presented: true)
            case .searchFirstPart:
                SearchView(
                    model: model, search: .fixture(text: "security deposit", hits: Fixtures.hits, extents: [1: .firstPart, 2: .deepening]), presented: true)
            case .searchCopies:
                SearchView(model: model, search: .fixture(text: "lease", hits: Fixtures.copies), presented: true)
            case .searchError:
                SearchView(model: model, search: .fixture(text: "electricity bill", failed: true), presented: true)
            case .searchLoading:
                SearchView(model: model, search: .fixture(text: "electricity bill", slow: true), presented: true)
            case .searchNoAccess:
                SearchView(model: model, search: .fixture(text: "beach at sunset", scope: .photos, hits: []), presented: true)
            case .searchReading:
                SearchView(model: model, search: .fixture(text: "passport", hits: []), presented: true)
            default:
                EmptyView()
            }
        }

        /// The reader's per-file progress as core sends it: a file, no file while the next one starts, the next file.
        @MainActor private static func turnOver(_ model: AppModel) async {
            let files = [model.progress.reading.current].compactMap { $0 } + model.progress.reading.recent
            var i = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(300))
                model.progress.reading.current = i % 2 == 0 ? nil : files[(i / 2) % files.count]
                i += 1
            }
        }

        /// Pages pushed over Home for a live route.
        var path: [Route] {
            switch self {
            case .browseLarge: [.files(FileQuery(sort: .largest, title: "Large files"))]
            case .browseFolders: [.folders]
            case .browseDuplicates: [.duplicates]
            default: []
            }
        }

        @MainActor func liveScreen(_ model: AppModel) -> some View {
            LiveRoute(route: self, model: model).task {
                // Once a launch: Home appears again on every Back, and ⌘F's keyboard must stay up then.
                guard !Self.launchKeyboardHidden else { return }
                Self.launchKeyboardHidden = true
                await Self.hideKeyboard()
            }
        }

        @MainActor private static var launchKeyboardHidden = false

        /// The search stays presented, with its scopes, but the keyboard leaves the screenshot.
        @MainActor static func hideKeyboard() async {
            try? await Task.sleep(for: .seconds(1.5))
            dismissKeyboard()
        }
    }

    /// Home on the real model, seeded from the launch arguments.
    private struct LiveRoute: View {
        let route: DebugRoute
        let model: AppModel
        @State private var search: SearchModel
        @Environment(\.openFile) private var openFile

        init(route: DebugRoute, model: AppModel) {
            self.route = route
            self.model = model
            let search = SearchModel(services: model.services)
            if route == .search, let q = DebugRoute.arg("-q") { search.setText(q) }
            if let scope = DebugRoute.arg("-scope").flatMap(SearchModel.Scope.init(arg:)) { search.scope = scope }
            _search = State(initialValue: search)
        }

        var body: some View {
            SearchView(model: model, search: search, presented: route == .search || route == .searchPhoto)
                .task { await seed() }
        }

        private func seed() async {
            guard let retriever = model.services?.retriever else { return }
            let q = DebugRoute.arg("-q") ?? ""
            switch route {
            case .searchPhoto:
                // The query names the photo to search with; otherwise the newest.
                let photos = (try? await retriever.files(kind: .photo, folder: nil, sort: .name, limit: 500, offset: 0)) ?? []
                if let file = photos.first(where: { q.isEmpty || $0.file.name.contains(q) })?.file { search.findSimilar(file) }
            case .viewerPdf:
                if let hit = try? await retriever.search(q: q, kinds: [.pdf], limit: 1).hits.first {
                    openFile(hit.file, locator: hit.locator, words: highlightWords(snippet: hit.snippet, query: q))
                }
            case .viewerPhoto:
                if let file = try? await retriever.search(q: q, kinds: [.photo], limit: 1).hits.first?.file { openFile(file) }
            case .viewerFile:
                // A document with no in-app viewer: Quick Look.
                if let file = try? await retriever.search(q: q, kinds: [.doc], limit: 1).hits.first?.file { openFile(file) }
            case .viewerVideo:
                if !q.isEmpty, let hit = try? await retriever.search(q: q, kinds: [.video], limit: 1).hits.first {
                    openFile(hit.file, locator: hit.locator)
                } else if let file = try? await retriever.files(kind: .video, folder: nil, sort: .newest, limit: 1, offset: 0).first?.file {
                    openFile(file, locator: "0:03")
                }
            default:
                break
            }
        }
    }

    private enum Fixtures {
        static func file(_ name: String, _ kind: Kind, _ folder: String, daysAgo: Double) -> SourceFile {
            SourceFile(
                uri: "folder://fixture/\(name)", name: name, folder: folder, mime: "application/pdf", kind: kind, size: 240_000,
                modifiedAt: Int64((Date().timeIntervalSince1970 - daysAgo * 86400) * 1000), takenAt: nil)
        }

        /// One lease kept in Downloads and in Home, as core folds it: one result, "2 copies".
        static let copies: [SearchEngine.Hit] = {
            let lease = { (folder: String, id: Int64) in
                SearchEngine.Hit(file: file("Lease agreement 2026.pdf", .pdf, folder, daysAgo: 40), fileId: id, chunkId: 0, locator: "", snippet: nil, score: 1)
            }
            let first = lease("Retriever/Downloads", 1)
            return [
                SearchEngine.Hit(
                    file: first.file, fileId: 1, chunkId: 0, locator: "", snippet: nil, score: 1, byName: true, matchReason: nil,
                    copies: [lease("Retriever/Home", 9)]),
                hits[1],
            ]
        }()

        static let hits: [SearchEngine.Hit] = [
            SearchEngine.Hit(
                file: file("Lease agreement 2026.pdf", .pdf, "Retriever/Home", daysAgo: 40), fileId: 1, chunkId: 11, locator: "p. 3",
                snippet: "The \u{2}security\u{3} \u{2}deposit\u{3} of two months' rent is refundable when the tenant leaves", score: 1),
            SearchEngine.Hit(
                file: file("Flat handover notes.docx", .doc, "Retriever/Home", daysAgo: 12), fileId: 2, chunkId: 21, locator: "",
                snippet: "Return of the \u{2}deposit\u{3} after the inspection on Friday", score: 0.8),
            SearchEngine.Hit(
                file: file("Screenshot 2026-09-14 at 18.42.07.png", .screenshot, "Photos/Screenshots", daysAgo: 30), fileId: 3, chunkId: 31,
                locator: "", snippet: "Paid \u{2}security\u{3} \u{2}deposit\u{3} ₹48,000 to Sharma Properties", score: 0.6),
        ]
    }

    extension Fixtures {
        /// Close but not good: shown after the strong hits, or alone when nothing matches well.
        static let closest: [SearchEngine.Hit] = [
            SearchEngine.Hit(
                file: file("Rent receipt March.pdf", .pdf, "Retriever/Home", daysAgo: 200), fileId: 4, chunkId: 41, locator: "p. 1",
                snippet: nil, score: 0.3),
            SearchEngine.Hit(
                file: file("Society notice board.docx", .doc, "Downloads", daysAgo: 90), fileId: 5, chunkId: 51, locator: "",
                snippet: nil, score: 0.25),
            SearchEngine.Hit(
                file: file("Scan_0001_long_file_name_without_spaces_final_copy.pdf", .pdf, "Downloads", daysAgo: 400), fileId: 6, chunkId: 61, locator: "p. 2",
                snippet: nil, score: 0.2),
        ]
    }
#endif
