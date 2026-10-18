package app.retriever.core

/** A NaN or Inf in a model output means a broken backend (seen on GPU delegates), never a valid vector. */
fun hasNonFinite(v: FloatArray): Boolean = v.any { !it.isFinite() }

/** The modality to probe after [parts] failed: a broken vision or audio encoder passes a text probe. */
fun probeKindFor(parts: List<Part>): Modality = when {
    parts.any { it is Part.Audio } -> Modality.AUDIO
    parts.any { it is Part.Image } -> Modality.IMAGE
    else -> Modality.TEXT
}
