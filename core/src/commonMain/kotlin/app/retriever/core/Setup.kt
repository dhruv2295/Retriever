package app.retriever.core

/** How far the person has answered setup, kept in Store meta so every stage resumes after process death. */
enum class SetupMark {
    NONE,
    ACCESS_CHOSEN,
    CHOOSING,
    ;

    internal companion object {
        /** A saved mark: TRYING (the Try screen, gone) resumes the demo; one this version never had (STARTED) reads as NONE. */
        fun saved(name: String?): SetupMark? = if (name == "TRYING") ACCESS_CHOSEN else entries.firstOrNull { it.name == name }
    }
}

internal const val SETUP_MARK_KEY = "setup_mark"
internal const val SETUP_SKIPPED_KEY = "setup_skipped"

/** What setup shows. WELCOME holds the access ask; SAMPLE is the demo; CHOOSE follows its Continue or the welcome's skip; DONE ends setup. */
enum class SetupStage { WELCOME, SAMPLE, CHOOSE, DONE }

/**
 * What the app runs next; each is safe to run twice. PLAN: [Retriever.planOrCommit]. READ_SAMPLE: read the plan in
 * sight and schedule the rest of it. SCHEDULE: schedule reading as Prefs say. Run READ_SAMPLE and SCHEDULE once per process.
 */
enum class SetupStep { NONE, PLAN, READ_SAMPLE, SCHEDULE }

/**
 * Setup's inputs. [Retriever.setup] fills every field but [accessGiven], which only the platform knows: photos, media or
 * file access granted, or a folder added. [skipped]: Skip the demo was chosen, so setup goes to the choice. [decided]: the
 * person chose what happens after the demo, or never had one. [planned]: the demo's files, 0 before the plan.
 */
data class SetupInputs(
    val mark: SetupMark = SetupMark.NONE,
    val skipped: Boolean = false,
    val decided: Boolean = false,
    val planned: Int = 0,
    val accessGiven: Boolean = false,
) {
    fun withAccess(given: Boolean): SetupInputs = copy(accessGiven = given)

    /** The demo is still the person's to watch: neither stopped (its Continue, or Skip the demo) nor decided. */
    val demoRunning: Boolean get() = !decided && !skipped && mark != SetupMark.CHOOSING

    /** Access was given and confirmed, so there is a library to read and search. */
    val libraryChosen: Boolean get() = mark >= SetupMark.ACCESS_CHOSEN && accessGiven

    val stage: SetupStage get() = when {
        !libraryChosen -> SetupStage.WELCOME
        decided -> SetupStage.DONE
        skipped || mark == SetupMark.CHOOSING -> SetupStage.CHOOSE
        else -> SetupStage.SAMPLE
    }

    /** Open on setup at launch until a choice is made, else on Home. */
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
    /**
     * Back from the choice: to the demo (its done state), or to the welcome after Skip the demo ([skipped]); null where
     * setup has no screen to go back to. [Retriever.setSetupMark] NONE clears the skip.
     */
    fun back(stage: SetupStage, skipped: Boolean): SetupMark? = when {
        stage != SetupStage.CHOOSE -> null
        skipped -> SetupMark.NONE
        else -> SetupMark.ACCESS_CHOSEN
    }

    /**
     * The demo's Continue: the choice of when to process the rest, or, null, with nothing beyond the demo ([nothingLeft]:
     * core's [ReadingProgress.notScheduled] is 0, an unknown count is not), process everything with nothing to choose.
     */
    fun afterDemo(nothingLeft: Boolean): SetupMark? = if (nothingLeft) null else SetupMark.CHOOSING
}

/**
 * The one upgrade, "Search by meaning and what's in your photos": OFFER, then the platform's download, then the tune,
 * then the meaning pass over content read without the model. UNAVAILABLE on a phone too small for the model.
 */
enum class UpgradeStage { UNAVAILABLE, OFFER, DOWNLOAD, TUNE, MEANING, DONE }

object UpgradePolicy {
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
     * Home's compact row: the offer once the person has searched and until dismissed, then the download and the tune.
     * The meaning pass needs no row: the reading status says "Adding search by meaning".
     */
    fun showOnHome(stage: UpgradeStage, prefs: Prefs): Boolean = when (stage) {
        UpgradeStage.OFFER -> prefs.searchTried && !prefs.upgradeOfferDismissed
        UpgradeStage.DOWNLOAD, UpgradeStage.TUNE -> true
        else -> false
    }
}
