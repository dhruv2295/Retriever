import Foundation
import MetricKit
import os
@preconcurrency import RetrieverCore

/// The one set of adapters and the Retriever facade the app, the background task and the self-test share.
final class AppServices: Sendable {
    /// The composition root: the real directories, and the reconnect to a download the last run left going.
    static let shared: AppServices = {
        let fm = FileManager.default
        let services = AppServices(
            support: fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0],
            caches: fm.urls(for: .cachesDirectory, in: .userDomainMask)[0],
            documents: fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        )
        MXMetricManager.shared.add(services.diagnostics)
        let restore: @MainActor () -> Void = { services.download.restore() }
        if Thread.isMainThread { MainActor.assumeIsolated(restore) } else { DispatchQueue.main.async(execute: restore) }
        return services
    }()

    static let isSimulator: Bool = {
        #if targetEnvironment(simulator)
            return true
        #else
            return false
        #endif
    }()

    let modelsDir: URL
    let cacheDir: URL
    let dataDir: URL
    let monitor: IosDeviceMonitor
    let photos: PhotosSource
    let folders: FolderSource
    let extractor: FileExtractor
    let download: ModelDownload
    let retriever: Retriever
    let diagnostics: Diagnostics
    @MainActor private(set) var lifecycle: Lifecycle?

    /// Everything the app keeps lives under these three directories, so a test passes temporary ones and never touches
    /// the real index or model.
    init(support: URL, caches: URL, documents: URL) {
        let fm = FileManager.default
        cacheDir = caches.appendingPathComponent("embed")
        dataDir = support.appendingPathComponent("Retriever")
        try? fm.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        modelsDir = support.appendingPathComponent("models")
        diagnostics = Diagnostics(file: dataDir.appendingPathComponent("problems.json"))
        download = ModelDownload(dir: modelsDir)
        monitor = IosDeviceMonitor(ramGb: IosDeviceProbeKt.iosDeviceProbe().ramGb)
        folders = FolderSource(documents: documents)
        // The extractor is built before the facade it asks, so it reads the setting through this slot.
        let facade = OSAllocatedUnfairLock<Retriever?>(initialState: nil)
        extractor = FileExtractor(folders: folders, allowMobileData: {
            (facade.withLock { $0 }?.prefs.value as? Prefs)?.allowMobileData == true
        })
        let logger = Logger(subsystem: "app.retriever", category: "core")
        photos = PhotosSource()
        let created = RetrieverIosKt.createRetriever(
            opener: MediaPipeOpener(),
            extractor: extractor,
            photos: photos,
            folders: folders,
            monitor: monitor,
            modelDir: modelsDir.path,
            cacheDir: cacheDir.path,
            dataDir: dataDir.path,
            isSimulator: Self.isSimulator,
            // Retriever's own folder in Files is always readable, so a person who keeps Photos off is set up too.
            platformReady: { @Sendable in KotlinBoolean(bool: true) },
            // Core's messages hold no names, paths or queries.
            log: { @Sendable in logger.info("\($0, privacy: .public)") }
        )
        facade.withLock { $0 = created }
        retriever = created
        let onMain: @MainActor () -> Void = { [self] in
            lifecycle = Lifecycle(monitor: monitor, retriever: retriever)
        }
        if Thread.isMainThread { MainActor.assumeIsolated(onMain) } else { DispatchQueue.main.async(execute: onMain) }
    }

    /// A fresh set under one temporary directory, for tests.
    static func temporary() -> AppServices {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("retriever-test-\(UUID().uuidString)")
        return AppServices(
            support: root.appendingPathComponent("support"),
            caches: root.appendingPathComponent("caches"),
            documents: root.appendingPathComponent("documents")
        )
    }
}
