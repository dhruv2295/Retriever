import BackgroundTasks
import XCTest

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
}
