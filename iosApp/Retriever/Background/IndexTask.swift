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
    static let continuedPattern = "\(continuedId).*"
    /// Posted on the main thread when a background batch has ended, so a foreground loop it blocked can run. The
    /// continued task posts it with `continuedId` as the object.
    static let ended = Notification.Name("app.retriever.backgroundBatchEnded")
    private static let log = Logger(subsystem: "app.retriever", category: "background")
    /// Set once, at launch, before anything schedules; nil in the unit-test host, which never registers.
    nonisolated(unsafe) private static var services: AppServices?
    /// Set once in register(); a batch launched with the app waits for it.
    nonisolated(unsafe) private static var staleSessionEnded: Task<Void, Never>?

    /// Call before the app finishes launching.
    static func register(services: AppServices) {
        self.services = services
        // Every host of an explicit session lives in this process, so one left by an earlier process is stale. Read now,
        // so the cleanup can't end a session started after it.
        let stale = services.retriever.readNowSession
        if stale != 0 {
            staleSessionEnded = Task { _ = try? await services.retriever.endReadNowSession(expectedSession: stale, cancelReads: { @Sendable in }) }
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
            let complete = Completion { task.setTaskCompleted(success: $0) }
            let run = Run(session: 0)
            task.expirationHandler = { expire(complete, run: run) }
            runBatch(run: run) { complete($0) }
        }
        if #available(iOS 26.0, *) {
            let ok = BGTaskScheduler.shared.register(forTaskWithIdentifier: continuedPattern, using: nil) { task in
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
        do {
            try await submit(processingRequest())
            log.info("background reading scheduled")
        } catch {
            log.error("couldn't schedule background reading: \(error, privacy: .private)")
        }
    }

    /// iOS 27's submit reports every error, including a continued read iOS won't start now (ImmediateRunIneligible).
    /// Nothing before register(): submitting an identifier this process never registered raises an exception `try`
    /// can't catch (the unit-test host skips register(), and a Settings toggle there crashed it). A test swaps this to
    /// see what is submitted, and when.
    nonisolated(unsafe) static var submit: @Sendable (BGTaskRequest) async throws -> Void = { request in
        guard IndexTask.services != nil else { return }
        if #available(iOS 27.0, *) {
            try await BGTaskScheduler.shared.submitTaskRequest(request)
        } else {
            try BGTaskScheduler.shared.submit(request)
        }
    }

    /// Only from a person's tap while the app is in front; it starts at once or throws. The simulator throws
    /// BGTaskScheduler error 1 (unavailable).
    @available(iOS 26.0, *)
    @concurrent static func submitContinued(session: Int64) async throws {
        let request = BGContinuedProcessingTaskRequest(identifier: "\(continuedId).\(session)", title: Copy.continuedTitle, subtitle: Copy.continuedSubtitle(0))
        // A queued request may wait while the open app stands aside, reading nothing.
        request.strategy = .fail
        // No .gpu: asking for it needs the Background GPU Access entitlement, which every signing profile must then carry.
        // Add the capability to the App ID, regenerate the profiles, and only then put both back. backendNow picks the
        // CPU once the app leaves.
        continuedGpu.withLock { $0 = false }
        try await submit(request)
    }

    private static let host = OSAllocatedUnfairLock<Run?>(initialState: nil)

    /// Per task, so no old task can stop a successor's loop.
    final class Run: Sendable {
        let session: Int64
        let expired = OSAllocatedUnfairLock(initialState: false)
        init(session: Int64) { self.session = session }
    }

    /// The continued request asked for the GPU, for the task it starts (runContinued).
    private static let continuedGpu = OSAllocatedUnfairLock(initialState: false)

    /// The expiration handler: Swift cancellation doesn't reach the Kotlin coroutine, so this stops the batch within
    /// one file and ends the native reads in flight, and runBatch then completes the task. A read that outlasts that
    /// (an iCloud fetch, a long OCR) can't hold the task past its time: `complete` reports failure after a short grace,
    /// and the batch's own completion is then a no-op. The next run is submitted here first: a run uses up its
    /// request, and the batch's own submit may come after the grace timer, too late.
    static func expire(_ complete: Completion, run: Run = Run(session: 0)) {
        log.info("background time is up; stopping the batch")
        run.expired.withLock { $0 = true }
        Task {
            if run.session == 0 {
                if let services { try? await services.retriever.cancelAutomaticBatch { services.extractor.cancelInFlight() } }
            } else {
                if let services {
                    _ = try? await services.retriever.endReadNowSession(expectedSession: run.session) { services.extractor.cancelInFlight() }
                }
            }
        }
        schedule()
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

    /// The task's work, apart from BGTask so the debug drill can run it where BGTaskScheduler can't.
    static func runBatch(run: Run = Run(session: 0), complete: @escaping @Sendable (Bool) -> Void) {
        guard let services else { return complete(false) }
        let retriever = services.retriever
        Task {
            await staleSessionEnded?.value
            guard await mayRunAutomaticBatch(services: services) else {
                await reschedule()
                complete(true)
                return
            }
            let generation = retriever.processingRequestGeneration
            var next: NextRun?
            let started = ContinuousClock.now
            do {
                try await retriever.refreshSetUp()
                if run.expired.withLock({ $0 }) {
                    next = NextRun.soon
                } else {
                    next = try await retriever.automaticBatchSeconds(seconds: 1500)
                    if next == NextRun.done {
                        if try await retriever.completeSetupSample(expectedSession: 0).boolValue {
                            next = NextRun.soon
                        } else {
                            _ = try await retriever.finishProcessingRequest(expectedGeneration: generation)
                        }
                    }
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
            if !retriever.uiVisible { try? await retriever.releaseIfIdle() }
            // Even when caught up: the next run reads photos taken meanwhile. A failed batch (.retry) rests like a
            // cooldown: iOS has no backoff to hand it to, so the next scheduled run is the retry.
            await reschedule()
            let ok = next != nil && !run.expired.withLock { $0 }
            complete(ok)
            log.info("background task completed, success \(ok)")
            DispatchQueue.main.async { NotificationCenter.default.post(name: ended, object: nil) }
        }
    }

    /// Not while the app is open or an explicit session, live or being handed off, owns the index.
    @MainActor private static func mayRunAutomaticBatch(services: AppServices) -> Bool {
        !services.monitor.active && !services.monitor.continued && services.lifecycle?.keepsReading() != true && host.withLock({ $0 == nil })
            && services.retriever.readNowSession == 0
    }

    /// Batches until the requested work is finished or resources stop it. iOS expires a task whose progress
    /// stalls, so the counts drive it per file.
    @available(iOS 26.0, *)
    private static func runContinued(_ task: BGContinuedProcessingTask) {
        let session = Int64(task.identifier.split(separator: ".").last ?? "") ?? 0
        @Sendable func posted() {
            DispatchQueue.main.async { NotificationCenter.default.post(name: ended, object: continuedId, userInfo: ["session": session]) }
        }
        guard let services, session != 0, services.retriever.readNowSession == session else {
            task.setTaskCompleted(success: false)
            // The app counts this handoff as continuing until it hears it ended.
            return posted()
        }
        let retriever = services.retriever
        let monitor = services.monitor
        let generation = retriever.processingRequestGeneration
        let run = Run(session: session)
        host.withLock {
            $0 = run
            monitor.continued = true
            monitor.backgroundGpu = continuedGpu.withLock { $0 }
        }
        task.progress.totalUnitCount = 1
        // Pending at the first snapshot: progress counts this read, not the whole library.
        let start = OSAllocatedUnfairLock<Int?>(initialState: nil)
        let progress = retriever.observeAll { @Sendable snapshot in
            guard !run.expired.withLock({ $0 }), retriever.readNowSession == session else { return }
            let snap = AppModel.Snap(snapshot)
            // Later passes are not file reads, so this bar counts each file's first pass.
            let waiting = snap.pending + snap.rereads
            let total = max(
                start.withLock {
                    $0 = $0 ?? waiting
                    return $0 ?? waiting
                }, waiting, 1)
            task.progress.totalUnitCount = Int64(total)
            task.progress.completedUnitCount = Int64(total - waiting)
            task.updateTitle(Copy.continuedTitle, subtitle: Copy.continuedSubtitle(Int(snapshot.counts.sessionRead)))
        }
        // The batch's end or expire's grace timer, once: release this run's hold, then tell iOS.
        let complete = Completion { ok in
            progress.close()
            host.withLock {
                guard $0 === run else { return }
                $0 = nil
                monitor.continued = false
                monitor.backgroundGpu = false
            }
            task.setTaskCompleted(success: ok)
            Task {
                _ = try? await retriever.endReadNowSession(expectedSession: session, cancelReads: { @Sendable in })
                if !monitor.active { try? await retriever.releaseIfIdle() }
                await reschedule()
                posted()
            }
        }
        task.expirationHandler = { expire(complete, run: run) }
        Task {
            var next = NextRun.soon
            var failed = false
            while next == NextRun.soon, !run.expired.withLock({ $0 }), retriever.readNowSession == session {
                do {
                    next = try await retriever.continuedBatchSeconds(seconds: 120, session: session)
                    if next == NextRun.done {
                        if try await retriever.completeSetupSample(expectedSession: session).boolValue {
                            next = NextRun.soon
                        } else {
                            _ = try await retriever.finishProcessingRequest(expectedGeneration: generation)
                        }
                    }
                } catch {
                    log.error("continued batch failed: \(error, privacy: .private)")
                    failed = true
                    next = NextRun.afterCooldown
                }
            }
            let ok = !run.expired.withLock { $0 } && !failed
            complete(ok)
            log.info(
                "continued reading ended: \(next.name, privacy: .public), stop \(retriever.stateNow.lastStop?.name ?? "none", privacy: .public), success \(ok, privacy: .public)"
            )
        }
    }
}
