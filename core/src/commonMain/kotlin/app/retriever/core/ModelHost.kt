package app.retriever.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one embedder for indexing and search (two copies of the model would double memory), and its fallback ladder:
 * an NPU build that fails moves to the generic build, a GPU that breaks or crashes the process twice moves to CPU.
 * Lock order: a caller holding the index lock takes [lock] after it, never before.
 */
internal class ModelHost(
    private val store: Store,
    private val factory: EmbedderFactory,
    private val textOnly: Boolean,
    private val requiredBuild: StateFlow<ModelBuild>,
    private val modelReady: (ModelBuild) -> Boolean,
    private val log: (String, Throwable?) -> Unit,
    private val dispatchers: CoreDispatchers,
    /** Re-reads the store's setup, and with it [requiredBuild]. */
    private val reload: () -> Unit,
    private val onOpened: (Embedder, ModelBuild) -> Unit,
    private val onClosed: () -> Unit,
    /** The process before this one died of a native crash or a signal, not a swipe, an update or the low-memory killer. */
    private val lastExitCrashed: () -> Boolean,
    private val visible: () -> Boolean,
) {
    val lock = Mutex()
    private var embedder: Embedder? = null
    private var embedderKey: EmbedderKey? = null

    /** The open embedder, if any. Read under [lock]. */
    val current: Embedder? get() = embedder

    /** [current]'s build. Read under [lock]. */
    val currentBuild: ModelBuild? get() = embedderKey?.build

    val currentConfiguration: Pair<Backend, Backend?>? get() = embedderKey?.let { it.backend to it.visionBackend }

    /** Only what the model loads with: a prefs switch (kinds, charging) must not reopen it under a running batch. */
    private data class EmbedderKey(val build: ModelBuild, val backend: Backend, val visionBackend: Backend?, val visionTokens: Int, val maxTokens: Int)

    /** The shared embedder and its build, opened if needed. Null when not set up. */
    suspend fun open(): Pair<Embedder, ModelBuild>? = lock.withLock {
        withContext(dispatchers.default) { openLocked()?.let { it to embedderKey!!.build } }
    }

    /** [open], unless a model is open already: then it keeps that one, whatever build or backend it has. */
    suspend fun openIfClosed(): Embedder? = lock.withLock { embedder ?: withContext(dispatchers.default) { openLocked() } }

    suspend fun openLocked(): Embedder? {
        reload()
        if (textOnly) return null
        checkUnfinishedOpens()
        val build = requiredBuild.value
        val stored = store.profile() ?: build.initialProfile()
        if (!modelReady(build)) {
            closeLocked() // the file went away under an open model: stop answering by meaning, as a reopen would
            return null
        }
        val resolved = factory.profileNow(stored.copy(model = build.variant, audio = false), visible())
        val backend = resolved.backend
        val key = EmbedderKey(build, backend, resolved.visionBackend, stored.visionTokens, stored.maxTokens)
        embedder?.let { if (embedderKey == key) return it }
        closeLocked()
        val e = try {
            countedOpen {
                factory.open(resolved, build).also { opened ->
                    try {
                        opened.embedRaw(listOf(Part.Text(Prefix.query("warm up"))))
                    } catch (t: Throwable) {
                        opened.close()
                        throw t
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (build.isNpu) {
                markNpuFailed(build, t)
                return null
            }
            // Open and warm-up can throw untyped backend failures too. A CPU failure or an explicit temporary
            // refusal says nothing about the GPU: rethrow it so the batch rests or retries.
            if (backend != Backend.GPU || (t as? EmbedderClosedException)?.broken == false) throw t
            // Demoted, the next open is on CPU: try it now, so a batch carries on instead of ending.
            return if (demoteGpu(t)) openLocked() else null
        }
        embedder = e
        embedderKey = key
        factory.keepOnly(e)
        onOpened(e, build)
        return e
    }

    /**
     * Counts an open that may die natively (SIGSEGV, LMK) in the store. Success, a throw and a cancel all
     * reset it, so only a process that never came back leaves it counted.
     */
    inline fun <T> countedOpen(open: () -> T): T {
        setUnfinishedOpens(unfinishedOpens() + 1)
        try {
            return open()
        } finally {
            setUnfinishedOpens(0)
        }
    }

    /**
     * Opens that never came back crashed the process: stop using that build, or the GPU. A process that died otherwise
     * (swiped away, killed for memory) mid-open clears the count, so only crashes in a row demote.
     */
    // ponytail: the counter doesn't record the backend, so a native crash during a background CPU open (iOS) is
    // blamed on the GPU too; store the backend with the count if that ever shows up in the field.
    fun checkUnfinishedOpens() {
        val n = unfinishedOpens()
        if (n == 0) return
        if (!lastExitCrashed()) {
            setUnfinishedOpens(0)
            return
        }
        if (!ModelBuilds.shouldFallBack(n)) return
        setUnfinishedOpens(0)
        val build = requiredBuild.value
        if (build.isNpu) {
            markNpuFailed(build, null)
            return
        }
        log("The model never finished opening twice; using CPU", null)
        store.setMeta(GPU_UNSAFE, "1")
        store.profile()?.takeIf { it.backend == Backend.GPU }?.let { store.setProfile(it.copy(backend = Backend.CPU)) }
    }

    /** A GPU that crashed natively twice is not measured again, so Tune can finish on CPU. */
    fun measurable(): EmbedderFactory = if (store.meta(GPU_UNSAFE) != "1") {
        factory
    } else {
        object : EmbedderFactory by factory {
            override fun backends(build: ModelBuild) = factory.backends(build) - Backend.GPU
        }
    }

    /**
     * [used] threw [EmbedderClosedException]. If it is still the shared embedder, its backend broke (it closed
     * itself): retire it and fall back, NPU build to generic or GPU to CPU. If not, release() or a reopen
     * closed it, which is no failure. True when it broke.
     */
    suspend fun broke(used: Embedder?, build: ModelBuild?, cause: Throwable): Boolean = lock.withLock {
        if (used == null || build == null || embedder !== used) return@withLock false
        // The backend [used] was opened on: a CPU embedder from a background task failing says nothing about the GPU.
        val onGpu = embedderKey?.backend == Backend.GPU
        closeLocked()
        if ((cause as? EmbedderClosedException)?.broken == false) {
            log("The model can't run right now; it reopens on the next batch", cause)
            return@withLock false
        }
        if (build.isNpu) {
            markNpuFailed(build, cause)
        } else if (onGpu) {
            demoteGpu(cause)
        }
        true
    }

    /** Tune again measures the GPU afresh, even one that crashed twice. */
    fun clearGpuUnsafe() = store.setMeta(GPU_UNSAFE, "0")

    /** The GPU broke: CPU from now on. Callers rule out a GPU that can't run right now (broken = false). True when demoted. */
    private fun demoteGpu(cause: Throwable): Boolean {
        val p = store.profile()?.takeIf { it.backend == Backend.GPU } ?: return false
        log("The GPU failed; using CPU from now on", cause)
        store.setProfile(p.copy(backend = Backend.CPU))
        return true
    }

    fun npuFailed(): Set<String> = store.meta(NPU_FAILED)?.let { runCatching { Json.decodeFromString<Set<String>>(it) }.getOrNull() } ?: emptySet()

    /** The chip's build can't run here: require the generic file from now on, for this and every later launch. */
    fun markNpuFailed(build: ModelBuild, cause: Throwable?) {
        log("NPU build ${build.fileName} failed; falling back to ${ModelBuilds.fallback(build).fileName}", cause)
        store.setMeta(NPU_FAILED, Json.encodeToString(npuFailed() + build.fileName))
        // ponytail: picks the generic build's first backend uncalibrated; the unfinished-open counter demotes a crashing GPU.
        store.profile()?.takeIf { it.backend == Backend.NPU }?.let {
            store.setProfile(it.copy(backend = factory.backends(ModelBuilds.fallback(build)).first()))
        }
        reload()
    }

    fun closeLocked() {
        embedder?.close()
        embedder = null
        embedderKey = null
        onClosed()
    }

    /** Frees the model's memory; the next search or batch opens it again. */
    suspend fun release() {
        lock.withLock { withContext(dispatchers.default) { closeLocked() } }
    }

    /**
     * Opening already has a completion cleanup; an idle check must never make a query wait for that open. Returns whether
     * it closed the model; false while the lock is held or [allowed] says no, so the caller can try again later.
     */
    suspend fun releaseIf(allowed: () -> Boolean): Boolean {
        if (!lock.tryLock()) return false
        try {
            return withContext(dispatchers.default) { allowed().also { if (it) closeLocked() } }
        } finally {
            lock.unlock()
        }
    }

    fun unfinishedOpens(): Int = store.meta(UNFINISHED_OPENS)?.toIntOrNull() ?: 0

    fun setUnfinishedOpens(n: Int) = store.setMeta(UNFINISHED_OPENS, n.toString())

    internal companion object {
        const val NPU_FAILED = "npu_failed"
        const val UNFINISHED_OPENS = "unfinished_opens"
        const val GPU_UNSAFE = "gpu_unsafe"
    }
}

/** Stands in when no model can open, so search still answers from keywords (its signature never matches). */
internal object NoModel : Embedder {
    override val signature = Signature("none")
    override val backend = Backend.CPU

    override suspend fun embedRaw(parts: List<Part>): FloatArray = throw EmbedderClosedException("no model")

    override fun close() {}
}
