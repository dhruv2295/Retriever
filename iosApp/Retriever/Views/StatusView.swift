import Photos
import SwiftUI

/// How reading is going, what couldn't be read, and the few switches that steer it.
struct StatusView: View {
    let model: AppModel

    var body: some View {
        let snap = model.snap ?? AppModel.Snap()
        StatusContent(
            snap: snap,
            photos: model.photos,
            download: model.download,
            progress: model.downloadProgress,
            skippedFiles: model.skippedFiles,
            actions: StatusActions(
                pause: model.pause,
                resume: model.resume,
                retrySkipped: model.retrySkipped,
                useMobileData: { model.setUseMobileData(true) },
                chooseMorePhotos: model.chooseMorePhotos,
                openSettings: model.openSettings
            )
        )
        .onChange(of: snap.skipped, initial: true) { _, n in
            if n > 0 { model.loadSkipped() }
        }
    }
}

struct StatusActions {
    var pause: () -> Void = {}
    var resume: () -> Void = {}
    var retrySkipped: () -> Void = {}
    var useMobileData: () -> Void = {}
    var chooseMorePhotos: () -> Void = {}
    var openSettings: () -> Void = {}
}

struct StatusContent: View {
    let snap: AppModel.Snap
    let photos: PHAuthorizationStatus
    let download: ModelDownload.State
    let progress: Double
    let skippedFiles: [AppModel.SkippedFile]
    var actions = StatusActions()

    var body: some View {
        List {
            Section { progressSection }
            if snap.setUp != true { Section("Getting ready") { gettingReady } }
            if snap.skipped > 0 { Section { skipped } }
            if snap.tail > 0 {
                Section {
                    Note("Some long documents are only partly read. Retriever reads the rest while your iPhone charges.")
                }
            }
            photosSection
            Section {} footer: { Text(Copy.backgroundNote) }
        }
        .navigationTitle(Copy.statusTitle(snap))
    }

    @ViewBuilder private var progressSection: some View {
        if snap.running {
            Image("IllusReading")
                .resizable()
                .scaledToFit()
                .frame(maxWidth: 160)
                .frame(maxWidth: .infinity)
                .accessibilityHidden(true)
                .listRowSeparator(.hidden)
        }
        VStack(alignment: .leading, spacing: 8) {
            Text("\(Copy.count(snap.indexed)) of \(Copy.count(snap.total)) files searchable")
                .font(.headline)
                .monospacedDigit()
            ProgressView(value: snap.total > 0 ? Double(snap.indexed) / Double(snap.total) : 0)
                .accessibilityHidden(true)
            ForEach(detailLines, id: \.self) { Note($0) }
        }
        .accessibilityElement(children: .combine)
        .padding(.vertical, 4)
        if let error = snap.lastError { Failure(error) }
        if snap.paused {
            Button("Resume", action: actions.resume)
        } else if snap.pending > 0 {
            Button("Pause", action: actions.pause)
        }
    }

    private var detailLines: [String] {
        if snap.running && snap.pendingAtStart == 0 { return ["Looking for your files…"] }
        if snap.running {
            var lines = ["This round: \(Copy.count(snap.processed)) of \(Copy.count(snap.pendingAtStart)) read"]
            if snap.filesPerMinute >= 1 { lines[0] += " · \(Copy.count(Int(snap.filesPerMinute.rounded()))) files a minute" }
            if let eta = snap.etaMinutes { lines.append(Copy.eta(eta)) }
            if let current = snap.current { lines.append("Now reading: \(current)") }
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
        case let .failed(reason): Failure(reason)
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
