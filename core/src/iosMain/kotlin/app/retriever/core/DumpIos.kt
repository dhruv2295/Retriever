@file:OptIn(ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.closeFile
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.synchronizeFile
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.writeData

/**
 * The relevance-fit dump on a simulator (`-dump`, DumpMode.swift): [dir] holds manifest.json, queries.json and
 * library/ (photos/, docs/), read through [extractor] as folder://documents/library/...; [variant] (M440 or M740) is
 * opened on CPU at [visionTokens] from [modelDir]. Writes [dir]/dump-<variant>-vt<vt>.bin, resuming an earlier run of
 * the same signature. [limit] > 0 keeps that many files of each kind and that many queries, for a smoke run.
 * Returns the dump's path.
 */
@Throws(Throwable::class)
suspend fun runDump(
    extractor: NativeExtractor,
    opener: NativeEmbedderOpener,
    modelDir: String,
    cacheDir: String,
    variant: String,
    visionTokens: Int,
    dir: String,
    limit: Int,
    log: (String) -> Unit,
): String {
    val model = ModelVariant.valueOf(variant)
    var files = parseDumpManifest(readText("$dir/manifest.json"))
    var queries = parseDumpQueries(readText("$dir/queries.json"))
    if (limit > 0) {
        files = files.groupBy { it.kind }.values.flatMap { it.take(limit) }
        queries = queries.take(limit)
    }
    val profile = DeviceProfile(Tier.BALANCED, model, Backend.CPU, visionTokens, 0, false, false)
    val embedder = IosEmbedderFactory(opener, modelDir, cacheDir, isSimulator = true) { true }.open(profile, model.generic)
    val path = "$dir/dump-${model.name}-vt$visionTokens${if (limit > 0) "-limit$limit" else ""}.bin"
    val fm = NSFileManager.defaultManager
    val earlier = NSData.dataWithContentsOfFile(path)?.toByteArray()?.takeIf { it.isNotEmpty() }?.let(RelevanceDump::read)
    if (earlier == null) {
        fm.createFileAtPath(path, RelevanceDump.header(embedder.signature, visionTokens).toNSData(), null)
    } else {
        check(earlier.signature == embedder.signature.value) { "$path was made by ${earlier.signature}, not ${embedder.signature.value}" }
        log("resuming $path: ${earlier.files.size} files and ${earlier.queries.size} queries written")
    }
    val out = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(path)) { "can't write $path" }
    try {
        // Drop a torn last record from a killed run, then append after it.
        earlier?.let { out.truncateFileAtOffset(it.validBytes.toULong()) }
        out.seekToEndOfFile()
        log("signature ${embedder.signature.value}, ${files.size} files, ${queries.size} queries")
        embedder.use {
            dumpLibrary(
                files, queries, IosExtractor(extractor), embedder, profile,
                earlier?.files?.map { it.id }.orEmpty().toSet(), earlier?.queries?.map { it.id }.orEmpty().toSet(),
                append = { bytes ->
                    out.writeData(bytes.toNSData())
                    out.synchronizeFile()
                },
                log = log,
            )
        }
    } finally {
        out.closeFile()
    }
    return path
}

private fun readText(path: String): String = checkNotNull(NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)) { "can't read $path" }
