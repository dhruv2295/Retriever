package app.retriever.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The sample's files for the pile and See all: plan order, plan indices, and what of each is findable. */
class SampleFilesTest {
    @Test
    fun planOrderIndicesReadAndNotesSurviveATrim() = runBlocking {
        withTimeout(20.seconds) {
            val docs = (1..30).map { testFile("doc$it.txt", Kind.DOC, modifiedAt = it * 7_200_000L) }
            val store = newStore(Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens))
            var n = 0
            lateinit var r: Retriever
            // Every third file has no words inside: done, but Name only.
            val pieces = docs.filterIndexed { i, _ -> i % 3 != 0 }.associate { it.name to listOf(textPiece("words of ${it.name}")) }
            r = testRetriever(store, docs, FakeExtractor(pieces) { if (++n == 12) r.cancelBatch() }).apply { uiVisible = true }
            r.planTaste()
            store.setProfile(PROFILE)
            r.refreshSetUp()
            r.taste()

            val files = r.sampleFiles().first()
            val plan = store.tasteIds()
            assertEquals(plan.indices.toList(), files.map { it.index })
            assertEquals(plan.map { store.file(it)!!.uri }, files.map { it.file.uri }, "plan order")
            assertEquals(r.tasteProgress().first().read, files.count { it.read }, "read only with content")
            for (f in files) {
                val expected = when {
                    f.read -> null
                    store.fileByUri(f.file.uri)!!.state == FileState.INDEXED -> "Name only"
                    else -> "Waiting to read"
                }
                assertEquals(expected, f.note, f.file.name)
            }
            assertTrue(files.any { it.read } && files.any { it.note == "Name only" } && files.any { it.note == "Waiting to read" }, files.toString())
            assertTrue(files.dropWhile { it.note != "Waiting to read" }.all { it.note == "Waiting to read" }, "the plan reads in order")

            // A trim drops pending files only, all after the read ones, so every read file keeps its index.
            store.setMeta(TASTE_BUDGET_KEY, "0")
            r.trimTaste()
            val trimmed = r.sampleFiles().first()
            assertTrue(trimmed.size < files.size, "the trim dropped pending files")
            assertEquals(files.filter { it.read }, trimmed.filter { it.read })
        }
    }
}
