package app.retriever.core

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every shared sentence over a fixed matrix of inputs, found by reflection so a new [Words] or [Strings] member is covered
 * too: the English snapshot in core/fixtures/copy-en.txt must not change unless the wording does on purpose. Each other
 * language's sheet beside it (copy-hi.txt, …) is the same matrix in that language: what a native speaker reviews.
 * `-Dcopy.write=<dir>` writes the sheets instead of asserting them.
 */
class CopySnapshotTest {
    @Test
    fun everyLanguageMatchesItsSheet() {
        val write = System.getProperty("copy.write")
        Language.entries.forEach {
            val file = File(write ?: "fixtures", "copy-${it.tag}.txt")
            if (write !=
                null
            ) {
                file.writeText(snapshot(it))
            } else {
                assertEquals(file.readText(), snapshot(it), "${file.name} changed; rerun with -Dcopy.write=fixtures if on purpose")
            }
        }
    }

    private fun snapshot(language: Language): String {
        val before = Words.language
        Words.language = language
        try {
            return snapshot()
        } finally {
            Words.language = before
        }
    }

    private fun snapshot(): String {
        val rows = ArrayList<String>()
        rows += calls(Words.strings, Strings::class.java.methods.toList()).map { "strings.$it" }
        Words::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.name != "INSTANCE" }
            .forEach { rows += "${it.name} = ${show(it.get(null))}" }
        rows += calls(Words, Words::class.java.declaredMethods.toList())
        rows += calls(ReadingPolicy, ReadingPolicy::class.java.declaredMethods.filter { it.name in setOf("stopCopy", "line", "decide") })
        rows += calls(null, Class.forName("app.retriever.core.ReadingProgressKt").declaredMethods.filter { it.name == "readingLabel" })
        ReadingSchedule.entries.forEach { rows += "ReadingSchedule.$it = ${show(it.title)}" }
        StatusAction.entries.forEach { rows += "StatusAction.$it = ${show(it.label)}" }
        return rows.sorted().joinToString("\n", postfix = "\n")
    }

    private fun calls(target: Any?, methods: List<Method>): List<String> = methods
        .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge && it.name !in NOT_COPY && !it.name.startsWith("set") }
        .flatMap { m ->
            val getter = m.parameterCount == 0 && m.name.startsWith("get")
            val name = if (getter) m.name.removePrefix("get") else m.name
            inputs(m.parameterTypes.map { samples(it, m) }).mapNotNull { args ->
                val out = try {
                    show(m.invoke(target, *args.toTypedArray()))
                } catch (e: InvocationTargetException) {
                    if (e.cause is NullPointerException) return@mapNotNull null
                    "!" + e.cause?.javaClass?.simpleName
                }
                if (getter) "$name = $out" else "$name(${args.joinToString(", ") { show(it) }}) = $out"
            }
        }

    private companion object {
        val NOT_COPY = setOf("equals", "hashCode", "toString", "getStrings", "getLanguage")
    }

    /** Every combination of the leading parameters while that stays small, then each later one varied alone. */
    private fun inputs(samples: List<List<Any?>>): List<List<Any?>> {
        var lead = 0
        var size = 1
        while (lead < samples.size && size * samples[lead].size <= 600) size *= samples[lead++].size
        val firsts = samples.map { it.first() }
        var rows: List<List<Any?>> = listOf(emptyList())
        for (i in 0 until lead) rows = rows.flatMap { r -> samples[i].map { r + it } }
        val alone = (lead until samples.size).flatMap { i -> samples[i].drop(1).map { v -> firsts.toMutableList().also { it[i] = v } } }
        return (rows.map { it + firsts.drop(lead) } + alone).distinct()
    }

    private fun samples(type: Class<*>, m: Method): List<Any?> = when (type) {
        Long::class.javaPrimitiveType -> listOf(0L, 1L, 2L, 1234L)

        Int::class.javaPrimitiveType -> listOf(0, 1, 2, 57)

        Int::class.javaObjectType -> listOf(null, 0, 57)

        Long::class.javaObjectType -> listOf(null, 0L, 1L, 1234L)

        Boolean::class.javaPrimitiveType -> listOf(false, true)

        Double::class.javaPrimitiveType -> listOf(3.6)

        String::class.java -> listOf("388 MB", "p. 12", "1:01:05", "Downloads", null)

        List::class.java -> listOf(listOf("Photos", "Last week"))

        Set::class.java -> listOf(Kind.entries.toSet(), setOf(Kind.DOC))

        DeviceWords::class.java -> listOf(DeviceWords.ANDROID, DeviceWords.ios("iPhone"), DeviceWords.ios("iPad"))

        ReadingProgress::class.java -> listOf(
            ReadingProgress(),
            ReadingProgress(read = 1200, partlyRead = 3, scheduled = 40, nameOnly = 5, couldntRead = 2, laterPasses = 7, meaningWaiting = 9, rereads = 1),
            ReadingProgress(read = 1, nameOnly = 1, laterPasses = 1, meaningWaiting = 1),
            ReadingProgress(read = 10, scheduled = 1, counted = false),
            ReadingProgress(read = 2, scheduled = 3, sample = true),
            ReadingProgress(read = 2, scheduled = 3, notNow = true),
            ReadingProgress(read = 5, partlyRead = 1),
        )

        TasteProgress::class.java -> listOf(TasteProgress(emptyMap(), emptySet(), 0, 0, 0), TasteProgress(emptyMap(), emptySet(), 3, 2, 5))

        Store.StoredFile::class.java -> listOf(Kind.DOC, Kind.OTHER).flatMap { kind ->
            FileState.entries.flatMap { state ->
                listOf(false, true).flatMap { read ->
                    (listOf(null) + ReadExtent.entries).map { Store.StoredFile(1, testFile("a.pdf", kind), 3, state, extent = it, contentRead = read) } +
                        Store.StoredFile(1, testFile("a.pdf", kind), 1, state, partial = true, contentRead = read)
                }
            }
        } + listOf(null)

        DownloadState::class.java -> listOf(
            null,
            DownloadState(null, false, "388 MB"),
            DownloadState(40, false, "388 MB"),
            DownloadState(null, true, "388 MB"),
            DownloadState(null, false, "388 MB", settingUp = true),
            DownloadState(null, true, "388 MB", settingUp = true),
        )

        ReadingStatus::class.java -> listOf(
            null,
            ReadingStatus(ReadingPhase.COUNTING, "Counting"),
            ReadingStatus(ReadingPhase.READING, "Reading", count = "1 of 2"),
        )

        FacesProgress::class.java -> listOf(null, FacesProgress(5, 10))

        else -> if (type.isEnum) listOf(*type.enumConstants, null) else error("No samples for ${type.name} in ${m.name}")
    }

    private fun show(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\n", "\\n") + "\""
        is Char -> "'${v.code.toString(16)}'"
        is DeviceWords -> if (v.ios) v.device else "android"
        is Store.StoredFile -> "file(${v.file.kind}, ${v.state}, read=${v.contentRead}, partial=${v.partial}, ${v.extent}, tries=${v.attempts})"
        else -> v.toString()
    }
}
