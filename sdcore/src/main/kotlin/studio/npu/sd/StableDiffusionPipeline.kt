package studio.npu.sd

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class GenerationParams(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 4,
    val guidance: Float = 8f,
    val seed: Long = 42,
    /** true = TAESD (very fast, slightly softer); false = the model's full VAE decoder (best quality). */
    val tinyDecoder: Boolean = false,
    /** Decode a quick preview after every step (needs the TAESD decoder). */
    val previews: Boolean = false,
)

data class AnimationParams(
    val promptA: String,
    val promptB: String,
    val negativePrompt: String = "",
    val frames: Int = 24,
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 4,
    val guidance: Float = 8f,
    val seedA: Long = 1,
    val seedB: Long = 2,
    val tinyDecoder: Boolean = true,
)

data class GenerationStats(
    val textMs: Long,
    val unetMs: Long,
    val unetCalls: Int,
    val decodeMs: Long,
    val totalMs: Long,
    val backends: Map<Component, String>,
    /** Time spent drawing the live previews between steps. */
    val previewMs: Long = 0,
) {
    val msPerUnetCall: Long get() = if (unetCalls == 0) 0 else unetMs / unetCalls
}

class SdImage(val width: Int, val height: Int, val argb: IntArray, val seed: Long, val stats: GenerationStats?)

interface GenerationListener {
    fun onStatus(text: String) {}
    fun onStep(step: Int, total: Int) {}
    fun onPreview(image: SdImage) {}
}

/**
 * Fixed sizes for the models' symbolic dimensions (accelerators need static shapes).
 * Names cover the usual diffusers/Optimum ONNX exports. lh/lw = latent size = image size / 8.
 */
object ShapeHints {
    fun text() = mapOf("batch_size" to 1L, "batch" to 1L, "sequence_length" to 77L, "sequence" to 77L)

    fun unet(lh: Long, lw: Long) = mapOf(
        "batch_size" to 1L, "batch" to 1L, "num_channels" to 4L, "channels" to 4L,
        "height" to lh, "width" to lw, "steps" to 1L, "sequence_length" to 77L, "sequence" to 77L,
    )

    fun decoder(lh: Long, lw: Long) = mapOf(
        "batch_size" to 1L, "batch" to 1L, "num_channels_latent" to 4L, "channels" to 4L,
        "height_latent" to lh, "width_latent" to lw, "height" to lh, "width" to lw,
    )
}

/** Counts UNet calls and the time spent on live previews (so previews don't inflate the UNet timing). */
private class LoopCounter {
    var unetCalls = 0
    var previewNs = 0L
}

/** Lets the UI stop a running generation quickly (also aborts the ONNX Runtime call in progress). */
class RunControl {
    @Volatile
    var cancelled = false
        private set
    val runOptions = OrtSession.RunOptions()

    fun cancel() {
        cancelled = true
        runCatching { runOptions.setTerminate(true) }
    }

    fun check() {
        if (cancelled) throw GenerationCancelled()
    }

    fun close() = runCatching { runOptions.close() }
}

/**
 * Text -> image with Stable Diffusion 1.x ONNX models:
 * tokenizer -> text encoder -> (UNet x steps, guided by a scheduler) -> VAE decoder -> pixels.
 */
class StableDiffusionPipeline(
    private val env: OrtEnvironment,
    val model: SdModel,
    private val tinyDecoderFile: File?,
    private val sessions: FallbackSessionProvider,
) {
    private val tokenizer by lazy { ClipTokenizer.load(model.vocab, model.merges) }
    private val embedCache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 24
    }

    @Volatile
    var modelType: ModelType? = model.declaredType
        private set

    val hasTinyDecoder: Boolean get() = tinyDecoderFile?.isFile == true

    fun countTokens(text: String): Int = tokenizer.countTokens(text)

    // ---------------------------------------------------------------- public API

    fun generate(p: GenerationParams, control: RunControl, listener: GenerationListener = object : GenerationListener {}): SdImage {
        val tStart = System.nanoTime()
        validateSize(p.width, p.height)
        val lh = p.height / 8L
        val lw = p.width / 8L

        listener.onStatus("Loading model - the very first time on the NPU it is compiled, which can take a few minutes")
        val type = resolveType(lh, lw, control)
        listener.onStatus("Reading prompt")
        val t0 = System.nanoTime()
        val cond = encodePrompt(p.prompt, control)
        val uncond = if (usesCfg(type, p.guidance)) encodePrompt(p.negativePrompt, control) else null
        val textMs = (System.nanoTime() - t0) / 1_000_000

        val noise0 = gaussian(Random(p.seed), (4 * lh * lw).toInt())
        val stepRng = Random(p.seed * 7919 + 17)
        val counter = LoopCounter()
        val t1 = System.nanoTime()
        val latent = denoise(type, cond, uncond, noise0, p.steps, p.guidance, lh, lw, control, listener, counter,
            stepNoise = { gaussian(stepRng, noise0.size) },
            previewTiny = p.previews && hasTinyDecoder,
            seed = p.seed,
        )
        val unetMs = (System.nanoTime() - t1 - counter.previewNs) / 1_000_000

        listener.onStatus("Painting the image")
        val t2 = System.nanoTime()
        val argb = decode(latent, lh, lw, p.tinyDecoder && hasTinyDecoder, control)
        val decodeMs = (System.nanoTime() - t2) / 1_000_000

        val stats = GenerationStats(
            textMs, unetMs, counter.unetCalls, decodeMs, (System.nanoTime() - tStart) / 1_000_000, currentBackends(p.tinyDecoder),
            counter.previewNs / 1_000_000,
        )
        return SdImage(p.width, p.height, argb, p.seed, stats)
    }

    /**
     * A short "morph" animation: blends from prompt A + seed A to prompt B + seed B.
     * Each frame is a full generation, so frames come out at the same speed as single images.
     */
    fun animate(a: AnimationParams, control: RunControl, listener: GenerationListener, onFrame: (Int, SdImage) -> Unit) {
        validateSize(a.width, a.height)
        val lh = a.height / 8L
        val lw = a.width / 8L
        val type = resolveType(lh, lw, control)
        val embA = encodePrompt(a.promptA, control)
        val embB = if (a.promptB.isBlank()) embA else encodePrompt(a.promptB, control)
        val uncond = if (usesCfg(type, a.guidance)) encodePrompt(a.negativePrompt, control) else null
        val n = (4 * lh * lw).toInt()
        val noiseA = gaussian(Random(a.seedA), n)
        val noiseB = gaussian(Random(a.seedB), n)
        val frames = a.frames.coerceAtLeast(2)

        for (i in 0 until frames) {
            control.check()
            val tStart = System.nanoTime()
            val raw = i.toFloat() / (frames - 1)
            val t = raw * raw * (3 - 2 * raw) // ease in/out
            val cond = lerp(embA, embB, t)
            val start = slerp(noiseA, noiseB, t)
            listener.onStatus("Frame ${i + 1} / $frames")
            val counter = LoopCounter()
            val t1 = System.nanoTime()
            // Same step-noise for every frame keeps the animation smooth.
            val stepNoise: (Int) -> FloatArray = { step -> gaussian(Random(a.seedA * 7919 + step), n) }
            val latent = denoise(type, cond, uncond, start, a.steps, a.guidance, lh, lw, control,
                object : GenerationListener {}, counter, stepNoise, previewTiny = false, seed = a.seedA)
            val unetMs = (System.nanoTime() - t1 - counter.previewNs) / 1_000_000
            val t2 = System.nanoTime()
            val argb = decode(latent, lh, lw, a.tinyDecoder && hasTinyDecoder, control)
            val decodeMs = (System.nanoTime() - t2) / 1_000_000
            val stats = GenerationStats(0, unetMs, counter.unetCalls, decodeMs, (System.nanoTime() - tStart) / 1_000_000,
                currentBackends(a.tinyDecoder))
            listener.onStep(i + 1, frames)
            onFrame(i, SdImage(a.width, a.height, argb, a.seedA, stats))
        }
    }

    /** Load the networks ahead of time (on accelerators this is where the slow first-time compile happens). */
    fun prepare(width: Int, height: Int, tiny: Boolean, control: RunControl, listener: GenerationListener) {
        validateSize(width, height)
        val lh = height / 8L
        val lw = width / 8L
        listener.onStatus("Loading text encoder")
        encodePrompt("warm up", control)
        listener.onStatus("Loading UNet (first time on NPU/GPU can take a few minutes)")
        resolveType(lh, lw, control)
        listener.onStatus("Loading image decoder")
        val useTiny = tiny && hasTinyDecoder
        val comp = if (useTiny) Component.TINY_DECODER else Component.VAE_DECODER
        val file = if (useTiny) tinyDecoderFile!! else model.vaeDecoder
        sessions.run(comp, file, decoderDims(lh, lw), { control.cancelled }) { }
    }

    fun currentBackends(tiny: Boolean): Map<Component, String> {
        val m = LinkedHashMap<Component, String>()
        for (c in listOf(Component.TEXT_ENCODER, Component.UNET, if (tiny && hasTinyDecoder) Component.TINY_DECODER else Component.VAE_DECODER)) {
            sessions.activeBackend(c)?.let { m[c] = it }
        }
        return m
    }

    // ---------------------------------------------------------------- steps

    private fun usesCfg(type: ModelType, guidance: Float) = type != ModelType.LCM && guidance > 1f

    private fun resolveType(lh: Long, lw: Long, control: RunControl): ModelType {
        modelType?.let {
            // Still open the UNet so the first-time load/compile happens here, not mid-loop.
            sessions.run(Component.UNET, model.unet, unetDims(lh, lw), { control.cancelled }) { }
            return it
        }
        val hasCond = sessions.run(Component.UNET, model.unet, unetDims(lh, lw), { control.cancelled }) { s ->
            "timestep_cond" in s.inputNames
        }
        val t = if (hasCond) ModelType.LCM else ModelType.SD15
        modelType = t
        return t
    }

    fun encodePrompt(text: String, control: RunControl): FloatArray {
        synchronized(embedCache) { embedCache[text]?.let { return it } }
        val ids = tokenizer.tokenize(text)
        val out = sessions.run(Component.TEXT_ENCODER, model.textEncoder, textDims(), { control.cancelled }) { s ->
            val name = s.inputNames.first()
            val info = s.inputInfo[name]!!.info as TensorInfo
            val shape = longArrayOf(1, ClipTokenizer.MAX_LENGTH.toLong())
            val tensor = if (info.type == OnnxJavaType.INT64) {
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape)
            } else {
                OnnxTensor.createTensor(env, IntBuffer.wrap(ids), shape)
            }
            tensor.use { t ->
                s.run(mapOf(name to t), control.runOptions).use { r -> readFloats(r, 0, "text encoder") }
            }
        }
        require(out.size == ClipTokenizer.MAX_LENGTH * 768) { "Unexpected text encoder output size ${out.size}" }
        synchronized(embedCache) { embedCache[text] = out }
        return out
    }

    private fun denoise(
        type: ModelType,
        cond: FloatArray,
        uncond: FloatArray?,
        noise0: FloatArray,
        steps: Int,
        guidance: Float,
        lh: Long,
        lw: Long,
        control: RunControl,
        listener: GenerationListener,
        counter: LoopCounter,
        stepNoise: (Int) -> FloatArray,
        previewTiny: Boolean,
        seed: Long,
    ): FloatArray {
        val scheduler: Scheduler = when (type) {
            ModelType.LCM -> LcmScheduler(steps)
            ModelType.SD15 -> EulerScheduler(steps)
            ModelType.RECTIFIED_FLOW -> RectifiedFlowScheduler(steps)
        }
        val wEmb = if (type == ModelType.LCM) guidanceEmbedding(guidance) else null
        var latent = FloatArray(noise0.size) { noise0[it] * scheduler.initNoiseSigma }
        val total = scheduler.timesteps.size
        val dims = unetDims(lh, lw)

        for (i in 0 until total) {
            control.check()
            listener.onStatus("Step ${i + 1} / $total")
            val t = scheduler.timesteps[i]
            val input = scheduler.scaleModelInput(latent, i)
            val out = sessions.run(Component.UNET, model.unet, dims, { control.cancelled }) { s ->
                val c = runUnet(s, input, t, cond, wEmb, lh, lw, control)
                counter.unetCalls++
                if (uncond == null) c else {
                    val u = runUnet(s, input, t, uncond, null, lh, lw, control)
                    counter.unetCalls++
                    FloatArray(c.size) { k -> u[k] + guidance * (c[k] - u[k]) }
                }
            }
            latent = scheduler.step(out, i, latent) { stepNoise(i) }
            listener.onStep(i + 1, total)
            // At most ~4 previews per image so long (20-step) runs don't spend time on previews.
            if (previewTiny && i < total - 1 && (i + 1) % maxOf(1, total / 4) == 0) {
                scheduler.previewLatent()?.let { pl ->
                    val p0 = System.nanoTime()
                    runCatching { listener.onPreview(SdImage(lw.toInt() * 8, lh.toInt() * 8, decode(pl, lh, lw, true, control), seed, null)) }
                        .onFailure { if (it is GenerationCancelled) throw it }
                    counter.previewNs += System.nanoTime() - p0
                }
            }
        }
        return latent
    }

    private fun runUnet(
        s: OrtSession, sample: FloatArray, t: Float, hidden: FloatArray, wEmb: FloatArray?,
        lh: Long, lw: Long, control: RunControl,
    ): FloatArray {
        val inputs = LinkedHashMap<String, OnnxTensorLike>()
        try {
            for (name in s.inputNames) {
                val info = s.inputInfo[name]!!.info as TensorInfo
                inputs[name] = when (name) {
                    "sample" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(sample), longArrayOf(1, 4, lh, lw))
                    "timestep" -> timestepTensor(info, t)
                    "encoder_hidden_states" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(hidden), longArrayOf(1, 77, 768))
                    "timestep_cond" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(wEmb ?: guidanceEmbedding(1f)), longArrayOf(1, 256))
                    else -> error("UNet has an input I don't know: '$name'")
                }
            }
            return s.run(inputs, control.runOptions).use { r -> readFloats(r, 0, "UNet") }
        } finally {
            inputs.values.forEach { (it as? OnnxTensor)?.close() }
        }
    }

    private fun timestepTensor(info: TensorInfo, t: Float): OnnxTensor {
        val shape = if (info.shape.isEmpty()) longArrayOf() else longArrayOf(1)
        return when (info.type) {
            OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(t.roundToInt().toLong())), shape)
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(t.roundToInt())), shape)
            else -> OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(t)), shape)
        }
    }

    fun decode(latent: FloatArray, lh: Long, lw: Long, tiny: Boolean, control: RunControl): IntArray {
        val comp = if (tiny) Component.TINY_DECODER else Component.VAE_DECODER
        val file = if (tiny) tinyDecoderFile!! else model.vaeDecoder
        // The full VAE expects latents divided by the SD 1.x scaling factor; TAESD takes them as they are.
        val input = if (tiny) latent else FloatArray(latent.size) { latent[it] / 0.18215f }
        val pixels = sessions.run(comp, file, decoderDims(lh, lw), { control.cancelled }) { s ->
            val name = s.inputNames.first()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, lh, lw)).use { t ->
                s.run(mapOf(name to t), control.runOptions).use { r -> readFloats(r, 0, comp.label) }
            }
        }
        return toArgb(pixels, (lw * 8).toInt(), (lh * 8).toInt())
    }

    /** One UNet call with dummy inputs - used by the app's speed benchmark. */
    fun dummyUnetCall(s: OrtSession, lh: Long, lw: Long): FloatArray {
        val ctl = RunControl()
        try {
            val sample = FloatArray((4 * lh * lw).toInt()) { ((it * 31) % 100 - 50) / 50f }
            val hidden = FloatArray(77 * 768) { ((it * 17) % 100 - 50) / 500f }
            return runUnet(s, sample, 500f, hidden, guidanceEmbedding(8f), lh, lw, ctl)
        } finally {
            ctl.close()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun textDims() = ShapeHints.text()
    private fun unetDims(lh: Long, lw: Long) = ShapeHints.unet(lh, lw)
    private fun decoderDims(lh: Long, lw: Long) = ShapeHints.decoder(lh, lw)

    private fun validateSize(w: Int, h: Int) {
        require(w % 64 == 0 && h % 64 == 0) { "Width and height must be multiples of 64" }
        require(w in 128..1024 && h in 128..1024) { "Size must be between 128 and 1024" }
    }

    private fun readFloats(r: OrtSession.Result, index: Int, what: String): FloatArray {
        val buf = (r.get(index) as OnnxTensor).floatBuffer
        val arr = FloatArray(buf.remaining())
        buf.get(arr)
        // Accelerators run in half precision; overflow shows up as NaN/Inf. Treat that as a backend failure.
        var bad = 0
        for (v in arr) if (v.isNaN() || v.isInfinite()) bad++
        if (bad > 0) throw BadOutputException("$what produced $bad invalid values (NaN/Inf)")
        return arr
    }

    private fun toArgb(chw: FloatArray, w: Int, h: Int): IntArray {
        val plane = w * h
        require(chw.size >= 3 * plane) { "Decoder output too small" }
        return IntArray(plane) { i ->
            val r = ((chw[i] / 2f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
            val g = ((chw[plane + i] / 2f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
            val b = ((chw[2 * plane + i] / 2f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun gaussian(rng: Random, n: Int) = FloatArray(n) { rng.nextGaussian().toFloat() }

    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }

    private fun slerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        val cos = (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1.0, 1.0)
        val theta = acos(cos)
        if (theta < 1e-4) return lerp(a, b, t)
        val s = sin(theta)
        val wa = (sin((1 - t) * theta) / s).toFloat()
        val wb = (sin(t * theta) / s).toFloat()
        return FloatArray(a.size) { wa * a[it] + wb * b[it] }
    }
}
