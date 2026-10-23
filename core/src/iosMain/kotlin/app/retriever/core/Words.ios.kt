package app.retriever.core

import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.autoupdatingCurrentLocale

// One formatter for every call ("Cache Formatters for Efficiency"); formatters are thread-safe since iOS 7, and the
// autoupdating locale follows a change in Settings.
private val countFormatter = NSNumberFormatter().apply {
    numberStyle = NSNumberFormatterDecimalStyle
    locale = NSLocale.autoupdatingCurrentLocale
}

actual fun formatCount(n: Long): String = countFormatter.stringFromNumber(NSNumber(longLong = n)) ?: n.toString()
