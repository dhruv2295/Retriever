@preconcurrency import BackgroundTasks
import Foundation
@preconcurrency import RetrieverCore
import os

/// Reads files while the app is in the background: iOS runs it when it sees fit, overnight on the charger.
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
    /// Set once, at launch, before anything schedules; nil in the unit-test host, which never registers.
    nonisolated(unsafe) private static var services: AppServices?

    /// Call before the app finishes launching.
    static func register(services: AppServices) {
        self.services = services
        BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
            let complete = Completion { task.setTaskCompleted(success: $0) }
            task.expirationHandler = { expire(complete) }
            runBatch { complete($0) }
        }
        if #available(iOS 26.0, *) {
            let ok = BGTaskScheduler.shared.register(forTaskWithIdentifier: continuedId, using: nil) { task in
                guard let task = task as? BGContinuedProcessingTask else { return task.setTaskCompleted(success: false) }
                runContinued(task)
            }
            log.info("continued reading registered: \(ok, privacy: .public)")
        }
    }

    /// The overnight request. Always on the charger, whatever the schedule: `requiresExternalPower` is the only way to
    /// turn off iOS's CPU Monitor, which kills a background app that runs the model on the CPU for minutes, so on
    /// battery each run would end mid-file and count a try against it. "Also on battery" governs reading while the app
    /// is open and the read a person starts, where intensive work is allowed.
    static func processingRequest() -> BGProcessingTaskRequest {
        let request = BGProcessingTaskRequest(identifier: id)
        request.requiresExternalPower = true
        request.requiresNetworkConnectivity = false
        return request
    }

    /// Submitting again replaces the waiting request. Fails on the simulator, which has no BGTaskScheduler.
    static func schedule() {
        Task { await reschedule() }
    }

    /// Off the main thread, as iOS 27's submit asks.
    @concurrent private static func reschedule() async {
        // Submitting an identifier this process never registered raises an exception `try` can't catch: the unit-test
        // host skips register(), and a Settings toggle there crashed it.
        guard services != nil else { return }
        do {
            try await submit(processingRequest())
            log.info("background reading scheduled")
        } catch {
            log.error("couldn't schedule background reading: \(error, privacy: .private)")
        }
    }

    /// iOS 27's submit reports every error, including a continued read iOS won't start now (ImmediateRunIneligible).
    private static func submit(_ request: BGTaskRequest) async throws {
        if #available(iOS 27.0, *) {
            try await BGTaskScheduler.shared.submitTaskRequest(request)
        } else {
            try BGTaskScheduler.shared.submit(request)
        }
    }

    /// Only from a person's tap while the app is in front; it starts at once or throws. The simulator throws
    /// BGTaskScheduler error 1 (unavailable).
    @available(iOS 26.0, *)
    @concurrent static func submitContinued() async throws {
        let request = BGContinuedProcessingTaskRequest(identifier: continuedId, title: Copy.continuedTitle, subtitle: Copy.continuedSubtitle(0))
        // A queued request may wait while the open app stands aside, reading nothing.
        request.strategy = .fail
        // The GPU in the background where the device supports it (the Background GPU Access capability). Set before
        // submitting: the task starts at once. Without it, backendNow picks the CPU once the app leaves.
        if BGTaskScheduler.supportedResources.contains(.gpu) {
            request.requiredResources = .gpu
            continuedGpu.withLock { $0 = true }
            do {
                return try await submit(request)
            } catch {
                log.error("continued reading with the GPU refused: \(error, privacy: .private)")
                request.requiredResources = []
            }
        }
        continuedGpu.withLock { $0 = false }
        try await submit(request)
    }

    /// The continued request asked for the GPU, for the task it starts (runContinued).
    private static let continuedGpu = OSAllocatedUnfairLock(initialState: false)

    /// The expiration handler: Swift cancellation doesn't reach the Kotlin coroutine, so this stops the batch within
    /// one file and ends the native reads in flight, and runBatch then completes the task. A read that outlasts that
    /// (an iCloud fetch, a long OCR) can't hold the task past its time: `complete` reports failure after a short grace,
    /// and the batch's own completion is then a no-op.
    static func expire(_ complete: Completion? = nil) {
        log.info("background time is up; stopping the batch")
        expired.withLock { $0 = true }
        services?.retriever.cancelBatch()
        services?.extractor.cancelInFlight()
        guard let complete else { return }
        DispatchQueue.global().asyncAfter(deadline: .now() + .seconds(2)) {
            if complete(false) { log.notice("background task completed after the grace period") }
        }
    }

    /// Reports a task's end once: the batch's own end, or `expire`'s grace timer, whichever comes first.
    final class Completion: Sendable {
        private let complete: OSAllocatedUnfairLock<(@Sendable (Bool) -> Void)?>

        init(_ complete: @escaping @Sendable (Bool) -> Void) {
            self.complete = OSAllocatedUnfairLock(initialState: complete)
        }

        /// False when the task was already completed.
        @discardableResult
        func callAsFunction(_ success: Bool) -> Bool {
            let once = complete.withLock { c in
                defer { c = nil }
                return c
            }
            once?(success)
            return once != nil
        }
    }

    /// Set by `expire()`: cancelBatch() reaches only a batch already running, so one about to start must not.
    /// ponytail: shared by both tasks, so one expiring stops the other's batch too; split it if they ever overlap often.
    private static let expired = OSAllocatedUnfairLock(initialState: false)

    /// The task's work, apart from BGTask so the debug drill can run it where BGTaskScheduler can't.
    static func runBatch(complete: @escaping @Sendable (Bool) -> Void) {
        // The person's continued read holds the index; it schedules the next run itself when it ends.
        if services?.monitor.continued == true {
            Task {
                await reschedule()
                complete(true)
            }
            return
        }
        guard let retriever = services?.retriever else { return complete(false) }
        expired.withLock { $0 = false }
        Task {
            var next: NextRun?
            let started = ContinuousClock.now
            do {
                try await retriever.refreshSetUp()
                if expired.withLock({ $0 }) {
                    next = NextRun.soon
                } else {
                    next = try await retriever.indexBatchSeconds(seconds: 1500)
                }
            } catch {
                log.error("background batch failed: \(error, privacy: .private)")
            }
            let state = retriever.stateNow
            log.info(
                "background batch: \(next?.name ?? "failed", privacy: .public), backend \(state.backend?.name ?? "none", privacy: .public), processed \(state.processed)"
            )
            // Measured, so the estimate can count nights.
            if state.processed > 0 {
                try? await retriever.recordBackgroundRun(files: state.processed, seconds: (ContinuousClock.now - started).components.seconds)
            }
            // The foreground loop may already have taken over the index after our cancellation.
            if !retriever.uiVisible && !retriever.stateNow.running { try? await retriever.release() }
            // Even when caught up: the next run reads photos taken meanwhile. A failed batch (.retry) rests like a
            // cooldown: iOS has no backoff to hand it to, so the next scheduled run is the retry.
            await reschedule()
            let ok = next != nil && !expired.withLock { $0 }
            complete(ok)
            log.info("background task completed, success \(ok)")
            DispatchQueue.main.async { NotificationCenter.default.post(name: ended, object: nil) }
        }
    }

    /// Batches until no file is waiting; long files' later parts are left for later. iOS expires a task whose progress
    /// stalls, so the counts drive it per file.
    @available(iOS 26.0, *)
    private static func runContinued(_ task: BGContinuedProcessingTask) {
        guard let services else { return task.setTaskCompleted(success: false) }
        let retriever = services.retriever
        let monitor = services.monitor
        expired.withLock { $0 = false }
        let complete = Completion { task.setTaskCompleted(success: $0) }
        task.expirationHandler = {
            expire(complete)
            Task { try? await services.retriever.endReadNowSession() }
        }
        task.progress.totalUnitCount = 1
        monitor.continued = true
        monitor.backgroundGpu = continuedGpu.withLock { $0 }
        let pending = OSAllocatedUnfairLock(initialState: 1)
        // Pending at the first snapshot: progress counts this read, not the whole library.
        let start = OSAllocatedUnfairLock<Int?>(initialState: nil)
        let progress = retriever.observeAll { @Sendable snapshot in
            let snap = AppModel.Snap(snapshot)
            // Later passes are left out: with the screen on in another app they do not run, so they would hold the bar short.
            let waiting = snap.pending + snap.rereads
            pending.withLock { $0 = waiting }
            let total = max(
                start.withLock {
                    $0 = $0 ?? waiting
                    return $0 ?? waiting
                }, waiting, 1)
            task.progress.totalUnitCount = Int64(total)
            task.progress.completedUnitCount = Int64(total - waiting)
            task.updateTitle(Copy.continuedTitle, subtitle: Copy.continuedSubtitle(Int(snapshot.counts.sessionRead)))
        }
        Task {
            var next = NextRun.soon
            while next == NextRun.soon, !expired.withLock({ $0 }) {
                next = (try? await retriever.continuedBatchSeconds(seconds: 120)) ?? NextRun.afterCooldown
            }
            progress.close()
            monitor.continued = false
            monitor.backgroundGpu = false
            // Stopped early (too hot, or out of time) with files still waiting: not a finished read.
            let ok = !expired.withLock { $0 } && pending.withLock { $0 } == 0
            if !monitor.active { try? await retriever.release() }
            complete(ok)
            log.info("continued reading ended: \(next.name, privacy: .public), success \(ok, privacy: .public)")
            DispatchQueue.main.async { NotificationCenter.default.post(name: ended, object: continuedId) }
        }
    }
}
