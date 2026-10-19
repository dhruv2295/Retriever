package app.retriever.core

/** How far the person has answered setup, kept in Store meta so every stage resumes after process death. */
enum class SetupMark { NONE, STARTED, ACCESS_CHOSEN, TRYING, CHOOSING }

internal const val SETUP_MARK_KEY = "setup_mark"
internal const val SETUP_SKIPPED_KEY = "setup_skipped"

/** What setup shows. TRY and CHOOSE follow the person's taps on the sample screen; DONE ends setup. */
enum class SetupStage { WELCOME, ACCESS, SAMPLE, TRY, CHOOSE, DONE }

/**
 * What the app runs next; each is safe to run twice. PLAN: [Retriever.planOrCommit]. READ_SAMPLE: read the plan in
 * sight and schedule the rest of it. SCHEDULE: schedule reading as Prefs say. Run READ_SAMPLE and SCHEDULE once per process.
 */
enum class SetupStep { NONE, PLAN, READ_SAMPLE, SCHEDULE }

/**
 * Setup's inputs. [Retriever.setup] fills every field but [accessGiven], which only the platform knows: photos, media or
 * file access granted, or a folder added. [skipped]: Go to Home, or Search by name, was chosen; the sample still reads.
 * [decided]: the person chose what happens after the sample, or never had one. [planned]: the sample's files, 0 before the plan.
 */
data class SetupInputs(
    val mark: SetupMark = SetupMark.NONE,
    val skipped: Boolean = false,
    val decided: Boolean = false,
    val planned: Int = 0,
    val accessGiven: Boolean = false,
) {
    fun withAccess(given: Boolean): SetupInputs = copy(accessGiven = given)

    /** Access was given and confirmed, so there is a library to read and search. */
    val libraryChosen: Boolean get() = mark >= SetupMark.ACCESS_CHOSEN && accessGiven

    val stage: SetupStage get() = when {
        mark == SetupMark.NONE -> SetupStage.WELCOME
        !libraryChosen -> SetupStage.ACCESS
        skipped || decided -> SetupStage.DONE
        mark == SetupMark.CHOOSING -> SetupStage.CHOOSE
        mark == SetupMark.TRYING -> SetupStage.TRY
        else -> SetupStage.SAMPLE
    }

    /** Open on setup at launch, else on Home. Skipping never skips access: without it there is nothing to search. */
    val showSetup: Boolean get() = stage != SetupStage.DONE && (!skipped || stage == SetupStage.ACCESS)

    val step: SetupStep get() = when {
        !libraryChosen -> SetupStep.NONE
        decided -> SetupStep.SCHEDULE
        planned == 0 -> SetupStep.PLAN
        else -> SetupStep.READ_SAMPLE
    }
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
