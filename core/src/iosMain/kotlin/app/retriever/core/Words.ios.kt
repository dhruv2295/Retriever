package app.retriever.core

import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle

actual fun formatCount(n: Long): String =
    NSNumberFormatter().apply { numberStyle = NSNumberFormatterDecimalStyle }.stringFromNumber(NSNumber(longLong = n)) ?: n.toString()
