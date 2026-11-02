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
    fun open(modelPath: String, gpu: Boolean, visionGpu: Boolean, visionTokens: Int, maxTokens: Int, cacheDir: String): NativeEmbedder?

    val lastMessage: String?
}

/** File access by URI ("ph://<localIdentifier>", "folder://..."). Null means unreadable; never throw. */
interface NativeExtractor {
    fun imageJpeg(uri: String, maxSide: Int): NSData?

    /**
     * One decode of an image ([page] -1, at core's OCR size) or one render of a PDF page (about 1600 px) serves the
     * OCR, the classifier labels (images only) and the model's JPEG of at most [jpegSide]. Null when there is nothing to read.
     */
    fun scan(uri: String, page: Int, jpegSide: Int): NativeScan?

    /** -1 when locked or unreadable. */
    fun pdfPageCount(uri: String): Int

    fun pdfPageText(uri: String, page: Int): String?

    fun pdfPageJpeg(uri: String, page: Int, maxSide: Int): NSData?

    fun videoDurationMs(uri: String): Long

    fun videoFrameJpeg(uri: String, ms: Long, maxSide: Int): NSData?

    /** The frame [videoFrameJpeg] gives, with its classifier labels; no OCR, so [NativeScan.text] is empty. */
    fun videoFrameScan(uri: String, ms: Long, maxSide: Int): NativeScan?

    /** In archive order. */
    fun zipEntryNames(uri: String): List<String>

    fun zipEntryText(uri: String, name: String, maxBytes: Int): String?

    /** Up to [length] bytes at [offset]; empty at the end of the file, null when unreadable. folder:// only: photos are never hashed. */
    fun readBytes(uri: String, offset: Long, length: Int): NSData?
}

/** What [NativeExtractor.scan] read: [labels] are classifier labels only, never OCR text; null for a PDF page. */
class NativeScan(val jpeg: NSData, val text: String, val labels: String?)

/**
 * People on iOS: Vision finds the faces and their landmarks, [FaceAlign] places each one, SFace gives its fingerprint.
 * Null when the photo can't be opened; empty when it has no face clear enough. Must be thread-safe.
 */
interface NativeFaceReader {
    fun faces(uri: String): List<NativeFace>?
}

/** The face's box as fractions of the upright photo; [vector] is SFace's [FACE_DIMS] Float32s. */
class NativeFace(val left: Float, val top: Float, val right: Float, val bottom: Float, val vector: NSData)

internal class IosFaceReader(private val native: NativeFaceReader) : FaceReader {
    override suspend fun faces(uri: String): List<FoundFace>? = withContext(Dispatchers.IO) {
        native.faces(uri)?.map { FoundFace(FaceBox(it.left, it.top, it.right, it.bottom), it.vector.toFloatArray()) }
    }
}

/** What the index already holds for a URI, so a source can skip lookups for files that haven't changed. */
fun interface KnownFiles {
    fun file(uri: String): SourceFile?
}

/** Swift builds [Changes] and [SourceFile] itself. */
interface NativeFileSource {
    fun changes(since: String?, known: KnownFiles): Changes
}

fun NativeFileSource.asFileSource(known: KnownFiles): FileSource = object : FileSource {
    override suspend fun changes(since: String?): Changes = withContext(Dispatchers.IO) { this@asFileSource.changes(since, known) }
}

internal fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

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
