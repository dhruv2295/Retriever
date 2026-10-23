package app.retriever.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** 50k synthetic names: build <= 300 ms, query p95 <= 2 ms, arrays <= 8 MB (budgets from the wave 5 plan, P2). */
class NameIndexBenchTest {
    private val rng = Random(7)
    private val syllables =
        listOf("ka", "lo", "mi", "ra", "te", "su", "pa", "no", "vi", "da", "ben", "sol", "tur", "man", "lin", "gar", "pro", "tec", "ion", "est")
    private val word = { (1..rng.nextInt(2, 5)).joinToString("") { syllables[rng.nextInt(syllables.size)] } }
    private val vocab = List(6_000) { word() }
    private val folders = List(400) { "Files/" + vocab[rng.nextInt(vocab.size)] }
    private val kinds = Kind.entries

    private fun name(i: Int) = when (i % 5) {
        0 -> "IMG_2024%02d%02d_%06d.jpg".format(i % 12 + 1, i % 28 + 1, i)
        1 -> "Screenshot_2024-%02d-%02d-%02d-%02d.png".format(i % 12 + 1, i % 28 + 1, i % 24, i % 60)
        else -> (1..rng.nextInt(1, 4)).joinToString("_") { vocab[rng.nextInt(vocab.size)] } + (if (i % 3 == 0) "$i" else "") + ".pdf"
    }

    private val entries = List(50_000) { NameEntry(it.toLong(), name(it), folders[rng.nextInt(folders.size)], kinds[it % kinds.size]) }

    @Test fun fiftyThousandNames() {
        // Warm the JIT on a copy first: a phone runs the build long after start-up, but report the cold figure too.
        val t0 = System.nanoTime()
        val index = NameIndex.build(entries)
        val coldMs = (System.nanoTime() - t0) / 1e6
        val warmMs = (1..3).minOf {
            val s = System.nanoTime()
            NameIndex.build(entries)
            (System.nanoTime() - s) / 1e6
        }

        val queries = buildList {
            repeat(60) {
                val w = vocab[rng.nextInt(vocab.size)]
                add(w)
                add(w.take(3))
                add(w.drop(1).take(maxOf(3, w.length - 2)))
            }
            repeat(40) {
                val w = vocab[rng.nextInt(vocab.size)]
                if (w.length >= 6) add(w.removeRange(2, 3))
                add("screnshot")
                add("invoce")
            }
            repeat(40) { add(vocab[rng.nextInt(vocab.size)] + " " + vocab[rng.nextInt(vocab.size)].take(2)) }
            add("zzzzqq")
            add("a")
            add("img")
            add("2024")
        }
        repeat(2) { queries.forEach { index.search(it) } }
        val times = queries.map {
            val s = System.nanoTime()
            index.search(it)
            (System.nanoTime() - s) / 1e6
        }.sorted()
        val slow = queries.map {
            val s = System.nanoTime()
            index.search(it)
            (System.nanoTime() - s) / 1e6 to it
        }.sortedByDescending { it.first }.take(8)
        println("NameIndexBench slowest: " + slow.joinToString { "'%s' %.2f".format(it.second, it.first) })
        val p50 = times[times.size / 2]
        val p95 = times[(times.size * 95) / 100]
        val mb = index.arrayBytes / 1_048_576.0
        println(
            "NameIndexBench: 50k names, build cold %.0f ms warm %.0f ms, query p50 %.3f ms p95 %.3f ms max %.3f ms, arrays %.2f MB".format(
                coldMs,
                warmMs,
                p50,
                p95,
                times.last(),
                mb,
            ),
        )
        assertTrue(coldMs <= 300, "build $coldMs ms")
        assertTrue(p95 <= 2.0, "p95 $p95 ms")
        assertTrue(mb <= 8.0, "arrays $mb MB")

        // A content word in no name: every keystroke from 3 letters on runs the typo pass over every token.
        val miss = listOf("ele", "elec", "elect", "electr", "electri", "electric", "electrici", "electricit", "electricity")
        repeat(5) { miss.forEach { index.search(it) } }
        val typoMs = miss.associateWith { q -> List(9) { timed { index.search(q) } }.sorted()[4] }
        println(
            "NameIndexBench: typing 'electricity' (no name holds it), median per keystroke: " +
                typoMs.entries.joinToString { "'%s' %.3f".format(it.key, it.value) },
        )
        assertTrue(typoMs.values.max() <= 2.0, "typo pass ${typoMs.values.max()} ms")
    }

    private inline fun timed(block: () -> Unit): Double {
        val s = System.nanoTime()
        block()
        return (System.nanoTime() - s) / 1e6
    }
}
