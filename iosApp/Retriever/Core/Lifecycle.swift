import Photos
import RetrieverCore
import UIKit

/// Feeds foreground, battery and photo-library changes to the Kotlin monitor and facade. Lives as long as the app
/// (AppServices holds it), so its observers are never removed.
@MainActor
final class Lifecycle: NSObject, PHPhotoLibraryChangeObserver {
    private let monitor: IosDeviceMonitor
    private let retriever: Retriever
    private var observingPhotos = false
    private var releaseTask = UIBackgroundTaskIdentifier.invalid

    /// Called after the photo library changes, so the reading loop can wake.
    var onLibraryChange: (() -> Void)?
    /// A read the person started carries on after they leave (IndexTask.continuedId).
    var keepsReading: () -> Bool = { false }

    init(monitor: IosDeviceMonitor, retriever: Retriever) {
        self.monitor = monitor
        self.retriever = retriever
        super.init()
        UIDevice.current.isBatteryMonitoringEnabled = true
        // Inactive (Control Center, a banner, the app switcher) is still in front: reading and the GPU carry on.
        observe(UIApplication.willEnterForegroundNotification) { [weak self] in self?.enterForeground() }
        observe(UIApplication.didBecomeActiveNotification) { [weak self] in self?.enterForeground() }
        observe(UIApplication.didEnterBackgroundNotification) { [weak self] in self?.enterBackground() }
        observe(IndexTask.ended) { [weak self] in
            if UIApplication.shared.applicationState == .background { self?.retriever.uiVisible = false }
        }
        // The model reopens on the next batch; under pressure the system would sooner kill the app than wait.
        // Not while the app is in front or a continued read goes on: the next batch would only reopen it.
        observe(UIApplication.didReceiveMemoryWarningNotification) { [weak self] in
            guard let self, !monitor.active, !keepsReading() else { return }
            release()
            // The vector table too: the next search loads it again.
            retriever.trimMemory { _ in }
        }
        observe(UIDevice.batteryStateDidChangeNotification) { [weak self] in self?.readBattery() }
        observe(UIDevice.batteryLevelDidChangeNotification) { [weak self] in self?.readBattery() }
        if UIApplication.shared.applicationState != .background { enterForeground() }
        readBattery()
        observePhotos()
    }

    private func observe(_ name: Notification.Name, _ action: @escaping @MainActor () -> Void) {
        _ = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated(action)
        }
    }

    /// Registers once access is granted; before that, touching the library could ask for access.
    func observePhotos() {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard !observingPhotos, status == .authorized || status == .limited else { return }
        observingPhotos = true
        PHPhotoLibrary.shared().register(self)
    }

    /// Off the main thread. The running batch syncs again when it ends; an idle loop wakes.
    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        DispatchQueue.main.async {
            self.retriever.notifyChanged()
            self.onLibraryChange?()
        }
    }

    private func enterForeground() {
        monitor.active = true
        retriever.uiVisible = true
    }

    func enterBackground() {
        // The model reopens on the CPU: iOS refuses GPU work in the background, unless it granted a continued read the GPU.
        monitor.active = false
        // A batch begun in front must not carry on here, where iOS refuses its GPU work. A continued read's loop
        // starts its next batch, which opens the model on the CPU; one granted the GPU carries on as it is.
        if !(keepsReading() && monitor.backgroundGpu) { retriever.cancelBatch() }
        // The person asked for this read and watches it on the Lock Screen, so it goes on as if open, on battery too.
        guard !keepsReading() else { return }
        retriever.uiVisible = false
        let session = retriever.readNowSession
        guard session != 0 else { return release() }
        holdRelease()
        Task {
            _ = try? await retriever.endReadNowSession(expectedSession: session, cancelReads: { @Sendable in })
            release()
        }
    }

    /// Frees the model's memory once the app is in the background. Under a background task, so the embed still
    /// in flight finishes before the model closes.
    private func release() {
        holdRelease()
        retriever.releaseIfIdle { @Sendable [weak self] _ in DispatchQueue.main.async { self?.endRelease() } }
    }

    // ponytail: one task covers overlapping releases; the first to finish ends it.
    private func holdRelease() {
        guard releaseTask == .invalid else { return }
        releaseTask = UIApplication.shared.beginBackgroundTask(withName: "release-model") { [weak self] in self?.endRelease() }
    }

    private func endRelease() {
        guard releaseTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(releaseTask)
        releaseTask = .invalid
    }

    private func readBattery() {
        let device = UIDevice.current
        // .unknown is the simulator: not charging, full.
        monitor.charging = device.batteryState == .charging || device.batteryState == .full
        monitor.batteryPct = device.batteryLevel < 0 ? 100 : Int32((device.batteryLevel * 100).rounded())
    }
}
