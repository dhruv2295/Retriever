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
            Text("Your files").font(.headline).accessibilityAddTraits(.isHeader)
            Text("\(Copy.count(snap.libraryTotal)) files in your library").font(.title2.bold()).monospacedDigit()
            Text(Copy.readCount(snap)).monospacedDigit()
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
