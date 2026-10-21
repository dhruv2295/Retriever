import Photos
import RetrieverCore
import SwiftUI

/// First run on core's stage machine (`SetupInputs.stage`): the welcome with its access choice, the sample read with no download, Try,
/// then when to read more. Every stage follows from stored state, so each resumes after the app is closed.
struct SetupView: View {
    let model: AppModel
    /// In Home's stack (its status row leads here): back to Home once setup has nothing left to ask.
    var onDone: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let stage = model.setup?.stage ?? .welcome
        Group {
            switch stage {
            case .welcome: WelcomeStep(model: model)
            case .try_: TryStep(model: model)
            case .choose: ChooseStep(model: model)
            default: SampleStep(model: model)
            }
        }
        .id(stage)
        .transition(reduceMotion ? .opacity : .push(from: .trailing))
        .animation(reduceMotion ? nil : .snappy, value: stage)
        .onChange(of: stage, initial: true) { _, stage in
            if stage == .done { onDone?() }
        }
    }
}

/// One setup screen: its content scrolls, its actions stay at the bottom. Content shorter than the screen sits in the
/// middle of it, the spare height split above and below.
private struct StepPage<Content: View, Actions: View>: View {
    var alignment: HorizontalAlignment = .leading
    @ViewBuilder let content: Content
    @ViewBuilder let actions: Actions

    var body: some View {
        GeometryReader { screen in
            ScrollView {
                VStack(alignment: alignment, spacing: 16) { content }
                    .multilineTextAlignment(alignment == .center ? .center : .leading)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("step-content")
                    .padding(.horizontal, 24)
                    .padding(.vertical, 16)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity, minHeight: screen.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) { actions }
                .controlSize(.large)
                .padding(.horizontal, 24)
                .padding(.vertical, 12)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                // Opaque: text over a material takes on what scrolls behind it, and the previews would show through.
                .background(.background)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("step-actions")
        }
    }
}

/// A button's label filling the footer's width. It wraps at large text sizes: the prominent style keeps one line otherwise.
private func wide(_ title: String) -> some View {
    Text(title).fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity)
}

/// The welcome and the one decision it asks: all photos, folders from Files, or search by name.
private struct WelcomeStep: View {
    let model: AppModel
    @State private var picking = false
    @State private var folderNote: String?

    var body: some View {
        StepPage {
            MascotView(mood: .welcome, settles: false).frame(width: 260).frame(maxWidth: .infinity)
            Text(Words.shared.WELCOME_TITLE).font(.title.bold()).accessibilityAddTraits(.isHeader)
            Text(Words.shared.WELCOME_BODY)
            Text(Words.shared.nothingSent(words: Copy.words)).font(.headline)
            if let snap = model.snap, snap.textOnly {
                Note(Words.shared.textOnlyNote(ramGb: snap.ramGb, words: Copy.words))
            }
            Note(Words.shared.iosAccessNote(words: Copy.words))
            if model.photos == .limited {
                Note(Words.shared.ONLY_SELECTED_PHOTOS)
                Button(Words.shared.CHOOSE_MORE_PHOTOS, action: model.chooseMorePhotos).buttonStyle(.bordered)
            }
            // In the content, not the footer: three stacked actions left no room for a label to wrap at larger text.
            Button(Words.shared.ADD_FOLDERS_FROM_FILES) { picking = true }.buttonStyle(.bordered)
            if let folderNote { Note(folderNote) }
        } actions: {
            // Access in place: Continue. Not asked yet: ask for all photos. Refused: only Settings can change it.
            if model.accessGiven {
                Button {
                    model.setSetupMark(.accessChosen)
                } label: {
                    wide(Words.shared.CONTINUE)
                }.buttonStyle(.primaryAction)
            } else if model.photos == .notDetermined {
                Button(action: model.requestPhotos) { wide(Words.shared.SEARCH_ALL_PHOTOS) }.buttonStyle(.primaryAction)
            } else {
                Button(action: model.openSettings) { wide("Open Settings") }.buttonStyle(.primaryAction)
            }
            Button(action: model.searchByName) { wide(Words.shared.SEARCH_BY_NAME) }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            folderNote = model.addFolders(urls).map(Copy.addFolderResult).joined(separator: "\n")
        }
    }
}

/// Reading a few files with no download: the retriever's pile of what is read, and Try search once enough is.
private struct SampleStep: View {
    let model: AppModel
    @State private var seeingAll = false

    var body: some View {
        // Both kept on the model, so coming back to this screen never starts from zero or replays the pile.
        let taste = model.taste
        let sample = model.sample ?? []
        let pause = model.progress.reading.pauseReason
        // One predicate for the mascot, the time line and the pile: planning, or reading, not stopped, with files left.
        // Not `running`: it drops between two sample batches, and the mascot would sit down for that gap.
        let reading = model.setup?.step == .plan || (pause == nil && taste.pending > 0)
        let planned = Int64(taste.planned)
        // Before the plan exists (it syncs the library first), there is nothing to count or see yet: the sample is starting.
        let starting = planned == 0 || (taste.read == 0 && taste.pending > 0)
        let count = starting ? Words.shared.SAMPLE_PICKING : Words.shared.readOf(read: Int64(taste.read), planned: planned, pending: Int64(taste.pending))
        // One muted slot: why it stopped, else the time and whether search can be tried, else nothing.
        let note =
            pause
            ?? Words.shared.sampleNote(
                reading: reading, readyToTry: taste.readyToTry, secondsLeft: taste.secondsLeft.map { KotlinInt(int: Int32($0)) })
            ?? (planned > 0 && taste.pending == 0 ? Words.shared.SAMPLE_DONE : nil)
        // Every row is laid out from the first frame, before the plan too, so the centred group never moves.
        StepPage(alignment: .center) {
            MascotView(mood: reading ? .walking : .resting, settles: false).frame(width: 160)
            Text(Words.shared.SAMPLE_TITLE).font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
                .accessibilityValue(taste.spoken)
            SamplePileView(
                cards: SamplePile.shared.shown(files: sample), kinds: SamplePileView.kinds(sample), label: count,
                hint: Words.shared.seeAll(files: planned, pending: Int64(taste.pending)), seeAll: planned > 0 ? { seeingAll = true } : nil
            )
            // A new pile at the first delivery: the files read by then are there from its start, so they never settle again.
            .id(model.sample == nil)
            VStack(spacing: 8) {
                // The picking sentence's height is held throughout, so the shorter count never pulls the group up.
                ZStack(alignment: .bottom) {
                    Text(Words.shared.SAMPLE_PICKING).hidden()
                    Text(count).monospacedDigit()
                }
                .multilineTextAlignment(.center)
                // Fills when every planned file is done, read or not; the line above counts the read ones.
                ProgressView(value: Double(taste.planned - taste.pending), total: Double(max(taste.planned, 1))).accessibilityHidden(true)
            }
            // Two lines kept for the note whether it shows or not; larger text still grows it.
            ZStack {
                Text(" \n ").hidden()
                if let note { Text(note) }
            }
            .font(.subheadline)
            .fixedSize(horizontal: false, vertical: true)
            let seeAll = Button(Words.shared.seeAll(files: planned, pending: Int64(taste.pending))) { seeingAll = true }
            if planned > 0 { seeAll } else { seeAll.hidden() }
        } actions: {
            Button {
                model.setSetupMark(.trying)
            } label: {
                wide(Words.shared.TRY_SEARCH)
            }
            .buttonStyle(.primaryAction)
            .disabled(!taste.readyToTry)
            Button(action: model.goHome) { wide(Words.shared.GO_TO_HOME) }
        }
        .sheet(isPresented: $seeingAll) { SampleList(model: model, sample: model.sample ?? []) }
    }
}

/// The last twelve files read, dropped on a pile at core's angles and offsets (Gallery's), in a fixed square, so
/// the screen never moves as files arrive. One element: the count, and See all as its action.
struct SamplePileView: View {
    let cards: [SampleFile]
    /// Shown as blank cards while nothing is read.
    let kinds: [Kind]
    let label: String
    let hint: String
    /// Nil before there is a plan to see.
    let seeAll: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The plan's first three kinds, for the empty pile.
    static func kinds(_ sample: [SampleFile]) -> [Kind] {
        var kinds: [Kind] = []
        for file in sample where !kinds.contains(file.file.kind) && kinds.count < 3 { kinds.append(file.file.kind) }
        return kinds.isEmpty ? [.photo, .pdf, .doc] : kinds
    }

    var body: some View {
        Button {
            seeAll?()
        } label: {
            GeometryReader { box in
                let card = box.size.width * 0.75
                let scale = card / CGFloat(SamplePile.shared.CARD_DP)
                ZStack {
                    if cards.isEmpty {
                        ForEach(Array(kinds.prefix(3).enumerated()), id: \.offset) { i, kind in
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(Color(.tertiarySystemFill))
                                .overlay { Image(systemName: Format.icon(kind)).font(.largeTitle).foregroundStyle(.secondary) }
                                .frame(width: card, height: card)
                                .modifier(Slot(index: i, scale: scale))
                        }
                    }
                    ForEach(cards, id: \.index) { c in
                        FileTile(file: c.file).equatable()
                            .frame(width: card, height: card)
                            .modifier(Slot(index: Int(c.index), scale: scale))
                            .zIndex(Double(c.index))
                            // Only the arrival moves: the card pushed off the pile simply goes, as on Android.
                            .transition(
                                reduceMotion
                                    ? .identity
                                    : .asymmetric(
                                        insertion: .modifier(active: Settle(dropping: true), identity: Settle(dropping: false)), removal: .identity))
                    }
                }
                .frame(width: box.size.width, height: box.size.width)
                // Only a new card moves: one present when the pile appears is not inserted, so it never replays.
                .animation(reduceMotion ? nil : .spring(duration: 0.25), value: cards.last?.index)
            }
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: CGFloat(SamplePile.shared.FOOTPRINT_DP))
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .disabled(seeAll == nil)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityHint(seeAll == nil ? "" : hint)
        .accessibilityAddTraits(seeAll == nil ? [] : .isButton)
        .accessibilityAction(.default) { seeAll?() }
        .accessibilityIdentifier("sample-pile")
    }

    /// A card's place on the pile, by its plan position.
    struct Slot: ViewModifier {
        let index: Int
        let scale: CGFloat

        func body(content: Content) -> some View {
            let i = Int32(index)
            content
                .rotationEffect(.degrees(Double(SamplePile.shared.angle(i: i))))
                .offset(x: CGFloat(SamplePile.shared.dx(i: i)) * scale, y: CGFloat(SamplePile.shared.dy(i: i)) * scale)
        }
    }

    /// A new card drops in from above, turned a little further, and settles into its slot.
    private struct Settle: ViewModifier {
        let dropping: Bool

        func body(content: Content) -> some View {
            content.rotationEffect(.degrees(dropping ? 8 : 0)).offset(y: dropping ? -48 : 0).opacity(dropping ? 0 : 1)
        }
    }
}

/// "See all": every planned file in plan order, read or not; a read one opens in the viewer.
private struct SampleList: View {
    let model: AppModel
    let sample: [SampleFile]
    @State private var path: [Route] = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        AppStack(model: model, path: $path) {
            // It lists every planned file, read or not, so it says how many are read.
            SampleRows(sample: sample)
                .navigationTitle(
                    Words.shared.readOf(read: Int64(sample.filter(\.read).count), planned: Int64(sample.count), pending: Int64(model.taste.pending))
                )
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

private struct SampleRows: View {
    let sample: [SampleFile]
    @Environment(\.openFile) private var openFile

    var body: some View {
        let read = sample.filter(\.read).map(\.file)
        List(sample, id: \.index) { item in
            let row = HStack(spacing: 12) {
                FileTile(file: item.file).equatable().frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.file.name).lineLimit(2).truncationMode(.middle)
                    Text(item.note ?? Words.shared.READ).font(.subheadline)
                }
            }
            .accessibilityElement(children: .combine)
            if item.read {
                Button {
                    openFile(item.file, among: read)
                } label: {
                    row
                }
                .tint(.primary)
                .accessibilityHint("Open file")
            } else {
                row
            }
        }
    }
}

/// The real search, names over the whole library and words inside the sample.
private struct TryStep: View {
    let model: AppModel
    @State private var search: SearchModel
    @State private var path: [Route] = []

    init(model: AppModel) {
        self.model = model
        _search = State(initialValue: SearchModel(services: model.services))
    }

    var body: some View {
        let more = !model.nothingLeft
        AppStack(model: model, path: $path) {
            SearchView(model: model, search: search, trial: Trial(ids: model.sampleIds, photoSearch: model.meaningSearch))
                .toolbar { SetupBack(model: model, search: search) }
                .safeAreaInset(edge: .bottom) {
                    VStack(spacing: 8) {
                        Button(action: model.chooseWhenToReadMore) { wide(more ? Words.shared.CHOOSE_WHEN : Words.shared.GO_TO_HOME) }
                            .buttonStyle(.primaryAction)
                        if more { Button(action: model.goHome) { wide(Words.shared.GO_TO_HOME) } }
                    }
                    .controlSize(.large)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 8)
                    .background(.background)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("step-actions")
                }
        }
        .onChange(of: model.taste.pending, initial: true) { _, pending in search.stillReading = pending }
    }
}

/// When to read the rest; no mascot, the decision is the focal point.
private struct ChooseStep: View {
    let model: AppModel

    var body: some View {
        // A stack of its own, as Try has, so the toolbar's Back shows.
        NavigationStack {
            ScrollView {
                ReadingChoice(
                    charging: { model.commit(whileCharging: true) }, readNow: { model.commit(whileCharging: false) },
                    notNow: model.decideLater
                )
                .padding(24)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .toolbar { SetupBack(model: model) }
        }
    }
}

/// Back from Try to the sample, or from the choice to Try, by core's rule. On Try's results it first goes back to Try's
/// own start, in one press.
private struct SetupBack: ToolbarContent {
    let model: AppModel
    var search: SearchModel?

    var body: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button("Back", systemImage: "chevron.backward") {
                if let search, search.active {
                    search.setText("")
                    search.clearPhoto()
                } else {
                    model.setupBack()
                }
            }
        }
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
        // An HStack, not a Label: in a List row the Label's text stopped growing with the type size and was cut off.
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
            Text(text).fixedSize(horizontal: false, vertical: true)
        }
        .font(.subheadline)
        .accessibilityElement(children: .combine)
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

#Preview("Welcome, limited Photos") {
    SetupView(model: AppModel(fixture: .photosLimited))
}

#Preview("Welcome, Photos refused") {
    SetupView(model: AppModel(fixture: .photosDenied))
}

#Preview("Sample starting") {
    SetupView(model: AppModel(fixture: .sampleStart))
}

#Preview("Sample reading") {
    SetupView(model: AppModel(fixture: .sampleReading))
}

#Preview("Sample paused") {
    SetupView(model: AppModel(fixture: .samplePaused))
}

#Preview("Sample done") {
    SetupView(model: AppModel(fixture: .sampleDone))
}
