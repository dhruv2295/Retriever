import Observation
import Photos
import PhotosUI
@preconcurrency import RetrieverCore
import SwiftUI
import UIKit
import os

/// The scene phase and the foreground reading loop.
extension AppModel {
    // MARK: Phase

    func setPhase(_ phase: ScenePhase) {
        guard let services else { return }
        switch phase {
        case .active:
            // Back from Control Center, a banner or the app switcher, nothing stopped, so there's nothing to redo.
            guard !active else { return }
            active = true
            // Scene phase can arrive before Lifecycle's notifications; establish visibility before the batch checks it.
            services.monitor.active = true
            services.retriever.uiVisible = true
            photos = services.photos.status
            services.lifecycle?.observePhotos()
            // A background batch may still hold the index; it stops within a file and its task wakes the loop.
            // The person's own continued read is left to finish; its end restarts the loop.
            if !continuing { services.retriever.cancelBatch() }
            Task {
                try? await services.retriever.warmSearch()
                recent = (try? await services.retriever.recentQueries()) ?? recent
            }
            advance()
        case .background:
            stopLoop()
            if tune == .running { tuneLeft = true }
            // Even when caught up: photos taken before the next open get read overnight.
            if snap?.accessChosen == true { IndexTask.schedule() }
        default:
            break
        }
    }

    // MARK: Foreground loop

    func startLoop() {
        guard let services, active, setup?.libraryChosen == true, !continuing, loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                self?.keepScreenOn(true)
                let next = try? await services.retriever.foregroundBatchSeconds(seconds: 120)
                self?.keepScreenOn(false)
                if Task.isCancelled {
                    Self.log.info("foreground reading stopped")
                    // This batch held the index until now, so a newer loop may have found it locked and gone to sleep.
                    self?.wake()
                    return
                }
                guard let self else { return }
                if next == NextRun.soon { continue }
                // .done, .whenIdle, .whenCharging: nothing to do until something changes. A failed batch (.retry) rests like heat does.
                let rests = next == NextRun.afterCooldown || next == NextRun.retry
                await sleepUntilWoken(timeout: rests ? .seconds(ThermalRest.shared.RECHECK_SECONDS) : nil)
            }
        }
    }

    func stopLoop() {
        active = false
        loop?.cancel()
        loop = nil
        // Swift cancellation doesn't reach the Kotlin coroutine. The continued read is the person's: it goes on.
        if !continuing { services?.retriever.cancelBatch() }
        keepScreenOn(false)
    }

    /// On while files wait to be read, on battery too: a locked iPhone sends the app to the background, which stops
    /// reading. Off when only long files' later parts are left, when the iPhone runs hot, and on a low battery or in
    /// Low Power Mode unless it is charging: reading goes on, and the screen locks as the person set it to.
    func keepScreenOn(_ on: Bool) {
        reading = on
        let info = ProcessInfo.processInfo
        let hot = info.thermalState == .serious || info.thermalState == .critical
        let monitor = services?.monitor
        let power = monitor?.charging == true || ((monitor?.batteryPct ?? 100) > 20 && !info.isLowPowerModeEnabled)
        screenKeptOn =
            on && keepScreenAwakeForSession && (snap?.readNowUntil ?? 0) > Int64(Date.now.timeIntervalSince1970 * 1000) && !hot && power
            && !info.isLowPowerModeEnabled && (snap?.pending ?? 0) > 0
        UIApplication.shared.isIdleTimerDisabled = screenKeptOn
        Self.log.info("isIdleTimerDisabled \(self.screenKeptOn, privacy: .public)")
    }

    /// Status's "Keep reading after I leave", and Setup's done button: hands reading to a task iOS lets carry on
    /// after the person leaves, with its progress on the Lock Screen.
    func keepReading() {
        guard #available(iOS 26.0, *), let services, active, !continuing, let snap else { return }
        // Submitted in the tap itself: iOS takes it only from an app in front. The task waits for this loop's batch,
        // which stops within its current file.
        do {
            try IndexTask.submitContinued(snap)
            Self.log.info("continued reading submitted")
        } catch {
            Self.log.error("couldn't keep reading after leaving: \(error, privacy: .private)")
            readingMessage =
                "iOS couldn’t keep processing while you weren’t using Retriever. Keep it open, or let it process your files while your \(Copy.device) charges."
            return
        }
        continuing = true
        loop?.cancel()
        loop = nil
        services.retriever.cancelBatch()
        keepScreenOn(false)
    }

    /// Something changed (a photo, a folder, Resume, setup finishing): run the next batch now.
    func wake() {
        if let waiter {
            self.waiter = nil
            waiter.resume()
        } else {
            woken = true
        }
    }

    private func sleepUntilWoken(timeout: Duration?) async {
        if woken {
            woken = false
            return
        }
        let timer = timeout.map { t in
            Task { [weak self] in
                try? await Task.sleep(for: t)
                if !Task.isCancelled { self?.wake() }
            }
        }
        await withTaskCancellationHandler {
            await withCheckedContinuation { waiter = $0 }
        } onCancel: {
            Task { @MainActor [weak self] in self?.wake() }
        }
        timer?.cancel()
    }
}
