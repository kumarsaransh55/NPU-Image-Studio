package studio.npu.sd

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/** The four neural networks that make up Stable Diffusion. */
enum class Component(val label: String) {
    TEXT_ENCODER("Text encoder"),
    UNET("UNet (main model)"),
    VAE_DECODER("Image decoder (full VAE)"),
    TINY_DECODER("Image decoder (TAESD)"),
}

/** Thrown when a run was cancelled by the user (e.g. live mode: the prompt changed). */
class GenerationCancelled : RuntimeException("cancelled")

/** Thrown when a backend produced garbage (NaN/Inf), e.g. fp16 overflow on an accelerator. */
class BadOutputException(msg: String) : RuntimeException(msg)

/**
 * One way of running a model: MediaTek NPU, GPU, NNAPI or CPU.
 * The Android app provides the hardware ones; the PC test runner only uses [CpuBackend].
 */
interface Backend {
    val id: String
    val label: String

    /** Accelerators need fixed tensor sizes; CPU can handle any size with one session. */
    val staticShapes: Boolean

    fun createSession(env: OrtEnvironment, component: Component, modelFile: File, dims: Map<String, Long>): OrtSession

    /** Called when a session from this backend failed while running (e.g. to drop a bad saved compile). */
    fun onRunFailed(component: Component, modelFile: File, dims: Map<String, Long>) {}
}

/**
 * Remembers which (part, chip, model, size) combinations failed, so the app doesn't waste a slow
 * NPU compile on them again. The app keeps this on disk; the PC runner keeps it in memory.
 */
interface FailureMemory {
    fun reason(key: String): String?
    fun remember(key: String, reason: String)
    fun clear()

    class InMemory : FailureMemory {
        private val map = HashMap<String, String>()
        override fun reason(key: String) = synchronized(map) { map[key] }
        override fun remember(key: String, reason: String) { synchronized(map) { map[key] = reason } }
        override fun clear() = synchronized(map) { map.clear() }
    }
}

/** Common session options. [dims] fixes symbolic sizes like "height" so accelerators see static shapes. */
fun baseSessionOptions(dims: Map<String, Long>, applyDims: Boolean, threads: Int): OrtSession.SessionOptions {
    val o = OrtSession.SessionOptions()
    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    if (threads > 0) o.setIntraOpNumThreads(threads)
    // The UNet weights alone are 3.4 GB. The default memory arena grows in big doubling chunks,
    // which can fail on a phone (or PC) with a few GB free, so allocate exactly what is needed.
    o.setCPUArenaAllocator(false)
    o.addConfigEntry("session.use_device_allocator_for_initializers", "1")
    if (applyDims) for ((k, v) in dims) o.setSymbolicDimensionValue(k, v)
    return o
}

class CpuBackend(private val threads: Int = 0) : Backend {
    override val id = "cpu"
    override val label = "CPU"
    override val staticShapes = false

    override fun createSession(env: OrtEnvironment, component: Component, modelFile: File, dims: Map<String, Long>): OrtSession {
        val o = baseSessionOptions(dims, applyDims = false, threads = threads)
        return env.createSession(modelFile.absolutePath, o)
    }
}

/** What the app shows the user about where each part is running. */
data class BackendEvent(val component: Component, val backend: String, val message: String, val isError: Boolean)

/**
 * Wraps work on accelerators. A vendor driver can crash the whole app (not just throw an error);
 * the app's guard leaves a marker on disk while the work runs so the next start can switch that accelerator off.
 */
interface CrashGuard {
    fun <T> around(backend: Backend, what: String, block: () -> T): T

    companion object {
        val NONE = object : CrashGuard {
            override fun <T> around(backend: Backend, what: String, block: () -> T): T = block()
        }
    }
}

/**
 * Keeps one live session per component and falls back to the next backend when one fails
 * (failed to compile, crashed during a run, or produced NaN output).
 */
class FallbackSessionProvider(
    private val env: OrtEnvironment,
    private val backendsFor: (Component) -> List<Backend>,
    private val guard: CrashGuard = CrashGuard.NONE,
    private val failures: FailureMemory = FailureMemory.InMemory(),
    private val onEvent: (BackendEvent) -> Unit = {},
) : AutoCloseable {

    private class Live(val backend: Backend, val dimsKey: String, val modelPath: String, val session: OrtSession)

    private val live = HashMap<Component, Live>()

    @Synchronized
    fun activeBackend(component: Component): String? = live[component]?.backend?.label

    @Synchronized
    fun release(component: Component) {
        live.remove(component)?.session?.close()
    }

    @Synchronized
    fun releaseAll() {
        live.values.forEach { runCatching { it.session.close() } }
        live.clear()
    }

    /** Forget earlier failures (e.g. after the user changed accelerator settings). */
    @Synchronized
    fun resetFailures() {
        failures.clear()
    }

    override fun close() = releaseAll()

    /**
     * Runs [block] with a session for [component]. If the current backend fails, it is marked as failed
     * and the next backend in the list is tried, ending with CPU.
     */
    fun <T> run(
        component: Component,
        modelFile: File,
        dims: Map<String, Long>,
        isCancelled: () -> Boolean,
        block: (OrtSession) -> T,
    ): T {
        val dimsKey = dims.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        var lastError: Throwable? = null
        val candidates = backendsFor(component)
        if (candidates.isEmpty()) {
            throw IllegalStateException("The hardware chosen for the ${component.label} is not available on this phone (see the Engine tab).")
        }
        for (backend in candidates) {
            if (isCancelled()) throw GenerationCancelled()
            val failKey = "${component.name}|${backend.id}|${modelFile.absolutePath}|${if (backend.staticShapes) dimsKey else "*"}"
            failures.reason(failKey)?.let { why ->
                onEvent(BackendEvent(component, backend.label, "skipped (failed before: ${why.take(120)})", false))
                lastError = IllegalStateException("${backend.label} failed before: $why")
                continue
            }

            val session = try {
                guard.around(backend, "preparing ${component.label}") { obtain(component, backend, modelFile, dims, dimsKey) }
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) System.gc()
                // A CPU failure means the model files are the problem, not the chip: don't block future tries.
                if (backend.id != "cpu") failures.remember(failKey, "could not start: ${e.shortMessage()}")
                lastError = e
                onEvent(BackendEvent(component, backend.label, "could not start: ${e.shortMessage()}", true))
                continue
            }

            try {
                return guard.around(backend, "running ${component.label}") { block(session) }
            } catch (e: GenerationCancelled) {
                throw e
            } catch (e: Throwable) {
                if (isCancelled()) throw GenerationCancelled()
                lastError = e
                if (backend.id != "cpu") failures.remember(failKey, "failed while running: ${e.shortMessage()}")
                release(component)
                runCatching { backend.onRunFailed(component, modelFile, dims) }
                onEvent(BackendEvent(component, backend.label, "failed while running: ${e.shortMessage()}", true))
            }
        }
        throw IllegalStateException(
            "No backend could run ${component.label}. Last error: ${lastError?.shortMessage()}", lastError,
        )
    }

    @Synchronized
    private fun obtain(component: Component, backend: Backend, modelFile: File, dims: Map<String, Long>, dimsKey: String): OrtSession {
        val cur = live[component]
        if (cur != null && cur.backend.id == backend.id && cur.modelPath == modelFile.absolutePath &&
            (!backend.staticShapes || cur.dimsKey == dimsKey)
        ) {
            return cur.session
        }
        // Free the old one first: the UNet alone is ~3.4 GB.
        if (cur != null) {
            live.remove(component)
            runCatching { cur.session.close() }
            System.gc()
        }
        val t0 = System.nanoTime()
        val s = backend.createSession(env, component, modelFile, dims)
        val ms = (System.nanoTime() - t0) / 1_000_000
        live[component] = Live(backend, dimsKey, modelFile.absolutePath, s)
        onEvent(BackendEvent(component, backend.label, "ready in ${ms} ms", false))
        return s
    }
}

fun Throwable.shortMessage(): String {
    val m = (message ?: javaClass.simpleName).lineSequence().firstOrNull { it.isNotBlank() } ?: javaClass.simpleName
    return if (m.length > 300) m.take(300) + "..." else m
}
