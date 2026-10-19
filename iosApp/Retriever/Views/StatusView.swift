import Photos
import SwiftUI

/// How reading is going and what couldn't be read. The switches that steer it are in Settings.
struct StatusView: View {
    let model: AppModel

    var body: some View {
        let snap = model.live ?? AppModel.Snap()
        StatusContent(
            snap: snap,
            photos: model.photos,
            download: model.download,
            progress: model.downloadProgress,
            skippedFiles: model.skippedFiles,
            screenKeptOn: model.screenKeptOn,
            canKeepReading: model.canKeepReading,
            celebrating: model.caughtUpDue,
            actions: actions
        )
        .onChange(of: snap.skipped, initial: true) { _, n in
            if n > 0 { model.loadSkipped() }
        }
    }

    private var actions: StatusActions {
        StatusActions(
            pause: model.pause,
            resume: model.resume,
            retrySkipped: model.retrySkipped,
            useMobileData: { model.setUseMobileData(true) },
            chooseMorePhotos: model.chooseMorePhotos,
            openSettings: model.openSettings,
            keepReading: model.keepReading,
            showOnThisDay: model.setShowOnThisDay,
            celebrate: model.celebrate
        )
    }
}

struct StatusActions {
    var pause: () -> Void = {}
    var resume: () -> Void = {}
    var retrySkipped: () -> Void = {}
    var useMobileData: () -> Void = {}
    var chooseMorePhotos: () -> Void = {}
    var openSettings: () -> Void = {}
    var keepReading: () -> Void = {}
    var showOnThisDay: (Bool) -> Void = { _ in }
    var celebrate: () -> Void = {}
}

struct StatusContent: View {
    let snap: AppModel.Snap
    let photos: PHAuthorizationStatus
    let download: ModelDownload.State
    let progress: Double
    let skippedFiles: [AppModel.SkippedFile]
    var screenKeptOn = false
    var canKeepReading = false
    /// CAUGHT_UP is due (`AppModel.caughtUpDue`).
    var celebrating = false
    var actions = StatusActions()

    var body: some View {
        List {
            Section { progressSection }
            if snap.textOnly {
                Section { Note(Copy.textOnlyNote(ramGb: snap.ramGb)) }
            } else if snap.setUp != true {
                Section("Getting ready") { gettingReady }
            }
            if snap.skipped > 0 { Section { skipped } }
            if snap.tailFiles > 0 && !snap.textOnly { Section { Note(Copy.deepReading(snap.tailFiles)) } }
            photosSection
            if snap.setUp == true {
                Section {
                    Toggle(isOn: Binding(get: { snap.showOnThisDay }, set: { actions.showOnThisDay($0) })) {
                        Text("Show On This Day on Home").fixedSize(horizontal: false, vertical: true)
                    }
                } footer: {
                    Text("Photos and videos from this date in earlier years.").foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
                }
            }
            Section {
                NavigationLink("Settings", value: Route.settings)
            } footer: {
                Text(Copy.backgroundNote).foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .navigationTitle(Copy.statusTitle(snap))
    }

    @ViewBuilder private var progressSection: some View {
        // Walking while reading runs, resting while it waits or is paused; CAUGHT_UP once, the first time all is read.
        StatusDog(mood: snap.running ? .walking : snap.readProgress.unfinished ? .resting : nil, due: celebrating, done: actions.celebrate)
            .frame(maxWidth: .infinity)
            .listRowSeparator(.hidden)
        VStack(alignment: .leading, spacing: 8) {
            Text(Copy.searchable(snap))
                .font(.headline)
                .monospacedDigit()
            ProgressView(value: snap.readProgress.libraryTotal > 0 ? Double(snap.indexed) / Double(snap.readProgress.libraryTotal) : 0)
                .accessibilityHidden(true)
            ForEach(detailLines, id: \.self) { Note($0) }
        }
        .accessibilityElement(children: .combine)
        .padding(.vertical, 4)
        if snap.running {
            ReadingNow(file: snap.reading.current, size: 56)
            RecentlyRead(files: snap.reading.recent)
        }
        if screenKeptOn { Note(Copy.screenKeptOn) }
        if let error = snap.lastError { Failure(error) }
        if snap.paused {
            Button("Resume", action: actions.resume)
        } else if snap.readProgress.unfinished {
            Button("Pause", action: actions.pause)
        }
        if canKeepReading {
            VStack(alignment: .leading, spacing: 4) {
                Button(action: actions.keepReading) {
                    Text(Copy.keepReading).fixedSize(horizontal: false, vertical: true).frame(minHeight: 44).contentShape(Rectangle())
                }
                Note(Copy.keepReadingNote)
            }
        }
    }

    private var detailLines: [String] {
        if snap.running && !snap.readProgress.unfinished { return ["Looking for your files…"] }
        if snap.tailFiles > 0 && snap.pending == 0 { return ["\(Copy.count(snap.tailFiles)) files partly read"] }
        if snap.running {
            var lines = ["This round: \(Copy.count(snap.processed)) of \(Copy.count(snap.pendingAtStart)) read"]
            if snap.filesPerMinute >= 1 { lines[0] += " · \(Copy.count(Int(snap.filesPerMinute.rounded()))) files a minute" }
            if let eta = snap.etaMinutes { lines.append(Copy.eta(eta)) }
            return lines
        }
        // The title already says paused or all caught up.
        if snap.pending > 0 && !snap.paused, let waiting = Copy.waiting(snap.lastStop) { return [waiting] }
        return []
    }

    @ViewBuilder private var gettingReady: some View {
        Note("Until Retriever is ready, search finds files by their names. It reads inside them once setup finishes.")
        switch download {
        case .downloading:
            ProgressView(value: progress) {
                Text("Downloading").font(.subheadline)
            } currentValueLabel: {
                Text(progress.formatted(.percent.precision(.fractionLength(0)))).monospacedDigit()
            }
        case .waitingForWifi:
            Note("Waiting for Wi-Fi.")
            Button("Use mobile data", action: actions.useMobileData)
        case .failed(let reason): Failure(reason)
        default: EmptyView()
        }
        NavigationLink("Setup details", value: Route.setup)
    }

    private var skipped: some View {
        DisclosureGroup {
            ForEach(skippedFiles) { file in
                VStack(alignment: .leading, spacing: 2) {
                    Text(file.name).lineLimit(1)
                    Text(file.folder).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
                .accessibilityElement(children: .combine)
            }
            Button("Try again", action: actions.retrySkipped)
        } label: {
            Text(snap.skipped == 1 ? "1 file couldn't be read" : "\(Copy.count(snap.skipped)) files couldn't be read")
        }
    }

    @ViewBuilder private var photosSection: some View {
        switch photos {
        case .limited:
            Section {
                Button("Choose more photos", action: actions.chooseMorePhotos)
            } footer: {
                Text("Retriever can only see the photos you picked.")
            }
        case .denied, .restricted:
            Section {
                Button("Open Settings", action: actions.openSettings)
            } footer: {
                Text("Photos access is off, so Retriever can't search your photos.")
            }
        default:
            EmptyView()
        }
    }
}

#Preview("Reading") {
    NavigationStack { StatusView(model: AppModel(fixture: .reading(done: 1_204, total: 5_310))) }
}
