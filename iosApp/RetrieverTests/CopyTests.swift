import XCTest
import RetrieverCore
@testable import Retriever

final class CopyTests: XCTestCase {
    func testEtaRoundsToPlainUnits() {
        XCTAssertEqual(Copy.eta(0), "Less than a minute left")
        XCTAssertEqual(Copy.eta(1), "About a minute left")
        XCTAssertEqual(Copy.eta(45), "About 45 minutes left")
        XCTAssertEqual(Copy.eta(75), "About an hour left")
        XCTAssertEqual(Copy.eta(150), "About 3 hours left")
        XCTAssertEqual(Copy.eta(3 * 24 * 60), "About 3 days left")
    }

    func testStatusLineFollowsTheIndex() {
        XCTAssertNil(Copy.statusLine(nil))
        XCTAssertEqual(Copy.statusLine(AppModel.Snap(setUp: false)), "Getting ready. For now Retriever finds files by name.")
        XCTAssertEqual(
            Copy.statusLine(AppModel.Snap(setUp: true, indexed: 3, running: true, pendingAtStart: 0)),
            "Looking for your files…"
        )
        XCTAssertEqual(
            Copy.statusLine(AppModel.Snap(setUp: true, indexed: 3, pending: 2, lastStop: StopReason.done)),
            "3 of 5 files searchable · Will read the new files soon"
        )
        XCTAssertEqual(Copy.statusLine(AppModel.Snap(setUp: true, indexed: 4)), "All caught up · 4 files searchable")
    }

    func testStopReasonsReadAsSentences() {
        XCTAssertEqual(Copy.stop(StopReason.busy), "Paused while you use your iPhone")
        XCTAssertEqual(Copy.waiting(StopReason.hot), "Resting while your iPhone cools down")
        XCTAssertNil(Copy.stop(nil))
    }
}
