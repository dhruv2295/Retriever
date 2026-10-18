package app.retriever.core.android

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import app.retriever.core.ContentHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

class AndroidContentHasher(private val context: Context) : ContentHasher {

    override suspend fun quickHash(uri: String, size: Long): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(size.toString().toByteArray())
            open(uri)?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    val buf = ByteArray(EDGE)
                    val head = input.read(buf)
                    if (head > 0) digest.update(buf, 0, head)
                    if (size > EDGE) {
                        channel.position(maxOf(EDGE.toLong(), size - EDGE))
                        val tail = input.read(buf)
                        if (tail > 0) digest.update(buf, 0, tail)
                    }
                }
            } ?: return@withContext null
            digest.digest().toHexString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun fullHash(uri: String): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val stream = context.contentResolver.openInputStream(uri.toUri()) ?: return@withContext null
            stream.use {
                val buf = ByteArray(1 shl 20)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = it.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().toHexString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun open(uri: String): ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri.toUri(), "r")

    private companion object {
        const val EDGE = 64 * 1024
    }
}
