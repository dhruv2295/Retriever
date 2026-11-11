package app.retriever.model

import android.app.DownloadManager
import android.content.Context
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.CalibrationDeferred
import app.retriever.core.ModelBuild
import app.retriever.core.ModelVariant
import app.retriever.core.Prefs
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.settings.StorageUsage
import app.retriever.settings.storageUsage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.shadows.ShadowStorageManager
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** One volume with [allocatable] bytes, free space plus caches Android may clear; Robolectric has no volumes of its own. */
@Implements(StorageManager::class)
class SpaceShadow : ShadowStorageManager() {
    @Implementation fun getUuidForPath(@Suppress("UNUSED_PARAMETER") path: File): UUID = StorageManager.UUID_DEFAULT

    @Implementation fun getAllocatableBytes(@Suppress("UNUSED_PARAMETER") uuid: UUID): Long = allocatable

    @Implementation fun allocateBytes(@Suppress("UNUSED_PARAMETER") uuid: UUID, bytes: Long) {
        if (bytes > allocatable) throw IOException("Failed to allocate $bytes")
        allocated += bytes
    }

    companion object {
        var allocatable = 4_000_000_000L
        var allocated = 0L
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, shadows = [SpaceShadow::class])
class ModelDownloadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.filesDir, "models")
    private val variant = ModelVariant.entries.first()
    private fun build(name: String) = ModelBuild(variant, name, 4, "x")

    private fun touch(f: File, bytes: Int = 1) = f.apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(bytes))
    }

    @Before fun setUp() {
        SpaceShadow.allocatable = 4_000_000_000L
        SpaceShadow.allocated = 0
    }

    @Test fun deleteModelRemovesItsFilesOnly() {
        val mine = build("emb_m740.litertlm")
        val other = build("emb_m440.litertlm")
        val keep = listOf(
            touch(File(dir, other.fileName)),
            touch(File(dir, other.fileName + ".sha256")),
            touch(File(context.cacheDir, "embed/emb_m440-GPU-vt256-t512/a")),
            touch(File(context.cacheDir, "embed/emb_m740_x-GPU-vt256-t512/a")),
        )
        val gone = listOf(
            touch(File(dir, mine.fileName)),
            touch(File(dir, mine.fileName + ".sha256")),
            touch(File(context.cacheDir, "embed/emb_m740-GPU-vt256-t512/a")),
            touch(File(context.cacheDir, "embed/emb_m740-CPU-vt0-t128/a")),
        )
        ModelDownload(context, dir).deleteModel(mine)
        gone.forEach { assertFalse(it.path, it.exists()) }
        keep.forEach { assertTrue(it.path, it.exists()) }
    }

    @Test fun resourceDeferralDuringHashKeepsTheFileUnverifiedAndAFullRetryCanVerifyIt() = runBlocking {
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val b = build("emb_hash.litertlm").copy(
            sizeBytes = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
        val dl = ModelDownload(context, dir)
        dl.file(b).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
        var checks = 0
        assertThrows(CalibrationDeferred::class.java) {
            runBlocking { dl.verify(b) { if (++checks == 3) throw CalibrationDeferred(StopReason.PAUSED) } }
        }
        assertTrue(dl.file(b).exists())
        assertFalse("an interrupted digest never writes an integrity marker", dl.ready(b))
        assertTrue("a complete retry verifies the whole retained file", dl.verify(b))
        assertTrue(dl.ready(b))
    }

    @Test fun startReadsTheMobileDataPref() {
        val dl = ModelDownload(context, dir)
        val b = build("emb_start.litertlm").copy(sizeBytes = 1)
        val manager = shadowOf(context.getSystemService(DownloadManager::class.java))
        val id = dl.start(b, Prefs(allowMobileData = true))
        assertTrue(shadowOf(manager.getRequest(id)).allowedOverMetered)
        val id2 = dl.start(b, Prefs())
        assertFalse(shadowOf(manager.getRequest(id2)).allowedOverMetered)
    }

    /** Other apps' caches count as room: Android clears them for the download (allocateBytes) before it is queued. */
    @Test fun startClearsCachesForTheModelOrQueuesNothing() {
        val dl = ModelDownload(context, dir)
        val b = build("emb_space.litertlm").copy(sizeBytes = 1_000)
        val manager = shadowOf(context.getSystemService(DownloadManager::class.java))
        SpaceShadow.allocatable = ModelDownload.neededBytes(b)
        assertEquals(ModelDownload.neededBytes(b), dl.freeBytes())
        dl.start(b, Prefs())
        assertEquals(ModelDownload.neededBytes(b), SpaceShadow.allocated)
        assertEquals(1, manager.requestCount)

        SpaceShadow.allocatable = ModelDownload.neededBytes(b) - 1
        // Room by StatFs, which ignores Android's low-storage reserve: Android's refusal still stands.
        ShadowStatFs.registerStats(dir, 1_000_000, 1_000_000, 1_000_000)
        assertFalse(dl.hasSpaceFor(b))
        assertThrows(ModelDownload.NoSpace::class.java) { dl.start(b, Prefs()) }
        assertEquals(1, manager.requestCount)
    }

    @Test fun storageUsageSumsTheDbWithItsWal() {
        val db = context.getDatabasePath(Store.DB_NAME)
        touch(db, 100)
        touch(File(db.path + "-wal"), 30)
        touch(File(db.path + "-shm"), 5)
        val b = build("emb_usage.litertlm")
        touch(File(dir, b.fileName), 7)
        touch(File(context.cacheDir, "embed/c1/f"), 11)
        touch(File(context.cacheDir, "embed/c2/f"), 13)
        assertEquals(StorageUsage(135, 7, 24), storageUsage(context, dir, b))
    }
}
