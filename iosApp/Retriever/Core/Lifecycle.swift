import RetrieverCore
import UIKit

/// Feeds foreground and battery state to the Kotlin monitor and facade. Create on the main thread.
final class Lifecycle {
    private let monitor: IosDeviceMonitor
    private let retriever: Retriever
    private var tokens: [NSObjectProtocol] = []

    init(monitor: IosDeviceMonitor, retriever: Retriever) {
        self.monitor = monitor
        self.retriever = retriever
        UIDevice.current.isBatteryMonitoringEnabled = true
        let center = NotificationCenter.default
        let observe: (Notification.Name, @escaping () -> Void) -> Void = { name, action in
            self.tokens.append(center.addObserver(forName: name, object: nil, queue: .main) { _ in action() })
        }
        observe(UIApplication.didBecomeActiveNotification) { [weak self] in self?.setActive(true) }
        observe(UIApplication.willResignActiveNotification) { [weak self] in self?.setActive(false) }
        observe(UIApplication.didEnterBackgroundNotification) { [weak self] in self?.release() }
        observe(UIDevice.batteryStateDidChangeNotification) { [weak self] in self?.readBattery() }
        observe(UIDevice.batteryLevelDidChangeNotification) { [weak self] in self?.readBattery() }
        setActive(UIApplication.shared.applicationState == .active)
        readBattery()
    }

    deinit {
        tokens.forEach(NotificationCenter.default.removeObserver)
    }

    private func setActive(_ active: Bool) {
        monitor.active = active
        retriever.uiVisible = active
        // A batch begun in front must not carry on into the background, where iOS refuses its GPU work.
        if !active { retriever.cancelBatch() }
    }

    /// Frees the model's memory once the app is in the background. Under a background task, so the embed still
    /// in flight finishes before the model closes.
    private func release() {
        var task = UIBackgroundTaskIdentifier.invalid
        let end = {
            guard task != .invalid else { return }
            UIApplication.shared.endBackgroundTask(task)
            task = .invalid
        }
        task = UIApplication.shared.beginBackgroundTask(withName: "release-model") { end() }
        retriever.release { _ in DispatchQueue.main.async { end() } }
    }

    private func readBattery() {
        let device = UIDevice.current
        // .unknown is the simulator: not charging, full. A visible app indexes regardless of charging.
        monitor.charging = device.batteryState == .charging || device.batteryState == .full
        monitor.batteryPct = device.batteryLevel < 0 ? 100 : Int32((device.batteryLevel * 100).rounded())
    }
}
