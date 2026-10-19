import RetrieverCore
import SwiftUI

struct LibraryOverview<Status: View>: View {
    let snap: AppModel.Snap
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
        VStack(alignment: .leading, spacing: 4) {
            Text("Your library").font(.headline).accessibilityAddTraits(.isHeader)
            Text(Copy.searchable(snap)).font(.title2.bold()).monospacedDigit()
            GeometryReader { bounds in
                let active = Self.kinds.filter { (snap.byKind[$0] ?? 0) > 0 }
                let minimum = min(bounds.size.height, bounds.size.width / CGFloat(max(active.count, 1)))
                let remaining = max(0, bounds.size.width - minimum * CGFloat(active.count))
                HStack(spacing: 0) {
                    ForEach(Array(Self.kinds.enumerated()), id: \.element) { index, kind in
                        let count = snap.byKind[kind] ?? 0
                        if count > 0 {
                            let read = snap.reads(kind) && kind != .other ? min(count, snap.readByKind[kind] ?? 0) : 0
                            let width = minimum + remaining * CGFloat(count) / CGFloat(max(snap.libraryTotal, 1))
                            let readWidth = read > 0 ? min(width, max(bounds.size.height / 2, width * CGFloat(read) / CGFloat(count))) : 0
                            ZStack(alignment: .leading) {
                                colors[index].opacity(0.18)
                                colors[index].frame(width: readWidth)
                                    .overlay(alignment: .bottom) {
                                        if snap.trial && read > 0 { Rectangle().fill(.primary).frame(height: 2) }
                                    }
                            }
                            .frame(width: width)
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
                            HStack(spacing: 6) {
                                Circle().fill(colors[Self.kinds.firstIndex(of: kind)!]).frame(width: 10, height: 10).accessibilityHidden(true)
                                Text("\(Self.title(kind)) \(Copy.count(snap.byKind[kind] ?? 0))").foregroundStyle(.primary)
                            }
                        }.buttonStyle(.bordered).controlSize(.regular)
                            .accessibilityLabel("\(Self.title(kind)): \(snap.reads(kind) && kind != .other ? min(snap.byKind[kind] ?? 0, snap.readByKind[kind] ?? 0) : 0) of \(snap.byKind[kind] ?? 0) read")
                            .accessibilityHint("Browse \(Self.title(kind).lowercased())")
                            .accessibilityIdentifier("kind-\(kind.name)")
                    }
                }
            }
            status()

        }
        .padding(.vertical, 8)
        .accessibilityIdentifier("home.library")
    }
}

struct LibraryFolders: View {
    let folders: [FolderCount]
    var body: some View {
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
}
