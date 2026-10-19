import Photos
import RetrieverCore
import SwiftUI

/// How reading is going and what couldn't be read. The switches that steer it are in Settings.
struct StatusView: View {
    let model: AppModel

    var body: some View {
        let snap = model.live ?? AppModel.Snap()
        StatusContent(
            snap: snap,
            photos: model.photos,
            // The download and tune under way; meaning added later has its own line.
            upgrade: model.upgrade == .download || model.upgrade == .tune ? UpgradeCard(model: model) : nil,
            skippedFiles: model.skippedFiles,
            screenKeptOn: model.screenKeptOn,
            celebrating: model.caughtUpDue,
            actions: actions,
            message: model.readingMessage
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
            chooseMorePhotos: model.chooseMorePhotos,
            openSettings: model.openSettings,
            celebrate: model.celebrate
        )
    }
}

struct StatusActions {
    var pause: () -> Void = {}
    var resume: () -> Void = {}
    var retrySkipped: () -> Void = {}
    var chooseMorePhotos: () -> Void = {}
    var openSettings: () -> Void = {}
    var celebrate: () -> Void = {}
}

struct StatusContent: View {
    let snap: AppModel.Snap
    let photos: PHAuthorizationStatus
    var upgrade: UpgradeCard?
    let skippedFiles: [AppModel.SkippedFile]
    var screenKeptOn = false
    /// CAUGHT_UP is due (`AppModel.caughtUpDue`).
    var celebrating = false
    var actions = StatusActions()
    var message: String?

    var body: some View {
        List {
            Section { progressSection }
            if snap.textOnly {
                Section { Note(Words.shared.textOnlyNote(ramGb: snap.ramGb, words: Copy.words)) }
            } else if let upgrade {
                Section { upgrade }
            }
            if snap.skipped > 0 { Section { skipped } }
            if snap.meaningWaiting > 0 && !snap.textOnly { Section { Note(Copy.meaningWaiting(snap.meaningWaiting)) } }
            if snap.tailFiles > 0 && !snap.textOnly { Section { Note(Copy.deepReading(snap.tailFiles)) } }
            photosSection
            Section {
                NavigationLink("Settings", value: Route.settings)
            } footer: {
                Text(Copy.backgroundNote).foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .navigationTitle("Reading status")
    }

    /// The one reason to show: a failure outranks the schedule's own wait, which follows from the title.
    static func reason(detail: String?, lastError: String?, message: String?) -> (text: String, failed: Bool)? {
        if let failure = message ?? lastError { return (failure, true) }
        return detail.map { ($0, false) }
    }

    private var reason: (text: String, failed: Bool)? {
        Self.reason(detail: Copy.reading(snap).detail, lastError: snap.lastError, message: message)
    }

    @ViewBuilder private var progressSection: some View {
        // Walking while reading runs, resting while it waits or is paused; CAUGHT_UP once, the first time all is read.
        StatusDog(mood: snap.running ? .walking : snap.readProgress.unfinished ? .resting : nil, due: celebrating, done: actions.celebrate)
            .frame(maxWidth: .infinity)
            .listRowSeparator(.hidden)
        VStack(alignment: .leading, spacing: 8) {
            Text(Copy.reading(snap).title).font(.title2.bold())
            if let reason { if reason.failed { Failure(reason.text) } else { Note(reason.text) } }
            Text(Words.shared.readCount(read: Int64(snap.indexed), partlyRead: Int64(snap.partlyRead)))
                .font(.headline)
                .monospacedDigit()
            Text(Words.shared.waitingToRead(files: Int64(snap.pending + snap.waiting)))
            if snap.waiting > 0 { Note("\(Copy.count(snap.pending)) scheduled · \(Copy.count(snap.waiting)) not scheduled") }
            if snap.nameOnly > 0 { Text("\(Copy.count(snap.nameOnly)) name only") }
        }
        .accessibilityElement(children: .combine)
        .padding(.vertical, 4)
        if Copy.reading(snap).active, snap.reading.current != nil { ReadingNow(file: snap.reading.current, size: 56) }
        // What was read stays on show through a pause or a wait; only the sentence above changes.
        if !snap.reading.recent.isEmpty { RecentlyRead(files: snap.reading.recent) }
        if screenKeptOn { Note(Copy.screenKeptOn) }
        if snap.paused {
            Button(action: actions.resume) { Text("Resume reading").fixedSize(horizontal: false, vertical: true).frame(minHeight: 44) }
        } else if snap.readProgress.unfinished {
            Button(action: actions.pause) { Text("Pause reading").fixedSize(horizontal: false, vertical: true).frame(minHeight: 44) }
        }
    }

    private var skipped: some View {
        DisclosureGroup {
            ForEach(skippedFiles) { file in
                VStack(alignment: .leading, spacing: 2) {
                    Text(file.name).lineLimit(2).truncationMode(.middle)
                    Text(file.folder).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
                .accessibilityElement(children: .combine)
            }
            Button("Try again", action: actions.retrySkipped)
        } label: {
            Text("Couldn’t read \(Words.shared.files(n: Int64(snap.skipped)))")
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
