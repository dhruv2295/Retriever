import CryptoKit
import Foundation
import Network
import Observation
@preconcurrency import RetrieverCore
import UIKit

/// Downloads the model in a background URLSession, so it keeps going after the app leaves the screen, then checks
/// its SHA-256 and writes "<fileName>.sha256": the marker RetrieverIosKt.modelReady reads. The check runs under a
/// background task: iOS relaunches the app to hand over a finished download and would otherwise suspend it mid-check.
@Observable @MainActor
final class ModelDownload: NSObject, URLSessionDownloadDelegate {
    enum State: Equatable {
        case idle
        /// Wi-Fi only and on mobile data: the download starts by itself on Wi-Fi.
        case waitingForWifi
        case downloading
        case verifying
        case ready
        /// Plain words, safe to show.
        case failed(String)
        /// Not started, or not kept, for want of free space; plain words saying how much to free.
        case noSpace(String)
    }

    static let sessionId = "app.retriever.model"
    private static let headroom: Int64 = 300 * 1024 * 1024

    private(set) var state: State = .idle
    /// 0...1 while downloading.
    private(set) var progress: Double = 0
    /// Held while a finished file is checked. ponytail: one task covers overlapping checks; the first to finish ends it.
    @ObservationIgnored private(set) var checkTask = UIBackgroundTaskIdentifier.invalid

    /// Set from the app delegate's handleEventsForBackgroundURLSession; called once the session's events are in.
    /// Setting it reconnects the session on a cold relaunch, so the pending events reach this delegate.
    @ObservationIgnored var backgroundCompletion: (() -> Void)? {
        didSet { _ = session }
    }

    nonisolated let dir: URL
    @ObservationIgnored private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: Self.sessionId)
        config.sessionSendsLaunchEvents = true
        config.isDiscretionary = false
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// Read when a Wi-Fi-only download starts, to say it is waiting rather than stuck.
    @ObservationIgnored nonisolated private let pathMonitor = NWPathMonitor()

    /// Nonisolated, so AppServices can build it on any thread; restore() runs on main.
    nonisolated init(dir: URL) {
        self.dir = dir
        super.init()
        pathMonitor.start(queue: DispatchQueue(label: "app.retriever.network"))
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = dir
        try? url.setResourceValues(values)
    }

    /// Reconnects to a download that was running when the app was last closed. Call at launch.
    func restore() {
        session.getAllTasks { tasks in
            guard let task = tasks.first(where: { $0.state == .running || $0.state == .suspended }) else { return }
            self.set(self.waiting(task) ? .waitingForWifi : .downloading)
        }
    }

    /// [allowCellular] false waits for Wi-Fi, as Android's "wait for Wi-Fi" does: the model is about 390 MB.
    func start(_ build: ModelBuild, allowCellular: Bool) {
        if RetrieverIosKt.modelReady(modelDir: dir.path, build: build) { return set(.ready) }
        // A whole file with no marker (the app closed while checking a finished download): check it, don't fetch it again.
        let size = (try? dir.appendingPathComponent(build.fileName).resourceValues(forKeys: [.fileSizeKey]))?.fileSize
        if size.map(Int64.init) == build.sizeBytes {
            set(.verifying)
            check({ self.verifyInPlace(build) ? .ready : nil }, otherwise: { self.download(build, allowCellular: allowCellular) })
            return
        }
        download(build, allowCellular: allowCellular)
    }

    private func download(_ build: ModelBuild, allowCellular: Bool) {
        let free = (try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage ?? 0
        if free < build.sizeBytes + Self.headroom {
            return set(.noSpace(Words.shared.freeUp(size: Copy.downloadSize(build.sizeBytes + Self.headroom - free))))
        }
        guard let url = URL(string: build.url) else { return set(.failed(Words.shared.DOWNLOAD_FAILED)) }
        try? FileManager.default.removeItem(at: marker(build.fileName))
        let description = [build.fileName, build.sha256, allowCellular ? "1" : "0"].joined(separator: "|")
        let session = session
        session.getAllTasks { tasks in
            // Already going, from this launch or an earlier one: adopt it rather than download it twice.
            let live = tasks.first { ($0.state == .running || $0.state == .suspended) && $0.taskDescription == description }
            for stale in tasks where stale !== live { stale.cancel() }
            let task: URLSessionTask
            var fresh = false
            if let live {
                task = live
            } else {
                // Resume data embeds its request, network rule included, so it is kept per rule.
                let saved = self.resumeFile(build.fileName, allowCellular)
                let resume = try? Data(contentsOf: saved)
                try? FileManager.default.removeItem(at: saved)
                var request = URLRequest(url: url)
                request.allowsExpensiveNetworkAccess = allowCellular
                request.allowsConstrainedNetworkAccess = allowCellular
                fresh = resume == nil
                task = resume.map { session.downloadTask(withResumeData: $0) } ?? session.downloadTask(with: request)
                task.taskDescription = description
                task.countOfBytesClientExpectsToReceive = build.sizeBytes
            }
            task.resume()
            let waiting = self.waiting(task)
            DispatchQueue.main.async {
                if fresh { self.progress = 0 }
                self.state = waiting ? .waitingForWifi : .downloading
            }
        }
    }

    /// A Wi-Fi-only download on mobile data (or offline): it won't move until Wi-Fi is back.
    nonisolated private func waiting(_ task: URLSessionTask) -> Bool {
        guard task.taskDescription?.hasSuffix("|0") == true else { return false }
        let path = pathMonitor.currentPath
        return path.status != .satisfied || path.isExpensive || path.isConstrained
    }

    func cancel() {
        session.getAllTasks { tasks in for task in tasks { task.cancel() } }
        set(.idle)
    }

    /// The person's Cancel. The bytes so far are kept, so starting again carries on from there.
    func stop() {
        session.getAllTasks { tasks in
            for task in tasks.compactMap({ $0 as? URLSessionDownloadTask }) {
                let parts = (task.taskDescription ?? "").split(separator: "|").map(String.init)
                task.cancel(byProducingResumeData: { data in
                    guard parts.count == 3, let data else { return }
                    try? data.write(to: self.resumeFile(parts[0], parts[2] == "1"), options: .atomic)
                })
            }
        }
        set(.idle)
    }

    /// For a file already in place (copied in, or an earlier run that stopped before checking). Slow: call off main.
    @discardableResult
    nonisolated func verifyInPlace(_ build: ModelBuild) -> Bool {
        if RetrieverIosKt.modelReady(modelDir: dir.path, build: build) { return true }
        let file = dir.appendingPathComponent(build.fileName)
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize.map(Int64.init)
        return size == build.sizeBytes && verify(file, fileName: build.fileName, sha256: build.sha256)
    }

    /// Removes the model file, its marker and any saved resume data, for Free up space.
    func deleteModel(_ build: ModelBuild) {
        cancel()
        let fm = FileManager.default
        let files = [
            dir.appendingPathComponent(build.fileName), marker(build.fileName),
            resumeFile(build.fileName, true), resumeFile(build.fileName, false),
        ]
        for file in files { try? fm.removeItem(at: file) }
    }

    // MARK: URLSessionDownloadDelegate

    nonisolated func urlSession(
        _ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        let total = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : downloadTask.countOfBytesClientExpectsToReceive
        guard total > 0 else { return }
        // Whole percents: @Observable skips an equal value, so the bar redraws 100 times, not per chunk.
        let fraction = (min(1, Double(totalBytesWritten) / Double(total)) * 100).rounded() / 100
        DispatchQueue.main.async {
            self.state = .downloading
            self.progress = fraction
        }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let parts = (downloadTask.taskDescription ?? "").split(separator: "|").map(String.init)
        guard parts.count >= 2 else { return }
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            return set(.failed(Words.shared.DOWNLOAD_FAILED))
        }
        // The system deletes `location` when this returns, so move it now and check it afterwards.
        let file = dir.appendingPathComponent(parts[0])
        do {
            try? FileManager.default.removeItem(at: file)
            try FileManager.default.moveItem(at: location, to: file)
        } catch {
            return set(.noSpace(Words.shared.NO_SPACE))
        }
        set(.verifying)
        check { self.verify(file, fileName: parts[0], sha256: parts[1]) ? .ready : .failed(Words.shared.DOWNLOAD_FAILED) }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error = error as? URLError, error.code != .cancelled else { return }
        let parts = (task.taskDescription ?? "").split(separator: "|").map(String.init)
        // Kept so the next start() carries on from here instead of fetching all of it again.
        if parts.count == 3, let data = error.downloadTaskResumeData {
            try? data.write(to: resumeFile(parts[0], parts[2] == "1"), options: .atomic)
        }
        set(.failed(Words.shared.DOWNLOAD_FAILED))
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.backgroundCompletion?()
            self.backgroundCompletion = nil
        }
    }

    // MARK: Helpers

    nonisolated private func marker(_ fileName: String) -> URL {
        dir.appendingPathComponent(fileName + ".sha256")
    }

    nonisolated private func resumeFile(_ fileName: String, _ allowCellular: Bool) -> URL {
        dir.appendingPathComponent(fileName + (allowCellular ? ".resume-any" : ".resume-wifi"))
    }

    /// Streams the file through SHA-256; writes the marker on a match and deletes the file otherwise.
    nonisolated private func verify(_ file: URL, fileName: String, sha256: String) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: file) else { return false }
        var hasher = SHA256()
        while true {
            let more: Bool = autoreleasepool {
                guard let chunk = try? handle.read(upToCount: 8 << 20), !chunk.isEmpty else { return false }
                hasher.update(data: chunk)
                return true
            }
            if !more { break }
        }
        try? handle.close()
        let hex = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard hex == sha256 else {
            try? FileManager.default.removeItem(at: file)
            return false
        }
        return (try? Data(hex.utf8).write(to: marker(fileName), options: .atomic)) != nil
    }

    /// Runs a slow SHA-256 check off main under a background task, then shows its state, or runs [otherwise] when it
    /// gives none. Begun on main before the session's events finish (`urlSessionDidFinishEvents` queues behind it).
    nonisolated private func check(_ work: @escaping @Sendable () -> State?, otherwise: @escaping @MainActor @Sendable () -> Void = {}) {
        DispatchQueue.main.async {
            if self.checkTask == .invalid {
                self.checkTask = UIApplication.shared.beginBackgroundTask(withName: "verify-model") { self.endCheck() }
            }
            DispatchQueue.global(qos: .utility).async {
                let state = work()
                DispatchQueue.main.async {
                    if let state { self.state = state } else { otherwise() }
                    self.endCheck()
                }
            }
        }
    }

    private func endCheck() {
        guard checkTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(checkTask)
        checkTask = .invalid
    }

    nonisolated private func set(_ state: State) {
        DispatchQueue.main.async { self.state = state }
    }
}
