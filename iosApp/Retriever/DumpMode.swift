#if DEBUG
    import Foundation
    import RetrieverCore

    /// `-dump` launch argument: the relevance-fit dump (scripts/ios-dump.sh). Reads Library/Caches/bench (manifest.json,
    /// queries.json, library/), embeds it on CPU with `-dumpModel M440|M740` (default M440) at `-dumpVt` vision tokens
    /// (default 70), and writes Library/Caches/bench/dump-<model>-vt<vt>.bin, resuming an earlier run. `-dumpLimit N`
    /// keeps N files of each kind and N queries. No Retriever, so nothing else indexes or competes for the model.
    /// Prints DUMP DONE and exits.
    enum DumpMode {
        static func run() {
            Task.detached {
                let fm = FileManager.default
                let dir = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("bench")
                let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                let args = UserDefaults.standard
                // folder://documents/ resolves under this source's root, so the library is read by the app's own extractor.
                let folders = FolderSource(documents: dir)
                var code: Int32 = 0
                do {
                    let path = try await DumpIosKt.runDump(
                        extractor: FileExtractor(folders: folders, allowMobileData: { false }),
                        opener: MediaPipeOpener(),
                        modelDir: support.appendingPathComponent("models").path,
                        cacheDir: fm.temporaryDirectory.appendingPathComponent("dump-embed").path,
                        variant: args.string(forKey: "dumpModel") ?? "M440",
                        visionTokens: Int32(args.object(forKey: "dumpVt") == nil ? 70 : args.integer(forKey: "dumpVt")),
                        dir: dir.path,
                        limit: Int32(args.integer(forKey: "dumpLimit")),
                        log: { say($0) }
                    )
                    say("DONE \(path)")
                } catch {
                    say("FAILED \(error)")
                    code = 1
                }
                exit(code)
            }
        }

        private static func say(_ line: String) {
            print("DUMP \(line)")
            fflush(stdout)
        }
    }
#endif
