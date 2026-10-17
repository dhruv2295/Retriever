#if DEBUG
    import RetrieverCore
    import SwiftUI

    /// `-bgdrill <seconds>` (0: when iOS's background time runs out): the simulator has no BGTaskScheduler (submit
    /// fails with "not available on this platform" and lldb's `_simulateLaunchForTaskWithIdentifier:` never
    /// returns), so once the app is in the background this runs the task's own handler pieces: runBatch, then
    /// expire() after that long.
    enum BackgroundDrill {
        static func armIfAsked() {
            let args = CommandLine.arguments
            guard let i = args.firstIndex(of: "-bgdrill"), i + 1 < args.count, let expireAfter = Double(args[i + 1]) else { return }
            _ = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
                var hold = UIBackgroundTaskIdentifier.invalid
                hold = UIApplication.shared.beginBackgroundTask(withName: "bgdrill") { IndexTask.expire() }
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    IndexTask.runBatch { ok in
                        print("BGDRILL setTaskCompleted(success: \(ok))")
                        DispatchQueue.main.async { UIApplication.shared.endBackgroundTask(hold) }
                    }
                }
                if expireAfter > 0 { DispatchQueue.main.asyncAfter(deadline: .now() + 2 + expireAfter) { IndexTask.expire() } }
            }
        }
    }

    /// `-route <name>` opens one screen, so screenshots need no taps. Fixture routes pin a state and need no index;
    /// live routes run the real engine on the seeded library (`-q <words>`, `-scope pdf`).
    enum DebugRoute: String {
        case setupWelcome = "setup.welcome"
        case setupPhotos = "setup.photos"
        case setupLimited = "setup.limited"
        case setupDenied = "setup.denied"
        case setupFolders = "setup.folders"
        case setupDownload = "setup.download"
        case setupWaitingWifi = "setup.waitingWifi"
        case setupFailed = "setup.failed"
        case setupTuning = "setup.tuning"
        case statusReading = "status.reading"
        case statusCaughtUp = "status.caughtUp"
        case statusPaused = "status.paused"
        case statusSkipped = "status.skipped"
        case homeReading = "home.reading"
        case homeEmpty = "home.empty"
        case searchEmpty = "search.empty"
        case searchWordsOnly = "search.wordsOnly"
        case searchError = "search.error"
        case searchLoading = "search.loading"
        case searchNoAccess = "search.noAccess"
        case searchReading = "search.reading"
        // Live.
        case home
        case search
        case searchPhoto = "search.photo"
        case browse
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
            case .home, .search, .searchPhoto, .browse, .browseLarge, .browseFolders, .browseDuplicates, .viewerPdf, .viewerVideo, .viewerPhoto, .viewerFile: true
            default: false
            }
        }

        /// A fixture route; the app then runs on a fixture model.
        static let current: DebugRoute? = parsed.flatMap { $0.isLive ? nil : $0 }

        /// A live route; the app runs on the real model.
        static let live: DebugRoute? = parsed.flatMap { $0.isLive ? $0 : nil }

        /// `-open status|setup` pushes that page over the live Home, for screenshots of a real run.
        static let openOnLaunch: Route? = arg("-open").flatMap { ["status": Route.status, "setup": .setup][$0] }

        static func arg(_ name: String) -> String? {
            let args = CommandLine.arguments
            guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
            return args[i + 1]
        }

        var fixture: AppFixture {
            switch self {
            case .setupWelcome, .setupPhotos: .firstRun
            case .setupLimited: .photosLimited
            case .setupDenied: .photosDenied
            case .setupFolders: .downloading(0.08)
            case .setupDownload: .downloading(0.34)
            case .setupWaitingWifi: .waitingForWifi
            case .setupFailed: .downloadFailed
            case .setupTuning: .tuning
            case .statusReading, .searchReading: .reading(done: 1_204, total: 5_310)
            case .statusPaused: .paused
            case .statusSkipped: .skipped(6)
            case .searchNoAccess: .noAccess
            default: .caughtUp
            }
        }

        @MainActor @ViewBuilder func screen(_ model: AppModel) -> some View {
            switch self {
            case .setupWelcome:
                SetupView(model: model)
            case .setupPhotos, .setupLimited, .setupDenied, .setupFolders, .setupDownload, .setupWaitingWifi, .setupFailed, .setupTuning:
                SetupView(model: model, asked: true, onDone: {})
            case .statusReading, .statusCaughtUp, .statusPaused, .statusSkipped:
                NavigationStack { StatusView(model: model) }
            default:
                AppStack(model: model) { fixtureSearch(model).task { await Self.hideKeyboard() } }
            }
        }

        @MainActor @ViewBuilder private func fixtureSearch(_ model: AppModel) -> some View {
            switch self {
            case .homeReading:
                SearchView(model: model, search: .fixture(), snap: AppModel.Snap(setUp: true, running: true))
            case .homeEmpty:
                SearchView(model: model, search: .fixture(), snap: AppModel.Snap(setUp: true))
            case .searchEmpty:
                SearchView(model: model, search: .fixture(text: "boarding pass", scope: .documents, hits: []), presented: true)
            case .searchWordsOnly:
                SearchView(model: model, search: .fixture(text: "security deposit", hits: Fixtures.hits, vectorLane: false), presented: true)
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

        /// Pages pushed over Home for a live route.
        var path: [Route] {
            switch self {
            case .browse: [.browse]
            case .browseLarge: [.browse, .files(FileQuery(sort: .largest, title: "Large files"))]
            case .browseFolders: [.browse, .folders]
            case .browseDuplicates: [.browse, .duplicates]
            default: []
            }
        }

        @MainActor func liveScreen(_ model: AppModel) -> some View {
            LiveRoute(route: self, model: model).task { await Self.hideKeyboard() }
        }

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
            SourceFile(uri: "folder://fixture/\(name)", name: name, folder: folder, mime: "application/pdf", kind: kind, size: 240_000,
                       modifiedAt: Int64((Date().timeIntervalSince1970 - daysAgo * 86400) * 1000))
        }

        static let hits: [SearchEngine.Hit] = [
            SearchEngine.Hit(file: file("Lease agreement 2026.pdf", .pdf, "Retriever/Home", daysAgo: 40), fileId: 1, chunkId: 11, locator: "p. 3",
                             snippet: "The \u{2}security\u{3} \u{2}deposit\u{3} of two months' rent is refundable when the tenant leaves", score: 1),
            SearchEngine.Hit(file: file("Flat handover notes.docx", .doc, "Retriever/Home", daysAgo: 12), fileId: 2, chunkId: 21, locator: "",
                             snippet: "Return of the \u{2}deposit\u{3} after the inspection on Friday", score: 0.8),
            SearchEngine.Hit(file: file("Screenshot 2026-09-14 at 18.42.07.png", .screenshot, "Photos/Screenshots", daysAgo: 30), fileId: 3, chunkId: 31,
                             locator: "", snippet: "Paid \u{2}security\u{3} \u{2}deposit\u{3} ₹48,000 to Sharma Properties", score: 0.6),
        ]
    }

    /// `-a11ydump <seconds>` prints every accessibility element on screen after that long, for checking labels.
    enum AccessibilityDump {
        static func armIfAsked() {
            if CommandLine.arguments.contains("-selfcheck") { selfCheck() }
            guard let delay = DebugRoute.arg("-a11ydump").flatMap(Double.init) else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                guard let window = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else { return }
                var seen = Set<ObjectIdentifier>()
                var lines: [String] = []
                walk(window, &seen, &lines)
                print("A11YDUMP BEGIN\n" + lines.joined(separator: "\n") + "\nA11YDUMP END")
            }
        }

        /// `-selfcheck`: the locator and snippet parsing the results and viewers depend on.
        private static func selfCheck() {
            let checks: [(String, Bool)] = [
                ("page", Format.page("p. 12") == 12 && Format.page("p. 0") == nil && Format.page("12") == nil),
                ("ms", Format.ms("0:37") == 37_000 && Format.ms("1:02:03") == 3_723_000 && Format.ms("p. 3") == nil && Format.ms("1:") == nil),
                ("badge", Format.badge("p. 3") == "Page 3" && Format.badge("0:37") == "0:37" && Format.badge("") == nil),
                ("marked", Set(Format.markedWords("a \u{2}rent\u{3} and \u{2}deposit\u{3} b")) == ["rent", "deposit"]),
                ("highlight", String(Format.highlighted("x \u{2}y\u{3} z").characters) == "x y z"),
            ]
            let failed = checks.filter { !$0.1 }.map(\.0)
            print(failed.isEmpty ? "I5 SELFCHECK PASS" : "I5 SELFCHECK FAIL: \(failed.joined(separator: ", "))")
        }

        private static func walk(_ node: NSObject, _ seen: inout Set<ObjectIdentifier>, _ lines: inout [String]) {
            guard seen.insert(ObjectIdentifier(node)).inserted else { return }
            if node.isAccessibilityElement {
                var line = "[\(node.accessibilityLabel ?? "")]"
                if let hint = node.accessibilityHint { line += " hint: \(hint)" }
                if let actions = node.accessibilityCustomActions, !actions.isEmpty { line += " actions: \(actions.map(\.name).joined(separator: ", "))" }
                if node.accessibilityTraits.contains(.button) { line += " (button)" }
                if node.accessibilityTraits.contains(.header) { line += " (heading)" }
                lines.append(line)
            }
            let count = node.accessibilityElementCount()
            if count != NSNotFound, count > 0 {
                for i in 0..<count { if let child = node.accessibilityElement(at: i) as? NSObject { walk(child, &seen, &lines) } }
            } else if let view = node as? UIView {
                view.subviews.forEach { walk($0, &seen, &lines) }
            }
        }
    }
#endif
