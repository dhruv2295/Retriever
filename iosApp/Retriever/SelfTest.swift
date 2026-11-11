#if DEBUG
    import Foundation
    import Photos
    @preconcurrency import RetrieverCore
    import UIKit
    import ZIPFoundation

    /// `-selftest` launch argument: the adapters and the facade end to end on a simulator, printing SELFTEST lines and
    /// exiting 0 on pass, 1 on fail. Needs the M440 file in Application Support/models, Photos access granted and a
    /// few images added with simctl addmedia (`-selftestPhotos N` sets how many to expect).
    enum SelfTest {
        static func run() {
            let services = AppServices.shared  // on main, so the lifecycle observer is set up first
            DispatchQueue.global().asyncAfter(deadline: .now() + 1800) {
                say("FAIL timed out")
                exit(1)
            }
            Task.detached {
                var failures: [String] = []
                func check(_ name: String, _ ok: Bool, _ detail: String) {
                    say("\(ok ? "ok  " : "FAIL") \(name): \(detail)")
                    if !ok { failures.append(name) }
                }
                do {
                    try await body(services, check)
                } catch {
                    check("error", false, "\(error)")
                }
                say(failures.isEmpty ? "PASS" : "FAIL \(failures.joined(separator: ", "))")
                exit(failures.isEmpty ? 0 : 1)
            }
        }

        private static func body(_ s: AppServices, _ check: (String, Bool, String) -> Void) async throws {
            let build = ModelVariant.m440.generic
            let modelPath = s.modelsDir.appendingPathComponent(build.fileName).path
            say("model \(modelPath) simulator=\(AppServices.isSimulator)")
            let verified = s.download.verifyInPlace(build)
            check("(e) model verified in place", verified, "sha256 \(build.sha256.prefix(12))…")
            guard verified else { return }

            // (a) Text and image land in one space.
            guard let dogJpeg = UIImage(named: "IllusEmpty")?.jpegData(compressionQuality: 0.85) else {
                return check("(a) embed", false, "IllusEmpty asset missing")
            }
            let cache = FileManager.default.temporaryDirectory.appendingPathComponent("selftest-embed").path
            let opener = MediaPipeOpener()
            guard let embedder = opener.open(modelPath: modelPath, gpu: false, visionGpu: false, visionTokens: 70, maxTokens: 256, cacheDir: cache) else {
                return check("(a) embed", false, "open failed: \(opener.lastMessage ?? "?")")
            }
            let query = { (q: String) in floats(embedder.embed(parts: [Prefix.shared.query(q: q)])) }
            let dogText = query("a cartoon dog")
            let sheetText = query("a spreadsheet of numbers")
            let dogImage = floats(embedder.embed(parts: [dogJpeg]))
            let mixed = floats(embedder.embed(parts: ["a photo", dogJpeg]))
            embedder.close()  // calibration loads its own copy
            let dogCos = cosine(dogText, dogImage)
            let sheetCos = cosine(sheetText, dogImage)
            check(
                "(a) embed", dogImage.count >= 256 && mixed.count == dogImage.count && dogCos > sheetCos,
                "dims \(dogImage.count) (mixed \(mixed.count)), cos(dog text, dog image) \(fmt(dogCos)) > cos(spreadsheet, dog image) \(fmt(sheetCos))")

            // (b) OCR of a rendered image, read back through folder://.
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let ocrFile = docs.appendingPathComponent("selftest-ocr.png")
            try textImage("Invoice 48213 total 1250").pngData()?.write(to: ocrFile)
            let ocr = s.extractor.scan(uri: "folder://documents/selftest-ocr.png", page: -1, jpegSide: FileExtractor.embedSide)?.text ?? ""
            try? FileManager.default.removeItem(at: ocrFile)
            check("(b) OCR", ocr.contains("48213"), "\"\(ocr.replacingOccurrences(of: "\n", with: " | "))\"")

            // (c) A PDF and a docx dropped into Documents.
            let pdfName = "Invoice-48213.pdf"
            let docxName = "Lighthouse-notes.docx"
            try makePdf(docs.appendingPathComponent(pdfName), "Invoice 48213. Total due 1250 rupees for garden supplies, payable within thirty days.")
            try makeDocx(docs.appendingPathComponent(docxName), "Meeting notes on the lighthouse renovation budget and the new paint schedule.")
            let folderFiles = s.folders.changes(since: nil, known: KnownMap([:])).upserted
            let pdf = folderFiles.first { $0.name == pdfName }
            let docx = folderFiles.first { $0.name == docxName }
            check(
                "(c) folder scan", pdf?.kind == Kind.pdf && docx?.kind == Kind.doc,
                "\(folderFiles.count) files; \(pdf?.uri ?? "no pdf") [\(pdf?.kind.name ?? "-")], \(docx?.uri ?? "no docx") [\(docx?.kind.name ?? "-")] mime \(docx?.mime ?? "-")"
            )
            let pdfText = pdf.flatMap { s.extractor.pdfPageText(uri: $0.uri, page: 0) } ?? ""
            let docxXml = docx.flatMap { s.extractor.zipEntryText(uri: $0.uri, name: "word/document.xml", maxBytes: 4096) } ?? ""
            check(
                "(c) native text", pdfText.contains("48213") && docxXml.contains("lighthouse"),
                "pdf p.1 \"\(head(pdfText))\", pages \(pdf.map { s.extractor.pdfPageCount(uri: $0.uri) } ?? -1); docx xml \(docxXml.count) bytes")

            // (d) Photos.
            let wanted = max(1, UserDefaults.standard.integer(forKey: "selftestPhotos"))
            let photos = s.photos.changes(since: nil, known: KnownMap([:]))
            check(
                "(d) Photos scan", photos.upserted.count >= wanted,
                "status \(s.photos.status.rawValue), \(photos.upserted.count) assets (expected >= \(wanted)), token \(photos.nextToken.isEmpty ? "none" : "\(photos.nextToken.count) chars")"
            )
            let resources = s.photos.lastResourceLookups
            say("first scan: looked up \(s.photos.lastLookups) of \(s.photos.lastAssets) assets, \(resources) through their resources")
            for f in photos.upserted { say("  photo \(f.name) \(f.size) B \(f.mime) [\(f.kind.name)]") }
            let synthetic = photos.upserted.filter { $0.name.range(of: #"^(Photo|Screenshot|Video) \d{4}-"#, options: .regularExpression) != nil }
            // iOS 27 prefetches each name with its asset: no asset's resources are listed.
            var prefetched = true
            if #available(iOS 27.0, *) { prefetched = resources == 0 }
            check(
                "(d) real names", synthetic.isEmpty && s.photos.lastLookups == s.photos.lastAssets && prefetched,
                "\(photos.upserted.count - synthetic.count) of \(photos.upserted.count) photos have an original name; \(resources) resource lookups")

            // (e) Calibrate, index, search through the facade.
            let images = photos.upserted.prefix(2).compactMap { s.extractor.imageJpeg(uri: $0.uri, maxSide: FileExtractor.embedSide) } + [dogJpeg]
            let texts = ["Invoice for garden supplies", "Notes from the lighthouse meeting", "A recipe for lemon rice"]
            let calibration = try await s.retriever.calibrate(samples: RetrieverIosKt.samples(images: images, texts: texts), tuneAgain: false)
            let p = calibration.profile
            say(
                "calibrated: chose \(calibration.chosen.name), tier \(p.tier.name), vision tokens \(p.visionTokens), max tokens \(p.maxTokens); "
                    + calibration.backends.map { "\($0.backend.name) ok=\($0.ok) load \(Int($0.loadMs)) ms \($0.error ?? "")" }.joined(separator: "; "))

            for (label, file) in [("pdf", pdf), ("docx", docx)] {
                guard let file else { continue }
                let pieces = try await IosExtractor(native: s.extractor).extract(file: file, profile: p)
                let text = pieces.flatMap { $0.parts.compactMap { ($0 as? PartText)?.text } }.joined(separator: " ")
                check(
                    "(c) IosExtractor \(label)", text.contains(label == "pdf" ? "48213" : "lighthouse"),
                    "\(pieces.count) pieces, locators \(pieces.map(\.locator)), text \"\(head(text))\"")
            }

            // The made-up invoice would outrank the seeded one for "invoice total", so it leaves before indexing.
            for name in [pdfName, docxName] { try? FileManager.default.removeItem(at: docs.appendingPathComponent(name)) }

            s.monitor.active = true
            s.retriever.uiVisible = true
            // Later parts wait for the charger unless the person asks to process now (ReadingPolicy.blocker), and the
            // simulator is never on one: Start, so the whole index, deepen pass included, is built here.
            _ = try await s.retriever.startProcessing(setup: false)
            let next = try await indexAll(s).next
            let snap = await snapshot(s.retriever)
            let byKind = snap.counts.byKind.map { "\($0.key.name)=\($0.value)" }.sorted().joined(separator: " ")
            let indexed = snap.counts.byKind.values.reduce(0) { $0 + $1.intValue }
            let byState = snap.counts.byState.map { "\($0.key.name)=\($0.value)" }.sorted().joined(separator: " ")
            check("(e) indexed", next == .done, "next \(next.name); by kind \(byKind); by state \(byState)")

            // (f) A second full scan finds every photo already known: no resource lookups.
            let known = KnownMap(try await storedFiles(s.retriever))
            _ = s.photos.changes(since: nil, known: known)
            say("second scan: looked up \(s.photos.lastLookups) of \(s.photos.lastAssets) assets")
            check(
                "(f) second scan", s.photos.lastLookups == 0 && s.photos.lastAssets >= wanted,
                "\(s.photos.lastLookups) lookups for \(s.photos.lastAssets) assets")

            // (g) Ranking: the right file first, and a strong match, not just somewhere in the list.
            let labelled: [(query: String, file: String, locator: String?)] = [
                ("dog playing on grass", "IMG_1001", nil),
                ("sunset by the sea", "IMG_1003", nil),
                ("solar panels cut our power bill", "Document_03.pdf", "p. 3"),
                ("invoice total", "Document_01.pdf", nil),
                ("IMG_1007", "IMG_1007", nil),
            ]
            for l in labelled {
                let r = try await s.retriever.search(q: l.query, kinds: nil, limit: 20)
                let rank = r.hits.firstIndex { rightFile($0, l.file) && (l.locator == nil || $0.locator == l.locator) }
                let first = r.hits.first
                check(
                    "(g) rank \"\(l.query)\"", rank == 0 && r.strongCount > 0,
                    "want \(l.file)\(l.locator.map { " at \($0)" } ?? ""): hits[0] \(first?.file.name ?? "-") \(first?.locator ?? "") rank \(rank.map { "\($0 + 1)" } ?? "none") score \(rank.map { fmt3(r.hits[$0].score) } ?? "-") strong \(r.strongCount) vector=\(r.vectorLane)"
                )
            }

            // (g) Nothing here is about swimming. The relevance rule ships off until it is fitted on public data, so the
            // nearest files still come back, none dropped: the best LIKELY_MEANING_FILES by cosine stay strong and the rest
            // fold into the tail, so at least three stay strong. With the rule on, the shipped
            // split applies.
            let none = try await s.retriever.search(q: "kids swimming in a pool", kinds: nil, limit: 20)
            check(
                "(g) no match \"kids swimming in a pool\"",
                none.vectorLane && !none.hits.isEmpty
                    && (RankingKt.RELEVANCE_RULE_ENABLED || (none.hits.count == min(20, indexed) && none.strongCount >= min(3, none.hits.count))),
                "strong \(none.strongCount) of \(none.hits.count) hits (\(indexed) indexed); top \(top(none))")

            // (h) Largest first. Photos carry no byte size on iOS, so only the order is checked.
            let largest = try await s.retriever.files(kind: nil, folder: nil, sort: .largest, limit: 100, offset: 0)
            let sizes = largest.map(\.file.size)
            say("largest: " + largest.prefix(4).map { "\($0.file.name) \($0.file.size)" }.joined(separator: "; "))
            check("(h) largest first", sizes == sizes.sorted(by: >), "\(largest.count) files descending")

            // (i) Duplicates cover folder files only: the seeded photos must not form a group.
            let groups = try await s.retriever.duplicates(minSize: 64 * 1024)
            say("duplicates: " + groups.map { "[\($0.files.map(\.name).joined(separator: ", ")) @ \($0.size) B]" }.joined(separator: " "))
            check("(i) duplicates", groups.allSatisfy { g in g.files.allSatisfy { !$0.uri.hasPrefix("ph://") } }, "\(groups.count) folder group(s), no photos")

            // (j) An edit puts one photo back in the queue and nothing else.
            guard let target = largest.first(where: { rightFile($0.file, "IMG_1006") }) else {
                return check("(j) edit", false, "IMG_1006 is not indexed")
            }
            let before = try await state(of: target.file.uri, s.retriever)
            try await edit(localId: String(target.file.uri.dropFirst("ph://".count)))
            try await s.retriever.pause()
            _ = try await s.retriever.indexBatchSeconds(seconds: 60)  // syncs, indexes nothing
            let editLookups = s.photos.lastLookups
            let afterEdit = try await state(of: target.file.uri, s.retriever)
            let others = try await storedFiles(s.retriever).values.filter { $0.kind == .photo && $0.uri != target.file.uri }
            var untouched = 0
            for f in others {
                if try await state(of: f.uri, s.retriever) == .indexed { untouched += 1 }
            }
            say(
                "edit: \(target.file.name) \(before.name) -> \(afterEdit.name), \(editLookups) lookup(s), \(untouched) of \(others.count) other photos still indexed"
            )
            check(
                "(j) edit detected", before == .indexed && afterEdit == .pending && (1...others.count + 1).contains(editLookups) && untouched == others.count,
                "\(target.file.name) \(before.name) -> \(afterEdit.name); others untouched \(untouched)/\(others.count)")
            try await s.retriever.resume()
            // The edit's PhotoKit notification (Lifecycle.photoLibraryDidChange) can make round 1 end SOON, so a later round
            // may index 0; judge the summed count and the end state, not the last batch.
            let reindexed = try await indexAll(s).processed
            let finalState = try await state(of: target.file.uri, s.retriever)
            let othersAfter = try await storedFiles(s.retriever).values.filter { $0.kind == .photo && $0.uri != target.file.uri }
            var stillIndexed = 0
            for f in othersAfter {
                if try await state(of: f.uri, s.retriever) == .indexed { stillIndexed += 1 }
            }
            if reindexed == 0 { say("edit: target was already re-indexed before this step's batches (another batch got there first)") }
            check(
                "(j) edit re-indexed", finalState == .indexed && reindexed <= 1 && stillIndexed == othersAfter.count,
                "\(target.file.name) \(finalState.name), \(reindexed) file(s) indexed over the rounds, others indexed \(stillIndexed)/\(othersAfter.count)")
        }

        // MARK: Steps

        /// Runs batches until done; `processed` is summed over the rounds, since each batch resets the state's count.
        private static func indexAll(_ s: AppServices) async throws -> (next: NextRun, processed: Int) {
            var next = NextRun.soon
            var total = 0
            for round in 1...5 where next != .done {
                next = try await s.retriever.indexBatchSeconds(seconds: 180)
                let st = s.retriever.stateNow
                total += Int(st.processed)
                say(
                    "batch \(round): next \(next.name), processed \(st.processed), failed \(st.failed), backend \(st.backend?.name ?? "-"), stop \(st.lastStop?.name ?? "-") \(st.lastError ?? "")"
                )
                if next == .whenCharging || next == .whenIdle { break }
            }
            return (next, total)
        }

        private static func storedFiles(_ retriever: Retriever) async throws -> [String: SourceFile] {
            let all = try await retriever.files(kind: nil, folder: nil, sort: .newest, limit: 100_000, offset: 0)
            return Dictionary(all.map { ($0.file.uri, $0.file) }, uniquingKeysWith: { a, _ in a })
        }

        private static func state(of uri: String, _ retriever: Retriever) async throws -> FileState {
            let all = try await retriever.files(kind: nil, folder: nil, sort: .newest, limit: 100_000, offset: 0)
            return all.first { $0.file.uri == uri }?.state ?? .skipped
        }

        private static func rightFile(_ hit: SearchEngine.Hit, _ name: String) -> Bool { rightFile(hit.file, name) }

        /// "IMG_1001" matches IMG_1001.jpg and IMG_1001.JPG; a full file name must match exactly.
        private static func rightFile(_ file: SourceFile, _ name: String) -> Bool {
            name.contains(".") ? file.name == name : file.name.uppercased().hasPrefix(name)
        }

        private enum EditError: Error { case noInput }

        /// A real adjustment through PhotoKit, the way an editing extension makes one: the photo gets a new rendition.
        private static func edit(localId: String) async throws {
            guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localId], options: nil).firstObject else { throw EditError.noInput }
            // The editing input and output never leave this callback; only the outcome crosses back.
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                asset.requestContentEditingInput(with: nil) { input, _ in
                    guard let input, let url = input.fullSizeImageURL, let image = UIImage(contentsOfFile: url.path),
                        let jpeg = image.jpegData(compressionQuality: 0.4)
                    else { return cont.resume(throwing: EditError.noInput) }
                    let output = PHContentEditingOutput(contentEditingInput: input)
                    output.adjustmentData = PHAdjustmentData(formatIdentifier: "app.retriever.selftest", formatVersion: "1", data: Data("selftest".utf8))
                    do { try jpeg.write(to: output.renderedContentURL) } catch { return cont.resume(throwing: error) }
                    PHPhotoLibrary.shared().performChanges(
                        {
                            PHAssetChangeRequest(for: asset).contentEditingOutput = output
                        },
                        completionHandler: { ok, error in
                            if ok { cont.resume() } else { cont.resume(throwing: error ?? EditError.noInput) }
                        })
                }
            }
        }

        // MARK: Helpers

        static func say(_ line: String) {
            print("SELFTEST \(line)")
            fflush(stdout)
        }

        private static func floats(_ data: Data?) -> [Float] {
            guard let data else { return [] }
            return data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        }

        private static func cosine(_ a: [Float], _ b: [Float]) -> Float {
            guard a.count == b.count, !a.isEmpty else { return -2 }
            return zip(a, b).map(*).reduce(0, +)  // both are unit length
        }

        private static func fmt(_ x: Float) -> String { String(format: "%.4f", x) }

        private static func fmt3(_ x: Double) -> String { String(format: "%.3f", x) }

        private final class KnownMap: NSObject, KnownFiles {
            let files: [String: SourceFile]

            init(_ files: [String: SourceFile]) { self.files = files }

            func file(uri: String) -> SourceFile? { files[uri] }
        }

        private static func head(_ s: String) -> String {
            String(s.replacingOccurrences(of: "\n", with: " ").prefix(80))
        }

        private static func top(_ r: SearchEngine.SearchResult) -> String {
            r.hits.prefix(3).map { "\($0.file.name) [\($0.file.kind.name)] \($0.locator) \(String(format: "%.3f", $0.score))" }.joined(separator: "; ")
        }

        private static func snapshot(_ retriever: Retriever) async -> RetrieverSnapshot {
            final class Once: @unchecked Sendable {
                let lock = NSLock()
                var cont: CheckedContinuation<RetrieverSnapshot, Never>?
                var handle: KotlinAutoCloseable?
            }
            let once = Once()
            return await withCheckedContinuation { cont in
                once.cont = cont
                once.handle = retriever.observeAll { @Sendable snap in
                    once.lock.lock()
                    let c = once.cont
                    once.cont = nil
                    once.lock.unlock()
                    c?.resume(returning: snap)
                }
            }
        }

        private static func textImage(_ text: String) -> UIImage {
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            return UIGraphicsImageRenderer(size: CGSize(width: 1400, height: 300), format: format).image { ctx in
                UIColor.white.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 1400, height: 300))
                (text as NSString).draw(at: CGPoint(x: 60, y: 110), withAttributes: [.font: UIFont.systemFont(ofSize: 72), .foregroundColor: UIColor.black])
            }
        }

        private static func makePdf(_ url: URL, _ text: String) throws {
            let page = CGRect(x: 0, y: 0, width: 612, height: 792)
            try UIGraphicsPDFRenderer(bounds: page).writePDF(to: url) { ctx in
                ctx.beginPage()
                (text as NSString).draw(in: page.insetBy(dx: 72, dy: 72), withAttributes: [.font: UIFont.systemFont(ofSize: 18)])
            }
        }

        private static func makeDocx(_ url: URL, _ text: String) throws {
            try? FileManager.default.removeItem(at: url)
            let xml = Data(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>\(text)</w:t></w:r></w:p></w:body></w:document>
                """.utf8)
            let archive = try Archive(url: url, accessMode: .create)
            try archive.addEntry(with: "word/document.xml", type: .file, uncompressedSize: Int64(xml.count), compressionMethod: .deflate) { position, size in
                xml.subdata(in: Int(position)..<Int(position) + size)
            }
        }
    }
#endif
