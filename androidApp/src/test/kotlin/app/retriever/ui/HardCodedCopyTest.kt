package app.retriever.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Both apps show words only from core's Strings (Words.strings), so every language gets them. A sentence-like literal in
 * either app's source fails here unless core/fixtures/copy-allowlist.txt lists it: log lines, debug screens, OS-read
 * strings and the like. A new one belongs in core's Strings and its translations, not in that list.
 */
class HardCodedCopyTest {
    @Test fun noUiWordsOutsideCoreStrings() {
        val allowed = File("../core/fixtures/copy-allowlist.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
        val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" } + File("../iosApp/Retriever").walk().filter { it.extension == "swift" }
        val found = sources.flatMap { file ->
            val path = file.invariantSeparatorsPath.substringAfter("../")
            file.readLines().filterNot { COMMENT.containsMatchIn(it) }.flatMap { line -> LITERAL.findAll(line).map { "$path\t${it.value}" } }
        }.filter { (file, literal) -> file !in allowed && literal !in allowed && "$file\t$literal" !in allowed }
            .map { (file, literal) -> "$file\t$literal" }.distinct().sorted().toList()
        assertEquals("Move these into core's Strings (or, if no person ever sees them, into copy-allowlist.txt)", emptyList<String>(), found)
    }

    private operator fun String.component1() = substringBefore('\t')

    private operator fun String.component2() = substringAfter('\t')

    private companion object {
        /** A quoted literal that reads as words: a capitalised word, or two words. Interpolations count as words too. */
        val LITERAL = Regex(""""(?:[A-Z][a-z’']+|[A-Za-z’']+ [A-Za-z$\\(])[^"]*"""")
        val COMMENT = Regex("""^\s*(//|\*|/\*)""")
    }
}
