import UIKit
import XCTest

@MainActor
final class ColorContrastTests: XCTestCase {
    func testAccentAndPrimaryButtonLabelsContrastInBothAppearances() throws {
        for style in [UIUserInterfaceStyle.light, .dark] {
            let traits = UITraitCollection(userInterfaceStyle: style)
            let accent = try XCTUnwrap(UIColor(named: "AccentColor", in: .main, compatibleWith: traits))
                .resolvedColor(with: traits)
            // The system background is also the label colour of a filled primary button.
            for background in [UIColor.systemBackground, .secondarySystemGroupedBackground] {
                let a = luminance(accent)
                let b = luminance(background.resolvedColor(with: traits))
                XCTAssertGreaterThanOrEqual((max(a, b) + 0.05) / (min(a, b) + 0.05), 4.5, "\(style)")
            }
        }
    }

    private func luminance(_ color: UIColor) -> Double {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        XCTAssertTrue(color.getRed(&red, green: &green, blue: &blue, alpha: &alpha))
        return zip([red, green, blue], [0.2126, 0.7152, 0.0722]).reduce(0) { sum, channel in
            let value = Double(channel.0)
            return sum + channel.1 * (value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4))
        }
    }
}
