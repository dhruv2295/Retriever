#if DEBUG
    import RetrieverCore
    import SwiftUI

    /// Every mascot animation in one place, with the switches that drive its transitions. `-route mascot.gallery`.
    struct MascotGallery: View {
        var body: some View {
            ScrollView {
                VStack(spacing: 20) {
                    Section(title: "Reading", moods: [.walking, .resting])
                    Section(title: "Search", moods: [.searching, .noMatch, .noMatchQuiet])
                    Section(title: "Welcome (180 pt)", moods: [.welcome], width: 180)
                    Section(title: "All caught up", moods: [.caughtUp])
                    Section(title: "Stages", moods: Stage.entries.map { MascotDirector.shared.working(stage: $0) } + [.resting])
                    Section(title: "Stage done", moods: [.foundIt])
                }
                .padding()
            }
        }

        private struct Section: View {
            let title: String
            let moods: [MascotMood]
            var width: CGFloat = 160
            @State private var mood: MascotMood?
            @State private var replays = 0

            var body: some View {
                VStack(spacing: 8) {
                    Text(title).font(.headline)
                    MascotView(mood: mood ?? moods[0]).frame(width: width).id(replays)
                    HStack {
                        if moods.count > 1 {
                            ForEach(moods, id: \.self) { m in
                                Button(m.name.lowercased()) { mood = m }
                                    .buttonStyle(.bordered)
                                    .tint((mood ?? moods[0]) == m ? .accentColor : .secondary)
                            }
                        }
                        Button("Replay") { replays += 1 }.buttonStyle(.bordered)
                    }
                    .font(.footnote)
                }
            }
        }
    }
#endif
