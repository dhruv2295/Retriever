import BackgroundTasks
import Foundation
import os
import RetrieverCore

/// Reads files while the app is in the background: iOS runs it when it sees fit, mostly overnight on the charger.
/// The model runs on the CPU there (IosEmbedderFactory.backendNow), since iOS refuses GPU work in the background.
enum IndexTask {
    static let id = "app.retriever.index"
    /// Posted on the main thread when a background batch has ended, so a foreground loop it blocked can run.
    static let ended = Notification.Name("app.retriever.backgroundBatchEnded")
    private static let log = Logger(subsystem: "app.retriever", category: "background")

    /// Call before the app finishes launching.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
            task.expirationHandler = expire
            runBatch { task.setTaskCompleted(success: $0) }
        }
    }

    /// Fails on the simulator, which has no BGTaskScheduler.
    static func schedule() {
        let request = BGProcessingTaskRequest(identifier: id)
        request.requiresExternalPower = true
        request.requiresNetworkConnectivity = false
        do {
            try BGTaskScheduler.shared.submit(request)
            log.info("background reading scheduled")
        } catch {
            log.error("couldn't schedule background reading: \(error, privacy: .public)")
        }
    }

    /// The expiration handler: Swift cancellation doesn't reach the Kotlin coroutine, so this stops the batch
    /// within one file, and runBatch then completes the task.
    static func expire() {
        log.info("background time is up; stopping the batch")
        expired.withLock { $0 = true }
        AppServices.shared.retriever.cancelBatch()
    }

    /// Set by `expire()`: cancelBatch() reaches only a batch already running, so one about to start must not.
    private static let expired = OSAllocatedUnfairLock(initialState: false)

    /// The task's work, apart from BGTask so the debug drill can run it where BGTaskScheduler can't.
    static func runBatch(complete: @escaping (Bool) -> Void) {
        let retriever = AppServices.shared.retriever
        expired.withLock { $0 = false }
        Task {
            var next: NextRun?
            do {
                try await retriever.refreshSetUp()
                if expired.withLock({ $0 }) {
                    next = NextRun.soon
                } else {
                    next = try await retriever.indexBatchSeconds(seconds: 1500)
                }
            } catch {
                log.error("background batch failed: \(error, privacy: .public)")
            }
            let state = retriever.state.value as? IndexState
            log.info("background batch: \(next?.name ?? "failed", privacy: .public), backend \(state?.backend?.name ?? "none", privacy: .public), processed \(state?.processed ?? 0)")
            try? await retriever.release()
            // Even when caught up: the next run reads photos taken meanwhile.
            if (retriever.prefs.value as? Prefs)?.paused != true { schedule() }
            complete(next != nil)
            log.info("background task completed, success \(next != nil)")
            DispatchQueue.main.async { NotificationCenter.default.post(name: ended, object: nil) }
        }
    }
}
