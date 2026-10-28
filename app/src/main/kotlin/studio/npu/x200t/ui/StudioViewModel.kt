package studio.npu.x200t.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.npu.sd.AnimationParams
import studio.npu.sd.ClipTokenizer
import studio.npu.sd.Component
import studio.npu.sd.GenerationCancelled
import studio.npu.sd.GenerationListener
import studio.npu.sd.GenerationParams
import studio.npu.sd.GenerationStats
import studio.npu.sd.ModelType
import studio.npu.sd.RunControl
import studio.npu.sd.SdImage
import studio.npu.sd.SdModel
import studio.npu.x200t.StudioApp
import studio.npu.x200t.engine.Accel
import studio.npu.x200t.engine.Engine
import studio.npu.x200t.engine.NeuroPilotBackend
import studio.npu.x200t.engine.WorkService
import studio.npu.x200t.engine.backendName
import studio.npu.x200t.media.MediaSaver
import studio.npu.x200t.media.Mp4Encoder
import studio.npu.x200t.models.CatalogModel
import studio.npu.x200t.models.ModelCatalog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class CreateState(
    val modelId: String? = null,
    val prompt: String = "",
    val negative: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 4,
    val guidance: Float = 8f,
    val seed: Long = 42,
    val randomSeed: Boolean = true,
    val tinyDecoder: Boolean = false,
    val live: Boolean = false,
    val busy: Boolean = false,
    val status: String = "",
    val progress: Float = 0f,
    val image: Bitmap? = null,
    val info: String? = null,
    val error: String? = null,
    val savedUri: Uri? = null,
    val tokenCount: Int = 0,
)

data class AnimState(
    val promptA: String = "",
    val promptB: String = "",
    val frames: Int = 16,
    val fps: Int = 8,
    val pingPong: Boolean = true,
    val busy: Boolean = false,
    val status: String = "",
    val progress: Float = 0f,
    val frameBitmaps: List<Bitmap> = emptyList(),
    val videoUri: Uri? = null,
    val error: String? = null,
)

data class InstalledModel(val id: String, val name: String, val type: ModelType?, val model: SdModel)

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val studio = app as StudioApp
    val engine = studio.engine
    private val store = studio.store
    private val downloadsMgr = studio.downloads
    private val prefs = engine.settings

    val downloads = downloadsMgr.state
    val npu = engine.npu
    val log = engine.log
    val disabledAccels = engine.disabled
    val runEvents = engine.runEvents
    val planVersion = engine.planVersion

    fun reEnable(id: String) = engine.reEnable(id)

    private val _benchText = MutableStateFlow<String?>(null)
    val benchText: StateFlow<String?> = _benchText.asStateFlow()

    /** Shown at the top of the Create screen while the automatic first speed test runs. */
    private val _autoTest = MutableStateFlow<String?>(null)
    val autoTest: StateFlow<String?> = _autoTest.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    private val _create = MutableStateFlow(
        CreateState(
            modelId = prefs.getString("model", "").ifEmpty { null },
            prompt = prefs.getString("prompt", "a cozy cabin in a snowy forest at night, warm light in the windows, highly detailed"),
            negative = prefs.getString("negative", "blurry, low quality, deformed"),
            width = prefs.getInt("width", 512),
            height = prefs.getInt("height", 512),
            steps = prefs.getInt("steps", 4),
            guidance = prefs.getFloat("guidance", 8f),
            seed = prefs.getLong("seed", 42),
            randomSeed = prefs.getBool("randomSeed", true),
            tinyDecoder = prefs.getBool("tiny", false),
        ),
    )
    val create: StateFlow<CreateState> = _create.asStateFlow()

    private val _anim = MutableStateFlow(
        AnimState(
            promptA = prefs.getString("animA", "a calm lake at sunrise, mountains, mist"),
            promptB = prefs.getString("animB", "the same lake at night under a starry sky, aurora"),
        ),
    )
    val anim: StateFlow<AnimState> = _anim.asStateFlow()

    private val _selfTest = MutableStateFlow<String?>(null)
    val selfTest: StateFlow<String?> = _selfTest.asStateFlow()

    private val _engineBusy = MutableStateFlow(false)
    val engineBusy: StateFlow<Boolean> = _engineBusy.asStateFlow()

    private var genJob: Job? = null
    private var liveJob: Job? = null
    private var control: RunControl? = null
    private var tokenizer: Pair<String, ClipTokenizer>? = null

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    init {
        refreshInstalled()
        onPromptChanged(_create.value.prompt)
        // While anything long is running, keep a foreground service so locking the phone doesn't freeze the work.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(engineBusy, downloads) { busy, dl -> busy || dl.values.any { it.running } }
                .collect { on -> WorkService.keepAlive(getApplication(), on) }
        }
        viewModelScope.launch {
            engine.initNpu()
            // First start (or after an update that changed the NPU setup): measure the chips once so
            // "Auto" knows which one is fastest on this phone.
            if (prefs.benchVersion < Engine.BENCH_VERSION) {
                _engineBusy.value = true
                _autoTest.value = "Measuring the speed of your NPU, GPU and CPU (one time, under a minute)..."
                _selfTest.value = runCatching { engine.selfTest() }.getOrElse { "Self-test failed: ${it.message}" }
                _autoTest.value = null
                _engineBusy.value = false
            }
        }
    }

    // ------------------------------------------------------------------ models

    fun refreshInstalled() {
        val list = store.installed().map { m ->
            val id = m.dir.name
            InstalledModel(id, ModelCatalog.byId(id)?.name ?: m.name, m.declaredType ?: ModelCatalog.byId(id)?.type, m)
        }
        _installed.value = list
        val cur = _create.value.modelId
        if (list.isNotEmpty() && list.none { it.id == cur }) selectModel(list.first().id)
    }

    fun selectedModel(): InstalledModel? = _installed.value.firstOrNull { it.id == _create.value.modelId }

    fun selectModel(id: String) {
        val m = _installed.value.firstOrNull { it.id == id } ?: return
        val steps = m.model.defaultSteps ?: defaultSteps(m.type)
        val guidance = m.model.defaultGuidance ?: defaultGuidance(m.type)
        _create.update { it.copy(modelId = id, steps = steps, guidance = guidance) }
        prefs.putString("model", id)
        saveCreatePrefs()
        onPromptChanged(_create.value.prompt) // refresh the word-piece counter
    }

    fun startDownload(m: CatalogModel) = downloadsMgr.start(m) { refreshInstalled() }
    fun pauseDownload(id: String) = downloadsMgr.pause(id)
    fun downloadedBytes(m: CatalogModel) = store.downloadedBytes(m)
    fun freeSpace() = store.freeBytes()
    fun modelsFolder(): String = store.root.absolutePath

    fun deleteModel(id: String) {
        viewModelScope.launch {
            if (_create.value.modelId == id) engine.releaseMemory()
            withContext(Dispatchers.IO) { store.delete(id) }
            downloadsMgr.clear(id)
            if (_create.value.modelId == id) _create.update { it.copy(modelId = null) }
            refreshInstalled()
        }
    }

    fun importFolder(uri: Uri, name: String, type: ModelType?) {
        studio.appScope.launch {
            try {
                _importStatus.value = "Starting import..."
                store.importFolder(uri, name, type) { _importStatus.value = it }
                _importStatus.value = "Imported \"$name\""
                refreshInstalled()
            } catch (e: Throwable) {
                _importStatus.value = "Import failed: ${e.message}"
            }
        }
    }

    // ------------------------------------------------------------------ create screen

    fun update(block: (CreateState) -> CreateState) {
        val before = _create.value
        _create.update(block)
        val after = _create.value
        saveCreatePrefs()
        if (after.live && (after.prompt != before.prompt || after.width != before.width || after.height != before.height ||
                after.seed != before.seed || after.guidance != before.guidance || (!before.live && after.live))
        ) scheduleLive()
    }

    fun onPromptChanged(text: String) {
        update { it.copy(prompt = text) }
        val m = selectedModel() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val n = runCatching {
                val tok = synchronized(this@StudioViewModel) {
                    tokenizer?.takeIf { it.first == m.id }?.second
                        ?: ClipTokenizer.load(m.model.vocab, m.model.merges).also { tokenizer = m.id to it }
                }
                synchronized(tok) { tok.countTokens(text) }
            }.getOrDefault(0)
            _create.update { it.copy(tokenCount = n) }
        }
    }

    fun newSeed() = update { it.copy(seed = Random.nextLong(1, 2_000_000_000)) }

    private fun scheduleLive() {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            delay(350)
            generate(live = true)
        }
    }

    fun stop() {
        control?.cancel()
        _create.update { it.copy(status = "Stopping...") }
    }

    fun generate(live: Boolean = false) {
        val m = selectedModel()
        if (m == null) {
            _create.update { it.copy(error = "Download a model first (Models tab).") }
            return
        }
        control?.cancel()
        genJob?.cancel()
        val ctl = RunControl()
        control = ctl
        var st = _create.value
        if (!live && st.randomSeed) {
            _create.update { it.copy(seed = Random.nextLong(1, 2_000_000_000)) }
            st = _create.value
            saveCreatePrefs()
        }
        val type = m.type ?: ModelType.LCM
        val params = GenerationParams(
            prompt = st.prompt,
            negativePrompt = st.negative,
            width = st.width,
            height = st.height,
            steps = if (live) liveSteps(type, st.steps) else st.steps,
            guidance = st.guidance,
            seed = st.seed,
            tinyDecoder = live || st.tinyDecoder,
            previews = !live,
        )
        genJob = viewModelScope.launch {
            _engineBusy.value = true
            engine.clearRunEvents()
            _create.update { it.copy(busy = true, error = null, progress = 0f, status = "Starting", savedUri = null) }
            val listener = object : GenerationListener {
                override fun onStatus(text: String) = _create.update { it.copy(status = text) }
                override fun onStep(step: Int, total: Int) = _create.update { it.copy(progress = step.toFloat() / total) }
                override fun onPreview(image: SdImage) {
                    val bmp = image.toBitmap()
                    _create.update { it.copy(image = bmp) }
                }
            }
            try {
                engine.initNpu()
                val (img, detected) = engine.onEngine {
                    val p = engine.pipelineFor(m.model)
                    p.generate(params, ctl, listener) to p.modelType
                }
                if (m.type == null && detected != null) rememberDetectedType(m, detected)
                val info = statsText(img.stats, live)
                engine.log("Image done: $info")
                _create.update { it.copy(image = img.toBitmap(), info = info, status = "", progress = 1f) }
            } catch (e: GenerationCancelled) {
                // replaced by a newer request (live mode) or stopped by the user
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                engine.log("Generation failed: ${e.message}")
                _create.update { it.copy(error = e.message ?: e.javaClass.simpleName, status = "") }
            } finally {
                if (control === ctl) {
                    _create.update { it.copy(busy = false) }
                    _engineBusy.value = false
                }
                ctl.close()
            }
        }
    }

    fun saveImage() {
        val bmp = _create.value.image ?: return
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    MediaSaver.saveImage(getApplication(), bmp, "npu_${_create.value.seed}_${stamp()}")
                }
                _create.update { it.copy(savedUri = uri, info = (it.info ?: "") + "\nSaved to Pictures/NPU Image Studio") }
            } catch (e: Throwable) {
                _create.update { it.copy(error = "Save failed: ${e.message}") }
            }
        }
    }

    fun shareImage() {
        viewModelScope.launch {
            val uri = _create.value.savedUri ?: run {
                val bmp = _create.value.image ?: return@launch
                withContext(Dispatchers.IO) { MediaSaver.saveImage(getApplication(), bmp, "npu_${_create.value.seed}_${stamp()}") }
                    .also { u -> _create.update { it.copy(savedUri = u) } }
            }
            MediaSaver.share(getApplication(), uri, "image/png")
        }
    }

    // ------------------------------------------------------------------ animate screen

    fun updateAnim(block: (AnimState) -> AnimState) {
        _anim.update(block)
        prefs.putString("animA", _anim.value.promptA)
        prefs.putString("animB", _anim.value.promptB)
    }

    fun animate() {
        val m = selectedModel() ?: run {
            _anim.update { it.copy(error = "Download a model first (Models tab).") }
            return
        }
        control?.cancel()
        val ctl = RunControl()
        control = ctl
        val a = _anim.value
        val c = _create.value
        val seedA = c.seed
        val params = AnimationParams(
            promptA = a.promptA, promptB = a.promptB, negativePrompt = c.negative, frames = a.frames,
            width = c.width, height = c.height, steps = c.steps, guidance = c.guidance,
            seedA = seedA, seedB = seedA + 1, tinyDecoder = c.tinyDecoder,
        )
        viewModelScope.launch {
            _engineBusy.value = true
            engine.clearRunEvents()
            _anim.update { it.copy(busy = true, error = null, progress = 0f, frameBitmaps = emptyList(), videoUri = null, status = "Starting") }
            val frames = ArrayList<IntArray>()
            try {
                engine.initNpu()
                val listener = object : GenerationListener {
                    override fun onStatus(text: String) = _anim.update { it.copy(status = text) }
                    override fun onStep(step: Int, total: Int) = _anim.update { it.copy(progress = step.toFloat() / total) }
                }
                var lastStats: GenerationStats? = null
                engine.onEngine {
                    engine.pipelineFor(m.model).animate(params, ctl, listener) { _, img ->
                        frames.add(img.argb)
                        lastStats = img.stats
                        val bmp = img.toBitmap()
                        _anim.update { it.copy(frameBitmaps = it.frameBitmaps + bmp) }
                    }
                }
                _anim.update { it.copy(status = "Encoding MP4 video") }
                val sequence = if (a.pingPong && frames.size > 2) frames + frames.reversed().drop(1).dropLast(1) else frames
                val uri = withContext(Dispatchers.Default) {
                    val tmp = File(getApplication<Application>().cacheDir, "anim.mp4")
                    val enc = Mp4Encoder(tmp, params.width, params.height, a.fps)
                    try {
                        sequence.forEach { enc.addFrame(it) }
                    } finally {
                        enc.finish()
                    }
                    MediaSaver.saveVideo(getApplication(), tmp, "npu_anim_${stamp()}").also { tmp.delete() }
                }
                val per = lastStats?.let { " · ~${"%.1f".format(Locale.US, it.totalMs / 1000.0)} s per frame" } ?: ""
                engine.log("Animation done: ${frames.size} frames$per")
                _anim.update { it.copy(videoUri = uri, status = "Saved to Movies/NPU Image Studio (${sequence.size} frames$per)", progress = 1f) }
            } catch (e: GenerationCancelled) {
                _anim.update { it.copy(status = "Stopped") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                engine.log("Animation failed: ${e.message}")
                _anim.update { it.copy(error = e.message ?: e.javaClass.simpleName, status = "") }
            } finally {
                _anim.update { it.copy(busy = false) }
                _engineBusy.value = false
                ctl.close()
            }
        }
    }

    fun stopAnim() {
        control?.cancel()
    }

    fun openVideo() {
        _anim.value.videoUri?.let { MediaSaver.view(getApplication(), it, "video/mp4") }
    }

    fun shareVideo() {
        _anim.value.videoUri?.let { MediaSaver.share(getApplication(), it, "video/mp4") }
    }

    // ------------------------------------------------------------------ engine screen

    fun accel(c: Component): Accel = prefs.accel(c)

    fun setAccel(c: Component, a: Accel) {
        prefs.setAccel(c, a)
        viewModelScope.launch { engine.applySettings() }
    }

    var npuCache: Boolean
        get() = prefs.npuCache
        set(v) {
            prefs.npuCache = v
        }

    /** Strict = a chip you picked by hand is used alone; if it fails you see the error instead of a CPU fallback. */
    var strict: Boolean
        get() = prefs.strict
        set(v) {
            prefs.strict = v
            viewModelScope.launch { engine.applySettings() }
        }

    /** "NPU (NNAPI) → CPU" style list for the UNet, used by the badge on the Create screen. */
    fun unetPlanShort(): String {
        val a = prefs.accel(Component.UNET)
        val c = _create.value
        val tooBigForNpu = (c.width / 8L) * (c.height / 8L) > NeuroPilotBackend.MAX_UNET_TOKENS
        if (tooBigForNpu && a != Accel.CPU && (a == Accel.AUTO || a == Accel.NPU)) {
            return "CPU at ${c.width}×${c.height} (the NPU handles up to 256×256)"
        }
        return when {
            a == Accel.AUTO -> engine.autoOrderText(Component.UNET)
            a == Accel.CPU -> "CPU"
            !engine.isAvailable(a.backendId!!) ->
                if (prefs.strict) "nothing (${backendName(a.backendId)} not available)" else "CPU (${backendName(a.backendId)} not available)"
            prefs.strict -> backendName(a.backendId)
            else -> backendName(a.backendId) + " → CPU"
        }
    }

    /** Plain-language description of what the current setting for [c] really does. */
    fun planText(c: Component): String {
        val a = prefs.accel(c)
        return when {
            a == Accel.AUTO -> "Auto uses: " + engine.autoOrderText(c).ifEmpty { "CPU" }
            a == Accel.CPU -> "Always the CPU."
            !engine.isAvailable(a.backendId!!) ->
                "⚠ ${backendName(a.backendId)} isn't available on this phone (see NPU status above)" +
                    if (prefs.strict) " - images will fail with an error." else " - the CPU is used instead."
            prefs.strict -> "Only ${backendName(a.backendId!!)}. If it fails you get an error (strict mode)."
            else -> "${backendName(a.backendId!!)}, and the CPU if that fails."
        }
    }

    fun resetToAuto() = viewModelScope.launch { engine.resetToAuto() }

    fun retryFailed() = viewModelScope.launch { engine.retryFailed() }

    /** Chips that failed before (skipped until "Try again"): "UNet on NPU (NeuroPilot)" -> reason. */
    fun failedChips(): List<Pair<String, String>> = engine.failures.describe()

    fun benchmarkUnet() {
        val m = selectedModel() ?: run {
            _benchText.value = "Download a model first."
            return
        }
        val c = _create.value
        viewModelScope.launch {
            _engineBusy.value = true
            _benchText.value = "Starting..."
            _benchText.value = runCatching {
                engine.benchmarkUnet(m.model, c.width, c.height) { msg -> _benchText.value = msg }
            }.getOrElse { "Benchmark failed: ${it.message}" }
            _engineBusy.value = false
        }
    }

    fun clearNpuCache() {
        viewModelScope.launch {
            engine.releaseMemory()
            withContext(Dispatchers.IO) { File(getApplication<Application>().filesDir, "npu_cache").deleteRecursively() }
            engine.log("Saved NPU compiles deleted")
        }
    }

    fun runSelfTest() {
        viewModelScope.launch {
            _engineBusy.value = true
            _selfTest.value = "Running... (the first NPU compile can take a little while)"
            _selfTest.value = runCatching { engine.selfTest() }.getOrElse { "Self-test failed: ${it.message}" }
            _engineBusy.value = false
        }
    }

    fun releaseMemory() = viewModelScope.launch { engine.releaseMemory() }

    /** This app's own log lines from logcat (ONNX Runtime + MediaTek plugin messages). */
    suspend fun systemLog(): String = withContext(Dispatchers.IO) {
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "800", "--pid", android.os.Process.myPid().toString()))
            p.inputStream.bufferedReader().readText()
        }.getOrElse { "Could not read logcat: ${it.message}" }
    }

    // ------------------------------------------------------------------ helpers

    /** Imported models without a declared type: store what the pipeline detected (LCM or normal SD 1.5). */
    private fun rememberDetectedType(m: InstalledModel, t: ModelType) {
        runCatching {
            SdModel.writeManifest(m.model.dir, m.name, t, defaultSteps(t), defaultGuidance(t))
        }
        _installed.update { list -> list.map { if (it.id == m.id) it.copy(type = t) else it } }
        if (t == ModelType.LCM && _create.value.steps > 8) _create.update { it.copy(steps = 4, guidance = 8f) }
    }

    private fun saveCreatePrefs() {
        val s = _create.value
        prefs.putString("prompt", s.prompt)
        prefs.putString("negative", s.negative)
        prefs.putInt("width", s.width)
        prefs.putInt("height", s.height)
        prefs.putInt("steps", s.steps)
        prefs.putFloat("guidance", s.guidance)
        prefs.putLong("seed", s.seed)
        prefs.putBool("randomSeed", s.randomSeed)
        prefs.putBool("tiny", s.tinyDecoder)
    }

    private fun statsText(s: GenerationStats?, live: Boolean): String {
        if (s == null) return ""
        fun sec(ms: Long) = String.format(Locale.US, "%.2f s", ms / 1000.0)
        val b = s.backends
        val loading = s.totalMs - s.textMs - s.unetMs - s.decodeMs - s.previewMs
        return buildString {
            append(if (live) "Live preview in " else "Done in ")
            append(sec(s.totalMs))
            append("\n• Main model (UNet): ${s.unetCalls} × ${sec(s.msPerUnetCall)} on ${b[Component.UNET] ?: "?"}")
            val dec = b[Component.TINY_DECODER] ?: b[Component.VAE_DECODER]
            append("\n• Image decoder: ${sec(s.decodeMs)} on ${dec ?: "?"}")
            append("\n• Text encoder: ${sec(s.textMs)} on ${b[Component.TEXT_ENCODER] ?: "(remembered)"}")
            if (loading > 2000) {
                append("\n• One-time loading/compiling: ${sec(loading)} (first image after opening the app, or after changing size/hardware)")
            }
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    companion object {
        fun defaultSteps(t: ModelType?) = when (t) {
            ModelType.SD15 -> 20
            ModelType.RECTIFIED_FLOW -> 1
            else -> 4
        }

        fun defaultGuidance(t: ModelType?) = when (t) {
            ModelType.SD15 -> 7f
            ModelType.RECTIFIED_FLOW -> 0f
            else -> 8f
        }

        fun stepRange(t: ModelType?) = when (t) {
            ModelType.SD15 -> 5..40
            ModelType.RECTIFIED_FLOW -> 1..4
            else -> 1..8
        }

        fun liveSteps(t: ModelType, chosen: Int) = when (t) {
            ModelType.LCM -> minOf(chosen, 2)
            ModelType.RECTIFIED_FLOW -> 1
            ModelType.SD15 -> minOf(chosen, 8)
        }

        fun usesNegative(t: ModelType?, guidance: Float) = t != ModelType.LCM && guidance > 1f
    }
}

fun SdImage.toBitmap(): Bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
