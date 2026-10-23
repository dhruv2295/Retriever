package app.retriever.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaV3Test {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { IndexDb.Schema.create(it) }
    private val store = Store(driver).also { it.resetForModel(SIG) }

    private fun vector(seed: Int) = Vector.of(FloatArray(DIMS) { if (it == seed % DIMS) 1f else 0f }, SIG)

    private fun index(file: SourceFile, vararg chunks: Store.NewChunk): Long {
        store.upsertFiles(listOf(file))
        val id = store.fileByUri(file.uri)!!.id
        store.replaceChunks(id, chunks.toList())
        return id
    }

    /**
     * Every char in the tokenchars literal of Index.sq and 2.sqm must be a combining mark this JDK knows. Marks the JDK
     * knows that the literal lacks are only printed: the literal is persisted FTS config, so it is never regenerated
     * (changing it means a migration), and an older JDK's Unicode must not fail the build.
     */
    @Test
    fun tokencharsAreCombiningMarksOutsideTheFoldedDiacriticBlocks() {
        val generated = generatedTokenchars()
        val pattern = Regex("\"tokenchars=([^\"]*)\"")
        for (name in listOf("Index.sq", "2.sqm")) {
            val found = pattern.findAll(File(SQL_DIR, name).readText()).map { it.groupValues[1] }.toList()
            assertEquals(2, found.size, "both FTS tables in $name")
            for (literal in found) {
                val unknown = literal.filter { it !in generated }.map { "U+%04X".format(it.code) }
                assertTrue(unknown.isEmpty(), "$name: not combining marks on this JDK: $unknown")
                val newer = generated.filter { it !in literal }.map { "U+%04X".format(it.code) }
                if (newer.isNotEmpty()) println("WARNING: $name tokenchars lacks ${newer.size} marks this JDK knows: $newer")
            }
        }
    }

    @Test
    fun wordsInAbugidasStayWholeAndAccentsStillFold() {
        val words = listOf("বিদ্যুৎ", "மின்சாரம்", "విద్యుత్", "ไฟฟ้า", "बिजली")
        index(testFile("bills.txt"), Store.NewChunk(1, "", words.joinToString(" ") + " Café", vector(1)))
        driver.execute(null, "CREATE VIRTUAL TABLE terms USING fts4aux(chunk_fts)", 0)
        val terms = driver.executeQuery(null, "SELECT term FROM terms WHERE col = '*'", { c ->
            app.cash.sqldelight.db.QueryResult.Value(buildList { while (c.next().value) add(c.getString(0)!!) })
        }, 0).value
        for (word in words) assertTrue(word in terms, "$word is one token, got $terms")

        for (query in listOf("cafe", "café", "CAFE")) assertEquals(1, store.keywordSearch(query, 10).size, query)
        for (word in words) assertEquals(1, store.keywordSearch(word, 10).size, word)
    }

    @Test
    fun starsAreKeptByUriAndSurviveAModelReset() {
        val a = testFile("a.pdf", Kind.PDF)
        val b = testFile("b.pdf", Kind.PDF)
        store.upsertFiles(listOf(a, b))
        store.setStarred(a.uri, true, at = 10)
        store.setStarred(b.uri, true, at = 20)
        store.setStarred("content://test/unknown", true, at = 30)

        assertEquals(setOf(a.uri, b.uri), store.starredUris(), "only a known file can be starred")
        assertEquals(listOf(b, a), store.starredFiles(10).map { it.file }, "most recently starred first")
        assertEquals(2L, store.starredCount())

        store.resetForModel(Signature("OTHER/vt70/d256"))
        assertEquals(2L, store.starredCount())

        store.setStarred(b.uri, false, at = 40)
        assertEquals(setOf(a.uri), store.starredUris())
    }

    @Test
    fun aStarOnARemovedFileIsHiddenAndComesBackWithTheFile() {
        val a = testFile("a.pdf", Kind.PDF)
        store.upsertFiles(listOf(a))
        store.setStarred(a.uri, true, at = 1)
        store.removeFiles(listOf(a.uri))
        assertEquals(0L, store.starredCount())
        assertEquals(emptyList(), store.starredFiles(10))
        assertEquals(emptySet(), store.starredUris())
        // Restored from the trash under the same URI: starred again.
        store.upsertFiles(listOf(a))
        assertEquals(setOf(a.uri), store.starredUris())
    }

    @Test
    fun allFilterQueryFindsOldFilesByCaptureTimeAndStars() {
        val old = testFile("old.jpg", Kind.PHOTO, modifiedAt = 9_000).copy(takenAt = 100)
        val recent = (1..20).map { testFile("new$it.jpg", Kind.PHOTO, modifiedAt = 10_000L + it) }
        store.upsertFiles(recent + old)
        assertEquals(listOf(old.uri), store.captured(setOf(Kind.PHOTO), 0, 1_000, false, 5).map { it.file.uri })
        assertEquals(listOf("new20.jpg", "new19.jpg"), store.captured(setOf(Kind.PHOTO), Long.MIN_VALUE, Long.MAX_VALUE, false, 2).map { it.file.name })
        assertEquals(emptyList(), store.captured(setOf(Kind.PHOTO), Long.MIN_VALUE, Long.MAX_VALUE, true, 5))
        store.setStarred(old.uri, true, at = 1)
        assertEquals(listOf(old.uri), store.captured(setOf(Kind.PHOTO), Long.MIN_VALUE, Long.MAX_VALUE, true, 5).map { it.file.uri })
    }

    @Test
    fun aChangedFileTakesItsNewCaptureTime() {
        val a = testFile("a.jpg", Kind.PHOTO, modifiedAt = 1).copy(takenAt = 100)
        store.upsertFiles(listOf(a))
        store.upsertFiles(listOf(a.copy(modifiedAt = 2, takenAt = 200)))
        assertEquals(200L, store.fileByUri(a.uri)!!.file.takenAt)
    }

    @Test
    fun longUriListsRunInPartsUnderAndroid11sVariableLimit() {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        app.retriever.core.db.IndexDb.Schema.create(driver)
        // sqlite-jdbc is on the runtime classpath only, so its limit API is reached by name.
        val sqlite = driver.getConnection().unwrap(Class.forName("org.sqlite.SQLiteConnection"))
        val limits = Class.forName("org.sqlite.SQLiteLimits")
        sqlite.javaClass.getMethod("setLimit", limits, Int::class.javaPrimitiveType)
            .invoke(sqlite, limits.getField("SQLITE_LIMIT_VARIABLE_NUMBER").get(null), 999)
        val small = Store(driver)
        val files = (1..1_200).map { testFile("f$it.txt") }
        small.upsertFiles(files)
        assertEquals(1_200, small.fileIds(files.map { it.uri }).size)
        assertEquals(1_200, small.nameEntries(files.map { it.uri }).size)
    }

    @Test
    fun filesByIdsAndFirstTextsInOneQueryEach() {
        val pdf = index(
            testFile("lease.pdf", Kind.PDF),
            Store.NewChunk(1, "p. 1", null, vector(1)),
            Store.NewChunk(2, "p. 1", "rent is due monthly", vector(2)),
            Store.NewChunk(3, "p. 1", "the deposit is refundable", vector(3)),
            Store.NewChunk(4, "p. 2", "signed by both", vector(4)),
        )
        val photo = index(testFile("IMG_1.jpg", Kind.PHOTO), Store.NewChunk(1, "", null, vector(5)))

        val files = store.files(listOf(pdf, photo, 999L))
        assertEquals(setOf(pdf, photo), files.keys)
        assertEquals("IMG_1.jpg", files.getValue(photo).name)
        assertEquals(emptyMap(), store.files(emptyList()))

        val texts = store.firstTextAt(listOf(pdf to "p. 1", pdf to "p. 2", photo to "", pdf to "p. 9"))
        assertEquals(mapOf((pdf to "p. 1") to "rent is due monthly", (pdf to "p. 2") to "signed by both"), texts)
        assertEquals(mapOf((pdf to "p. 1") to "rent is due monthly"), store.firstTextAt(listOf(pdf to "p. 1")))
    }

    @Test
    fun kindByStateCountsSumToTheStateAndKindCounts() = runBlocking {
        store.upsertFiles(listOf(testFile("a.pdf", Kind.PDF), testFile("b.pdf", Kind.PDF), testFile("c.jpg", Kind.PHOTO), testFile("d.jpg", Kind.PHOTO)))
        index(testFile("a.pdf", Kind.PDF), Store.NewChunk(1, "", "words", vector(1)))
        store.markFailed(store.fileByUri("content://test/c.jpg")!!.id, 3)

        val counts = store.countsNow()
        assertEquals(
            mapOf(
                (Kind.PDF to FileState.INDEXED) to 1L,
                (Kind.PDF to FileState.PENDING) to 1L,
                (Kind.PHOTO to FileState.SKIPPED) to 1L,
                (Kind.PHOTO to FileState.PENDING) to 1L,
            ),
            counts.byKindState,
        )
        assertEquals(store.countsByState(), counts.byState)
        assertEquals(mapOf(Kind.PDF to 2L, Kind.PHOTO to 2L), counts.byKind)
    }

    @Test
    fun nameOnlyCountsIndexedFilesWithNothingInsideInFullAndTextOnlyMode() {
        // Full mode: a file nothing could be read from keeps only its name vector, at ordinal 0.
        index(testFile("blank.pdf", Kind.PDF), Store.NewChunk(0, "", null, vector(1)))
        index(testFile("empty.pdf", Kind.PDF))
        index(testFile("lease.pdf", Kind.PDF), Store.NewChunk(1, "p. 1", "rent", vector(2)))
        store.upsertFiles(listOf(testFile("waiting.pdf", Kind.PDF)))
        assertEquals(2L, store.nameOnlyCount())

        // Text-only mode: no vectors at all, and images keep no chunk.
        val textOnly = newStore(sig = null)
        for ((file, chunks) in listOf(
            testFile("IMG_1.jpg", Kind.PHOTO) to emptyList(),
            testFile("notes.txt") to listOf(Store.NewChunk(1, "", "notes", null)),
        )) {
            textOnly.upsertFiles(listOf(file))
            textOnly.replaceChunks(textOnly.fileByUri(file.uri)!!.id, chunks)
        }
        assertEquals(1L, textOnly.nameOnlyCount())
    }

    @Test
    fun homeFactsComeFromSmallQueries() {
        store.upsertFiles(
            listOf(
                testFile("old.pdf", Kind.PDF, modifiedAt = 1, folder = "Docs"),
                testFile("new.txt", Kind.DOC, modifiedAt = 3, folder = "Docs"),
                testFile("mid.pdf", Kind.PDF, modifiedAt = 2, folder = "Download", size = 200L * 1024 * 1024),
                testFile("IMG_1.jpg", Kind.PHOTO, modifiedAt = 9, folder = "DCIM", size = 150L * 1024 * 1024),
            ),
        )
        assertEquals(listOf("new.txt", "mid.pdf"), store.newestFiles(setOf(Kind.PDF, Kind.DOC), 2).map { it.file.name })
        assertEquals(3L, store.folderCount())
        assertEquals(2L to 350L * 1024 * 1024, store.largeFiles(100L * 1024 * 1024))
        assertEquals(0L to 0L, store.largeFiles(Long.MAX_VALUE))
    }

    @Test
    fun takenAtIsKeptAndNeverQueuesAFileAgain() {
        val day = 86_400_000L
        val photo = testFile("IMG_1.jpg", Kind.PHOTO, modifiedAt = 40 * day, takenAt = 10 * day)
        val id = index(photo, Store.NewChunk(1, "", null, vector(1)))
        assertEquals(10 * day, store.fileByUri(photo.uri)!!.file.takenAt)

        // A source that doesn't say, or says something else, changes nothing.
        store.upsertFiles(listOf(photo.copy(takenAt = null)))
        store.upsertFiles(listOf(photo.copy(takenAt = 20 * day)))
        store.fileByUri(photo.uri)!!.let {
            assertEquals(10 * day, it.file.takenAt)
            assertEquals(FileState.INDEXED, it.state)
        }

        // A file synced before taken_at existed is filled in without being read again.
        val old = testFile("IMG_2.jpg", Kind.PHOTO, modifiedAt = 40 * day)
        index(old, Store.NewChunk(1, "", null, vector(2)))
        store.upsertFiles(listOf(old.copy(takenAt = 30 * day)))
        store.fileByUri(old.uri)!!.let {
            assertEquals(30 * day, it.file.takenAt)
            assertEquals(FileState.INDEXED, it.state)
        }
        assertTrue(store.pending(10).isEmpty())

        // The vector table carries the capture day, falling back to the modified day.
        val docId = index(testFile("a.txt", modifiedAt = 41 * day + 5), Store.NewChunk(1, "", "words", vector(3)))
        val table = store.loadVectors()
        val days = (0 until table.size).associate { table.fileId(it) to table.day(it) }
        assertEquals(mapOf(id to 10, store.fileByUri(old.uri)!!.id to 30, docId to 41), days)
    }

    @Test
    fun keywordSearchFiltersByCaptureDate() {
        val day = 86_400_000L
        index(testFile("march.jpg", Kind.PHOTO, modifiedAt = 90 * day, takenAt = 60 * day), Store.NewChunk(1, "", "receipt", vector(1)))
        index(testFile("may.pdf", Kind.PDF, modifiedAt = 120 * day), Store.NewChunk(1, "", "receipt", vector(2)))
        val march = 59 * day..61 * day - 1

        assertEquals(2, store.keywordSearch("receipt", 10).size)
        val inMarch = store.keywordSearch("receipt", 10, dates = march).map { store.chunk(it.chunkId)!!.fileId }
        assertEquals(listOf(store.fileByUri("content://test/march.jpg")!!.id), inMarch)
        assertEquals(emptyList(), store.keywordSearch("receipt", 10, kinds = setOf(Kind.PDF), dates = march))
        assertEquals(1, store.keywordSearch("receipt", 10, kinds = setOf(Kind.PDF)).size)

        assertEquals(listOf(store.fileByUri("content://test/march.jpg")!!.id), store.fileKeywordSearch("march", 10, dates = march))
        assertEquals(emptyList(), store.fileKeywordSearch("may", 10, dates = march))
    }

    @Test
    fun eachVectorRowKnowsWhatItWasMadeFrom() {
        index(testFile("IMG_1.jpg", Kind.PHOTO), Store.NewChunk(1, "", null, vector(1)))
        index(testFile("shot.png", Kind.SCREENSHOT), Store.NewChunk(1, "", null, vector(2)), Store.NewChunk(2, "", "long ocr text", vector(3)))
        index(testFile("song.mp3", Kind.AUDIO), Store.NewChunk(1, "0:00", null, vector(4)))
        index(testFile("clip.mp4", Kind.VIDEO), Store.NewChunk(1, "0:05", null, vector(5)))
        index(testFile("blank.pdf", Kind.PDF), Store.NewChunk(0, "", null, vector(6)))
        val table = store.loadVectors()
        assertEquals(
            listOf(Modality.IMAGE, Modality.IMAGE, Modality.TEXT, Modality.AUDIO, Modality.IMAGE_TEXT, Modality.TEXT),
            (0 until table.size).map { table.modality(it) },
        )
        assertEquals(Kind.SCREENSHOT, table.kind(2), "the kind is untouched")
    }

    @Test
    fun newPrefsDefaultWhenAnOlderAppSavedThem() {
        store.setMeta("prefs", """{"kinds":["PHOTO"],"paused":true}""")
        val prefs = store.prefs()
        assertTrue(prefs.paused)
        assertFalse(prefs.allowMobileData)
        assertTrue(prefs.rememberSearches)
        assertTrue(prefs.dynamicColor)
        assertNull(prefs.sampleSize)
    }

    companion object {
        val SQL_DIR = File("src/commonMain/sqldelight/app/retriever/core/db")

        /** Combining marks unicode61 would split words at; remove_diacritics=2 already folds the generic blocks. */
        fun generatedTokenchars(): String = buildString {
            val folded = listOf(0x0300..0x036F, 0x1AB0..0x1AFF, 0x1DC0..0x1DFF, 0x20D0..0x20FF, 0xFE20..0xFE2F)
            for (c in 0..0xFFFF) {
                val ch = c.toChar()
                if (ch.category != CharCategory.NON_SPACING_MARK && ch.category != CharCategory.COMBINING_SPACING_MARK) continue
                if (folded.none { c in it }) append(ch)
            }
        }
    }
}
