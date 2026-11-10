import RetrieverCore
import SwiftUI

/// Words and numbers as the person reads them.
enum Format {
    static func icon(_ kind: Kind) -> String {
        switch kind {
        case .pdf: "doc.richtext"
        case .doc: "doc.text"
        case .screenshot: "camera.viewfinder"
        case .photo: "photo"
        case .audio: "waveform"
        case .video: "video"
        default: "doc"
        }
    }

    static func date(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .long, time: .omitted)
    }

    static func badge(_ locator: String) -> String? {
        if let page = Locator.shared.pageOf(l: locator)?.intValue { return Words.shared.strings.pageBadge(page: Int32(page)) }
        return Locator.shared.msOf(l: locator) != nil ? locator : nil
    }

    static func openHint(_ locator: String) -> String {
        if let page = Locator.shared.pageOf(l: locator)?.intValue { return Words.shared.strings.opensAtPage(page: Int32(page)) }
        if Locator.shared.msOf(l: locator) != nil { return Words.shared.strings.playsFrom(time: locator) }
        return Words.shared.strings.opensHint
    }

    /// The \u{2}...\u{3} words bold on a tinted background. An unclosed span runs to the end.
    static func highlighted(_ snippet: String) -> AttributedString {
        var out = AttributedString()
        var run = ""
        var marked = false
        func flush() {
            var piece = AttributedString(run)
            if marked {
                piece.inlinePresentationIntent = .stronglyEmphasized
                piece.backgroundColor = Color.accentColor.opacity(0.2)
            }
            out += piece
            run = ""
        }
        for ch in snippet {
            switch ch {
            case "\u{2}":
                flush()
                marked = true
            case "\u{3}":
                flush()
                marked = false
            default: run.append(ch)
            }
        }
        flush()
        return out
    }

    /// The words a keyword match marked, for highlighting them inside the file.
    static func markedWords(_ snippet: String?) -> [String] {
        guard let snippet else { return [] }
        var words: [String] = []
        var current: String?
        for ch in snippet {
            switch ch {
            case "\u{2}": current = ""
            case "\u{3}":
                if let w = current, !w.isEmpty { words.append(w) }
                current = nil
            default: current?.append(ch)
            }
        }
        return Array(Set(words))
    }

    /// The file name with the parts keyword search matched in bold: core decides which (`nameMatchRanges`, the rule
    /// Android uses too). Its indexes count UTF-16 units, as NSRange does.
    static func name(_ name: String, query: String) -> AttributedString {
        var out = AttributedString(name)
        for r in HighlightKt.nameMatchRanges(name: name, query: query) {
            if let range = Range(NSRange(location: Int(r.first), length: Int(r.last - r.first) + 1), in: out) {
                out[range].inlinePresentationIntent = .stronglyEmphasized
            }
        }
        return out
    }

}
