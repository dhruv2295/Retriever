package app.retriever.core

import app.cash.sqldelight.driver.native.inMemoryDriver
import app.retriever.core.db.IndexDb
import kotlin.time.Duration.Companion.seconds

private const val CORPUS = "folder://documents/eval/corpus/"

/**
 * The retrieval eval on a simulator (`-eval`, EvalMode.swift), over Documents/eval/corpus and [queriesJson]:
 * calibrates and indexes Documents through [retriever] and searches it (the production-path row), frees that model,
 * then runs [RetrievalEval]'s grid on the real model with an in-memory store. Returns the markdown report.
 */
@Throws(Throwable::class)
suspend fun runEval(
    retriever: Retriever,
    folders: NativeFileSource,
    extractor: NativeExtractor,
    opener: NativeEmbedderOpener,
    modelDir: String,
    cacheDir: String,
    isSimulator: Boolean,
    queriesJson: String,
    log: (String) -> Unit,
): String {
    // FolderSource itself, so names, folders, MIME types and kinds are exactly what the app indexes.
    val corpus = folders.changes(null, KnownFiles { null }).upserted.filter { it.uri.startsWith(CORPUS) }.sortedBy { it.uri }
    val queries = parseEvalQueries(queriesJson)
    log("corpus ${corpus.size} files, ${queries.size} queries")

    val photos = corpus.filter { it.kind == Kind.PHOTO }.take(2).mapNotNull { extractor.imageJpeg(it.uri, EMBED_PX)?.toByteArray() }
    val calibration = retriever.calibrate(Samples(photos, listOf("Invoice for garden supplies", "Notes from the lighthouse meeting"), emptyList()), false)
    val profile = calibration.profile
    log("calibrated: ${profile.tier} ${profile.backend} vt${profile.visionTokens} t${profile.maxTokens}")

    var next = NextRun.SOON
    for (round in 1..10) {
        next = retriever.indexBatch(300.seconds)
        val st = retriever.state.value
        log("batch $round: $next, processed ${st.processed}, failed ${st.failed}, stop ${st.lastStop} ${st.lastError.orEmpty()}")
        if (next == NextRun.DONE) break
    }
    val keywordOnly = mutableListOf<String>()
    val results = queries.map { q -> retriever.search(q.q).also { if (!it.vectorLane) keywordOnly += q.q } }
    val production = queries.zip(results) { q, r ->
        q.file?.let { f -> r.hits.indexOfFirst { it.file.uri.startsWith(CORPUS) && it.file.name == f }.takeIf { it >= 0 }?.plus(1) }
    }
    retriever.release()

    val eval = RetrievalEval(
        IosExtractor(extractor),
        IosEmbedderFactory(opener, modelDir, cacheDir, isSimulator) { true },
        retriever.requiredBuild.value,
        profile,
        Store(inMemoryDriver(IndexDb.Schema)),
        log,
    )
    val run = eval.run(corpus, queries, evalGrid((listOf(70, 140, 280) + profile.visionTokens).distinct()))
    return "## Simulator run\n\nModel ${retriever.requiredBuild.value.fileName}, ${profile.backend}, tier ${profile.tier}, " +
        "vision tokens ${profile.visionTokens}, max tokens ${profile.maxTokens}. Production index: $next" +
        (if (keywordOnly.isEmpty()) "; every production search used the vector lane." else "; keyword-only searches: $keywordOnly") +
        "\n\n" + run.report(EvalSetting(profile.visionTokens), production, results.map { it.strongCount })
}
