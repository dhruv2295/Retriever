package app.retriever.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class LogPrivacyTest {
    private val names = listOf("Tax Return 2025.pdf", "Passport Scan.jpg")
    private val folder = "Documents/Secret Folder"
    private val query = "zebra-confidential-query"
    private val path = "/storage/emulated/0/$folder/${names[0]}"

    private val lines = mutableListOf<String>()
    private val log: (String, Throwable?) -> Unit = { m, t -> lines += m + (t?.let { " ${it::class.simpleName}" }.orEmpty()) }

    private fun assertClean() {
        assertTrue(lines.isNotEmpty(), "the failure should have been logged")
        val all = lines.joinToString("\n")
        for (secret in names + listOf(folder, query, path, "Tax Return", "Passport")) assertFalse(secret in all, "'$secret' leaked into: $all")
    }

    @Test
    fun aFailingExtractorLogsKindMimeSizeAndClassNotTheNameOrPath() = runTest {
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = throw java.io.IOException("cannot open $path")
        }
        val source = FakeSource().apply { next = Changes(names.map { testFile(it, Kind.PDF, folder = folder) }, emptyList(), "t", isFullScan = true) }
        val hasher = object : ContentHasher {
            override suspend fun quickHash(uri: String, size: Long): String? = null
            override suspend fun fullHash(uri: String): String? = null
        }
        val r =
            Retriever(
                newStore(log = log).also {
                    it.setProfile(PROFILE)
                },
                source, extractor, FakeFactory(), FakeMonitor(), hasher, PIXEL, { true }, { true }, log, System::currentTimeMillis, { 0L },
            )
        r.indexBatch(9.minutes)

        assertClean()
        assertTrue(lines.any { "PDF" in it && "text/plain" in it && "100 B" in it && "IOException" in it }, lines.toString())
    }

    @Test
    fun aBrokenKeywordSearchLogsNoQuery() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        IndexDb.Schema.create(driver)
        val store = Store(driver, log)
        driver.execute(null, "DROP TABLE file_fts", 0)

        store.fileKeywordSearch(query, 10)

        assertClean()
    }
}
