package app.retriever.model

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import app.retriever.R
import app.retriever.core.Backend
import app.retriever.core.CalibrationDeferred
import app.retriever.core.CalibrationResult
import app.retriever.core.DeviceProfile
import app.retriever.core.EMBED_PX
import app.retriever.core.ModelVariant
import app.retriever.core.Retriever
import app.retriever.core.Samples
import app.retriever.core.StopReason
import app.retriever.core.Tier
import app.retriever.core.android.ImageBytes
import app.retriever.core.android.MediaDecoding
import app.retriever.core.audioWindowSec
import app.retriever.core.audioWindows
import app.retriever.indexing.IndexService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object CalibrationSamples {
    /** A throwaway profile for reading sample files before any profile exists. */
    fun readingProfile(variant: ModelVariant) = DeviceProfile(Tier.BALANCED, variant, Backend.CPU, 70, 5, variant.audio, false)
}

/** Reads a few of the person's newest photos to tune the model on this phone; shared by Setup and Status "Tune again". */
class Calibrator(private val context: Context, private val retriever: Retriever, private val index: IndexService) {
    /** The facade stops any running batch and holds indexing meanwhile. */
    suspend fun calibrate(tuneAgain: Boolean): CalibrationResult {
        retriever.calibrationBlocker()?.let { throw CalibrationDeferred(it) }
        try {
            return retriever.calibrate(samples(), tuneAgain)
        } finally {
            // calibrate() cancelled the running batch; start again, even when the tune failed.
            if (tuneAgain) index.indexNow()
        }
    }

    private val firstTune = Mutex()

    /**
     * The tune after the download, from Setup in front or a background run, whichever comes first; the other then finds
     * it done and gets null. Also null on a phone without the model to tune.
     */
    suspend fun tuneOnce(): CalibrationResult? = firstTune.withLock {
        retriever.refreshSetUp()
        if (retriever.textOnly || !retriever.modelPresent.value || retriever.setUp.value == true) return@withLock null
        calibrate(tuneAgain = false).also { retriever.refreshSetUp() }
    }

    /** The core's one GPU retest after a Tune turned the GPU down; a run calls it before its batch, so no batch is cancelled. */
    suspend fun retestGpuIfDue(): CalibrationResult? = retriever.retestGpuIfDue { samples() }

    /** The newest photos (as iOS fetches them), else the bundled illustrations; core adds the stand-in texts. */
    private suspend fun samples(): Samples = withContext(Dispatchers.IO) {
        val owner = currentCoroutineContext()
        fun checkResources() {
            owner.ensureActive()
            retriever.calibrationBlocker()?.let { throw CalibrationDeferred(it) }
        }
        checkResources()
        val variant = retriever.requiredBuild.value.variant
        val images = newest(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 10).mapNotNull {
            checkResources()
            ImageBytes.jpeg(context, it, EMBED_PX)
        }
            .ifEmpty {
                listOf(R.drawable.illus_welcome, R.drawable.illus_reading, R.drawable.illus_empty).mapNotNull {
                    checkResources()
                    ImageBytes.jpeg(context.resources, it, EMBED_PX)
                }
            }
        val window = audioWindowSec(CalibrationSamples.readingProfile(variant).maxTokens)
        val audio = if (!variant.audio) {
            emptyList()
        } else {
            newest(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 3).mapNotNull {
                checkResources()
                try {
                    audioWindows(MediaDecoding.decodeAudio16kMono(context, it, 30), window).firstOrNull()?.second?.takeIf { w -> w.isNotEmpty() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            }
        }
        checkResources()
        Samples(images, emptyList(), audio)
    }

    /** One limited, sorted MediaStore query instead of a full sync scan; empty without access. */
    private fun newest(collection: Uri, limit: Int): List<Uri> = runCatching {
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
            List(c.count) {
                c.moveToNext()
                ContentUris.withAppendedId(collection, c.getLong(0))
            }
        }
    }.getOrNull().orEmpty()
}

/** UI tuning waits outside calibration's locks; automatic readers catch deferral and keep name updates running. */
internal suspend fun <T> Retriever.whenCalibrationAllowed(running: () -> Unit, deferred: (StopReason) -> Unit, operation: suspend () -> T): T {
    while (true) {
        val blocker = calibrationBlocker()
        if (blocker != null) {
            deferred(blocker)
            delay(if (blocker == StopReason.UNPLUGGED || blocker == StopReason.PAUSED || blocker == StopReason.POWER_SAVE) 30.seconds else 1_000.milliseconds)
            continue
        }
        running()
        try {
            return operation()
        } catch (e: CalibrationDeferred) {
            deferred(e.reason)
            delay(
                if (e.reason == StopReason.UNPLUGGED || e.reason == StopReason.PAUSED ||
                    e.reason == StopReason.POWER_SAVE
                ) {
                    30.seconds
                } else {
                    1_000.milliseconds
                },
            )
        }
    }
}
