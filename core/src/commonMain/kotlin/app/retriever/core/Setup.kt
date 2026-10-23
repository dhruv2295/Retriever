package app.retriever.core

/** How far the person has answered setup, kept in Store meta so every stage resumes after process death. Ordered. */
enum class SetupMark {
    NONE,

    /** Get started on Hello: the access screen next, and the model download under way. */
    GREETED,
    ACCESS_CHOSEN,
    ;

    internal companion object {
        /**
         * A saved mark. TRYING (the Try screen) and CHOOSING (the old choice after the demo) resume the first search, whose
         * Continue now ends setup; one this version never had (STARTED) reads as NONE.
         */
        fun saved(name: String?): SetupMark? = if (name == "TRYING" || name == "CHOOSING") ACCESS_CHOSEN else entries.firstOrNull { it.name == name }
    }
}

internal const val SETUP_MARK_KEY = "setup_mark"
internal const val SETUP_SKIPPED_KEY = "setup_skipped"

/** What setup shows: Hello, the access screen, the first search on the recent files, then Home. */
enum class SetupStage { WELCOME, ACCESS, SAMPLE, DONE }

/**
 * What the app runs next; each is safe to run twice. PLAN: [Retriever.planOrCommit]. READ_SAMPLE: read the plan in
 * sight and schedule the rest of it. SCHEDULE: schedule reading as Prefs say. Run READ_SAMPLE and SCHEDULE once per process.
 */
enum class SetupStep { NONE, PLAN, READ_SAMPLE, SCHEDULE }

/**
 * Setup's inputs. [Retriever.setup] fills every field but [accessGiven], which only the platform knows: photos, media or
 * file access granted, or a folder added. [skipped]: an older version's Skip the demo, kept so a saved one still stops
 * the demo. [decided]: setup finished (the first search's Continue), or never had a demo. [planned]: the demo's files,
 * 0 before the plan.
 */
data class SetupInputs(
    val mark: SetupMark = SetupMark.NONE,
    val skipped: Boolean = false,
    val decided: Boolean = false,
    val planned: Int = 0,
    val accessGiven: Boolean = false,
) {
    fun withAccess(given: Boolean): SetupInputs = copy(accessGiven = given)

    /** The demo is still the person's to watch: not ended by the first search's Continue (or an older Skip). */
    val demoRunning: Boolean get() = !decided && !skipped

    /** Access was given and confirmed, so there is a library to read and search. */
    val libraryChosen: Boolean get() = mark >= SetupMark.ACCESS_CHOSEN && accessGiven

    val stage: SetupStage get() = when {
        mark < SetupMark.GREETED -> SetupStage.WELCOME
        !libraryChosen -> SetupStage.ACCESS
        decided -> SetupStage.DONE
        else -> SetupStage.SAMPLE
    }

    /** Open on setup at launch until the first search's Continue, else on Home. */
    val showSetup: Boolean get() = stage != SetupStage.DONE

    val step: SetupStep get() = when {
        !libraryChosen -> SetupStep.NONE
        decided -> SetupStep.SCHEDULE
        planned == 0 -> SetupStep.PLAN
        else -> SetupStep.READ_SAMPLE
    }
}

/** The person's moves between setup's screens, the same on both platforms. */
object SetupPolicy {
    /** Back from the access screen to Hello; null elsewhere (access given is not taken back by a Back). */
    fun back(stage: SetupStage): SetupMark? = if (stage == SetupStage.ACCESS) SetupMark.NONE else null

    /** Get started starts the one-time download at once on a phone that can run the model and doesn't have it. */
    fun downloadOnGetStarted(textOnly: Boolean, modelPresent: Boolean): Boolean = !textOnly && !modelPresent
}

/**
 * Search by description (the model): OFFER, then the platform's download, then the tune, then the pass that adds it to
 * content processed without the model. UNAVAILABLE on a phone too small for the model.
 */
enum class UpgradeStage { UNAVAILABLE, OFFER, DOWNLOAD, TUNE, MEANING, DONE }

/** Where the platform's download is, in the words both apps share. */
enum class DownloadPhase { NONE, RUNNING, WAITING_FOR_WIFI, CHECKING, FAILED, NO_SPACE }

/** Search by description as the first search and Home's one-time card say it. */
enum class DescriptionState { READY, DOWNLOADING, WAITING_FOR_WIFI, PREPARING, FAILED, NO_SPACE, NOT_STARTED, UNAVAILABLE }

object UpgradePolicy {
    /** One state for both apps' setup screens and Home card, from the upgrade's stage and the platform's download. */
    fun description(stage: UpgradeStage, download: DownloadPhase): DescriptionState = when (stage) {
        UpgradeStage.UNAVAILABLE -> DescriptionState.UNAVAILABLE

        UpgradeStage.TUNE -> DescriptionState.PREPARING

        UpgradeStage.MEANING, UpgradeStage.DONE -> DescriptionState.READY

        UpgradeStage.OFFER, UpgradeStage.DOWNLOAD -> when (download) {
            DownloadPhase.NONE -> if (stage == UpgradeStage.OFFER) DescriptionState.NOT_STARTED else DescriptionState.DOWNLOADING
            DownloadPhase.RUNNING -> DescriptionState.DOWNLOADING
            DownloadPhase.WAITING_FOR_WIFI -> DescriptionState.WAITING_FOR_WIFI
            DownloadPhase.CHECKING -> DescriptionState.PREPARING
            DownloadPhase.FAILED -> DescriptionState.FAILED
            DownloadPhase.NO_SPACE -> DescriptionState.NO_SPACE
        }
    }

    /**
     * [downloading]: the person chose Download and the platform's download is queued, running, waiting for Wi-Fi, checking
     * or failed. [setUp]: [Retriever.setUp]. [meaningWaiting]: [ReadingProgress.meaningWaiting].
     */
    fun stage(textOnly: Boolean, modelPresent: Boolean, downloading: Boolean, setUp: Boolean, meaningWaiting: Long): UpgradeStage = when {
        textOnly -> UpgradeStage.UNAVAILABLE
        !modelPresent -> if (downloading) UpgradeStage.DOWNLOAD else UpgradeStage.OFFER
        !setUp -> UpgradeStage.TUNE
        meaningWaiting > 0 -> UpgradeStage.MEANING
        else -> UpgradeStage.DONE
    }

    /**
     * Home's compact row: the offer until dismissed (an install from before setup downloaded it gets it at once), then the
     * download and the tune. Hidden while the one-time card ([Prefs.homeIntroDue]) says the same. The pass over earlier
     * content needs no row: the reading status says it.
     */
    fun showOnHome(stage: UpgradeStage, prefs: Prefs): Boolean = !prefs.homeIntroDue && when (stage) {
        UpgradeStage.OFFER -> !prefs.upgradeOfferDismissed
        UpgradeStage.DOWNLOAD, UpgradeStage.TUNE -> true
        else -> false
    }
}
