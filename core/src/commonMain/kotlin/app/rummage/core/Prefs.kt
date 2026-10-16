package app.rummage.core

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
    audio = Kind.AUDIO in kinds || Kind.VIDEO in kinds,
    videoFrameEverySec = if (Kind.VIDEO in kinds) (profile.videoFrameEverySec.takeIf { it > 0 } ?: 5) else 0,
    bulkOnlyWhenCharging = onlyWhileCharging,
)

private val prefsJson = Json { ignoreUnknownKeys = true }

fun Store.prefs(): Prefs =
    runCatching { prefsJson.decodeFromString<Prefs>(meta("prefs")!!) }.getOrDefault(Prefs())

fun Store.setPrefs(p: Prefs) = setMeta("prefs", prefsJson.encodeToString(p))

fun Store.hasPrefs(): Boolean = meta("prefs") != null
