import RetrieverCore
import SwiftUI

/// The one upgrade, "Search by meaning and what's in your photos", wherever it shows: under Try's suggestions, Home's compact row
/// and Settings. Offer, then the download, then the tune, then the meaning pass, by core's `UpgradePolicy`.
struct UpgradeCard: View {
    let model: AppModel
    /// Try and Home: the offer can be put off with Not now. Settings keeps it.
    var compact = false
    @State private var askingMobileData = false
    @Environment(\.dynamicTypeSize) private var typeSize

    private var size: String { Copy.downloadSize(model.snap?.downloadBytes ?? 0) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch model.upgrade {
            case .offer: offer
            case .download: download
            case .tune: tune
            case .meaning: meaning
            default: EmptyView()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .alert(Words.shared.mobileDataAsk(size: size), isPresented: $askingMobileData) {
            Button(Words.shared.DOWNLOAD) { model.setUseMobileData(true) }
            Button(Words.shared.WAIT_FOR_WIFI, role: .cancel) {}
        }
    }

    @ViewBuilder private var offer: some View {
        title(Words.shared.UPGRADE_TITLE)
        Text(Words.shared.upgradeBody(size: size, words: Copy.words)).font(.subheadline).fixedSize(horizontal: false, vertical: true)
        // Side by side, stacked at accessibility sizes rather than cut short; AnyLayout keeps the buttons' identity.
        let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 12))
        layout {
            Button(Words.shared.DOWNLOAD, action: model.downloadModel).buttonStyle(.primaryAction)
            if compact { Button(Words.shared.NOT_NOW, action: model.dismissUpgradeOffer).buttonStyle(.bordered) }
        }
    }

    private func title(_ text: String) -> some View {
        Text(text).font(.headline).fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder private var download: some View {
        title(Words.shared.UPGRADE_TITLE)
        switch model.download {
        case .downloading:
            let progress = min(max(model.downloadProgress, 0), 1)
            let total = model.snap?.downloadBytes ?? 0
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(
                    Words.shared.downloadProgress(
                        done: Copy.downloadSize(Int64(progress * Double(total))), total: size, percent: Int32((progress * 100).rounded()))
                )
                .font(.subheadline).monospacedDigit()
                .accessibilityIdentifier("download-amount")
                Spacer(minLength: 0)
                // Borderless: its own 44 pt target, not the whole list row's.
                Button(action: model.cancelDownload) { Text(Words.shared.CANCEL).frame(minWidth: 44, minHeight: 44) }.buttonStyle(.borderless)
            }
            ProgressView(value: progress).accessibilityHidden(true)
        case .waitingForWifi:
            Note(Words.shared.WAITING_FOR_WIFI)
            HStack(spacing: 12) {
                Button(Words.shared.USE_MOBILE_DATA) { askingMobileData = true }.buttonStyle(.bordered)
                Button(Words.shared.CANCEL, action: model.cancelDownload)
            }
        case .failed(let reason):
            Failure(reason)
            Button(Words.shared.TRY_AGAIN, action: model.downloadModel).buttonStyle(.bordered)
        default:
            // Checked, or finished and about to be found: the tune follows.
            Busy(Words.shared.CHECKING_DOWNLOAD)
        }
    }

    @ViewBuilder private var tune: some View {
        title(Words.shared.tuneTitle(words: Copy.words))
        if case .failed = model.tune {
            Failure(Copy.tuneFailed)
            Button(Words.shared.TRY_AGAIN, action: model.retryTune).buttonStyle(.bordered)
        } else {
            Note(Words.shared.tuneBody(words: Copy.words))
            // The title above already says what is happening.
            ProgressView().progressViewStyle(.linear).accessibilityHidden(true)
            Note(Words.shared.TUNE_LEAVING)
        }
    }

    @ViewBuilder private var meaning: some View {
        if let snap = model.snap {
            let progress = snap.readProgress
            let read = Double(max(progress.contentRead, 1))
            Text(Words.shared.addingMeaning(progress: progress)).font(.subheadline).monospacedDigit()
            ProgressView(value: Double(progress.contentRead - progress.meaningWaiting) / read).accessibilityHidden(true)
        }
    }
}
