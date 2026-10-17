package app.retriever.core

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Replays a relevance-fit dump (RelevanceDump, made on a simulator by scripts/ios-dump.sh) through SearchEngine's own
 * scan and RelevanceRule's own histogram statistics. [replayDump] runs only with -Drelevance.dump=<dump.bin> and
 * -Drelevance.data=<prepared dir>; it writes one TSV row per query and modality group to -Drelevance.out (default:
 * the dump's path + .tsv); -Drelevance.sample=N replays a seeded N-file library instead,
 * and -Drelevance.minRows overrides the rule's thin-group cut. The other tests run in the default gate on a small synthetic dump.
 */
class RelevanceReplayTest {
    /** One query in one modality group: the group's noise, the right file's best chunk and the best wrong file's. */
    class Row(
        val query: DumpQuery,
        val group: Modality,
        val n: Int,
        val noise: RelevanceRule.Noise?,
        val rightFile: String?,
        val rightCos: Float,
        val rightFileRank: Int?,
        val rightChunkRank: Int?,
        val wrongFile: String?,
        val wrongCos: Float,
        val wrongFileRank: Int?,
    ) {
        val rightExcess get() = if (rightFile == null) Double.NaN else noise?.excess(rightCos) ?: Double.NaN
        val wrongExcess get() = if (wrongFile == null) Double.NaN else noise?.excess(wrongCos) ?: Double.NaN
    }

    private class NoEmbedder(override val signature: Signature) : Embedder {
        override val backend = Backend.CPU

        override suspend fun embedRaw(parts: List<Part>): FloatArray = error("the replay never embeds")

        override fun close() {}
    }

    /**
     * Every query of [queries] against the dumped library minus the files its construction leaves out, scanned over
     * every admitted row (not only search's top 100, so the right and best wrong files are always seen).
     */
    private fun replay(dump: RelevanceDump.Dump, manifest: List<DumpFile>, queries: List<DumpQuery>, rule: RelevanceRule = RelevanceRule()): List<Row> {
        val sig = Signature(dump.signature)
        val table = VectorTable.withCapacity(dump.files.sumOf { it.chunks.size })
        var chunkId = 0L
        dump.files.forEachIndexed { i, f ->
            for (c in f.chunks) table.append(++chunkId, i + 1L, c.bytes, c.scale, f.kind, c.modality, 0)
        }
        val names = dump.files.map { it.id }
        val idOf = names.withIndex().associate { (i, name) -> name to i + 1L }
        val dataset = manifest.associate { it.file to it.dataset }
        val vectors = dump.queries.associate { it.id to Vector(it.bytes, it.scale, sig) }
        val engine = SearchEngine(Store(testDriver()), NoEmbedder(sig), rule = rule)
        return queries.parallelStream().map { q ->
            val qv = checkNotNull(vectors[q.id]) { "query ${q.id} is not in the dump" }
            val excluded = q.excludeFiles.toSet()
            val admitted = if (excluded.isEmpty() && q.excludeDataset == null) null else {
                names.filter { it !in excluded && dataset[it] != q.excludeDataset }.map { idOf.getValue(it) }.toLongArray().apply { sort() }
            }
            val relevant = q.relevant.mapNotNull { idOf[it] }.toSet()
            val h = rule.histogram()
            val s = engine.scan(qv, table.size, table, fileIds = admitted, histogram = h)
            val noise = rule.noise(h)
            val seen = HashSet<Long>()
            var right: Triple<Int, Int, Int>? = null // row index, file rank, chunk rank
            val wrong = arrayOfNulls<Int>(Modality.entries.size)
            val wrongRank = arrayOfNulls<Int>(Modality.entries.size)
            for (i in s.rows.indices) {
                val file = table.fileIds[s.rows[i]]
                seen += file
                val g = table.modalities[s.rows[i]].toInt()
                if (file in relevant) {
                    if (right == null) right = Triple(i, seen.size, i + 1)
                } else if (wrong[g] == null) {
                    wrong[g] = i
                    wrongRank[g] = seen.size
                }
            }
            Modality.entries.mapNotNull { g ->
                val n = (0 until RelevanceRule.BINS).sumOf { h[g.ordinal * RelevanceRule.BINS + it] }
                if (n == 0) return@mapNotNull null
                val r = right?.takeIf { table.modalities[s.rows[it.first]].toInt() == g.ordinal }
                val w = wrong[g.ordinal]
                Row(
                    q, g, n, noise[g.ordinal],
                    r?.let { names[table.fileIds[s.rows[it.first]].toInt() - 1] }, r?.let { s.cos[it.first] } ?: Float.NaN, r?.second, r?.third,
                    w?.let { names[table.fileIds[s.rows[it]].toInt() - 1] }, w?.let { s.cos[it] } ?: Float.NaN, wrongRank[g.ordinal],
                )
            }
        }.toList().flatten()
    }

    @Test
    fun replayDump() {
        val dumpPath = System.getProperty("relevance.dump")
        assumeTrue("set -Drelevance.dump=<dump.bin> -Drelevance.data=<prepared dir> to replay a dump", dumpPath != null)
        val data = File(checkNotNull(System.getProperty("relevance.data")) { "-Drelevance.data is required" })
        val full = RelevanceDump.read(File(dumpPath).readBytes())
        val manifest = parseDumpManifest(File(data, "manifest.json").readText())
        val inDump = full.queries.map { it.id }.toSet()
        // -Drelevance.sample=N: a seeded library of about N files with the same kind mix (the library-size sweep);
        // match queries whose right files all fell out are dropped.
        val sample = System.getProperty("relevance.sample")?.toInt()
        val dump = if (sample == null) full else {
            val kept = full.files.groupBy { it.kind }.values.flatMap { fs ->
                fs.shuffled(Random(17)).take(Math.round(sample.toDouble() * fs.size / full.files.size).toInt())
            }.map { it.id }.toSet()
            RelevanceDump.Dump(full.signature, full.visionTokens, full.files.filter { it.id in kept }, full.queries, full.validBytes)
        }
        val inLibrary = dump.files.map { it.id }.toSet()
        val queries = parseDumpQueries(File(data, "queries.json").readText())
            .filter { it.id in inDump && (it.kind != "match" || it.relevant.any { r -> r in inLibrary }) }
        println("RelevanceReplay: ${dump.signature}, ${dump.files.size} files, ${dump.files.sumOf { it.chunks.size }} chunks, ${queries.size} queries")
        val rows = replay(dump, manifest, queries, RelevanceRule(minRows = System.getProperty("relevance.minRows")?.toInt() ?: RelevanceRule.MIN_ROWS))
        val out = File(System.getProperty("relevance.out") ?: "$dumpPath.tsv")
        out.printWriter().use { w ->
            w.println(
                "signature\tquery\tdataset\tlang\tsplit\tkind\tconstruction\tnoisy\tgroup\tn\tmedian\tsigma\texpected_max\t" +
                    "right_file\tright_cos\tright_excess\tright_file_rank\tright_chunk_rank\twrong_file\twrong_cos\twrong_excess\twrong_file_rank",
            )
            for (r in rows) {
                val q = r.query
                w.println(
                    listOf(
                        dump.signature, q.id, q.dataset, q.lang, q.split, q.kind, q.construction.orEmpty(), q.noisy, r.group, r.n,
                        r.noise?.median ?: "", r.noise?.sigma ?: "", r.noise?.expectedMax ?: "",
                        r.rightFile.orEmpty(), r.rightCos.orBlank(), r.rightExcess.orBlank(), r.rightFileRank ?: "", r.rightChunkRank ?: "",
                        r.wrongFile.orEmpty(), r.wrongCos.orBlank(), r.wrongExcess.orBlank(), r.wrongFileRank ?: "",
                    ).joinToString("\t"),
                )
            }
        }
        println("RelevanceReplay: ${rows.size} rows to $out")
        // Medians per (dataset, kind or construction, group): a glance, not the fit.
        println("dataset\tquery kind\tgroup\tqueries\tmedian right excess\tmedian best-wrong excess")
        rows.groupBy { Triple(it.query.dataset, it.query.construction ?: it.query.kind, it.group) }.toSortedMap(compareBy({ it.first }, { it.second }, { it.third })).forEach { (k, rs) ->
            println("${k.first}\t${k.second}\t${k.third}\t${rs.size}\t${median(rs.map { it.rightExcess })}\t${median(rs.map { it.wrongExcess })}")
        }
    }

    @Test
    fun readerKeepsWholeRecordsAndResumeRebuildsTheSameDump() = runBlocking {
        val (manifest, queries) = synthetic()
        val full = dumpOf(manifest, queries)
        val dump = RelevanceDump.read(full)
        assertEquals(SIG.value, dump.signature)
        assertEquals(70, dump.visionTokens)
        assertEquals(manifest.map { it.file }, dump.files.map { it.id })
        assertEquals(queries.map { it.id }, dump.queries.map { it.id })
        assertTrue(dump.files.filter { it.kind == Kind.PHOTO }.all { f -> f.chunks.single().modality == Modality.IMAGE })
        assertTrue(dump.files.filter { it.kind == Kind.DOC }.all { f -> f.chunks.single().modality == Modality.TEXT })
        assertEquals(full.size, dump.validBytes)

        // A run killed mid-record: the torn record is dropped, and a resumed run writes only what is missing.
        val torn = RelevanceDump.read(full.copyOf(full.size - 7))
        assertEquals(queries.size - 1, torn.queries.size)
        val resumed = ByteArrayOutputStream().apply { write(full, 0, torn.validBytes) }
        dumpLibrary(manifest, queries, FakeExtractor, TopicEmbedder, PROFILE, torn.files.map { it.id }.toSet(), torn.queries.map { it.id }.toSet(), resumed::write) {}
        assertContentEquals(full, resumed.toByteArray())

        // Torn inside the files: the queries all come after.
        val early = RelevanceDump.read(full.copyOf(full.size / 3))
        assertTrue(early.files.size in 1 until manifest.size && early.queries.isEmpty())
    }

    @Test
    fun replayMeasuresTheRightAndBestWrongFilePerGroup() = runBlocking {
        val (manifest, queries) = synthetic()
        val rows = replay(RelevanceDump.read(dumpOf(manifest, queries)), manifest, queries).groupBy { it.query.id }

        val doc = rows.getValue("doc7").associateBy { it.group }
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), doc.keys)
        assertEquals(DOCS, doc.getValue(Modality.TEXT).n)
        assertEquals(PHOTOS, doc.getValue(Modality.IMAGE).n)
        val t = doc.getValue(Modality.TEXT)
        assertEquals("doc_7.txt", t.rightFile)
        assertEquals(1, t.rightFileRank)
        assertEquals(1, t.rightChunkRank)
        assertTrue(t.rightExcess > 3 && t.wrongExcess < 1, "right ${t.rightExcess}, wrong ${t.wrongExcess}")
        assertNull(doc.getValue(Modality.IMAGE).rightFile)

        val photo = rows.getValue("photo3").associateBy { it.group }.getValue(Modality.IMAGE)
        assertEquals("photo_3.jpg", photo.rightFile)
        assertTrue(photo.rightExcess > 3)

        // Held out: the right file is gone from the library and from its group's statistics.
        val held = rows.getValue("doc7~ho").associateBy { it.group }.getValue(Modality.TEXT)
        assertNull(held.rightFile)
        assertEquals(DOCS - 1, held.n)
        assertTrue(held.wrongFile != "doc_7.txt" && held.wrongExcess < 1)
        // Absent domain: no docs at all, so only the photo group is left.
        assertEquals(listOf(Modality.IMAGE), rows.getValue("doc7~ab").map { it.group })
    }

    private object FakeExtractor : Extractor {
        override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = when (file.kind) {
            Kind.PHOTO -> listOf(Piece("", listOf(Part.Image(file.name.topicBytes())), null))
            else -> listOf(Piece("", listOf(Part.Text("about topic ${file.name.topic()}")), "about topic ${file.name.topic()}"))
        }
    }

    /** "topic K" text and an image whose bytes say K both embed near topic K's direction; anything else is noise. */
    private object TopicEmbedder : Embedder {
        override val signature = SIG
        override val backend = Backend.CPU

        override suspend fun embedRaw(parts: List<Part>): FloatArray {
            val (key, input) = when (val p = parts.single()) {
                is Part.Image -> p.jpeg.decodeToString().let { it to "image $it" }
                is Part.Text -> Regex("topic (\\S+)").find(p.text)!!.groupValues[1] to p.text
                else -> error("no audio")
            }
            val base = Random(key.hashCode().toLong())
            val noise = Random(input.hashCode().toLong())
            return FloatArray(DIMS) { (base.nextGaussian() + 0.3 * noise.nextGaussian()).toFloat() }
        }

        override fun close() {}
    }

    private fun synthetic(): Pair<List<DumpFile>, List<DumpQuery>> {
        val files = List(PHOTOS) { DumpFile("photo_$it.jpg", "photo", "pics", "$it") } + List(DOCS) { DumpFile("doc_$it.txt", "doc", "texts", "$it") }
        fun q(id: String, text: String, relevant: List<String>, kind: String = "match", construction: String? = null, exclude: List<String> = emptyList(), dataset: String? = null) =
            DumpQuery(id, text, "en", "texts", "calibration", relevant, kind, construction, exclude, dataset)
        val queries = listOf(
            q("doc7", "topic 7", listOf("doc_7.txt")),
            q("photo3", "topic p3", listOf("photo_3.jpg")),
            q("doc7~ho", "topic 7", emptyList(), "nomatch", "heldout_target", exclude = listOf("doc_7.txt")),
            q("doc7~ab", "topic 7", emptyList(), "nomatch", "absent_domain", dataset = "texts"),
        )
        return files to queries
    }

    private suspend fun dumpOf(manifest: List<DumpFile>, queries: List<DumpQuery>): ByteArray {
        val out = ByteArrayOutputStream().apply { write(RelevanceDump.header(SIG, 70)) }
        dumpLibrary(manifest, queries, FakeExtractor, TopicEmbedder, PROFILE, emptySet(), emptySet(), out::write) {}
        return out.toByteArray()
    }

    private companion object {
        val SIG = Signature("M440/92dcbea1/vt70/d256/t512")
        val PROFILE = DeviceProfile(Tier.BALANCED, ModelVariant.M440, Backend.CPU, 70, 0, false, false)
        const val PHOTOS = 220
        const val DOCS = 260

        /** photo_3.jpg is topic p3; doc_7.txt is topic 7. */
        fun String.topic() = substringAfter('_').substringBefore('.').let { if (startsWith("photo")) "p$it" else it }
        fun String.topicBytes() = topic().encodeToByteArray()

        fun Float.orBlank(): Any = if (isNaN()) "" else this
        fun Double.orBlank(): Any = if (isNaN()) "" else this

        fun median(xs: List<Double>): String = xs.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) "-" else "%.2f".format(it[it.size / 2]) }
    }
}
