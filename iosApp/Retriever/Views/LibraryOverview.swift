import RetrieverCore
import SwiftUI

struct LibraryOverview<Status: View>: View {
    let snap: AppModel.Snap
    let folders: [FolderCount]
    @ViewBuilder var status: () -> Status
    static var kinds: [Kind] { [.photo, .video, .screenshot, .pdf, .doc, .audio, .other] }
    private let colors: [Color] = [.accentColor, .blue, .purple, .orange, .teal, .pink, .secondary]

    static func title(_ kind: Kind) -> String {
        switch kind {
        case .photo: "Photos"
        case .video: "Videos"
        case .screenshot: "Screenshots"
        case .pdf: "PDFs"
        case .doc: "Documents"
        case .audio: "Audio"
        default: "Other files"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Your library").font(.headline).accessibilityAddTraits(.isHeader)
            Text(Copy.count(snap.libraryTotal)).font(.largeTitle.bold()).monospacedDigit()
            Text(snap.trial ? "Files in your whole library · trial reading" : "Files in your library").font(.subheadline)
            GeometryReader { bounds in
                HStack(spacing: 0) {
                    ForEach(Array(Self.kinds.enumerated()), id: \.element) { index, kind in
                        let count = snap.byKind[kind] ?? 0
                        if count > 0 {
                            let read = snap.reads(kind) && kind != .other ? min(count, snap.readByKind[kind] ?? 0) : 0
                            ZStack(alignment: .leading) {
                                colors[index].opacity(0.18)
                                colors[index].frame(width: bounds.size.width * CGFloat(read) / CGFloat(max(snap.libraryTotal, 1)))
                            }
                            .frame(width: bounds.size.width * CGFloat(count) / CGFloat(max(snap.libraryTotal, 1)))
                            .accessibilityLabel("\(Self.title(kind)): \(Copy.count(read)) of \(Copy.count(count)) read")
                        }
                    }
                }
                .clipShape(Capsule())
            }
            .frame(height: 16)
            .accessibilityHidden(true)
            ScrollView(.horizontal) {
                HStack {
                    ForEach(Self.kinds.filter { (snap.byKind[$0] ?? 0) > 0 }, id: \.self) { kind in
                        NavigationLink(value: Route.files(FileQuery(kind: kind, title: Self.title(kind)))) {
                            Text("\(Self.title(kind)) \(Copy.count(snap.byKind[kind] ?? 0))")
                        }.buttonStyle(.bordered).controlSize(.large)
                            .accessibilityLabel("\(Self.title(kind)): \(snap.reads(kind) && kind != .other ? min(snap.byKind[kind] ?? 0, snap.readByKind[kind] ?? 0) : 0) of \(snap.byKind[kind] ?? 0) read")
                            .accessibilityHint("Browse \(Self.title(kind).lowercased())")
                    }
                }
            }
            status()
            Text("Top folders").font(.subheadline.bold())
            ScrollView(.horizontal) {
                HStack {
                    ForEach(folders, id: \.path) { folder in
                        let name = folder.path.split(separator: "/").last.map(String.init) ?? "Files"
                        NavigationLink(value: Route.files(FileQuery(folder: folder.path, title: name))) {
                            Text("\(name) \(Copy.count(Int(folder.count)))")
                        }.buttonStyle(.bordered).controlSize(.large)
                    }
                    NavigationLink("All folders", value: Route.folders).buttonStyle(.bordered).controlSize(.large)
                }
            }
        }
        .padding(.vertical, 8)
        .accessibilityIdentifier("home.library")
    }
}
