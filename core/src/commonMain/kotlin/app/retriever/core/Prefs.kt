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
    val allowMobileData: Boolean = false,
    val rememberSearches: Boolean = true,
    /** Android only: colours from the wallpaper. */
    val dynamicColor: Boolean = true,
    /** Setup has begun, so a relaunch resumes it instead of starting over. */
    val setupStarted: Boolean = false,
    /** The person dismissed the ask for notification access. */
    val notificationsDismissed: Boolean = false,
    /** How many times the person has seen "No matches"; the screen switches to NO_MATCH_QUIET after a few. */
    val noMatchSeen: Int = 0,
    /** Home shows "On this day" unless the person hides it. */
    val showOnThisDay: Boolean = true,
    val readingCycle: Long = 0,
    val dismissedReadingCycle: Long = -1,

) {
    val caughtUpDismissed: Boolean get() = dismissedReadingCycle == readingCycle

    fun readingStarted(): Prefs = if (caughtUpDismissed) copy(readingCycle = readingCycle + 1) else this

    fun dismissCaughtUp(): Prefs = copy(dismissedReadingCycle = readingCycle)

    companion object {
        fun defaultFor(profile: DeviceProfile): Prefs = Prefs(
            // LIGHT keeps video at its sparse 30 s keyframes; audio windows are dense, so they stay off.
            kinds = if (profile.tier == Tier.LIGHT) Kind.entries.toSet() - Kind.AUDIO else Kind.entries.toSet(),
            onlyWhileCharging = profile.bulkOnlyWhenCharging,
        )
    }

    /**
     * A re-tune moved the defaults from [old]'s to [new]'s: every setting still at the old default takes the new one.
     * On the first tune there is no [old] profile, so the untouched [Prefs] defaults stand in for it.
     */
    fun retuned(old: DeviceProfile?, new: DeviceProfile): Prefs {
        val from = old?.let(::defaultFor) ?: Prefs()
        val to = defaultFor(new)
        return copy(
            kinds = Kind.entries.filter { k -> if ((k in kinds) == (k in from.kinds)) k in to.kinds else k in kinds }.toSet(),
            onlyWhileCharging = if (onlyWhileCharging == from.onlyWhileCharging) to.onlyWhileCharging else onlyWhileCharging,
        )
    }
}

/**
 * Kinds whose stored chunks no longer match what a re-tune from [old] to [new] asks for: kinds just switched on (they
 * were stored by name only), and videos when their moment spacing or sound changed.
 */
fun rereadAfterRetune(old: Prefs, oldProfile: DeviceProfile, new: Prefs, newProfile: DeviceProfile): Set<Kind> {
    val was = old.effective(oldProfile)
    val now = new.effective(newProfile)
    val video = Kind.VIDEO in new.kinds && (was.videoFrameEverySec != now.videoFrameEverySec || was.audio != now.audio)
    return (new.kinds - old.kinds) + (if (video) setOf(Kind.VIDEO) else emptySet())
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
