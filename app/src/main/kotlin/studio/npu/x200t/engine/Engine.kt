package studio.npu.x200t.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import studio.npu.sd.Backend
import studio.npu.sd.BackendEvent
import studio.npu.sd.Component
import studio.npu.sd.CpuBackend
import studio.npu.sd.CrashGuard
import studio.npu.sd.FallbackSessionProvider
import studio.npu.sd.SdModel
import studio.npu.sd.ShapeHints
import studio.npu.sd.StableDiffusionPipeline
import studio.npu.sd.shortMessage
import java.io.File
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Owns ONNX Runtime, the NPU setup, the loaded model and the single background thread
 * all inference runs on. Also decides which chip runs which part (see [backendsFor]).
 */
class Engine(private val context: Context) {
    val env: OrtEnvironment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "NPUStudio")
    val settings = EngineSettings(context)

    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "sd-engine", 64L * 1024 * 1024).apply { priority = Thread.MAX_PRIORITY }
    }
    val dispatcher = thread.asCoroutineDispatcher()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** Same lines as the on-screen log, kept in Android/data/studio.npu.x200t/files/app_log.txt (easy to pull over USB). */
    private val logFile: File? by lazy {
        context.getExternalFilesDir(null)?.let { File(it, "app_log.txt") }?.also { f ->
            // Keep the previous run (useful after a crash), start a fresh file.
            if (f.isFile) f.renameTo(File(f.parentFile, "app_log_prev.txt"))
        }
    }

    private val _npu = MutableStateFlow<NpuSetup.Status?>(null)
    val npu: StateFlow<NpuSetup.Status?> = _npu.asStateFlow()

    /** Problems (fallbacks) seen during the current/last generation, shown under the image. */
    private val _runEvents = MutableStateFlow<List<BackendEvent>>(emptyList())
    val runEvents: StateFlow<List<BackendEvent>> = _runEvents.asStateFlow()

    /** Bumps whenever speed-test results or settings change, so screens refresh their "Auto uses..." text. */
    private val _planVersion = MutableStateFlow(0)
    val planVersion: StateFlow<Int> = _planVersion.asStateFlow()

    private val cpu = CpuBackend()
    private val gpu = WebGpuBackend()
    private val nnapi = NnapiBackend()
    private var npuBackend: NeuroPilotBackend? = null

    /** Present on disk only while NPU/GPU/NNAPI work is running. Still there at startup = that work crashed the app. */
    private val crashMarker = File(context.filesDir, "accelerator_in_use.txt")

    private val _disabled = MutableStateFlow(settings.disabledAccels())
    /** Accelerators that were switched off after crashing the app (id -> what it was doing). */
    val disabled: StateFlow<Map<String, String>> = _disabled.asStateFlow()

    private val crashGuard = object : CrashGuard {
        override fun <T> around(backend: Backend, what: String, block: () -> T): T = guarded(backend.id, what, block)
    }

    val failures = PrefsFailureMemory(context)

    private val provider = FallbackSessionProvider(env, ::backendsFor, crashGuard, failures) { e ->
        log("${e.component.label} on ${e.backend}: ${e.message}")
        if (e.isError) _runEvents.update { it + e }
    }

    init {
        // MediaTek's plugin writes temporary compile files to the system temp folder, which on Android
        // points somewhere apps can't write. Use our own cache folder instead.
        runCatching { android.system.Os.setenv("TMPDIR", context.cacheDir.absolutePath, true) }

        // After an app update: forget old failures and old saved compiles (the NPU setup may have changed).
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        if (settings.getLong("seen_version", -1L) != version) {
            failures.clear()
            File(context.filesDir, "npu_cache").deleteRecursively()
            // A new version may have fixed whatever crashed: give switched-off chips another chance.
            settings.disabledAccels().keys.forEach { settings.enableAccel(it) }
            _disabled.value = settings.disabledAccels()
            crashMarker.delete()
            settings.putLong("seen_version", version)
        }

        if (crashMarker.isFile) {
            val text = runCatching { crashMarker.readText() }.getOrDefault("")
            val id = text.substringBefore('|').ifEmpty { "npu" }
            val what = text.substringAfter('|', "unknown work")
            settings.disableAccel(id, what)
            _disabled.value = settings.disabledAccels()
            crashMarker.delete()
            log("${backendName(id)} crashed the app last time (while $what), so it was switched off. You can turn it back on in the Engine tab.")
        }
    }

    private fun <T> guarded(id: String, what: String, block: () -> T): T {
        if (id == "cpu") return block()
        runCatching { crashMarker.writeText("$id|$what") }
        try {
            return block()
        } finally {
            crashMarker.delete()
        }
    }

    /** Turn an accelerator back on after it was switched off because of a crash (takes effect after restart). */
    fun reEnable(id: String) {
        settings.enableAccel(id)
        _disabled.value = settings.disabledAccels()
        log("${backendName(id)} turned back on - close and reopen the app to use it")
    }

    private var pipeline: StableDiffusionPipeline? = null
    private var pipelineDir: String? = null

    val tinyDecoderFile: File by lazy { copyAsset("taesd/vae_decoder.onnx") }

    fun log(msg: String) {
        Log.i("NPUStudio", msg)
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        _log.value = (_log.value + line).takeLast(400)
        runCatching {
            logFile?.let { f -> if (f.length() < 4_000_000) f.appendText(line + "\n") }
        }
    }

    fun clearRunEvents() {
        _runEvents.value = emptyList()
    }

    /** Runs on the engine thread. Safe to call many times. */
    suspend fun initNpu(): NpuSetup.Status = withContext(dispatcher) {
        NpuSetup.status?.let { return@withContext it }
        val skip = "npu" in _disabled.value
        val s = guarded("npu", "setting up the NPU") { NpuSetup.init(context, env, skipPlugin = skip) }
        if (s.npuReady) {
            npuBackend = NeuroPilotBackend(
                s.npuDevices, File(context.filesDir, "npu_cache"), { settings.npuCache }, ::log,
                // Developer testing: put this file in Android/data/studio.npu.x200t/files/ to try big UNets on the NPU.
                allowBigUnet = { File(context.getExternalFilesDir(null), "npu_try_big.txt").isFile },
            )
        }
        log(s.report().trim())
        log(s.summary())
        _npu.value = s
        s
    }

    // ------------------------------------------------------------------ who runs what

    private fun backendById(id: String): Backend? {
        if (id in _disabled.value) return null
        return when (id) {
            "npu" -> npuBackend
            "nnapi" -> nnapi
            "gpu" -> gpu.takeIf { _npu.value?.gpuAvailable == true }
            "cpu" -> cpu
            else -> null
        }
    }

    /**
     * The list of chips to try for [c], in order.
     *  - A specific choice (e.g. NNAPI): that chip, then CPU as a safety net (or nothing else in strict mode).
     *  - Auto: chips sorted by the speed measured on this phone (self-test / UNet benchmark). Chips that
     *    failed, or were slower than the CPU, are left out. CPU is always last.
     */
    fun backendsFor(c: Component): List<Backend> {
        val choice = settings.accel(c)
        if (choice != Accel.AUTO) {
            val chosen = backendById(choice.backendId!!)
            return if (settings.strict || chosen?.id == "cpu") listOfNotNull(chosen) else listOfNotNull(chosen, cpu)
        }
        return autoOrder(c).mapNotNull { backendById(it) }
    }

    /** Backend ids Auto will try for [c], best first. */
    fun autoOrder(c: Component): List<String> {
        if (c == Component.TEXT_ENCODER) return listOf("cpu") // once per prompt, well under a second on CPU
        val defaults = listOf("npu", "nnapi", "gpu", "cpu")
        val tiny = settings.bench("tiny")
        val unet = settings.bench("unet")
        val bench = if (c == Component.UNET && unet.isNotEmpty()) unet else tiny
        if (bench.isEmpty()) return listOf("npu", "nnapi", "cpu")
        val cpuMs = bench["cpu"]?.takeIf { it > 0 }
        val usable = defaults.filter { id ->
            if (id == "cpu") return@filter true
            val ms = bench[id] ?: return@filter id != "gpu" // untested: try it (except the GPU)
            ms >= 0 && (cpuMs == null || ms < cpuMs)
        }
        return usable.sortedBy { id -> if (id == "cpu") Long.MAX_VALUE else bench[id] ?: (Long.MAX_VALUE - 1) }
    }

    /** false if that chip can't be used on this phone (not found, or switched off after a crash). */
    fun isAvailable(id: String): Boolean = backendById(id) != null

    /** E.g. "NPU (NNAPI) → CPU" - what Auto means right now for [c]. */
    fun autoOrderText(c: Component): String =
        autoOrder(c).filter { backendById(it) != null }.joinToString(" → ") { backendName(it) }

    /** Get (or switch to) the pipeline for [model]. Must be called on the engine thread. */
    fun pipelineFor(model: SdModel): StableDiffusionPipeline {
        val cur = pipeline
        if (cur != null && pipelineDir == model.dir.absolutePath) return cur
        provider.releaseAll()
        val p = StableDiffusionPipeline(env, model, tinyDecoderFile, provider)
        pipeline = p
        pipelineDir = model.dir.absolutePath
        log("Model selected: ${model.name}")
        return p
    }

    suspend fun <T> onEngine(block: () -> T): T = withContext(dispatcher) { block() }

    /** Called after accelerator settings change: close sessions so the next run uses the new choice. */
    suspend fun applySettings() = withContext(dispatcher) {
        provider.releaseAll()
        provider.resetFailures()
        _planVersion.update { it + 1 }
        log("Hardware settings changed; models will reload on the next run")
    }

    /** Put every part back on Auto and switch strict mode off. */
    suspend fun resetToAuto() {
        Component.entries.forEach { settings.setAccel(it, Accel.AUTO) }
        settings.strict = false
        applySettings()
        log("All parts set back to Auto (fastest measured chip)")
    }

    /** Forget chips that failed before, so they are tried again. */
    suspend fun retryFailed() = withContext(dispatcher) {
        provider.releaseAll()
        provider.resetFailures()
        _planVersion.update { it + 1 }
        log("Failed chips will be tried again on the next run")
    }

    suspend fun releaseMemory() = withContext(dispatcher) {
        provider.releaseAll()
        System.gc()
        log("Released all loaded models from memory")
    }

    // ------------------------------------------------------------------ speed tests

    /**
     * Quick check that needs no big download: runs the small TAESD image decoder (bundled in the APK)
     * at 512x512 on each chip. The results also decide what "Auto" uses.
     */
    suspend fun selfTest(): String = withContext(dispatcher) {
        initNpu()
        val dims = ShapeHints.decoder(64, 64)
        val input = FloatArray(4 * 64 * 64) { ((it * 7919) % 200 - 100) / 100f }
        val sb = StringBuilder("Self-test: TAESD image decoder, 512x512 output\n")
        if (npuBackend == null) sb.append("${backendName("npu")}: not available - ${_npu.value?.summary()}\n")
        _disabled.value.forEach { (id, why) -> sb.append("${backendName(id)}: switched off (crashed while $why)\n") }
        val results = LinkedHashMap<String, Long>()
        for (id in listOf("npu", "nnapi", "gpu", "cpu")) {
            val b = backendById(id) ?: continue
            try {
                val t0 = System.nanoTime()
                val s = guarded(b.id, "self-test") { b.createSession(env, Component.TINY_DECODER, tinyDecoderFile, dims) }
                val loadMs = (System.nanoTime() - t0) / 1_000_000
                s.use { session ->
                    val inName = session.inputNames.first()
                    val best = timeRuns(b, 3) {
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, 64, 64)).use { t ->
                            session.run(mapOf(inName to t)).use { r -> checkFinite(r) }
                        }
                    }
                    results[id] = best
                    sb.append("${backendName(id)}: $best ms per image (load/compile $loadMs ms)\n")
                }
            } catch (e: Throwable) {
                results[id] = -1
                sb.append("${backendName(id)}: failed - ${e.shortMessage().take(140)}\n")
            }
        }
        settings.setBench("tiny", results)
        settings.benchVersion = BENCH_VERSION
        sb.append("\nAuto now uses: UNet ${autoOrderText(Component.UNET)} · decoders ${autoOrderText(Component.TINY_DECODER)}")
        provider.releaseAll()
        provider.resetFailures()
        _planVersion.update { it + 1 }
        val text = sb.toString().trim()
        log(text)
        text
    }

    /**
     * The real test: one step of the big UNet on each chip at the given size. Slow (the NPU compiles the
     * 3.4 GB model, which can take minutes) but tells exactly which chip is fastest for your images.
     */
    suspend fun benchmarkUnet(model: SdModel, width: Int, height: Int, progress: (String) -> Unit): String = withContext(dispatcher) {
        initNpu()
        provider.releaseAll()
        System.gc()
        val lh = height / 8L
        val lw = width / 8L
        val dims = ShapeHints.unet(lh, lw)
        val pipe = pipelineFor(model)
        val tiny = settings.bench("tiny")
        val cpuTiny = tiny["cpu"]?.takeIf { it > 0 }
        val sb = StringBuilder("UNet benchmark: ${model.name}, ${width}x$height, 1 step\n")
        val results = LinkedHashMap<String, Long>()
        for (id in listOf("npu", "nnapi", "gpu", "cpu")) {
            val b = backendById(id) ?: continue
            if (id == "gpu" && tiny["gpu"]?.let { it < 0 || (cpuTiny != null && it >= cpuTiny) } == true) {
                sb.append("${backendName(id)}: skipped (slower than CPU in the self-test)\n")
                continue
            }
            progress("Testing the UNet on ${backendName(id)} (first time on the NPU can take several minutes)...")
            var session: OrtSession? = null
            try {
                val t0 = System.nanoTime()
                session = guarded(b.id, "UNet benchmark") { b.createSession(env, Component.UNET, model.unet, dims) }
                val loadMs = (System.nanoTime() - t0) / 1_000_000
                val s = session
                val best = timeRuns(b, 2) { pipe.dummyUnetCall(s, lh, lw) }
                results[id] = best
                sb.append("${backendName(id)}: ${"%.2f".format(Locale.US, best / 1000.0)} s per step (load/compile ${loadMs / 1000} s)\n")
            } catch (e: NpuSizeLimit) {
                // Not a failure of the chip: it still runs the UNet at smaller sizes, so Auto keeps it.
                sb.append("${backendName(id)}: skipped at this size (the NPU handles the UNet up to 256x256)\n")
            } catch (e: Throwable) {
                results[id] = -1
                sb.append("${backendName(id)}: failed - ${e.shortMessage()}\n")
            } finally {
                runCatching { session?.close() }
                System.gc()
            }
        }
        settings.setBench("unet", results)
        sb.append("\nAuto now uses for the UNet: ${autoOrderText(Component.UNET)}")
        provider.resetFailures()
        _planVersion.update { it + 1 }
        val text = sb.toString().trim()
        log(text)
        text
    }

    /** One warm-up run, then the best of [n] timed runs, in ms. */
    private fun timeRuns(b: Backend, n: Int, run: () -> Unit): Long {
        guarded(b.id, "speed test") { run() }
        var best = Long.MAX_VALUE
        repeat(n) {
            val r0 = System.nanoTime()
            guarded(b.id, "speed test") { run() }
            best = minOf(best, (System.nanoTime() - r0) / 1_000_000)
        }
        return best
    }

    private fun checkFinite(r: OrtSession.Result) {
        val out = (r.get(0) as OnnxTensor).floatBuffer
        var bad = 0
        while (out.hasRemaining()) {
            val v = out.get()
            if (v.isNaN() || v.isInfinite()) bad++
        }
        if (bad > 0) error("$bad invalid (NaN) values")
    }

    private fun copyAsset(name: String): File {
        val out = File(context.filesDir, name)
        if (out.isFile && out.length() > 0) return out
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(out)
        return out
    }

    companion object {
        /** v2 = self-test with the NeuroPilot FP16 fix (v1.1). Older results are re-measured automatically. */
        const val BENCH_VERSION = 2
    }
}
