@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalUnsignedTypes::class)

package app.retriever.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSData
import kotlin.coroutines.cancellation.CancellationException

/**
 * SHA-256 over [NativeExtractor.readBytes], hashing what AndroidContentHasher hashes. ph:// assets are exported
 * to a temp file by the extractor, so photos and videos group by content like any other file.
 */
class IosContentHasher(private val native: NativeExtractor) : ContentHasher {
    override suspend fun quickHash(uri: String, size: Long): String? = io {
        sha256 { update ->
            update(size.toString().encodeToByteArray().toNSData())
            update(native.readBytes(uri, 0, EDGE) ?: return@sha256 false)
            if (size > EDGE) native.readBytes(uri, maxOf(EDGE.toLong(), size - EDGE), EDGE)?.let(update)
            true
        }
    }

    override suspend fun fullHash(uri: String): String? = io {
        sha256 { update ->
            var offset = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                // One pool per chunk, or a multi-GB video's NSData chunks pile up until the hash is done.
                val read = autoreleasepool {
                    native.readBytes(uri, offset, CHUNK)?.also(update)?.length?.toLong() ?: -1L
                }
                if (read < 0) return@sha256 false
                if (read == 0L) break
                offset += read
            }
            true
        }
    }

    private suspend fun io(block: suspend () -> String?): String? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Hex digest of what [feed] passes to its update function, or null when [feed] returns false. */
    private inline fun sha256(feed: ((NSData) -> Unit) -> Boolean): String? = memScoped {
        val ctx = alloc<CC_SHA256_CTX>()
        CC_SHA256_Init(ctx.ptr)
        if (!feed { CC_SHA256_Update(ctx.ptr, it.bytes, it.length.toUInt()) }) return null
        val digest = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
        CC_SHA256_Final(digest, ctx.ptr)
        UByteArray(CC_SHA256_DIGEST_LENGTH) { digest[it] }.toHexString()
    }

    private companion object {
        const val EDGE = 64 * 1024
        const val CHUNK = 1 shl 20
    }
}
