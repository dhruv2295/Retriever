import RetrieverCore
import SwiftUI

/// Home's compact hero: "Your files", how many, how many read, then the one status row.
struct LibraryOverview<Status: View>: View {
    let snap: AppModel.Snap
    @ViewBuilder var status: () -> Status

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(Words.shared.YOUR_FILES).font(.headline).accessibilityAddTraits(.isHeader)
            Text(Words.shared.inLibrary(files: Int64(snap.libraryTotal))).font(.title2.bold()).monospacedDigit()
            Text(Words.shared.readCount(read: Int64(snap.indexed), partlyRead: Int64(snap.partlyRead))).monospacedDigit()
            status()
        }
        .padding(.vertical, 8)
        .accessibilityIdentifier("home.library")
    }
}
