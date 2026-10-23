import RetrieverCore
import XCTest

/// core/fixtures/words.tsv, asserted here and in Android's WordsFixtureTest: both apps show the same shared sentences.
final class WordsFixtureTests: XCTestCase {
    func testEveryFixtureRowReadsTheSame() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../core/fixtures/words.tsv")
        let rows = try String(contentsOf: url, encoding: .utf8).split(separator: "\n")
            .filter { !$0.hasPrefix("#") }.map { $0.split(separator: "\t", omittingEmptySubsequences: false).map(String.init) }
        let words = [
            "android": DeviceWords.companion.ANDROID, "iphone": DeviceWords.companion.ios(device: "iPhone"), "ipad": DeviceWords.companion.ios(device: "iPad"),
        ]
        func kind(_ name: Substring) -> Kind { Kind.entries.first { $0.name == name }! }
        func kinds(_ s: String) -> [Kind] { s.split(separator: ",").map(kind) }
        func stop(_ name: String) -> StopReason { StopReason.entries.first { $0.name == name }! }
        for row in rows {
            let actual: String? =
                switch row[0] {
                case "stop": ReadingPolicy.shared.stopCopy(stop: stop(row[1]), words: words[row[2]]!)
                case "kindProgress":
                    Words.shared.kindProgress(
                        kinds: kinds(row[1]), inside: Set(kinds(row[2])),
                        pending: Dictionary(
                            uniqueKeysWithValues: row[3].split(separator: ",").map {
                                (kind($0.split(separator: "=")[0]), KotlinLong(value: Int64($0.split(separator: "=")[1])!))
                            }),
                        scheduled: Int64(row[4])!)
                case "spoken": Words.shared.spokenPosition(locator: row[1])
                case "readCount": Words.shared.readCount(read: Int64(row[1])!, partlyRead: Int64(row[2])!)
                case "textOnlyNote": Words.shared.textOnlyNote(ramGb: Double(row[1])!, words: words[row[2]]!)
                default: nil
                }
            XCTAssertEqual(actual, row.last!.isEmpty ? nil : row.last!, row.joined(separator: " | "))
        }
        XCTAssertEqual(rows.count, 34)
    }
}
