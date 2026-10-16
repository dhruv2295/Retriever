package app.retriever.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Prefs(
    val kinds: Set<Kind> = Kind.entries.toSet(),
    val onlyWhileCharging: Boolean = false,
    val excludedFolders: Set<String> = emptySet(),
    /** Set by the user's Pause; no indexing runs until "Index now". */
    val paused: Boolean = false,
    /** Testing: keep only the newest N files of each kind, so a full index takes minutes, not hours. */
    val sampleSize: Int? = null,
) {
    companion object {
        fun defaultFor(profile: DeviceProfile): Prefs = Prefs(
            kinds = if (profile.tier == Tier.LIGHT) Kind.entries.toSet() - Kind.AUDIO - Kind.VIDEO else Kind.entries.toSet(),
            onlyWhileCharging = profile.bulkOnlyWhenCharging,
        )
    }
}

fun Prefs.excludes(folder: String): Boolean = excludedFolders.any { folder == it || folder.startsWith(it + "/") }

fun Prefs.effective(profile: DeviceProfile): DeviceProfile = profile.copy(
    audio = profile.model.audio && (Kind.AUDIO in kinds || Kind.VIDEO in kinds),
    videoFrameEverySec = if (Kind.VIDEO in kinds) (profile.videoFrameEverySec.takeIf { it > 0 } ?: 5) else 0,
    bulkOnlyWhenCharging = onlyWhileCharging,
)

private const val PREFS_KEY = "prefs"

private val prefsJson = Json { ignoreUnknownKeys = true }

fun Store.prefs(): Prefs = meta(PREFS_KEY)?.let {
    try {
        prefsJson.decodeFromString<Prefs>(it)
    } catch (e: Exception) {
        log("stored prefs are unreadable", e)
        null
    }
} ?: Prefs()

/** Reads, transforms and writes the prefs in one transaction, so two writers never lose each other's change. */
fun Store.updatePrefs(transform: (Prefs) -> Prefs): Prefs = inTransaction {
    transform(prefs()).also { setMeta(PREFS_KEY, prefsJson.encodeToString(it)) }
}

fun Store.hasPrefs(): Boolean = meta(PREFS_KEY) != null
