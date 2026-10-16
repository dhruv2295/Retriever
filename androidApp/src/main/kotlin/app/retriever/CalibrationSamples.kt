package app.retriever

import android.content.Context
import android.net.Uri
import app.retriever.core.Backend
import app.retriever.core.CalibrationResult
import app.retriever.core.DeviceProfile
import app.retriever.core.FileRules
import app.retriever.core.Kind
import app.retriever.core.ModelVariant
import app.retriever.core.Part
import app.retriever.core.Samples
import app.retriever.core.SourceFile
import app.retriever.core.Tier
import app.retriever.core.android.ImageBytes
import app.retriever.core.android.MediaDecoding
import app.retriever.core.audioWindowSec
import app.retriever.core.audioWindows

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

    /** A throwaway profile for reading sample files before any profile exists. */
    fun readingProfile(variant: ModelVariant) =
        DeviceProfile(Tier.BALANCED, variant, Backend.CPU, 70, 5, variant.audio, false)

    /** The newest files of each kind in [quota], from a full scan of the library. */
    suspend fun newest(graph: AppGraph, quota: Map<Kind, Int>): List<SourceFile> =
        FileRules.pickSamples(graph.source.changes(null).upserted, quota, { it.kind }, { it.modifiedAt })

    suspend fun collect(context: Context, graph: AppGraph): Samples {
        val variant = graph.retriever.requiredBuild.value.variant
        val sample = newest(graph, mapOf(Kind.PHOTO to 10, Kind.PDF to 5, Kind.DOC to 5, Kind.AUDIO to 3))
        val images = sample.filter { it.kind == Kind.PHOTO }
            .mapNotNull { ImageBytes.jpeg(context, Uri.parse(it.uri), 512) }
        val profile = readingProfile(variant)
        val texts = mutableListOf<String>()
        for (f in sample.filter { it.kind == Kind.PDF || it.kind == Kind.DOC }) {
            if (texts.size >= 10) break
            texts += graph.extractor.extract(f, profile).flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
        }
        val sampleTexts = (texts.take(10) + FALLBACK_TEXTS).take(maxOf(10, texts.take(10).size))
        val audio = if (!variant.audio) emptyList() else sample.filter { it.kind == Kind.AUDIO }.take(3).mapNotNull {
            audioWindows(MediaDecoding.decodeAudio16kMono(context, Uri.parse(it.uri), 30), audioWindowSec(profile.maxTokens))
                .firstOrNull()?.second?.takeIf { w -> w.isNotEmpty() }
        }
        return Samples(images, sampleTexts, audio)
    }
}

/** Shared by Setup and Status "Tune again". The facade stops any running batch and holds indexing meanwhile. */
suspend fun calibrate(graph: AppGraph, context: Context, tuneAgain: Boolean): CalibrationResult {
    try {
        return graph.retriever.calibrate(CalibrationSamples.collect(context, graph), tuneAgain)
    } finally {
        // calibrate() cancelled the running batch; start again, even when the tune failed.
        if (tuneAgain) IndexScheduler.indexNow(context)
    }
}
