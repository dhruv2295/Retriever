import RetrieverCore
import SwiftUI

/// Words and numbers as the person reads them.
enum Format {
    static func kind(_ kind: Kind) -> String {
        switch kind {
        case .pdf: "PDF"
        case .doc: "Document"
        case .screenshot: "Screenshot"
        case .photo: "Photo"
        case .audio: "Audio"
        case .video: "Video"
        default: "File"
        }
    }

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
        if let page = Locator.shared.pageOf(l: locator)?.intValue { return "Page \(page)" }
        return Locator.shared.msOf(l: locator) != nil ? locator : nil
    }

    /// How VoiceOver says where the match is.
    static func spoken(_ locator: String) -> String? {
        if let page = Locator.shared.pageOf(l: locator)?.intValue { return "page \(page)" }
        guard let ms = Locator.shared.msOf(l: locator)?.int64Value else { return nil }
        return "at " + Duration.milliseconds(ms).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide))
    }

    static func openHint(_ locator: String) -> String {
        if let page = Locator.shared.pageOf(l: locator)?.intValue { return "Opens at page \(page)" }
        if Locator.shared.msOf(l: locator) != nil { return "Plays from \(locator)" }
        return "Opens"
    }

    static func files(_ n: Int) -> String {
        n == 1 ? "1 file" : "\(Copy.count(n)) files"
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

    /// One line on how much of a listed file is searchable; nil when all of it is. Same words as Android's `coverageNote`.
    @MainActor static func coverage(_ stored: Store.StoredFile) -> String? {
        if stored.state == FileState.skipped { return "Couldn't be read" }
        if !stored.searchable || stored.file.kind == .other { return "Found by name only" }
        if let extent = stored.extent { return Copy.extent(extent) }
        return stored.partial ? Copy.extent(.firstPart) : nil
    }
}
