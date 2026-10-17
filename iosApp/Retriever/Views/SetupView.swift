import Photos
import SwiftUI

/// First run, and the place Home's status line sends a person while the download or tune is still going.
struct SetupView: View {
    let model: AppModel
    /// Goes on to Home; offered once Retriever has something to read.
    let onDone: (() -> Void)?
    /// The person tapped Get started: the steps show instead of the welcome.
    @State private var asked: Bool
    @State private var picking = false
    @State private var folderNote: String?

    init(model: AppModel, asked: Bool = false, onDone: (() -> Void)? = nil) {
        self.model = model
        self.onDone = onDone
        _asked = State(initialValue: asked)
    }

    var body: some View {
        SetupContent(state: state, actions: SetupActions(
            getStarted: {
                asked = true
                model.requestPhotos()
            },
            allowPhotos: model.requestPhotos,
            chooseMorePhotos: model.chooseMorePhotos,
            openSettings: model.openSettings,
            addFolder: { picking = true },
            setUseMobileData: model.setUseMobileData,
            retryDownload: model.retryDownload,
            retryTune: model.retryTune,
            done: onDone
        ))
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder], allowsMultipleSelection: true) { result in
            guard case let .success(urls) = result else { return }
            folderNote = model.addFolders(urls).map(Copy.addFolderResult).joined(separator: "\n")
        }
    }

    private var state: SetupState {
        SetupState(
            asked: asked || model.started || model.photos != .notDetermined,
            photos: model.photos,
            folders: model.folders,
            folderNote: folderNote,
            downloadBytes: model.snap?.downloadBytes ?? 0,
            download: model.download,
            progress: model.downloadProgress,
            useMobileData: model.useMobileData,
            tune: model.tune,
            setUp: model.setUp,
            started: model.started
        )
    }
}

struct SetupState {
    var asked: Bool
    var photos: PHAuthorizationStatus
    var folders: [String]
    var folderNote: String?
    var downloadBytes: Int64
    var download: ModelDownload.State
    var progress: Double
    var useMobileData: Bool
    var tune: AppModel.Tune
    var setUp: Bool
    var started: Bool

    var photosReady: Bool { photos == .authorized || photos == .limited }
    /// A later launch never restarts a finished download, so its state stays idle: set-up says it's done.
    var downloaded: Bool { download == .ready || setUp }
    var size: String? { downloadBytes > 0 ? Copy.bytes(downloadBytes) : nil }
}

struct SetupActions {
    var getStarted: () -> Void = {}
    var allowPhotos: () -> Void = {}
    var chooseMorePhotos: () -> Void = {}
    var openSettings: () -> Void = {}
    var addFolder: () -> Void = {}
    var setUseMobileData: (Bool) -> Void = { _ in }
    var retryDownload: () -> Void = {}
    var retryTune: () -> Void = {}
    var done: (() -> Void)?
}

struct SetupContent: View {
    let state: SetupState
    var actions = SetupActions()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                welcome
                if state.asked { steps } else { getStarted }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image("IllusWelcome")
                .resizable()
                .scaledToFit()
                .frame(maxWidth: state.asked ? 160 : 260)
                .frame(maxWidth: .infinity)
                .accessibilityHidden(true)
            Text("Find anything on your iPhone by what's in it.")
                .font(.title.bold())
                .fixedSize(horizontal: false, vertical: true)
            Text("Search your photos, screenshots, PDFs and documents in your own words. Everything stays on this iPhone.")
                .font(.body)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var getStarted: some View {
        VStack(spacing: 12) {
            Button(action: actions.getStarted) {
                Text("Get started").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            Text(state.size.map { "Needs a one-time download of \($0). Best on Wi-Fi." } ?? "Needs a one-time download. Best on Wi-Fi.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    @ViewBuilder private var steps: some View {
        SetupStep(title: "Your photos", done: state.photosReady) {
            switch state.photos {
            case .notDetermined:
                Note("Retriever looks inside your photos and screenshots so you can find them by what's in them.")
                Button("Allow access to Photos", action: actions.allowPhotos).buttonStyle(.bordered)
            case .limited:
                Note("Retriever can see the photos you picked. Choose more to search them too.")
                Button("Choose more photos", action: actions.chooseMorePhotos).buttonStyle(.bordered)
            case .authorized:
                Note("Retriever can see all your photos.")
            default:
                Note("Photos access is off, so Retriever can't search your photos. You can turn it on in Settings.")
                Button("Open Settings", action: actions.openSettings).buttonStyle(.bordered)
            }
        }

        SetupStep(title: "Folders · optional", done: !state.folders.isEmpty) {
            ForEach(state.folders, id: \.self) { Label($0, systemImage: "folder") }
            if let note = state.folderNote { Note(note) }
            Button(state.folders.isEmpty ? "Add a folder" : "Add another folder", action: actions.addFolder).buttonStyle(.bordered)
            Note("Files you put in Retriever's folder in the Files app are always included.")
        }

        SetupStep(title: state.size.map { "Download the search model · \($0)" } ?? "Download the search model", done: state.downloaded) {
            if !state.downloaded {
                download
                Toggle("Use mobile data", isOn: Binding(get: { state.useMobileData }, set: actions.setUseMobileData))
                Note(state.useMobileData ? "Downloads on Wi-Fi or mobile data." : "Waits for Wi-Fi, so it won't use your data.")
            }
        }

        SetupStep(title: "Get to know this iPhone · about a minute", done: { if case .done = state.tune { true } else { false } }()) {
            switch state.tune {
            case .idle:
                if !state.downloaded { Note("Starts after the download.") }
            case .running:
                Busy("Testing how fast this iPhone reads photos…")
            case let .done(summary):
                Note(summary)
            case .failed:
                Failure("Couldn't finish this step.")
                Button("Try again", action: actions.retryTune).buttonStyle(.bordered)
            }
        }

        if let done = actions.done, state.started {
            Button(action: done) {
                Text(state.setUp ? "Start searching" : "Search while this finishes").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    @ViewBuilder private var download: some View {
        switch state.download {
        case .idle:
            Note(state.started ? "Starting…" : "Starts once Retriever can see your photos or a folder.")
        case .waitingForWifi:
            Note("Waiting for Wi-Fi. It starts by itself when you're on Wi-Fi.")
        case .downloading:
            ProgressView(value: state.progress) {
                Text("Downloading").font(.subheadline)
            } currentValueLabel: {
                Text(state.progress.formatted(.percent.precision(.fractionLength(0)))).monospacedDigit()
            }
        case .verifying:
            Busy("Checking the download…")
        case .ready:
            EmptyView()
        case let .failed(reason):
            Failure(reason)
            Button("Try again", action: actions.retryDownload).buttonStyle(.bordered)
        }
    }
}

/// A titled step with a done mark; one VoiceOver stop for the title and its state.
private struct SetupStep<Content: View>: View {
    let title: String
    let done: Bool
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Image(systemName: done ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(done ? Color.accentColor : Color.secondary)
                    .imageScale(.large)
                Text(title).font(.headline)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title)
            .accessibilityValue(done ? "Done" : "Not done yet")
            VStack(alignment: .leading, spacing: 10) { content }
                .padding(.leading, 36)
        }
    }
}

struct Note: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
    }
}

struct Failure: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Label(text, systemImage: "exclamationmark.triangle.fill")
            .font(.subheadline)
            .foregroundStyle(.red)
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

#Preview("Downloading") {
    SetupView(model: AppModel(fixture: .downloading(0.34)))
}
