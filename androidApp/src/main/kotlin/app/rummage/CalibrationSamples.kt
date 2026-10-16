package app.rummage

import android.content.Context
import android.net.Uri
import app.rummage.core.Backend
import app.rummage.core.Calibration
import app.rummage.core.CalibrationResult
import app.rummage.core.DeviceProfile
import app.rummage.core.Kind
import app.rummage.core.ModelVariant
import app.rummage.core.Part
import app.rummage.core.Prefs
import app.rummage.core.Samples
import app.rummage.core.Tier
import app.rummage.core.android.AndroidExtractor
import app.rummage.core.android.ImageBytes
import app.rummage.core.android.MediaDecoding
import app.rummage.core.android.MediaStoreSampler
import app.rummage.core.hasPrefs
import app.rummage.core.setPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Sampling logic copied from SpikeRunner.calibrate().
object CalibrationSamples {
    private val FALLBACK_TEXTS = listOf(
        "Lease agreement with a two month security deposit",
        "Flight booking confirmation for Bengaluru to Delhi",
        "Notes from the team meeting about the launch date",
        "Recipe for masala chai with ginger and cardamom",
        "Electricity bill for September",
        "Screenshot of an error dialog in the banking app",
        "Doctor's prescription for antibiotics",
        "Workout plan for the week",
        "Invoice 48213 for office chairs",
        "Photos from the beach trip at sunset",
    )

    suspend fun collect(context: Context, sampler: MediaStoreSampler, extractor: AndroidExtractor): Samples {
        val sample = sampler.sample(mapOf(Kind.PHOTO to 10, Kind.PDF to 5, Kind.DOC to 5, Kind.AUDIO to 3))
        val images = sample.filter { it.kind == Kind.PHOTO }
            .mapNotNull { ImageBytes.jpeg(context, Uri.parse(it.uri), 512) }
        val tmp = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 0, true, false)
        val texts = mutableListOf<String>()
        for (f in sample.filter { it.kind == Kind.PDF || it.kind == Kind.DOC }) {
            if (texts.size >= 10) break
            texts += extractor.extract(f, tmp).flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
        }
        val sampleTexts = (texts.take(10) + FALLBACK_TEXTS).take(maxOf(10, texts.take(10).size))
        val audio = sample.filter { it.kind == Kind.AUDIO }.take(3).mapNotNull {
            MediaDecoding.windows(MediaDecoding.decodeAudio16kMono(context, Uri.parse(it.uri), 30))
                .firstOrNull()?.second?.takeIf { w -> w.isNotEmpty() }
        }
        return Samples(images, sampleTexts, audio)
    }
}

/** Shared by Setup step 4 and Status "Tune again". Waits for any in-flight index batch. */
suspend fun calibrate(graph: AppGraph, context: Context, tuneAgain: Boolean): CalibrationResult {
    IndexScheduler.pause(context)
    try {
        return graph.indexLock.withLock {
            graph.releaseEmbedder()
            val r = withContext(Dispatchers.Default) {
                Calibration(graph.factory).run(
                    CalibrationSamples.collect(context, MediaStoreSampler(context), graph.extractor),
                    graph.monitor.state().ramGb,
                )
            }
            // A different vision-token count changes the index signature, which re-reads every file.
            // Tune again keeps the count the index was built with; only backend and pacing change.
            val old = graph.store.profile()
            val profile = if (tuneAgain && old != null) r.profile.copy(visionTokens = old.visionTokens) else r.profile
            graph.store.setProfile(profile)
            if (!graph.store.hasPrefs()) graph.store.setPrefs(Prefs.defaultFor(profile))
            r
        }
    } finally {
        // Even when the tune fails or is cancelled: pause() above stopped the queued runs.
        if (tuneAgain) IndexScheduler.indexNow(context)
    }
}
