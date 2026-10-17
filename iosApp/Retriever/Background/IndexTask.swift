import BackgroundTasks
import Foundation
import os
import RetrieverCore

/// Reads files while the app is in the background: iOS runs it when it sees fit, mostly overnight on the charger.
/// The model runs on the CPU there (IosEmbedderFactory.backendNow), since iOS refuses GPU work in the background.
enum IndexTask {
    static let id = "app.retriever.index"
    /// The read a person starts and iOS carries on after they leave (iOS 26+). The bundle ID is
    /// `app.retriever$(TEAM_ID)` and iOS wants it in front, so Info.plist lists `$(PRODUCT_BUNDLE_IDENTIFIER).continued`.
    static let continuedId = "\(Bundle.main.bundleIdentifier ?? "app.retriever").continued"
    /// Posted on the main thread when a background batch has ended, so a foreground loop it blocked can run. The
    /// continued task posts it with `continuedId` as the object.
    static let ended = Notification.Name("app.retriever.backgroundBatchEnded")
    private static let log = Logger(subsystem: "app.retriever", category: "background")

    /// Call before the app finishes launching.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
            task.expirationHandler = expire
            runBatch { task.setTaskCompleted(success: $0) }
        }
        if #available(iOS 26.0, *) {
            let ok = BGTaskScheduler.shared.register(forTaskWithIdentifier: continuedId, using: nil) { task in
                guard let task = task as? BGContinuedProcessingTask else { return task.setTaskCompleted(success: false) }
                runContinued(task)
            }
            log.info("continued reading registered: \(ok, privacy: .public)")
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

    /// Only from a person's tap while the app is in front; it starts at once or throws. The simulator throws
    /// BGTaskScheduler error 1 (unavailable).
    @available(iOS 26.0, *)
    static func submitContinued(_ snap: AppModel.Snap) throws {
        let request = BGContinuedProcessingTaskRequest(identifier: continuedId, title: Copy.continuedTitle, subtitle: Copy.continuedSubtitle(snap))
        // A queued request may wait while the open app stands aside, reading nothing.
        request.strategy = .fail
        // No .gpu: it needs the continued-processing GPU entitlement, and backendNow picks the CPU once the app leaves.
        try BGTaskScheduler.shared.submit(request)
    }

    /// The expiration handler: Swift cancellation doesn't reach the Kotlin coroutine, so this stops the batch
    /// within one file, and runBatch then completes the task.
    static func expire() {
        log.info("background time is up; stopping the batch")
        expired.withLock { $0 = true }
        AppServices.shared.retriever.cancelBatch()
    }

    /// Set by `expire()`: cancelBatch() reaches only a batch already running, so one about to start must not.
    /// ponytail: shared by both tasks, so one expiring stops the other's batch too; split it if they ever overlap often.
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

    /// Batches until no file is waiting; long files' later parts are left for later. iOS expires a task whose progress
    /// stalls, so the counts drive it per file.
    @available(iOS 26.0, *)
    private static func runContinued(_ task: BGContinuedProcessingTask) {
        let retriever = AppServices.shared.retriever
        let monitor = AppServices.shared.monitor
        expired.withLock { $0 = false }
        task.expirationHandler = expire
        task.progress.totalUnitCount = 1
        monitor.continued = true
        let pending = OSAllocatedUnfairLock(initialState: 1)
        let progress = retriever.observeAll { snapshot in
            let snap = AppModel.Snap(snapshot)
            pending.withLock { $0 = snap.pending }
            task.progress.totalUnitCount = Int64(max(snap.total, 1))
            task.progress.completedUnitCount = Int64(snap.indexed + snap.skipped)
            task.updateTitle(Copy.continuedTitle, subtitle: Copy.continuedSubtitle(snap))
        }
        Task {
            var next = NextRun.soon
            while next == NextRun.soon, !expired.withLock({ $0 }) {
                next = (try? await retriever.continuedBatchSeconds(seconds: 120)) ?? NextRun.afterCooldown
            }
            progress.close()
            monitor.continued = false
            // Stopped early (too hot, or out of time) with files still waiting: not a finished read.
            let ok = !expired.withLock { $0 } && pending.withLock { $0 } == 0
            if !monitor.active { try? await retriever.release() }
            task.setTaskCompleted(success: ok)
            log.info("continued reading ended: \(next.name, privacy: .public), success \(ok, privacy: .public)")
            DispatchQueue.main.async { NotificationCenter.default.post(name: ended, object: continuedId) }
        }
    }
}
