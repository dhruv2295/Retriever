import BackgroundTasks
import XCTest
import os

@testable import Retriever

final class IndexTaskTests: XCTestCase {
    /// iOS's CPU Monitor stays on unless the request needs external power, and kills a background app that runs the
    /// model on the CPU for minutes; "Also on battery" must not turn it on.
    func testOvernightReadingWaitsForTheCharger() {
        let request = IndexTask.processingRequest()
        XCTAssertEqual(request.identifier, IndexTask.id)
        XCTAssertTrue(request.requiresExternalPower)
        XCTAssertFalse(request.requiresNetworkConnectivity)
    }

    /// The batch's own end and the expiry grace timer race; iOS hears from whichever comes first, once.
    func testATaskIsCompletedOnce() async {
        let calls = OSAllocatedUnfairLock<[Bool]>(initialState: [])
        let complete = IndexTask.Completion { ok in calls.withLock { $0.append(ok) } }
        let firsts = await withTaskGroup(of: Bool.self) { group in
            for i in 0..<50 { group.addTask { complete(i == 0) } }
            return await group.reduce(0) { $0 + ($1 ? 1 : 0) }
        }
        XCTAssertEqual(firsts, 1)
        XCTAssertEqual(calls.withLock { $0.count }, 1)
        XCTAssertFalse(complete(true))
    }
}
