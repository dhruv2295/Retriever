import Photos
import RetrieverCore
import SwiftUI

/// First run on core's stage machine (`SetupInputs.stage`): Hello, the access screen, then the first search (processing
/// and its search on one screen). Every stage follows from stored state, so each resumes after the app is closed.
struct SetupView: View {
    let model: AppModel
    /// In Home's stack (its status row leads here): back to Home once setup has nothing left to ask.
    var onDone: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let stage = model.setup?.stage ?? .welcome
        Group {
            switch stage {
            case .welcome: HelloStep(model: model)
            case .access: AccessStep(model: model)
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

/// Over Hello's and the access screen's button: nothing leaves the iPhone.
private struct OnDevice: View {
    var body: some View {
        Label(Words.shared.onDevice(words: Copy.words), systemImage: "lock.fill")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.tint)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Hello: what Retriever does, shown by example, and Get started, which also starts the one-time download.
private struct HelloStep: View {
    let model: AppModel

    var body: some View {
        StepContent(alignment: .center, spacing: 8) {
            MascotView(mood: .welcome, settles: false).frame(width: 230)
            Text(Words.shared.helloTitle(textOnly: model.textOnly)).font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
            Text(Words.shared.HELLO_BODY).font(.title3).foregroundStyle(.secondary)
            HelloExamples(textOnly: model.textOnly).padding(.top, 12)
        }
        .modifier(
            StepActions {
                OnDevice()
                Button(action: model.getStarted) { wide(Words.shared.GET_STARTED) }.buttonStyle(.primaryAction)
                if model.downloadOnGetStarted {
                    // Primary: footnote-sized secondary grey falls short of contrast.
                    Text(Words.shared.downloadLine(size: model.downloadSize)).font(.footnote).multilineTextAlignment(.center)
                }
            })
    }
}

/// Hello's looping example: a description types into a search field, then the card that answers it springs in, three
/// in turn (a phone without search by description leaves out what only it finds). Illustrations, never the person's
/// files, so VoiceOver skips it; Reduce Motion shows the first, still.
private struct HelloExamples: View {
    /// Core's examples in order, and their art.
    private static let art = ["ExampleChat", "ExampleSofa", "ExampleLease"]
    let textOnly: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var index = 0
    @State private var typed = Int.max
    @State private var answered = true

    var body: some View {
        let examples = Words.shared.helloExamples(textOnly: textOnly)
        let example = examples[index % examples.count]
        VStack(alignment: .leading, spacing: 8) {
            // Primary throughout: footnote-sized secondary grey falls short of contrast, and the audit still reads these.
            Text(Words.shared.FOR_EXAMPLE.uppercased()).font(.footnote.weight(.semibold)).padding(.leading, 4)
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                Text(example.query.prefix(typed)).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 48)
            .background(.fill.tertiary, in: Capsule())
            HStack(spacing: 14) {
                Image(Self.art[Words.shared.HELLO_EXAMPLES.firstIndex(of: example) ?? 0]).resizable().scaledToFill().frame(width: 72, height: 72)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(example.title).font(.subheadline.weight(.semibold))
                    Text(example.detail).font(.footnote)
                }
                Spacer(minLength: 0)
            }
            .padding(10)
            .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .opacity(answered ? 1 : 0)
            .scaleEffect(answered ? 1 : 0.97)
            .offset(y: answered ? 0 : 14)
        }
        .multilineTextAlignment(.leading)
        .frame(maxWidth: 342)
        .accessibilityElement(children: .ignore)
        .accessibilityHidden(true)
        .task(id: reduceMotion) {
            guard !reduceMotion else {
                (index, typed, answered) = (0, .max, true)
                return
            }
            await loop()
        }
    }

    /// About 3.5 s each, as the design has it: type for a second, the card in, a hold, then the next.
    private func loop() async {
        do {
            while true {
                let examples = Words.shared.helloExamples(textOnly: textOnly)
                let query = examples[index % examples.count].query
                (typed, answered) = (0, false)
                for n in 1...query.count {
                    try await Task.sleep(for: .milliseconds(1_100 / query.count))
                    typed = n
                }
                try await Task.sleep(for: .milliseconds(150))
                withAnimation(.spring(duration: 0.5, bounce: 0.3)) { answered = true }
                try await Task.sleep(for: .milliseconds(2_000))
                withAnimation(.easeOut(duration: 0.2)) { answered = false }
                try await Task.sleep(for: .milliseconds(250))
                index = (index + 1) % examples.count
            }
        } catch {}
    }
}

/// What Retriever can search: photos through iOS's ask, documents through folders picked in Files. Continue waits until
/// one is given; both rows can be filled first. Back goes to Hello.
private struct AccessStep: View {
    let model: AppModel
    @State private var picking = false
    @State private var folderNote: String?

    var body: some View {
        // A stack of its own, so the toolbar's Back shows.
        NavigationStack {
            StepContent(alignment: .center, spacing: 8) {
                MascotView(mood: .searching, settles: false).frame(width: 230)
                Text(Words.shared.ACCESS_TITLE).font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                Text(Words.shared.ACCESS_BODY).font(.title3).foregroundStyle(.secondary)
                VStack(spacing: 0) {
                    AccessRow(icon: "photo.on.rectangle", title: Words.shared.PHOTOS_AND_VIDEOS, detail: photosDetail, below: model.photos == .limited) {
                        photosAction
                    }
                    Divider().padding(.leading, 70)
                    AccessRow(icon: "folder", title: Words.shared.DOCUMENTS, detail: foldersDetail) { foldersAction }
                }
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .frame(maxWidth: 342)
                .padding(.top, 20)
                if let folderNote { Note(folderNote) }
                // Continue starts processing, so the screen says so once there is something to process.
                if model.accessGiven {
                    Label(Words.shared.ACCESS_SCANNING, systemImage: "checkmark").font(.subheadline).padding(.top, 12)
                }
            }
            .modifier(
                StepActions {
                    OnDevice()
                    Button {
                        model.setSetupMark(.accessChosen)
                    } label: {
                        wide(Words.shared.CONTINUE)
                    }
                    .buttonStyle(.primaryAction)
                    .disabled(!model.accessGiven)
                }
            )
            .toolbar { SetupBack(model: model) }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            // An added folder shows in its row; only what went wrong needs saying.
            let problems = model.addFolders(urls).filter { if case .added = $0 { false } else { true } }
            folderNote = problems.isEmpty ? nil : problems.map(Copy.addFolderResult).joined(separator: "\n")
        }
    }

    private var photosDetail: String {
        switch model.photos {
        case .authorized: Words.shared.ALLOWED
        case .limited: Words.shared.SOME_PHOTOS
        case .denied, .restricted: Words.shared.PHOTOS_OFF
        default: Words.shared.INCLUDES_SCREENSHOTS
        }
    }

    @ViewBuilder private var photosAction: some View {
        switch model.photos {
        case .authorized: Done()
        case .limited: Button(Words.shared.CHOOSE_MORE, action: model.chooseMorePhotos).buttonStyle(.bordered)
        case .denied, .restricted: Button(Words.shared.SETTINGS, action: model.openSettings).buttonStyle(.bordered)
        default: Button(Words.shared.ALLOW, action: model.requestPhotos).buttonStyle(.primaryAction)
        }
    }

    private var foldersDetail: String {
        model.folders.isEmpty ? Words.shared.PICK_FOLDERS : Words.shared.foldersAdded(n: Int64(model.folders.count))
    }

    @ViewBuilder private var foldersAction: some View {
        if !model.folders.isEmpty {
            Done()
        } else if model.photos == .notDetermined {
            // Photos is the row to answer first; Choose is the strong one once it has been.
            Button(Words.shared.CHOOSE) { picking = true }.buttonStyle(.bordered)
        } else {
            Button(Words.shared.CHOOSE) { picking = true }.buttonStyle(.primaryAction)
        }
    }

    /// A row's answer once given; its line under the title already says it.
    private struct Done: View {
        var body: some View {
            Image(systemName: "checkmark.circle.fill").font(.title2).foregroundStyle(.tint).accessibilityHidden(true)
        }
    }
}

/// One of the access screen's rows: an icon, what it gives, where it stands, and its one action.
private struct AccessRow<Action: View>: View {
    let icon: String
    let title: String
    let detail: String
    /// The action under the words, not beside them: a long label there would break the title.
    var below = false
    @ViewBuilder let action: Action

    var body: some View {
        HStack(alignment: below ? .top : .center, spacing: 14) {
            Image(systemName: icon).font(.title3).foregroundStyle(.tint)
                .frame(width: 40, height: 40)
                .background(.tint.opacity(0.16), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline)
                    // Primary, as the app's notes are: secondary grey fails contrast on the grouped card.
                    Text(detail).font(.footnote)
                }
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityElement(children: .combine)
                if below { action.buttonBorderShape(.capsule) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // Whole: the words beside it wrap instead.
            if !below { action.buttonBorderShape(.capsule).fixedSize() }
        }
        .multilineTextAlignment(.leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }
}

/// The first search, one screen: the real search over the recent files (`SearchView` with a `Trial`), their progress
/// under its field until a word is typed, and Continue pinned below, enabled throughout; it ends setup.
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
                        Button(action: model.finishSetup) { wide(Words.shared.CONTINUE) }.buttonStyle(.primaryAction)
                    })
        }
    }
}

/// Under the first search's field before a word is typed: the dog (or, once search by description is ready, a banner
/// saying so), the title, the one count and its bar, where search by description stands, the pile of the newest files,
/// words to try; the pile opens the list of its files. Every row keeps its place from the first frame, so the centred group never
/// moves between states.
struct DemoIntro: View {
    let model: AppModel
    let search: SearchModel
    @State private var seeingAll = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        // Both kept on the model, so coming back to this screen never starts from zero or replays the pile.
        let taste = model.taste
        let sample = model.sample ?? []
        // Picking, or processing, not stopped, with files left. Not `running`: it drops between two batches, and the
        // mascot would sit down for that gap.
        let reading = model.setup?.step == .plan || (model.progress.reading.pauseReason == nil && taste.pending > 0)
        let planned = Int64(taste.planned)
        let done = Int64(taste.done)
        // The files are picked in under a second; the total is fixed from then on.
        let picking = planned == 0
        let count = picking ? Words.shared.DEMO_PICKING : Words.shared.readOf(done: done, total: planned)
        let state = model.descriptionState
        // Tighter than the other steps: with the full-size pile it must fit an iPhone Pro above the footer and search field.
        StepContent(alignment: .center, spacing: 10) {
            // The dog's height holds the banner's place, so the rows under it stay put when it arrives.
            ZStack {
                MascotView(mood: reading ? .walking : .resting, settles: false).frame(width: 120).opacity(state == .ready ? 0 : 1)
                if state == .ready { ReadyBanner().transition(.scale(scale: 0.96).combined(with: .opacity)) }
            }
            .animation(reduceMotion ? nil : .spring(duration: 0.5, bounce: 0.3), value: state == .ready)
            Text(Words.shared.FIRST_LOOK)
                .font(.title2.bold())
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .accessibilityValue(taste.spoken)
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
            DescriptionLine(model: model, state: state)
            SamplePileView(
                cards: SamplePile.shared.shown(files: sample), kinds: SamplePileView.kinds(sample), label: count,
                hint: Words.shared.seeAll(files: planned), seeAll: picking ? nil : { seeingAll = true }
            )
            // A new pile at the first delivery: the files done by then are there from its start, so they never settle again.
            .id(model.sample == nil)
            DemoChips(terms: model.trySuggestions, search: search)
        }
        .sheet(isPresented: $seeingAll) { SampleList(model: model, sample: model.sample ?? []) }
    }
}

/// At the top of the first search once search by description is ready.
private struct ReadyBanner: View {
    /// Grows with the checkmark, which would otherwise outgrow its circle at the accessibility sizes.
    @ScaledMetric(relativeTo: .headline) private var badge: CGFloat = 40

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark").font(.headline).foregroundStyle(Color(.systemBackground))
                .frame(width: badge, height: badge)
                .background(.tint, in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(Words.shared.DESCRIPTION_READY).font(.subheadline.weight(.semibold))
                Text(Words.shared.DESCRIPTION_READY_BODY).font(.footnote)
            }
            Spacer(minLength: 0)
        }
        .multilineTextAlignment(.leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(.tint.opacity(0.16), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// The quiet line under the count: why processing is paused, or else where search by description stands, with Use
/// mobile data or Try again where they help. A line and a button's height are kept, so the line coming and going never moves the group.
private struct DescriptionLine: View {
    let model: AppModel
    let state: DescriptionState
    @State private var askingMobileData = false

    var body: some View {
        let percent = model.download == .downloading ? KotlinInt(int: Int32((min(max(model.downloadProgress, 0), 1) * 100).rounded())) : nil
        ZStack {
            VStack(spacing: 0) {
                Text(" ")
                Text(" ").frame(minHeight: 44)
            }
            .hidden()
            // A pause wins: it says why the count stands still.
            if let pause = model.progress.reading.pauseReason {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Image(systemName: "pause.circle").foregroundStyle(.tint)
                    Text(pause)
                }
                .accessibilityElement(children: .combine)
            } else if let line = Words.shared.descriptionLine(state: state, percent: percent) {
                VStack(spacing: 0) {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Image(systemName: "sparkle").foregroundStyle(.tint)
                        Text(line).monospacedDigit()
                    }
                    .accessibilityElement(children: .combine)
                    switch state {
                    case .waitingForWifi:
                        Button {
                            askingMobileData = true
                        } label: {
                            Text(Words.shared.USE_MOBILE_DATA).frame(minHeight: 44)
                        }
                    case .failed: Button(action: model.downloadModel) { Text(Words.shared.TRY_AGAIN).frame(minHeight: 44) }
                    default: EmptyView()
                    }
                }
            }
        }
        .font(.footnote)
        .fixedSize(horizontal: false, vertical: true)
        .mobileDataAsk(model, isPresented: $askingMobileData)
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
        Button(term) { search.setText(term) }.buttonStyle(.bordered).accessibilityHint(Words.shared.strings.searchesForThis)
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
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button(Words.shared.DONE) { dismiss() } } }
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
                .accessibilityHint(Words.shared.strings.openFileHint)
            } else {
                row
            }
        }
    }
}

/// Back from the access screen to Hello, by core's rule. On the first search's results it goes back to the first search
/// itself, in one press; the first search has no Back of its own.
private struct SetupBack: ToolbarContent {
    let model: AppModel
    var search: SearchModel?

    var body: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            if search?.active ?? true {
                Button(Words.shared.strings.back, systemImage: "chevron.backward") {
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

#Preview("Hello") {
    SetupView(model: AppModel(fixture: .firstRun))
}

#Preview("Access, some photos") {
    SetupView(model: AppModel(fixture: .photosLimited))
}

#Preview("Access, photos refused") {
    SetupView(model: AppModel(fixture: .photosDenied))
}

#Preview("First search starting") {
    SetupView(model: AppModel(fixture: .sampleStart))
}

#Preview("First search, downloading") {
    SetupView(model: AppModel(fixture: .sampleReading))
}

#Preview("First search, ready") {
    SetupView(model: AppModel(fixture: .sampleReady))
}
