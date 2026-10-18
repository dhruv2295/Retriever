#if DEBUG
    import Foundation
    import RetrieverCore

    /// `-eval` launch argument: the retrieval eval over Documents/eval/corpus with Documents/eval/queries.json, on the
    /// real model (M440 in Application Support/models). Writes Documents/eval/report.md, prints EVAL DONE and exits.
    enum EvalMode {
        static func run() {
            let s = AppServices.shared  // on main, so the lifecycle observer is set up first
            Task.detached {
                let fm = FileManager.default
                let dir = fm.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("eval")
                let queries = dir.appendingPathComponent("queries.json")
                let report = dir.appendingPathComponent("report.md")
                // Out of Documents while the app indexes it: it names every query and file and would top the keyword lane.
                let parked = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("eval-queries.json")
                var code: Int32 = 0
                do {
                    if !fm.fileExists(atPath: queries.path), fm.fileExists(atPath: parked.path) { try fm.moveItem(at: parked, to: queries) }
                    let json = try String(contentsOf: queries, encoding: .utf8)
                    try? fm.removeItem(at: parked)
                    try fm.moveItem(at: queries, to: parked)
                    try? fm.removeItem(at: report)
                    guard s.download.verifyInPlace(ModelVariant.m440.generic) else { throw EvalError.noModel }
                    s.monitor.active = true
                    s.retriever.uiVisible = true
                    let markdown = try await EvalIosKt.runEval(
                        retriever: s.retriever,
                        folders: s.folders,
                        extractor: s.extractor,
                        opener: MediaPipeOpener(),
                        modelDir: s.modelsDir.path,
                        cacheDir: fm.temporaryDirectory.appendingPathComponent("eval-embed").path,
                        isSimulator: AppServices.isSimulator,
                        queriesJson: json,
                        log: { say($0) }
                    )
                    try markdown.write(to: report, atomically: true, encoding: .utf8)
                    say("EVAL DONE \(report.path)")
                } catch {
                    say("EVAL FAILED \(error)")
                    code = 1
                }
                try? fm.moveItem(at: parked, to: queries)
                exit(code)
            }
        }

        private enum EvalError: Error { case noModel }

        private static func say(_ line: String) {
            print("EVAL \(line)")
            fflush(stdout)
        }
    }
#endif
