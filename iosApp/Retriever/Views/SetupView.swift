import Photos
import RetrieverCore
import SwiftUI

/// First run on core's stage machine (`SetupInputs.stage`): the welcome with its access choice, the demo (processing
/// and its search on one screen), then when to process the rest. Every stage follows from stored state, so each resumes
/// after the app is closed.
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
            case .choose: ChooseStep(model: model)
            default: DemoStep(model: model)
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

/// A setup screen's content: it scrolls, and content shorter than the screen sits in the middle of it, the spare
/// height split above and below.
private struct StepContent<Content: View>: View {
    var alignment: HorizontalAlignment = .leading
    var spacing: CGFloat = 16
    @ViewBuilder let content: Content

    var body: some View {
        GeometryReader { screen in
            ScrollView {
                VStack(alignment: alignment, spacing: spacing) { content }
                    .multilineTextAlignment(alignment == .center ? .center : .leading)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("step-content")
                    .padding(.horizontal, 24)
                    .padding(.vertical, spacing)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity, minHeight: screen.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }
}

/// The actions pinned under a setup screen's content.
private struct StepActions<Actions: View>: ViewModifier {
    @ViewBuilder let actions: Actions

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) { actions }
                .controlSize(.large)
                .padding(.horizontal, 24)
                .padding(.vertical, 8)
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

/// The welcome and the one decision it asks: all photos, folders from Files, or skip the demo.
private struct WelcomeStep: View {
    let model: AppModel
    @State private var picking = false
    @State private var folderNote: String?

    var body: some View {
        StepContent(spacing: 8) {
            MascotView(mood: .welcome, settles: false).frame(width: 180).frame(maxWidth: .infinity)
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
            // Last in the content, just above the footer: in the pinned footer it clipped at larger text.
            Text(Words.shared.welcomeNext(words: Copy.words)).fixedSize(horizontal: false, vertical: true)
            Text(Words.shared.welcomeAfterDemo(words: Copy.words)).fixedSize(horizontal: false, vertical: true)
        }
        .modifier(
            StepActions {
                // Access in place: start the demo. Not asked yet: ask for all photos. Refused: only Settings can change it.
                if model.accessGiven {
                    Button {
                        model.setSetupMark(.accessChosen)
                    } label: {
                        wide(Words.shared.START_THE_DEMO)
                    }.buttonStyle(.primaryAction)
                } else if model.photos == .notDetermined {
                    Button(action: model.requestPhotos) { wide(Words.shared.SEARCH_ALL_PHOTOS) }.buttonStyle(.primaryAction)
                } else {
                    Button(action: model.openSettings) { wide("Open Settings") }.buttonStyle(.primaryAction)
                }
                Button(action: model.skipDemo) { wide(Words.shared.SKIP_THE_DEMO) }
            }
        )
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            folderNote = model.addFolders(urls).map(Copy.addFolderResult).joined(separator: "\n")
        }
    }
}

/// The demo, one screen: the real search over the demo's files (`SearchView` with a `Trial`), the demo's progress under
/// its field until a word is typed, and Continue pinned below, enabled throughout.
private struct DemoStep: View {
    let model: AppModel
    @State private var search: SearchModel
    @State private var path: [Route] = []

    init(model: AppModel) {
        self.model = model
        _search = State(initialValue: SearchModel(services: model.services))
    }

    var body: some View {
        AppStack(model: model, path: $path) {
            SearchView(model: model, search: search, trial: Trial(ids: model.sampleIds, photoSearch: model.meaningSearch))
                .toolbar { SetupBack(model: model, search: search) }
                .modifier(
                    StepActions {
                        Button(action: model.continueFromDemo) { wide(Words.shared.CONTINUE) }.buttonStyle(.primaryAction)
                    })
        }
    }
}

/// Under the demo's search field before a word is typed: what the demo is, the pile of its newest files, the one count
/// and its bar, one muted line, words to try, and the list of its files. Every row is laid out from the first frame,
/// so the centred group never moves between states.
struct DemoIntro: View {
    let model: AppModel
    let search: SearchModel
    @State private var seeingAll = false

    var body: some View {
        // Both kept on the model, so coming back to this screen never starts from zero or replays the pile.
        let taste = model.taste
        let sample = model.sample ?? []
        let pause = model.progress.reading.pauseReason
        // One predicate for the mascot and the time line: picking, or processing, not stopped, with files left.
        // Not `running`: it drops between two demo batches, and the mascot would sit down for that gap.
        let reading = model.setup?.step == .plan || (pause == nil && taste.pending > 0)
        let planned = Int64(taste.planned)
        let done = Int64(taste.done)
        // The files are picked in under a second; the total is fixed from then on.
        let picking = planned == 0
        let count = picking ? Words.shared.DEMO_PICKING : Words.shared.readOf(done: done, total: planned)
        let note =
            picking
            ? Words.shared.DEMO_PICKING_NOTE
            : taste.pending == 0
                ? Words.shared.demoDone(rest: model.snap?.readProgress.rest, words: Copy.words)
                : pause ?? Words.shared.demoNote(secondsLeft: taste.secondsLeft.map { KotlinInt(int: Int32($0)) })
        StepContent(alignment: .center) {
            // The full demo's title holds the height, so picking's shorter one never moves the rows under it.
            ZStack {
                Text(Words.shared.demoTitle(files: Int64(TasteKt.DEMO_FILES))).hidden()
                Text(Words.shared.demoTitle(files: planned))
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityValue(taste.spoken)
            }
            .font(.title2.bold())
            .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 16) {
                MascotView(mood: reading ? .walking : .resting, settles: false).frame(width: 96)
                SamplePileView(
                    cards: SamplePile.shared.shown(files: sample), kinds: SamplePileView.kinds(sample), label: count,
                    hint: Words.shared.seeAll(files: planned), seeAll: picking ? nil : { seeingAll = true }
                )
                // A new pile at the first delivery: the files done by then are there from its start, so they never settle again.
                .id(model.sample == nil)
            }
            VStack(spacing: 8) {
                // The picking sentence's height is held throughout, so the shorter count never pulls the group up.
                ZStack(alignment: .bottom) {
                    Text(Words.shared.DEMO_PICKING).hidden()
                    Text(count).monospacedDigit()
                }
                .fixedSize(horizontal: false, vertical: true)
                // Exactly the count's numbers: done of planned, name-only and unopenable files included.
                ProgressView(value: Double(done), total: Double(max(planned, 1))).accessibilityHidden(true)
            }
            // Two lines kept for the note; larger text still grows it.
            ZStack {
                Text(" \n ").hidden()
                Text(note)
            }
            .font(.subheadline)
            .fixedSize(horizontal: false, vertical: true)
            DemoChips(terms: model.trySuggestions, search: search)
            let seeAll = Button(Words.shared.seeAll(files: planned)) { seeingAll = true }
            if picking { seeAll.hidden() } else { seeAll }
        }
        .sheet(isPresented: $seeingAll) { SampleList(model: model, sample: model.sample ?? []) }
    }
}

/// Words from the demo's files to search for, once there are three; until then, what to type. One row, or two when
/// the words are long, never cut at the edge; the slot keeps two rows' height, so the chips arriving never move the
/// rows above.
private struct DemoChips: View {
    let terms: [String]
    let search: SearchModel

    var body: some View {
        ZStack {
            Text(Words.shared.DEMO_HINT).hidden()
            twoRows(["Try", "Try", "Try"]).hidden()
            if terms.count >= 3 {
                let shown = Array(terms.prefix(3))
                ViewThatFits(in: .horizontal) {
                    row(shown, label: true)
                    twoRows(shown)
                    VStack(spacing: 8) {
                        Text(Words.shared.TRY_LABEL)
                        ForEach(shown, id: \.self, content: chip)
                    }
                }
            } else {
                Text(Words.shared.DEMO_HINT)
            }
        }
        .font(.subheadline)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func twoRows(_ terms: [String]) -> some View {
        VStack(spacing: 8) {
            row(Array(terms.prefix(2)), label: true)
            row(Array(terms.dropFirst(2)), label: false)
        }
    }

    private func row(_ terms: [String], label: Bool) -> some View {
        HStack {
            if label { Text(Words.shared.TRY_LABEL) }
            ForEach(terms, id: \.self, content: chip)
        }
    }

    private func chip(_ term: String) -> some View {
        Button(term) { search.setText(term) }.buttonStyle(.bordered).accessibilityHint("Searches for this")
    }
}

/// The demo's newest files, dropped on a pile at core's angles and offsets (Gallery's), in a fixed square, so the
/// screen never moves as files arrive and no private file fills it. One element: the count, and See the files as its action.
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

/// See the files: every demo file in plan order, processed or not, each with its note; a read one opens in the viewer.
private struct SampleList: View {
    let model: AppModel
    let sample: [SampleFile]
    @State private var path: [Route] = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        AppStack(model: model, path: $path) {
            // The demo's own count, as its screen says it.
            SampleRows(sample: sample)
                .navigationTitle(Words.shared.readOf(done: Int64(model.taste.done), total: Int64(model.taste.planned)))
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

/// When to process the rest; no mascot, the decision is the focal point.
private struct ChooseStep: View {
    let model: AppModel

    var body: some View {
        let taste = model.taste
        // A stack of its own, so the toolbar's Back shows.
        NavigationStack {
            ScrollView {
                ReadingChoice(
                    ended: Words.shared.demoEnded(done: Int64(taste.done), planned: Int64(taste.planned), skipped: model.setup?.skipped ?? false),
                    rest: model.snap?.readProgress.rest,
                    charging: { model.commit(whileCharging: true) }, readNow: { model.commit(whileCharging: false) }, notNow: model.decideLater
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

/// Back from the choice to the demo, or to the welcome after Skip the demo, by core's rule. On the demo's results it
/// goes back to the demo itself, in one press; the demo has no Back of its own.
private struct SetupBack: ToolbarContent {
    let model: AppModel
    var search: SearchModel?

    var body: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            if search?.active ?? true {
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

#Preview("Demo starting") {
    SetupView(model: AppModel(fixture: .sampleStart))
}

#Preview("Demo processing") {
    SetupView(model: AppModel(fixture: .sampleReading))
}

#Preview("Demo paused") {
    SetupView(model: AppModel(fixture: .samplePaused))
}

#Preview("Demo done") {
    SetupView(model: AppModel(fixture: .sampleDone))
}

#Preview("Choose, after the demo") {
    SetupView(model: AppModel(fixture: .choosing))
}

#Preview("Choose, after a skip") {
    SetupView(model: AppModel(fixture: .chooseSkipped))
}
