import AppIntents
import Observation

/// A search handed in from Shortcuts or Siri; the search screen takes it and clears it.
@Observable @MainActor
final class LaunchQuery {
    static let shared = LaunchQuery()
    var pending: String?
}

struct SearchIntent: AppIntent {
    static let title: LocalizedStringResource = "Search Retriever"
    static let description = IntentDescription("Search your files and photos by what is inside them.")
    static let openAppWhenRun = true

    @Parameter(title: "Search for") var query: String

    @MainActor func perform() async throws -> some IntentResult {
        LaunchQuery.shared.pending = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return .result()
    }
}

struct RetrieverShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: SearchIntent(),
            phrases: ["Search \(.applicationName)"],
            shortTitle: "Search",
            systemImageName: "magnifyingglass"
        )
    }
}
