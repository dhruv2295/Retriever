import SwiftUI
import RetrieverCore
import MediaPipeTasksRetrieval

struct ContentView: View {
    var body: some View {
        VStack(spacing: 24) {
            Image("IllusWelcome")
                .resizable()
                .scaledToFit()
                .frame(maxWidth: 320)
            Text(ModelVariant.m440.generic.fileName)
                .font(.footnote.monospaced())
        }
        .padding()
    }
}
