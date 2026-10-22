import XCTest

@testable import Retriever

extension XCTestCase {
    /// A temporary AppServices closed when the test ends, so repeated runs don't run out of file descriptors.
    func temporaryServices() -> AppServices {
        let services = AppServices.temporary()
        addTeardownBlock { try await services.close() }
        return services
    }
}

final class AppServicesTests: XCTestCase {
    private func openFiles() -> Int {
        (0..<getdtablesize()).count { fcntl($0, F_GETFD) != -1 }
    }

    func testClosedServicesGiveBackTheirFiles() async throws {
        let before = openFiles()
        for _ in 0..<10 {
            let services = AppServices.temporary()
            try await services.retriever.refreshSetUp()  // opens the index database
            try await services.close()
        }
        // Unclosed, each opened set kept 3 files (the database, its -wal and -shm): 10 kept 30.
        XCTAssertLessThanOrEqual(openFiles(), before + 2)
    }
}
