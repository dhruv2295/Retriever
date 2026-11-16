import Photos
import RetrieverCore
import SwiftUI

/// Processing: the status line with its one button, what is being processed, and the counts under Details. The switches that
/// steer reading are in Settings.
struct StatusView: View {
    let model: AppModel

    var body: some View {
        let snap = model.live ?? AppModel.Snap()
        StatusContent(snap: snap, model: model)
            .onChange(of: snap.skipped, initial: true) { _, n in
                if n > 0 { model.loadSkipped() }
            }
            // Read again as files are processed and as Home's count of photos left for faces moves.
            .onChange(of: snap.indexed, initial: true) { model.loadFacesSummary() }
            .onChange(of: model.facesLeft) { model.loadFacesSummary() }
    }
}

struct StatusContent: View {
    let snap: AppModel.Snap
    let model: AppModel

    var body: some View {
        let status = model.statusLine(snap) ?? Copy.reading(snap)
        List {
            Section { progress(status) }
            // The reading status, not the line: a download or its setup holds the line, while the stages still apply.
            if let journey = Copy.reading(snap).journey { stages(journey) }
            if snap.textOnly { Section { Note(Words.shared.textOnlyNote(ramGb: snap.ramGb, words: Copy.words)) } }
            if snap.skipped > 0 { Section { skipped } }
            Section { details }
            photosSection
            Section {
                NavigationLink(Words.shared.strings.appSettings, value: Route.settings)
            } footer: {
                Text(Copy.backgroundNote).foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .navigationTitle(Words.shared.READING_STATUS)
    }

    static let dogSettles = false

    /// The one reason to show: a failure outranks the schedule's own wait, which follows from the title.
    static func reason(detail: String?, lastError: String?, message: String?) -> (text: String, failed: Bool)? {
        if let failure = message ?? lastError { return (failure, true) }
        return detail.map { ($0, false) }
    }

    @ViewBuilder private func progress(_ status: ReadingStatus) -> some View {
        // Walking while reading runs, resting while it waits or is paused; CAUGHT_UP once, the first time all is read.
        // Reading is what this screen is for, so the walk never settles into Home's walk-and-rest cadence.
        StatusDog(
            mood: snap.running ? .walking : snap.readProgress.unfinished ? .resting : nil, settles: Self.dogSettles, due: model.caughtUpDue,
            done: model.celebrate
        )
        .frame(maxWidth: .infinity)
        .listRowSeparator(.hidden)
        StatusLine(status: status, model: model, link: false)
        if let reason = Self.reason(detail: nil, lastError: snap.lastError, message: model.readingMessage) { Failure(reason.text) }
        // While reading runs, even between two batches: the row keeps its last file rather than leaving and coming back.
        if status.active { ReadingNow(file: snap.reading.current, size: 56) }
        // What was read stays on show through a pause or a wait; only the line above changes.
        if !snap.reading.recent.isEmpty { RecentlyRead(files: snap.reading.recent) }
        if model.screenKeptOn { Note(Copy.screenKeptOn) }
        // Resume and Stop are the line's own button.
        if status.offersPause(progress: snap.readProgress) {
            Button(action: model.pause) { Text(Words.shared.PAUSE).fixedSize(horizontal: false, vertical: true).frame(minHeight: 44) }
        }
    }

    /// The journey as a checklist: what each stage lets the person search, done, now or coming up. People while off is an
    /// option with its own Turn on, never a step left. Each row reads as one sentence: what it unlocks, then its state.
    private func stages(_ journey: Journey) -> some View {
        Section(Words.shared.strings.stagesHeader) {
            ForEach(journey.rows, id: \.stage) { row in
                StepRow(
                    icon: row.state == .done ? "checkmark" : Self.icon(row.stage), done: row.state == .done, later: row.state == .next,
                    title: row.label, detail: row.stateLabel
                ) {
                    if row.state == .off {
                        Button(Words.shared.strings.turnOn) { model.setRecognizePeople(true) }.buttonStyle(.bordered).buttonBorderShape(.capsule)
                    }
                }
            }
        }
    }

    private static func icon(_ stage: Stage) -> String {
        switch stage {
        case .names: "magnifyingglass"
        case .meaning: "photo"
        case .words: "text.viewfinder"
        case .people: "person.2"
        default: "doc.text"
        }
    }

    /// The count's breakdown, for whoever wants it.
    private var details: some View {
        DisclosureGroup(Words.shared.DETAILS) {
            // What the line's processed files are, then what is left: its parts add up to the count.
            let faces = model.facesSummary.map { Words.shared.facesBreakdown(faces: $0, live: nil) } ?? []
            ForEach(Words.shared.breakdown(progress: snap.readProgress) + faces, id: \.label) { row in
                LabeledContent(row.label) { Text(row.count).monospacedDigit() }
            }
        }
    }

    private var skipped: some View {
        DisclosureGroup {
            ForEach(model.skippedFiles) { file in
                VStack(alignment: .leading, spacing: 2) {
                    Text(file.name).lineLimit(2).truncationMode(.middle)
                    Text(file.folder).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
                .accessibilityElement(children: .combine)
            }
            Button(Words.shared.TRY_AGAIN, action: model.retrySkipped)
        } label: {
            Text(Words.shared.couldntProcess(files: Int64(snap.skipped)))
        }
    }

    @ViewBuilder private var photosSection: some View {
        switch model.photos {
        case .limited:
            Section {
                Button(Words.shared.strings.chooseMorePhotos, action: model.chooseMorePhotos)
            } footer: {
                Text(Words.shared.strings.onlySeesPickedPhotos)
            }
        case .denied, .restricted:
            Section {
                Button(Words.shared.strings.openSettings, action: model.openSettings)
            } footer: {
                Text(Words.shared.strings.photosOffCantSearch)
            }
        default:
            EmptyView()
        }
    }
}

#Preview("Reading") {
    NavigationStack { StatusView(model: AppModel(fixture: .reading(done: 1_204, total: 5_310))) }
}
