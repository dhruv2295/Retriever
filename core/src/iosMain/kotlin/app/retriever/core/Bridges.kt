@file:OptIn(ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

// Swift can't implement Kotlin suspend members, so it implements these synchronous protocols and the Kotlin
// wrappers (IosEmbedder, IosExtractor, IosContentHasher, asFileSource) keep locking, fallback and piece rules.
// Kotlin calls them from background threads; IosEmbedder serializes its calls, the others must be thread-safe.

/** One loaded model. Calls are serialized by the Kotlin wrapper. */
interface NativeEmbedder {
    /** [parts] in model order: String is text, NSData a JPEG. Float32 little-endian bytes, or null with [lastError] set. */
    fun embed(parts: List<Any>): NSData?

    /** 0 none, 1 the input is longer than the token window, 2 anything else. */
    val lastError: Int
    val lastMessage: String?

    fun close()
}

interface NativeEmbedderOpener {
    /** Null when the model can't load, with the reason in [lastMessage]. */
    fun open(modelPath: String, gpu: Boolean, visionTokens: Int, maxTokens: Int, cacheDir: String): NativeEmbedder?

    val lastMessage: String?
}

/** File access by URI ("ph://<localIdentifier>", "folder://..."). Null means unreadable; never throw. */
interface NativeExtractor {
    fun imageJpeg(uri: String, maxSide: Int): NSData?

    /** [page] -1 OCRs the image itself; otherwise a PDF page index, rendered about 1600 px. */
    fun ocr(uri: String, page: Int): String?

    /** -1 when locked or unreadable. */
    fun pdfPageCount(uri: String): Int

    fun pdfPageText(uri: String, page: Int): String?

    fun pdfPageJpeg(uri: String, page: Int, maxSide: Int): NSData?

    fun videoDurationMs(uri: String): Long

    fun videoFrameJpeg(uri: String, ms: Long, maxSide: Int): NSData?

    /** The first [maxBytes] bytes decoded as UTF-8. */
    fun textHead(uri: String, maxBytes: Int): String?

    /** In archive order. */
    fun zipEntryNames(uri: String): List<String>

    fun zipEntryText(uri: String, name: String, maxBytes: Int): String?

    /** Up to [length] bytes at [offset]; empty at the end of the file, null when unreadable and for ph:// URIs. */
    fun readBytes(uri: String, offset: Long, length: Int): NSData?
}

/** Swift builds [Changes] and [SourceFile] itself. */
interface NativeFileSource {
    fun changes(since: String?): Changes
}

fun NativeFileSource.asFileSource(): FileSource = object : FileSource {
    override suspend fun changes(since: String?): Changes = withContext(Dispatchers.IO) { this@asFileSource.changes(since) }
}

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

internal fun NSData.toFloatArray(): FloatArray {
    require(length % 4u == 0uL) { "embedding of $length bytes is not Float32" }
    val out = FloatArray((length / 4u).toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
