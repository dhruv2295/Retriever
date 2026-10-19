import Observation
import Photos
import RetrieverCore
import SwiftUI
import XCTest
import os

@testable import Retriever

/// Home's facts come from the Kotlin facade into the model; HomeList then shows a section only when it has something
/// true to say. The second half renders HomeList in a window and counts its sections and rows: SwiftUI exposes no
/// labels to UIKit outside VoiceOver, but a section that is hidden has no rows and no header.
@MainActor
final class HomeModelTests: XCTestCase {
    private var services: AppServices!

    override func setUp() async throws { services = AppServices.temporary() }

    func testFixtureFactsAreWhatHomeShows() throws {
        let home = try XCTUnwrap(AppModel(fixture: .caughtUp).home)
        XCTAssertEqual(home.newDocs.count, 3)
        XCTAssertEqual(home.newShots.count, 6)
        XCTAssertEqual(home.folders, 12)
        XCTAssertEqual(home.largeCount, 4)
        XCTAssertEqual(home.nameOnly, 0)
        XCTAssertEqual(Copy.largeFiles(home.largeCount), "4 files over 100 MB")
    }

    /// The mosaic's collapse: 0, 1, 2 draw that many; 3 to 5 draw the big cell and the stack; 6 or more the full grid.
    func testMosaicCollapsesByCount() {
        XCTAssertEqual((0...8).map(Mosaic.shown), [0, 1, 2, 3, 3, 3, 6, 6, 6])
        for n in [0, 1, 2, 3, 6] {
            let frames = Mosaic.frames(count: n, width: 390)
            XCTAssertEqual(frames.count, n)
            XCTAssertTrue(frames.allSatisfy { $0.minX >= 0 && $0.maxX <= 390.001 }, "\(n) cells stay inside the row")
            for (i, a) in frames.enumerated() { for b in frames[(i + 1)...] { XCTAssertFalse(a.intersects(b), "\(n) cells do not overlap") } }
        }
        let grid = Mosaic.frames(count: 6, width: 390)
        XCTAssertEqual(grid[0].width, 2 * grid[1].width + Mosaic.gap, accuracy: 0.001, "the big cell is 2 x 2")
        XCTAssertEqual(grid.map(\.maxY).max() ?? 0, 390, accuracy: 0.001, "six cells make a square")
        XCTAssertEqual(Mosaic.frames(count: 3, width: 390).map(\.maxY).max() ?? 0, grid[0].height, accuracy: 0.001, "no bottom row")
    }

    func testNoOtherFileInAnyHomeList() throws {
        let home = try XCTUnwrap(AppModel(fixture: .caughtUp).home)
        XCTAssertTrue(home.newMedia.allSatisfy { $0.kind == .photo || $0.kind == .video })
        XCTAssertTrue(home.newShots.allSatisfy { $0.kind == .screenshot })
        XCTAssertTrue(home.newDocs.allSatisfy { $0.kind == .pdf || $0.kind == .doc })
        XCTAssertTrue(home.starred.allSatisfy { $0.kind != .other })
    }

    /// No scan has finished (running, or never stopped): "Nothing to search yet" would be a guess.
    func testFirstRunSaysLookingUntilAScanFinishes() {
        XCTAssertTrue(HomeList.isLooking(AppModel.Snap(setUp: true), started: true), "a scan has not started or ended")
        XCTAssertTrue(HomeList.isLooking(AppModel.Snap(setUp: true, running: true, lastStop: .done), started: true))
        XCTAssertFalse(HomeList.isLooking(AppModel.Snap(setUp: true, lastStop: .done), started: true), "a finished scan that found nothing")
        XCTAssertFalse(HomeList.isLooking(AppModel.Snap(setUp: true), started: false), "Photos access has not been decided")
        XCTAssertFalse(HomeList.isLooking(AppModel.Snap(setUp: true, paused: true), started: true), "an empty paused library is not looking")
    }

    func testSectionSubtitlesDropWhatIsZero() {
        XCTAssertEqual(Copy.mediaSubtitle(photos: 22_000, videos: 1_733), "22,000 photos · 1,733 videos")
        XCTAssertEqual(Copy.mediaSubtitle(photos: 1, videos: 0), "1 photo")
        XCTAssertEqual(Copy.documentsSubtitle(pdfs: 1_591, docs: 125), "1,591 PDFs · 125 documents")
        XCTAssertEqual(Copy.documentsSubtitle(pdfs: 0, docs: 1, detail: "Ready"), "1 document · Ready")
        XCTAssertEqual(Copy.sectionSubtitle(1_716, "1,200 to go"), "1,716 · 1,200 to go")
        XCTAssertEqual(Copy.photosToGo(14_700, paused: true), "14,700 to go · Paused")
    }

    func testThumbnailSizesShareBuckets() {
        XCTAssertEqual([100, 256, 300, 384, 500, 720, 2_000].map(Thumbnails.bucket), [256, 256, 384, 384, 512, 768, 768])
    }

    func testThumbnailTaskRestartsWhenDynamicTypeNeedsMorePixels() throws {
        let file = try XCTUnwrap(AppModel(fixture: .caughtUp).home?.newDocs.first)
        XCTAssertNotEqual(Thumb.requestID(file, locator: "", pixels: 156), Thumb.requestID(file, locator: "", pixels: 312))
        XCTAssertNotEqual(Thumb.requestID(file, locator: "p. 1", pixels: 156), Thumb.requestID(file, locator: "p. 2", pixels: 156))
    }

    func testRefreshHomeMapsTheFacadeFactsOntoTheModel() async throws {
        let model = AppModel(services: services)
        XCTAssertNil(model.home, "nothing until the first load")
        await model.refreshHome()
        let home = try XCTUnwrap(model.home)
        // Whatever this simulator holds, the facts agree with themselves.
        XCTAssertGreaterThanOrEqual(home.folders, 0)
        XCTAssertGreaterThanOrEqual(home.largeCount, 0)
        XCTAssertGreaterThanOrEqual(home.largeBytes, 0)
        XCTAssertTrue(home.newDocs.allSatisfy { $0.kind == .pdf || $0.kind == .doc })
        XCTAssertTrue(home.newShots.allSatisfy { $0.kind == .screenshot })
        XCTAssertTrue(home.newMedia.allSatisfy { $0.kind == .photo || $0.kind == .video })
        XCTAssertTrue(home.starred.allSatisfy { $0.kind != .other })
    }

    func testRefreshHomeDoesNothingOnAFixture() async {
        let model = AppModel(fixture: .caughtUp)
        await model.refreshHome()
        XCTAssertEqual(model.home?.folders, 12)
    }

    // MARK: What Home shows

    func testLibraryHeroReadingTrialCaughtUpDismissedAndTextOnlyScreenshots() async throws {
        for name in ["reading", "trial", "caught-up", "dismissed", "text-only"] {
            let model = AppModel(fixture: name == "reading" || name == "trial" ? .reading(done: 20, total: 100) : name == "text-only" ? .textOnly(ramGb: 2) : .caughtUp)
            if name == "trial" { model.snap?.trial = true; model.snap?.waiting = 2_000 }
            if name == "dismissed" { model.dismissCaughtUp() }
            let result = try await layout(model, screenshot: name)
            XCTAssertGreaterThan(result.items.first ?? 0, 0, "The library hero remains visible: \(name)")
        }
    }

    func testCaughtUpHomeShowsEverySection() async throws {
        let model = withMedia(AppModel(fixture: .caughtUp), 7, starred: 2)
        // Status, the chip row, the mosaic, screenshots, documents, starred, the tiles, tidy up.
        let layout = try await layout(model, search: .fixture(recent: ["passport scan"]))
        XCTAssertEqual(layout.headers, 6)
        // The hero and folders; one chip row; the mosaic; the strip of screenshots; the strip of documents; the strip of
        // starred files; the grid of tiles (one row); Large files + Duplicates.
        XCTAssertEqual(layout.items, [3, 1, 1, 1, 1, 1, 1, 2])
    }

    func testPicturesHideWithoutPhotosAccessAndDocumentsStay() async throws {
        let model = withMedia(AppModel(fixture: .caughtUp), 7, starred: 2)
        let denied = try await layout(model, search: .fixture(recent: ["a"]), photos: .denied)
        XCTAssertEqual(
            denied.items, [4, 1, 1, 1, 1, 2],
            "status and the Photos note; no mosaic or screenshots; documents come from added folders; starred, tiles, tidy up stay")
    }

    func testSuggestionsStandInUntilThereAreRecentSearches() async throws {
        let model = withMedia(AppModel(fixture: .caughtUp), 7, starred: 2)
        XCTAssertEqual(model.suggestions.count, 3)
        let layout = try await layout(model, search: .fixture())
        XCTAssertEqual(layout.items, [3, 1, 1, 1, 1, 1, 1, 2], "still one chip row, of suggestions")
    }

    func testSectionsHideWhenTheyHaveNothingToSay() async throws {
        // The real model before its first load: no facts, no stars, no suggestions.
        let model = AppModel(services: services)
        XCTAssertNil(model.home)
        let loaded = await eventually { model.snap != nil }
        XCTAssertTrue(loaded)
        try XCTSkipUnless(model.starred.isEmpty && model.suggestions.isEmpty, "this simulator holds stars or suggestions")
        let snap = AppModel.Snap(setUp: true, byKind: [.pdf: 3, .screenshot: 2], indexed: 5)
        let layout = try await layout(model, snap: snap, search: .fixture())
        // No recent searches, suggestions, new files, folders or large files, and no stars: no tile either (PDFs and
        // screenshots have strips and headers of their own). What is left is the status, the nudge to add a folder
        // (5 files is nearly empty), and Duplicates.
        XCTAssertEqual(layout.headers, 1)
        XCTAssertEqual(layout.items, [3, 1, 1])
    }

    /// Videos, Documents (beside PDFs), Audio, Other files, Folders, in that order and only with something in them.
    func testTilesAreFixedOrderAndOnlyForCountsAboveZero() throws {
        var snap = try XCTUnwrap(AppModel(fixture: .caughtUp).snap)
        XCTAssertEqual(HomeTile.make(snap, folders: 12).map(\.title), ["Videos", "Documents", "Folders"], "no audio or other files in the fixture")
        snap.byKind = [.video: 3, .doc: 2, .audio: 4, .other: 349, .pdf: 1, .photo: 9, .screenshot: 5]
        XCTAssertEqual(HomeTile.make(snap, folders: 148).map(\.title), ["Videos", "Documents", "Audio", "Other files", "Folders"])
        XCTAssertEqual(HomeTile.make(snap, folders: 148).first { $0.title == "Other files" }?.detail, "Found by name")
        snap.byKind[.pdf] = nil
        XCTAssertFalse(HomeTile.make(snap, folders: 0).map(\.title).contains("Documents"), "without PDFs the header opens Documents")
        XCTAssertEqual(HomeTile.make(snap, folders: 0).map(\.title), ["Videos", "Audio", "Other files"], "photos, screenshots and PDFs have headers")
        snap.byKind = [.pdf: 64]
        XCTAssertEqual(HomeTile.make(snap, folders: 0), [])
        XCTAssertEqual(HomeTile.make(snap, folders: 0, foldersAlways: true).map(\.title), ["Folders"], "the sidebar keeps the way to add a folder")
    }

    func testTilesSayHowReadingIsGoing() throws {
        var snap = try XCTUnwrap(AppModel(fixture: .caughtUp).snap)
        snap.pending = 120
        snap.pendingByKind = [.video: 95, .doc: 20]
        let tiles = HomeTile.make(snap, folders: 0)
        XCTAssertEqual(tiles.first { $0.title == "Videos" }?.detail, "Waiting")
        XCTAssertEqual(tiles.first { $0.title == "Documents" }?.detail, "20 to go")
    }

    func testEmptyLibraryIsOneMessageNotSections() async throws {
        let model = AppModel(fixture: .caughtUp)
        let reading = try await layout(model, snap: AppModel.Snap(setUp: true, running: true), search: .fixture())
        XCTAssertEqual(reading.headers, 0)
        XCTAssertEqual(reading.items, [1], "first run says it once, on the screen")
        let empty = try await layout(model, snap: AppModel.Snap(setUp: true, lastStop: .done), search: .fixture())
        XCTAssertEqual(empty.headers, 0)
        XCTAssertEqual(empty.items, [1], "the empty message alone, not \"All caught up\" above it (home-spec section 9)")
    }

    // MARK: Commit card and CAUGHT_UP

    /// The card shows once the short read is done and files wait outside it; under the full scope core reports none.
    func testCommitCardShowsOnlyWhenTheShortReadIsDoneAndFilesWait() async throws {
        XCTAssertTrue(AppModel.Snap(setUp: true, indexed: 78, waiting: 26_214).asksCommit)
        XCTAssertTrue(AppModel.Snap(setUp: true, indexed: 40, pending: 38, waiting: 26_214, running: true).asksCommit, "a schedule can be chosen before the sample ends")
        XCTAssertFalse(AppModel.Snap(setUp: true, indexed: 26_292).asksCommit, "scope ALL or nothing left")
        let model = AppModel(fixture: .caughtUp)
        let plain = try await layout(model, search: .fixture())
        var asking = try XCTUnwrap(model.snap)
        asking.waiting = 26_214
        let card = try await layout(model, snap: asking, search: .fixture())
        XCTAssertEqual(plain.items.first, 3, "hero and folders")
        XCTAssertEqual(card.items.first, 4, "the commit choice stays below the compact hero and folders")
        XCTAssertEqual(Array(card.items.dropFirst()), Array(plain.items.dropFirst()))
    }

    func testCaughtUpMeansEverythingAskedForIsReadAndIdle() {
        let done = AppModel.Snap(setUp: true, indexed: 10, lastStop: .done)
        XCTAssertTrue(done.isCaughtUp)
        for waiting in [
            AppModel.Snap(setUp: true, indexed: 10, waiting: 5), AppModel.Snap(setUp: true, indexed: 10, pending: 1),
            AppModel.Snap(setUp: true, indexed: 10, running: true), AppModel.Snap(setUp: true, indexed: 10, paused: true),
            AppModel.Snap(setUp: true), AppModel.Snap(setUp: false, indexed: 10),
        ] {
            XCTAssertFalse(waiting.isCaughtUp)
        }
    }

    /// Core's `celebrated` flag stands in as a variable that outlives the models, as the Store outlives a launch.
    func testCaughtUpIsCelebratedOnceEverNotForALaterCatchUpOrAfterRelaunch() async {
        var flag = false
        func launch() -> AppModel {
            let model = AppModel(fixture: .reading(done: 3, total: 10))
            model.readCelebrated = { flag }
            model.markCelebrated = { flag = true }
            return model
        }
        let caughtUp = AppModel.Snap(setUp: true, indexed: 10, lastStop: .done)
        let reading = AppModel.Snap(setUp: true, indexed: 10, pending: 4, running: true)

        let first = launch()
        apply(caughtUp, to: first)
        let due = await eventually { first.caughtUpDue }
        XCTAssertTrue(due, "the first full read, never celebrated")
        apply(reading, to: first)
        XCTAssertFalse(first.caughtUpDue, "reading again before it was shown")
        apply(caughtUp, to: first)
        let again = await eventually { first.caughtUpDue }
        XCTAssertTrue(again, "still never celebrated")

        first.celebrate()
        XCTAssertFalse(first.caughtUpDue)
        let marked = await eventually { flag }
        XCTAssertTrue(marked, "written to core")
        apply(reading, to: first)
        var later = caughtUp
        later.indexed = 14
        apply(later, to: first)
        XCTAssertFalse(first.caughtUpDue, "a later catch-up")

        let second = launch()
        apply(caughtUp, to: second)
        let known = await eventually { second.celebrated }
        XCTAssertTrue(known)
        XCTAssertFalse(second.caughtUpDue, "after relaunch")
    }

    // MARK: On This Day

    private func newYork() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        return calendar
    }

    private func date(_ y: Int, _ m: Int, _ d: Int, in calendar: Calendar) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: 12))!
    }

    /// A local day is 23 hours when the clocks go forward and 25 when they go back; the range follows the zone.
    func testDayRangesFollowTheZoneAcrossAClockChange() {
        let ny = newYork()
        let hour: Int64 = 3_600_000
        let spring = OnThisDay.days(on: date(2026, 3, 10, in: ny), calendar: ny, years: 3)
        XCTAssertEqual(spring.map(\.year), [2025, 2024, 2023], "newest year first")
        XCTAssertEqual(spring.map { ($0.to + 1 - $0.from) / hour }, [24, 23, 24], "10 March 2024 lost an hour")
        let autumn = OnThisDay.days(on: date(2026, 11, 3, in: ny), calendar: ny, years: 3)
        XCTAssertEqual(autumn.map { ($0.to + 1 - $0.from) / hour }, [24, 25, 24], "3 November 2024 gained one")
        // Each range starts at local midnight and ends a millisecond before the next one.
        let day = spring[1]
        XCTAssertEqual(Date(timeIntervalSince1970: Double(day.from) / 1000), ny.startOfDay(for: date(2024, 3, 10, in: ny)))
        XCTAssertEqual(Date(timeIntervalSince1970: Double(day.to + 1) / 1000), ny.startOfDay(for: date(2024, 3, 11, in: ny)))
        XCTAssertEqual(day.title, date(2024, 3, 10, in: ny).formatted(Date.FormatStyle(calendar: ny, timeZone: ny.timeZone).day().month(.wide).year()))
    }

    func testLeapDayIsSkippedInYearsWithoutIt() {
        let ny = newYork()
        let days = OnThisDay.days(on: date(2028, 2, 29, in: ny), calendar: ny, years: 5)
        XCTAssertEqual(days.map(\.year), [2024], "2027 to 2025 and 2023 have no 29 February")
    }

    func testCountLineSaysWhatTheDayHolds() {
        XCTAssertEqual(OnThisDay.countLine(photos: 8, videos: 0), "8 photos")
        XCTAssertEqual(OnThisDay.countLine(photos: 1, videos: 0), "1 photo")
        XCTAssertEqual(OnThisDay.countLine(photos: 0, videos: 1), "1 video")
        XCTAssertEqual(OnThisDay.countLine(photos: 3, videos: 2), "5 photos and videos")
    }

    func testACardOpensThatDaysFiles() throws {
        let card = try XCTUnwrap(memories(1).first)
        XCTAssertEqual(card.route, .files(FileQuery(title: card.day.title, takenRange: card.day.from...card.day.to)))
    }

    private func memories(_ n: Int) -> [OnThisDay.Card] {
        let ny = newYork()
        let days = OnThisDay.days(on: date(2026, 3, 10, in: ny), calendar: ny, years: n)
        return days.map { day in
            let cover = SourceFile(
                uri: "folder://fixture/m\(day.year).jpg", name: "m\(day.year).jpg", folder: "Photos", mime: "image/jpeg", kind: .photo,
                size: 1, modifiedAt: day.from, takenAt: nil)
            return OnThisDay.Card(year: day.year, photos: 8, videos: 0, cover: cover, day: day)
        }
    }

    /// Hiding removes the section at once; the switch on Status brings it back.
    func testHidingOnThisDayRemovesItAndStatusBringsItBack() async throws {
        let model = withMedia(AppModel(fixture: .caughtUp), 7, starred: 2)
        let without = try await layout(model, search: .fixture(recent: ["a"]))
        model.memories = memories(3)
        let shown = try await layout(model, search: .fixture(recent: ["a"]))
        XCTAssertEqual(shown.headers, without.headers + 1)
        XCTAssertEqual(shown.items.count, without.items.count + 1)
        XCTAssertEqual(shown.items[5], 1, "one strip of cards, after the documents")

        model.setShowOnThisDay(false)
        XCTAssertEqual(model.snap?.showOnThisDay, false)
        let hidden = try await layout(model, search: .fixture(recent: ["a"]))
        XCTAssertEqual(hidden, without)

        model.setShowOnThisDay(true)
        let back = try await layout(model, search: .fixture(recent: ["a"]))
        XCTAssertEqual(back, shown)
    }

    func testOnThisDayStaysHiddenWithoutPhotosAccess() async throws {
        let model = withMedia(AppModel(fixture: .caughtUp), 7, starred: 2)
        let denied = try await layout(model, search: .fixture(recent: ["a"]), photos: .denied)
        model.memories = memories(3)
        let still = try await layout(model, search: .fixture(recent: ["a"]), photos: .denied)
        XCTAssertEqual(still, denied)
    }

    // MARK: Observation granularity

    private func apply(_ snap: AppModel.Snap, to model: AppModel) {
        model.apply(snap, starred: model.starred, modelMissing: model.modelMissing, suggestions: model.suggestions)
    }

    func testAnUnchangedSnapshotDoesNotReassignSnap() throws {
        let model = AppModel(fixture: .caughtUp)
        var snap = try XCTUnwrap(model.snap)
        let writes = model.snapWrites
        apply(snap, to: model)
        XCTAssertEqual(model.snapWrites, writes, "the same counts and prefs again")
        // Per-file progress moves on its own property and leaves snap alone.
        snap.progress = AppModel.Progress(processed: 3, current: "a.pdf", filesPerMinute: 30, etaMinutes: 4, lastError: nil)
        apply(snap, to: model)
        XCTAssertEqual(model.snapWrites, writes, "a tick changes progress only")
        XCTAssertEqual(model.progress.processed, 3)
        XCTAssertEqual(model.snap?.processed, 0, "snap carries no per-file progress")
        XCTAssertEqual(model.live?.current, "a.pdf", "live puts it back for Status and the Home row")
        snap.indexed += 1
        apply(snap, to: model)
        XCTAssertEqual(model.snapWrites, writes + 1, "a count changed")
    }

    func testProgressTicksDoNotInvalidateHomeFactsOrSearchFlags() throws {
        let model = AppModel(fixture: .reading(done: 10, total: 100))
        let invalidated = OSAllocatedUnfairLock(initialState: false)
        withObservationTracking {
            _ = model.snap
            _ = model.setUp
            _ = model.textOnly
            _ = model.useMobileData
        } onChange: {
            invalidated.withLock { $0 = true }
        }
        var snapshot = try XCTUnwrap(model.live)
        for n in 1...20 {
            snapshot.processed = n
            snapshot.current = "file-\(n)"
            snapshot.filesPerMinute = Double(n)
            snapshot.etaMinutes = 21 - n
            apply(snapshot, to: model)
        }
        XCTAssertFalse(invalidated.withLock { $0 })
        XCTAssertEqual(model.progress.processed, 20)
    }

    func testSetUpAndTextOnlyFollowTheSnapshot() throws {
        let model = AppModel(fixture: .caughtUp)
        XCTAssertTrue(model.setUp)
        XCTAssertFalse(model.textOnly)
        var snap = try XCTUnwrap(model.snap)
        snap.textOnly = true
        apply(snap, to: model)
        XCTAssertTrue(model.textOnly)
        XCTAssertTrue(model.setUp)
    }

    // MARK: Names and coverage

    private func bold(_ text: AttributedString) -> [String] {
        text.runs.filter { $0.inlinePresentationIntent == .stronglyEmphasized }.map { String(text[$0.range].characters) }
    }

    func testNameBoldsWhatCoreMatchedNotEveryLetterRun() {
        XCTAssertEqual(bold(Format.name("Cat photo.jpg", query: "cat")), ["Cat"])
        XCTAssertEqual(bold(Format.name("Concatenate notes.pdf", query: "cat")), [], "inside a word is not a match")
        XCTAssertEqual(bold(Format.name("Invoice printing.pdf", query: "in")), [])
        XCTAssertEqual(bold(Format.name("Lease agreement.pdf", query: "agree")), ["agreement"], "the last word matches by prefix from three letters")
    }

    func testCoverageLineSaysWhatIsSearchable() {
        func stored(_ kind: Kind, _ state: FileState, partial: Bool = false) -> Store.StoredFile {
            Store.StoredFile(
                id: 1, file: SourceFile(uri: "folder://t/a", name: "a", folder: "t", mime: "x/y", kind: kind, size: 1, modifiedAt: 0, takenAt: nil),
                attempts: 0, state: state, partial: partial, extent: nil, contentRead: state == .indexed && kind != .other)
        }
        XCTAssertEqual(Format.coverage(stored(.pdf, .skipped)), "Couldn’t read")
        XCTAssertEqual(Format.coverage(stored(.pdf, .pending)), "Waiting to read")
        XCTAssertEqual(Format.coverage(stored(.other, .indexed)), "Name only")
        XCTAssertEqual(Format.coverage(stored(.pdf, .indexed, partial: true)), Copy.extent(.firstPart))
        XCTAssertNil(Format.coverage(stored(.pdf, .indexed)))
    }

    // MARK: Rendering

    /// The fixture's facts with `n` newest pictures (the fixture itself holds none).
    private func withMedia(_ model: AppModel, _ n: Int, starred: Int = 0) -> AppModel {
        let h = model.home!
        let media = (0..<n).map { i in
            SourceFile(
                uri: "folder://fixture/p\(i).jpg", name: "p\(i).jpg", folder: "Photos", mime: "image/jpeg", kind: i == 1 ? .video : .photo,
                size: 1, modifiedAt: Int64(1_700_000_000_000 - i * 1000), takenAt: nil)
        }
        let stars = (0..<starred).map { i in
            SourceFile(
                uri: "folder://fixture/s\(i).pdf", name: "s\(i).pdf", folder: "Docs", mime: "application/pdf", kind: .pdf,
                size: 1, modifiedAt: 1_700_000_000_000, takenAt: nil)
        }
        model.home = h.doCopy(
            newMedia: media, newShots: h.newShots, newDocs: h.newDocs, starred: stars, starredCount: Int64(starred),
            folders: h.folders, largeCount: h.largeCount, largeBytes: h.largeBytes, nameOnly: h.nameOnly, topFolders: h.topFolders)
        return model
    }

    private struct Layout: Equatable {
        var headers: Int
        var items: [Int]
    }

    /// HomeList in a window of the host app's scene (without one SwiftUI lays out nothing), then the rows in each section.
    private func layout(
        _ model: AppModel, snap: AppModel.Snap? = nil, search: SearchModel? = nil,
        photos: PHAuthorizationStatus = .authorized, screenshot: String? = nil
    ) async throws -> Layout {
        let list = NavigationStack {
            HomeList(search: search ?? .fixture(), snap: snap ?? model.snap, photos: photos, model: model, useRecent: { _ in }, addFolder: {})
                .environment(\.horizontalSizeClass, .compact)
        }
        let host = UIHostingController(rootView: list)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: screenshot == nil ? 5_000 : 844)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        // A fixed sleep raced SwiftUI's first layout pass on a loaded machine (the flake). Poll until the list has
        // sections and the same answer twice running, or give up after 15 s and let the assertions report it.
        var last: Layout?
        for _ in 0..<150 {
            try await Task.sleep(for: .milliseconds(100))
            host.view.layoutIfNeeded()
            guard let collection = Self.collectionView(in: host.view), collection.numberOfSections > 0 else { continue }
            let now = Layout(
                headers: collection.visibleSupplementaryViews(ofKind: UICollectionView.elementKindSectionHeader).count,
                items: (0..<collection.numberOfSections).map { collection.numberOfItems(inSection: $0) })
            if now == last {
                if let screenshot {
                    let image = UIGraphicsImageRenderer(bounds: host.view.bounds).image { context in
                        host.view.layer.render(in: context.cgContext)
                    }
                    let pixels = try XCTUnwrap(image.cgImage?.dataProvider?.data) as Data
                    let shades = Set(stride(from: 0, to: pixels.count, by: 97).map { pixels[$0] })
                    XCTAssertGreaterThan(shades.count, 8, "The hero screenshot must contain rendered content")
                    let attachment = XCTAttachment(image: image)
                    attachment.name = "home-hero-" + screenshot
                    attachment.lifetime = .keepAlways
                    add(attachment)
                }
                return now
            }
            last = now
        }
        return try XCTUnwrap(last, "Home is not a list")
    }

    private static func collectionView(in view: UIView) -> UICollectionView? {
        (view as? UICollectionView) ?? view.subviews.lazy.compactMap(collectionView(in:)).first
    }
}
