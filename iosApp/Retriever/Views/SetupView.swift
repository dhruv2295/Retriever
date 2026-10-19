import Photos
import RetrieverCore
import SwiftUI

/// First run, and the place Home's status line sends a person while the download or tune is still going. The stage
/// follows from stored state alone (see `SetupState.stage`), so every stage resumes after the app is closed.
struct SetupView: View {
    let model: AppModel
    /// Goes on to Home; offered once Retriever has something to read.
    let onDone: (() -> Void)?
    /// Opened past the welcome (Home's setup details, previews).
    private let asked: Bool
    @State private var picking = false
    @State private var folderNote: String?
    @State private var askMobileData = false
    @State private var readEverything = false
    /// Nil until core has answered, so a relaunch mid-way is never taken for "nothing to try".
    @State private var taste: TasteSnap?
    @State private var decided: Bool?
    /// The short read's planned files, once core has them: the try-it search stays inside them.
    @State private var planIds: [Int64]?
    @State private var trySearch: SearchModel
    @State private var trialPath: [Route] = []
    /// Files beyond the short read, for the commit sentence; nil until counted.
    @State private var restFiles: Int?
    @AppStorage("firstRunTrying") private var trying = false
    @AppStorage("firstRunCommitting") private var committing = false

    init(model: AppModel, asked: Bool = false, onDone: (() -> Void)? = nil) {
        self.model = model
        self.onDone = onDone
        self.asked = asked
        let fixture = model.services == nil
        _taste = State(initialValue: fixture ? TasteSnap() : nil)
        _decided = State(initialValue: fixture ? true : nil)
        _trySearch = State(initialValue: SearchModel(services: model.services))
    }

    var body: some View {
        let state = state
        Group {
            if state.stage == .tryIt, let planIds {
                tryIt(planIds, state)
            } else {
                SetupContent(state: state, actions: actions, reading: { model.progress.reading })
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            folderNote = model.addFolders(urls).map(Copy.addFolderResult).joined(separator: "\n")
        }
        .alert(Copy.firstRunMobileDataTitle, isPresented: $askMobileData) {
            Button(Copy.firstRunUseMobileData) {
                model.setUseMobileData(true)
                model.beginSetup(readEverything: readEverything)
            }
            Button(Copy.firstRunWaitForWifi) { model.beginSetup(readEverything: readEverything) }
        } message: {
            Text(Copy.firstRunMobileDataMessage(size: state.size))
        }
        .task {
            guard let retriever = model.services?.retriever else { return }
            for await snap in retriever.tasteStream() { taste = snap }
        }
        .task(id: taste?.planned) {
            decided = await model.tasteDecided()
            planIds = (model.services?.retriever.scopeIds.value as? [KotlinLong])?.map(\.int64Value)
        }
    }

    private var actions: SetupActions {
        SetupActions(
            begin: begin,
            allowPhotos: model.requestPhotos,
            chooseMorePhotos: model.chooseMorePhotos,
            openSettings: model.openSettings,
            addFolder: { picking = true },
            setUseMobileData: model.setUseMobileData,
            stopDownload: model.stopDownload,
            retryDownload: model.retryDownload,
            retryTune: model.retryTune,
            tryNow: { trying = true },
            continueToCommit: continueFromTryIt,
            readWhileCharging: { model.commit(whileCharging: true) },
            readNow: { model.commit(whileCharging: false) },
            notNow: model.decideLater,
            skip: model.skipSetup,
            done: onDone
        )
    }

    /// The real search over the files just read. Inside Home's stack (setup details) it uses that stack's bar.
    @ViewBuilder private func tryIt(_ ids: [Int64], _ state: SetupState) -> some View {
        let taste = state.taste ?? TasteSnap()
        let page = SearchView(model: model, search: trySearch, trial: Trial(ids: ids, photoSearch: !state.textOnly && taste.photosRead >= 5))
            .toolbar {
                if state.canSkip {
                    ToolbarItem(placement: .topBarTrailing) { Button(Copy.firstRunSkip, action: model.skipSetup) }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Button(action: continueFromTryIt) {
                    Text(restFiles == 0 ? Copy.tryItDone : Copy.tryItContinue).frame(maxWidth: .infinity)
                }
                .buttonStyle(.primaryAction)
                .controlSize(.large)
                .padding(.horizontal, 24)
                .padding(.vertical, 8)
                .background(.bar)
            }
            .onChange(of: taste.pending, initial: true) { _, left in trySearch.stillReading = left }
            .task { await refreshRest() }
        if asked { page } else { AppStack(model: model, path: $trialPath) { page } }
    }

    /// Continue: the commit choice, or Done when the short read already was everything.
    private func continueFromTryIt() {
        Task {
            await refreshRest()
            if restFiles == 0 { model.commitScope() } else { committing = true }
        }
    }

    /// Files still unread beyond the short read; nil while no pace is measured.
    private func refreshRest() async {
        guard let retriever = model.services?.retriever else { return }
        restFiles = (try? await retriever.estimateAll()).map { Int($0.files) }
    }

    private func begin(readEverything: Bool) {
        self.readEverything = readEverything
        if model.asksMobileData { askMobileData = true } else { model.beginSetup(readEverything: readEverything) }
    }

    private var state: SetupState {
        SetupState(
            asked: asked || model.started,
            photos: model.photos,
            folders: model.folders,
            folderNote: folderNote,
            downloadBytes: model.snap?.downloadBytes ?? 0,
            download: model.download,
            downloadStopped: model.services?.download.stopped ?? false,
            progress: model.downloadProgress,
            useMobileData: model.useMobileData,
            tune: model.tune,
            setUp: model.setUp,
            ramGb: model.snap.flatMap { $0.textOnly ? $0.ramGb : nil },
            chosenPhotos: (model.snap?.byKind[.photo] ?? 0) + (model.snap?.byKind[.screenshot] ?? 0),
            taste: taste,
            decided: decided,
            trying: trying,
            committing: committing,
            canSkip: !model.setupSeen,
            restFiles: restFiles,
            moreFiles: model.snap?.waiting
        )
    }
}

enum SetupStage: Equatable {
    case welcome, access, waiting, readFew, tryIt, commit, done
}

struct SetupState {
    var asked: Bool
    var photos: PHAuthorizationStatus
    var folders: [String]
    var folderNote: String?
    var downloadBytes: Int64
    var download: ModelDownload.State
    var downloadStopped = false
    var progress: Double
    var useMobileData: Bool
    var tune: AppModel.Tune
    var setUp: Bool
    /// The memory of an iPhone too small for the model; nil when the model runs.
    var ramGb: Double?
    /// Photos and screenshots in the limited selection, once counted.
    var chosenPhotos = 0
    /// Nil until core has answered.
    var taste: TasteSnap?
    /// True when no short read waits on the person; nil until core has answered.
    var decided: Bool?
    var trying = false
    var committing = false
    var canSkip = false
    /// Files left beyond the short read, for the commit sentence; nil until counted.
    var restFiles: Int?
    /// Files beyond the short read that are not yet scheduled; what the commit sentence counts.
    var moreFiles: Int?

    var photosReady: Bool { photos == .authorized || photos == .limited }
    var accessGiven: Bool { photosReady || !folders.isEmpty }
    /// A later launch never restarts a finished download, so its state stays idle: set-up says it's done.
    var downloaded: Bool { download == .ready || setUp }
    var size: String? { downloadBytes > 0 ? Copy.downloadSize(downloadBytes) : nil }
    var textOnly: Bool { ramGb != nil }
    /// A short read is planned and the person has not answered what comes after it.
    var firstRun: Bool { decided == false && (taste?.planned ?? 0) > 0 }

    /// What shows. Every input is stored (UserDefaults, Photos, the model file, core's meta), so it survives the process.
    var stage: SetupStage {
        if !asked { return .welcome }
        if !accessGiven { return .access }
        // Until core has answered, a set-up phone could still have a short read to show.
        if !setUp || decided == nil || taste == nil { return .waiting }
        guard firstRun else { return .done }
        return committing ? .commit : trying ? .tryIt : .readFew
    }

    /// The dog is the stage's state: trotting while work runs, resting when nothing moves, sheepish on a failure.
    /// The commit choice and the try-it search have no mascot.
    var mood: MascotMood? {
        switch stage {
        case .welcome: return .welcome
        case .tryIt, .commit: return nil
        case .done: return .resting
        case .access, .waiting, .readFew:
            if !downloaded, case .failed = download { return .trouble }
            if case .failed = tune { return .trouble }
            if !downloaded, download == .waitingForWifi || downloadStopped { return .resting }
            return .walking
        }
    }
}

struct SetupActions {
    var begin: (_ readEverything: Bool) -> Void = { _ in }
    var allowPhotos: () -> Void = {}
    var chooseMorePhotos: () -> Void = {}
    var openSettings: () -> Void = {}
    var addFolder: () -> Void = {}
    var setUseMobileData: (Bool) -> Void = { _ in }
    var stopDownload: () -> Void = {}
    var retryDownload: () -> Void = {}
    var retryTune: () -> Void = {}
    var tryNow: () -> Void = {}
    var continueToCommit: () -> Void = {}
    var readWhileCharging: () -> Void = {}
    var readNow: () -> Void = {}
    var notNow: () -> Void = {}
    var skip: () -> Void = {}
    var done: (() -> Void)?
}

struct SetupContent: View {
    let state: SetupState
    var actions = SetupActions()
    var reading: () -> ReadingPreview = { ReadingPreview() }
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if state.canSkip, state.stage != .welcome, state.stage != .done {
                    Button(Copy.firstRunSkip, action: actions.skip).frame(maxWidth: .infinity, alignment: .trailing)
                }
                if let mood = state.mood {
                    MascotView(mood: mood, settles: false)
                        .frame(width: state.stage == .welcome ? 260 : 160)
                        .frame(maxWidth: .infinity)
                }
                stageBody
                    .id(state.stage)
                    .transition(reduceMotion ? .opacity : .push(from: .trailing))
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .animation(reduceMotion ? .default : .snappy, value: state.stage)
        }
        .safeAreaInset(edge: .bottom) {
            if state.stage == .readFew, state.taste?.readyToTry == true {
                VStack(alignment: .leading, spacing: 4) {
                    Button(action: actions.tryNow) { Text(Copy.firstRunTryNow).frame(maxWidth: .infinity) }
                        .buttonStyle(.primaryAction).controlSize(.large)
                    Note(Copy.firstRunStaysSearchable)
                }
                .padding(.horizontal, 24).padding(.vertical, 12).background(.bar)
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .clipped()
    }

    @ViewBuilder private var stageBody: some View {
        switch state.stage {
        case .welcome: welcome
        case .access: access
        case .waiting: waiting
        case .readFew: readFew
        // The search itself is SetupView's (it needs the model); this is the moment before core has the planned files.
        case .tryIt: ProgressView().frame(maxWidth: .infinity)
        case .commit: commit
        case .done: done
        }
    }

    // MARK: Stages

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(Copy.firstRunHeadline).font(.title.bold()).fixedSize(horizontal: false, vertical: true)
            Text(Copy.firstRunWelcomeBody).fixedSize(horizontal: false, vertical: true)
            let examples = Copy.firstRunExamples(textOnly: state.textOnly)
            VStack(alignment: .leading, spacing: 12) {
                ForEach(Array(examples.enumerated()), id: \.element) { index, query in
                    ExampleQuery(query: query, delay: 400 + examples.prefix(index).reduce(0) { $0 + $1.count * 35 + 250 })
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
            Button {
                actions.begin(false)
            } label: {
                Text(Copy.firstRunTry).frame(maxWidth: .infinity)
            }
            .buttonStyle(.primaryAction)
            .controlSize(.large)
            .padding(.top, 8)
            Button {
                actions.begin(true)
            } label: {
                Text(Copy.firstRunReadAll).frame(maxWidth: .infinity)
            }
            .controlSize(.large)
            Note(state.textOnly ? Copy.firstRunTextOnlyTime : Copy.firstRunTime(size: state.size))
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
            if let ramGb = state.ramGb {
                Note(Copy.textOnlyNote(ramGb: ramGb)).multilineTextAlignment(.center)
            }
        }
    }

    /// No file content here: only the ways to allow access, and how the download is doing.
    private var access: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(Copy.firstRunAccessTitle).font(.title2.bold())
            if state.photos == .denied || state.photos == .restricted {
                Note(Copy.firstRunPhotosOff)
                Button("Open Settings", action: actions.openSettings).buttonStyle(.bordered)
                Failure(Copy.firstRunNothingAllowed)
            } else {
                Button(Copy.firstRunAllowPhotos, action: actions.allowPhotos).buttonStyle(.primaryAction).controlSize(.large)
                Note(Copy.firstRunPhotosNote)
            }
            ForEach(state.folders, id: \.self) { Label($0, systemImage: "folder") }
            if let note = state.folderNote { Note(note) }
            Button(Copy.firstRunAddFolder, action: actions.addFolder).buttonStyle(.bordered)
            Note(Copy.firstRunFolderNote)
            if !state.textOnly { downloadLine }
        }
    }

    /// One line at the foot of the access screen: the download runs while the person decides.
    @ViewBuilder private var downloadLine: some View {
        switch state.download {
        case .downloading:
            Note(Copy.firstRunDownloadLine(state.progress)).monospacedDigit()
            ProgressView(value: state.progress).accessibilityLabel(Copy.firstRunDownloadA11y(state.progress))
        case .waitingForWifi: Note("Waiting for Wi-Fi")
        case .verifying: Note(Copy.firstRunChecking)
        case .ready: Note(Copy.firstRunDownloaded)
        case .failed(let reason): Failure(reason)
        case .idle: if state.downloadStopped { Note(Copy.firstRunStopped) }
        }
    }

    /// The download, then the tune. Names are searchable throughout.
    private var waiting: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(state.downloaded ? Copy.firstRunTuneTitle : Copy.firstRunWaitingTitle).font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
            GroupBox {
                VStack(alignment: .leading, spacing: 12) {
                    if state.downloaded { tuneBody } else { downloadBody }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !state.downloaded {
                VStack(alignment: .leading, spacing: 8) {
                    Text(Copy.firstRunWhyDownload).font(.subheadline.bold()).accessibilityAddTraits(.isHeader)
                    GroupBox {
                        VStack(alignment: .leading, spacing: 16) {
                            DownloadReason(icon: "sparkle.magnifyingglass", headline: Copy.firstRunUnderstandsTitle, supporting: Copy.firstRunUnderstandsBody)
                            DownloadReason(icon: "lock", headline: Copy.firstRunOnDeviceTitle, supporting: Copy.firstRunOnDeviceBody)
                            DownloadReason(
                                icon: "checkmark.circle", headline: Copy.firstRunDownloadOnceTitle, supporting: Copy.firstRunDownloadOnceBody(size: state.size))
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 8) {
                Text(Copy.firstRunWhileWaiting).font(.subheadline.bold()).accessibilityAddTraits(.isHeader)
                Note(Copy.firstRunNamesNow)
                Button(Copy.firstRunSearchByName, action: actions.skip).buttonStyle(.bordered)
                accessSummary
            }
        }
    }

    @ViewBuilder private var downloadBody: some View {
        switch state.download {
        case .downloading:
            let progress = min(max(state.progress, 0), 1)
            let amount = state.size.map { "\(Copy.downloadSize(Int64(progress * Double(state.downloadBytes)))) of \($0)" }
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(amount ?? "Downloading").font(.subheadline).monospacedDigit()
                    .accessibilityIdentifier("download-amount")
                Spacer(minLength: 0)
                if amount != nil {
                    Text(progress, format: .percent.precision(.fractionLength(0))).font(.subheadline).monospacedDigit()
                }
                Button(action: actions.stopDownload) {
                    Text(Copy.firstRunStop).frame(minWidth: 44, minHeight: 44)
                }
            }
            Group {
                if amount != nil { ProgressView(value: progress) } else { ProgressView().progressViewStyle(.linear) }
            }
            .accessibilityHidden(true)

        case .waitingForWifi:
            Note(Copy.firstRunWaitingForWifi)
            Button(Copy.firstRunUseMobileData) { actions.setUseMobileData(true) }.buttonStyle(.bordered)
        case .verifying: Note(Copy.firstRunChecking)
        case .failed(let reason):
            Failure(reason).transition(.opacity)
            Button(Copy.firstRunTryAgain, action: actions.retryDownload).buttonStyle(.bordered)
        case .idle:
            if state.downloadStopped {
                Note(Copy.firstRunStopped)
                Button(Copy.firstRunDownload, action: actions.retryDownload).buttonStyle(.bordered)
            } else if !state.textOnly {
                Note("Starting…")
            }
        case .ready: EmptyView()
        }
    }

    @ViewBuilder private var tuneBody: some View {
        if case .failed = state.tune {
            Failure(Copy.firstRunTuneFailed).transition(.opacity)
            Button(Copy.firstRunTryAgain, action: actions.retryTune).buttonStyle(.bordered)
        } else {
            VStack(alignment: .leading, spacing: 12) {
                ProgressView().progressViewStyle(.linear)
                Note(Copy.firstRunTuneBody)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Copy.firstRunTuneTitle)
            .accessibilityValue(Copy.firstRunTuneBody)
        }
    }

    /// What Photos access means for the search, only when it is partial or off.
    @ViewBuilder private var accessSummary: some View {
        switch state.photos {
        case .limited:
            Note(state.chosenPhotos > 0 ? Copy.limitedPhotosFootnote(photos: state.chosenPhotos, folders: state.folders.count) : Copy.firstRunLimitedNote)
            Button("Choose more photos", action: actions.chooseMorePhotos).buttonStyle(.bordered).foregroundStyle(.primary)
        case .denied, .restricted:
            Note(Copy.firstRunPhotosOff)
            Button("Open Settings", action: actions.openSettings).buttonStyle(.bordered).foregroundStyle(.primary)
        default:
            EmptyView()
        }
    }

    /// The file being read, the files ready to search, and progress through each kind.
    private var readFew: some View {
        let taste = state.taste ?? TasteSnap()
        return VStack(alignment: .leading, spacing: 16) {
            Text(Copy.firstRunReadingHeadline).font(.title2.bold())
            Note(Copy.firstRunReadingLine(files: taste.read, secondsLeft: reading().pauseReason == nil ? taste.secondsLeft : nil))
            SetupReadingFiles(reading: reading, read: taste.read)
            WrappingLayout { ForEach(taste.rows) { KindRow(row: $0) } }
        }
        .animation(reduceMotion ? nil : .snappy, value: taste.readyToTry)
    }

    /// One sentence about the rest, the nights, and three one-tap answers. No mascot: the decision is the focal point.
    private var commit: some View {
        ReadingChoice(files: state.moreFiles, charging: actions.readWhileCharging, readNow: actions.readNow, notNow: actions.notNow)
    }

    /// iOS 26 keeps a started read going after the person leaves; before it, only the open app reads.
    private static var continuedReading: Bool {
        if #available(iOS 26.0, *) { true } else { false }
    }

    /// Everything is ready and nothing waits on an answer: Home's setup details, or the read-everything path's way in.
    private var done: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(Copy.firstRunDone).font(.title2.bold())
            if let done = actions.done {
                Button(action: done) { Text(Copy.firstRunStartSearching).frame(maxWidth: .infinity) }
                    .buttonStyle(.primaryAction)
                    .controlSize(.large)
            }
        }
    }
}

/// Per-row state keeps each character update out of the rest of setup.
private struct ExampleQuery: View {
    let query: String
    let delay: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @SceneStorage private var played: Bool
    @State private var typed: Int?

    init(query: String, delay: Int) {
        self.query = query
        self.delay = delay
        _played = SceneStorage(wrappedValue: false, "setup.example.\(query)")
    }

    private var text: AttributedString {
        var result = AttributedString(query)
        let count = reduceMotion ? query.count : typed ?? (played ? query.count : 0)
        let tail = result.characters.index(result.startIndex, offsetBy: count)
        result[tail...].foregroundColor = .clear
        return result
    }

    var body: some View {
        Label {
            Text(text).fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(query)
        .task(id: reduceMotion) {
            guard !played, !reduceMotion else {
                typed = query.count
                played = true
                return
            }
            typed = 0
            played = true
            do {
                try await Task.sleep(for: .milliseconds(delay))
                for count in 1...query.count {
                    typed = count
                    try await Task.sleep(for: .milliseconds(35))
                }
            } catch {
                // Disappearing cancels the typing.
            }
        }
        .onDisappear {
            typed = nil
            played = false
        }
    }
}

private struct DownloadReason: View {
    let icon: String
    let headline: String
    let supporting: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 4) {
                Text(headline).font(.subheadline.bold())
                Note(supporting)
            }
            .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: icon).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Counts and the bar move without easing; a finished kind swaps its circle for a check once.
private struct KindRow: View {
    let row: TasteSnap.Row

    var body: some View {
        HStack(spacing: 4) {
            Text("\(row.name) \(row.read)/\(row.planned)").font(.subheadline).monospacedDigit()
            if row.done { Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor) }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.fill.quaternary, in: Capsule())
        .accessibilityElement(children: .combine)
        .accessibilityValue(row.done ? Copy.firstRunReadyToSearch : "")
    }
}

struct Note: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.subheadline).foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
    }
}

struct Failure: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Label {
            Text(text).foregroundStyle(.primary)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
        }
        .font(.subheadline)
        .fixedSize(horizontal: false, vertical: true)
    }
}

struct Busy: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text(text).font(.subheadline)
        }
        .accessibilityElement(children: .combine)
    }
}

#Preview("Welcome") {
    SetupView(model: AppModel(fixture: .firstRun))
}

#Preview("Downloading") {
    SetupView(model: AppModel(fixture: .downloading(0.34)), asked: true)
}

#Preview("Tuning") {
    SetupView(model: AppModel(fixture: .tuning), asked: true)
}

#Preview("Download failed") {
    SetupView(model: AppModel(fixture: .downloadFailed), asked: true)
}

#Preview("Reading a few files") {
    SetupContent(
        state: SetupState(
            asked: true, photos: .authorized, folders: [], downloadBytes: 0, download: .ready, progress: 1, useMobileData: false,
            tune: .done("Using the processor"), setUp: true,
            taste: TasteSnap(counts: [.screenshot: (6, 6), .pdf: (8, 12), .doc: (3, 4), .photo: (11, 18), .video: (0, 2)], secondsLeft: 70, readyToTry: true),
            decided: false, canSkip: true
        ))
}

extension TasteSnap {
    /// Photos read so far; screenshots are their own row.
    fileprivate var photosRead: Int { rows.first { $0.name == "Photos" }?.read ?? 0 }
}
