import Foundation
import MetricKit

/// Keeps the system's crash and hang reports on the device so a person can share them by hand. Nothing is sent anywhere.
final class Diagnostics: NSObject, MXMetricManagerSubscriber, @unchecked Sendable {
    static let maxEntries = 10
    private let file: URL
    private let lock = NSLock()

    init(file: URL) { self.file = file }

    /// Newest last. Each entry is one payload's JSON text.
    var entries: [String] {
        lock.withLock { read() }
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        let new = payloads.compactMap { String(data: $0.jsonRepresentation(), encoding: .utf8) }
        guard !new.isEmpty else { return }
        lock.withLock {
            let kept = Array((read() + new).suffix(Self.maxEntries))
            try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? JSONEncoder().encode(kept).write(to: file, options: .atomic)
        }
    }

    private func read() -> [String] {
        (try? Data(contentsOf: file)).flatMap { try? JSONDecoder().decode([String].self, from: $0) } ?? []
    }
}
