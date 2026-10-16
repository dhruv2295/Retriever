package app.rummage.core.android

import android.app.ActivityManager
import android.content.ContentUris
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.MediaStore
import app.rummage.core.DeviceMonitor
import app.rummage.core.DeviceState
import app.rummage.core.Kind
import app.rummage.core.SourceFile
import app.rummage.core.Thermal

class AndroidDeviceMonitor(context: Context) : DeviceMonitor {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val ramGb by lazy {
        ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }.advertisedMem / 1e9
    }

    override fun state(): DeviceState = DeviceState(
        ramGb = ramGb,
        charging = batteryManager.isCharging,
        batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        thermal = thermal(powerManager.currentThermalStatus),
    )

    private fun thermal(status: Int): Thermal = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> Thermal.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> Thermal.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> Thermal.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> Thermal.SEVERE
        else -> Thermal.CRITICAL
    }
}

class MediaStoreSampler(private val context: Context) {

    fun sample(quota: Map<Kind, Int>): List<SourceFile> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val counts = mutableMapOf<Kind, Int>()
        val out = mutableListOf<SourceFile>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.SIZE} > 0",
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (c.moveToNext() && !quotaFull(counts, quota)) {
                val name = c.getString(nameCol).orEmpty()
                val relativePath = c.getString(pathCol).orEmpty()
                val mime = c.getString(mimeCol) ?: "application/octet-stream"
                if (shouldSkip(name, relativePath)) continue
                val kind = classify(mime, relativePath, name)
                if ((counts[kind] ?: 0) >= (quota[kind] ?: 0)) continue
                counts[kind] = (counts[kind] ?: 0) + 1
                out += SourceFile(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)).toString(),
                    name = name,
                    folder = relativePath.removeSuffix("/"),
                    mime = mime,
                    kind = kind,
                    size = c.getLong(sizeCol),
                    modifiedAt = c.getLong(dateCol) * 1000,
                )
            }
        }
        return out
    }

    private fun quotaFull(counts: Map<Kind, Int>, quota: Map<Kind, Int>): Boolean =
        Kind.entries.all { (counts[it] ?: 0) >= (quota[it] ?: 0) }

    companion object {
        // Android/media holds WhatsApp and other apps' shared media (most photos on a typical phone);
        // only Android/data and Android/obb are private to their apps.
        fun shouldSkip(name: String, relativePath: String): Boolean =
            relativePath.startsWith("Android/data/", ignoreCase = true) ||
                relativePath.startsWith("Android/obb/", ignoreCase = true) ||
                relativePath.contains("/.") ||
                relativePath.startsWith(".") ||
                name.startsWith(".")

        private val DOC_EXTENSIONS = setOf(
            "txt", "md", "csv", "json", "xml", "html", "kt", "java", "py", "js", "ts", "swift",
            "go", "rs", "c", "cpp", "h", "yaml", "yml", "log", "docx", "pptx", "xlsx",
        )

        fun classify(mime: String, relativePath: String, name: String): Kind = when {
            mime.startsWith("image/") ->
                if (relativePath.contains("screenshot", ignoreCase = true) ||
                    name.contains("screenshot", ignoreCase = true)
                ) Kind.SCREENSHOT else Kind.PHOTO
            mime.startsWith("video/") -> Kind.VIDEO
            mime.startsWith("audio/") -> Kind.AUDIO
            mime == "application/pdf" -> Kind.PDF
            isDoc(mime, name) -> Kind.DOC
            else -> Kind.OTHER
        }

        private fun isDoc(mime: String, name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return mime.startsWith("text/") ||
                mime == "application/json" ||
                mime.contains("officedocument") ||
                mime.contains("msword") ||
                ext in DOC_EXTENSIONS
        }
    }
}
