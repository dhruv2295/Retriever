import Foundation
import os
import RetrieverCore

/// The one set of adapters and the Retriever facade the app, the background task and the self-test share.
final class AppServices {
    static let shared = AppServices()

    static let isSimulator: Bool = {
        #if targetEnvironment(simulator)
            return true
        #else
            return false
        #endif
    }()

    let modelsDir: URL
    let monitor: IosDeviceMonitor
    let photos: PhotosSource
    let folders: FolderSource
    let extractor: FileExtractor
    let download: ModelDownload
    let retriever: Retriever
    private(set) var lifecycle: Lifecycle?

    private init() {
        let fm = FileManager.default
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let caches = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("embed")
        let documents = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(at: caches, withIntermediateDirectories: true)
        modelsDir = support.appendingPathComponent("models")
        download = ModelDownload(dir: modelsDir)
        download.restore()
        monitor = IosDeviceMonitor(ramGb: IosDeviceProbeKt.iosDeviceProbe().ramGb)
        folders = FolderSource(documents: documents)
        extractor = FileExtractor(folders: folders)
        let logger = Logger(subsystem: "app.retriever", category: "core")
        let photos = PhotosSource()
        self.photos = photos
        retriever = RetrieverIosKt.createRetriever(
            opener: MediaPipeOpener(),
            extractor: extractor,
            photos: photos,
            folders: folders,
            monitor: monitor,
            modelDir: modelsDir.path,
            cacheDir: caches.path,
            isSimulator: Self.isSimulator,
            platformReady: { KotlinBoolean(bool: photos.isReady) },
            log: { logger.info("\($0, privacy: .public)") }
        )
        if Thread.isMainThread {
            lifecycle = Lifecycle(monitor: monitor, retriever: retriever)
        } else {
            DispatchQueue.main.async { self.lifecycle = Lifecycle(monitor: self.monitor, retriever: self.retriever) }
        }
    }
}
