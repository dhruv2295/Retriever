import BackgroundTasks
import XCTest
import os

@testable import Retriever

final class IndexTaskTests: XCTestCase {
    /// A run uses up its request, and the batch that would submit the next may still be finishing its file when the
    /// grace timer completes the task: the expiry submits it first, so overnight reading carries on. On the charger,
    /// where iOS's CPU Monitor is off.
    func testAnExpiredTaskSubmitsTheNextRunBeforeItCompletes() async {
        let events = OSAllocatedUnfairLock<[String]>(initialState: [])
        let submit = IndexTask.submit
        defer { IndexTask.submit = submit }
        IndexTask.submit = { request in
            let power = (request as? BGProcessingTaskRequest)?.requiresExternalPower == true
            let event = "submitted \(request.identifier), on the charger \(power)"
            events.withLock { $0.append(event) }
        }
        let completed = expectation(description: "completed")
        IndexTask.expire(
            IndexTask.Completion { ok in
                events.withLock { $0.append("completed \(ok)") }
                completed.fulfill()
            })
        await fulfillment(of: [completed], timeout: 10)
        XCTAssertEqual(events.withLock { $0 }, ["submitted \(IndexTask.id), on the charger true", "completed false"])
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
