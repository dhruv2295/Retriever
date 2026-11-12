package app.retriever.home

import app.retriever.access.Access
import app.retriever.access.Photos
import app.retriever.access.PickedFolder
import app.retriever.core.DeviceWords
import app.retriever.core.ReadingPolicy
import app.retriever.core.ReadingProgress
import app.retriever.search.FEW_FOR_WIDER_SEARCH
import app.retriever.search.showsWiderSearch
import app.retriever.settings.accessLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a person with partial access learns that more can be searched: Home's done card, Settings and few results. */
class WidenAccessTest {
    private val partial = Access(allFiles = false, photos = Photos.ALL, folders = listOf(PickedFolder("a", "A"), PickedFolder("b", "B")))
    private val allRead = ReadingProgress(read = 3_300)
    private val done = ReadingPolicy.decide(allRead, null, false, false, DeviceWords.ANDROID)
    private val ui = HomeUi(progress = allRead, access = partial)

    @Test fun homeOffersTheWholePhoneOnlyWhenDoneWithPartialAccessAndNotDismissed() {
        assertTrue(ui.showWidenAccess(done))
        val reading = ReadingPolicy.decide(ReadingProgress(read = 1_200, scheduled = 2_100), null, true, true, DeviceWords.ANDROID)
        assertFalse("still processing", ui.showWidenAccess(reading))
        assertFalse("all files already", ui.copy(access = Access.ALL).showWidenAccess(done))
        assertFalse("Not now", ui.copy(widenAccessOfferDismissed = true).showWidenAccess(done))
        assertFalse("under the one-time card", ui.copy(introDue = true).showWidenAccess(done))
    }

    @Test fun searchSaysWhatItLeftOutOnlyUnderFewResultsFromPartialAccess() {
        assertTrue(showsWiderSearch(partial, 0))
        assertTrue(showsWiderSearch(partial, FEW_FOR_WIDER_SEARCH))
        assertFalse(showsWiderSearch(partial, FEW_FOR_WIDER_SEARCH + 1))
        assertFalse(showsWiderSearch(Access.ALL, 0))
    }

    @Test fun settingsNamesWhatRetrieverSees() {
        assertEquals("Photos and 2 folders, not your whole phone", accessLine(partial))
        assertEquals("1 folder, not your whole phone", accessLine(Access(allFiles = false, folders = listOf(PickedFolder("a", "A")))))
        assertEquals("Your whole phone", accessLine(Access.ALL))
        assertNull(accessLine(Access.NONE))
    }
}
