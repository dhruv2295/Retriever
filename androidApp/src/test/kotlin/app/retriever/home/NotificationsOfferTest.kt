package app.retriever.home

import app.retriever.core.ReadingProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsOfferTest {
    private val pending = HomeUi(progress = ReadingProgress(read = 10, scheduled = 2))

    @Test fun aLaterVisitOffersProgressUntilDismissedOrEnabled() {
        assertTrue(pending.showNotificationsOffer(33, notificationsOn = false, justAsked = false))
        assertFalse(pending.showNotificationsOffer(32, notificationsOn = false, justAsked = false))
        assertFalse(pending.showNotificationsOffer(35, notificationsOn = true, justAsked = false))
        assertFalse(pending.showNotificationsOffer(35, notificationsOn = false, justAsked = true))
        assertFalse(pending.copy(notificationsOfferDismissed = true).showNotificationsOffer(35, notificationsOn = false, justAsked = false))
    }

    @Test fun emptyOrFinishedLibrariesHaveNothingToOfferButLaterPassesStillCount() {
        val finished = ReadingProgress(read = 10, nameOnly = 3, couldntRead = 1)
        for (progress in listOf(ReadingProgress(), finished, finished.copy(partlyRead = 1))) {
            assertFalse(HomeUi(progress = progress).showNotificationsOffer(35, notificationsOn = false, justAsked = false))
        }
        for (progress in listOf(
            finished.copy(notScheduled = 1),
            finished.copy(laterPasses = 1),
            finished.copy(meaningWaiting = 1),
            finished.copy(rereads = 1),
        )) {
            assertTrue(HomeUi(progress = progress).showNotificationsOffer(35, notificationsOn = false, justAsked = false))
        }
        assertTrue(HomeUi(progress = finished, facesLeft = 1).showNotificationsOffer(35, notificationsOn = false, justAsked = false))
    }
}
