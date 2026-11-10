package app.retriever.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ReadersReadyTest {
    /**
     * Android's text and label readers come from Play services after install. A file read without them would keep no words
     * or labels and not be read again, so a batch waits for them, then reads.
     */
    @Test
    fun noFileIsReadBeforeTheReadersAreHere() = runBlocking {
        val files = (1..3).map { testFile("p$it.jpg", Kind.PHOTO) }
        var asked = 0
        val extractor = FakeExtractor(onExtract = { assertTrue(asked > 1, "read before the readers were here") })
        extractor.ready = { ++asked > 1 }
        val store = newStore()
        val r = testRetriever(store, files, extractor)
        store.setProfile(PROFILE)
        r.indexBatch(1.minutes)
        assertEquals(2, asked, "missing once, then waited for")
        assertEquals(3, extractor.names.size)
        assertEquals(false, r.state.value.readersMissing)
    }
}
