package app.retriever.core

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping

@OptIn(BetaInteropApi::class)
actual fun foldLatin(s: String): String {
    val lower = s.lowercase()
    if (lower.all { it.code < 0x80 }) return lower
    return (lower as NSString).decomposedStringWithCanonicalMapping.filterNot(::isLatinMark)
}
