package app.retriever.model

import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.ModelBuild
import app.retriever.core.ModelVariant
import app.retriever.core.Prefs
import app.retriever.core.Store
import app.retriever.settings.StorageUsage
import app.retriever.settings.storageUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ModelDownloadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.filesDir, "models")
    private val variant = ModelVariant.entries.first()
    private fun build(name: String) = ModelBuild(variant, name, 4, "x")

    private fun touch(f: File, bytes: Int = 1) = f.apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(bytes))
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

    @Test fun startReadsTheMobileDataPref() {
        dir.mkdirs()
        org.robolectric.shadows.ShadowStatFs.registerStats(dir, 1_000_000, 900_000, 900_000)
        val dl = ModelDownload(context, dir)
        val b = build("emb_start.litertlm").copy(sizeBytes = 1)
        val manager = shadowOf(context.getSystemService(DownloadManager::class.java))
        val id = dl.start(b, Prefs(allowMobileData = true))
        assertTrue(shadowOf(manager.getRequest(id)).allowedOverMetered)
        val id2 = dl.start(b, Prefs())
        assertFalse(shadowOf(manager.getRequest(id2)).allowedOverMetered)
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
