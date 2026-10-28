package studio.npu.x200t.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import studio.npu.sd.Backend
import studio.npu.sd.Component
import studio.npu.sd.baseSessionOptions
import java.io.File
import java.util.EnumSet

/** User choice per network. [backendId] matches [Backend.id]. */
enum class Accel(val label: String, val backendId: String?) {
    AUTO("Auto - fastest measured on this phone", null),
    NNAPI("NPU via Android NNAPI", "nnapi"),
    NPU("NPU via MediaTek NeuroPilot plugin", "npu"),
    GPU("GPU (Vulkan)", "gpu"),
    CPU("CPU only", "cpu"),
}

/** Short names shown next to results, e.g. "UNet on NPU (NNAPI)". */
fun backendName(id: String): String = when (id) {
    "npu" -> "NPU (NeuroPilot)"
    "nnapi" -> "NPU (NNAPI)"
    "gpu" -> "GPU"
    else -> "CPU"
}

/**
 * MediaTek NPU through MediaTek's ONNX Runtime plugin. Parts of the model the NPU can't run
 * are automatically kept on the CPU by ONNX Runtime.
 *
 * Compiling a big model for the NPU is slow (it can take minutes the first time), so the compiled
 * result is saved as an "EPContext" model in [cacheDir] and reused next time.
 */
class NeuroPilotBackend(
    private val devices: List<OrtEpDevice>,
    private val cacheDir: File,
    private val useCache: () -> Boolean,
    private val log: (String) -> Unit,
    /** true = try the UNet on the NPU above [MAX_UNET_TOKENS] anyway (developer testing). */
    private val allowBigUnet: () -> Boolean = { false },
) : Backend {
    override val id = "npu"
    override val label = backendName("npu")
    override val staticShapes = true

    override fun createSession(env: OrtEnvironment, component: Component, modelFile: File, dims: Map<String, Long>): OrtSession {
        val tokens = (dims["height"] ?: 0L) * (dims["width"] ?: 0L)
        if (component == Component.UNET && tokens > MAX_UNET_TOKENS && !allowBigUnet()) throw NpuSizeLimit()
        return createCompiled(env, component, modelFile, dims)
    }

    companion object {
        /**
         * Latent positions (image width/8 x height/8) up to which the UNet runs on the NPU: 256x256.
         * Tested on the X200T: at 320, 384 and 512 the compile either fails (NEURON_UNMAPPABLE) or the NPU
         * refuses one of the parts while running (NEURON_BAD_DATA), so trying only wastes minutes.
         */
        const val MAX_UNET_TOKENS = 32L * 32L
    }

    private fun createCompiled(env: OrtEnvironment, component: Component, modelFile: File, dims: Map<String, Long>): OrtSession {
        val key = cacheKey(component, modelFile, dims, "plain")
        val ctx = File(cacheDir, "$key.onnx")
        if (useCache() && ctx.isFile) {
            try {
                val s = env.createSession(ctx.absolutePath, options(dims))
                log("${component.label}: loaded saved NPU compile")
                return s
            } catch (e: Throwable) {
                log("${component.label}: saved NPU compile unusable, recompiling (${e.message?.replace('\n', ' ')?.take(600)})")
                deleteCache(key)
            }
        }
        if (useCache()) {
            cacheDir.mkdirs()
            try {
                val o = options(dims)
                o.addConfigEntry("ep.context_enable", "1")
                o.addConfigEntry("ep.context_file_path", ctx.absolutePath)
                o.addConfigEntry("ep.context_embed_mode", "0")
                // No external-initializer file: the constants left on the CPU side are small, and a shape
                // constant stored externally makes the saved model fail to reload ("Cannot parse data from
                // external tensors" for the UNet's Expand node). The big weights live in the .bin files.
                val s = env.createSession(modelFile.absolutePath, o)
                log("${component.label}: compiled for NPU" + if (ctx.isFile) " and saved for next time" else "")
                return s
            } catch (e: Throwable) {
                deleteCache(key)
                val msg = e.message ?: ""
                // Only retry without the cache if saving the cache was the problem.
                if (!msg.contains("context", true) && !msg.contains("EPContext", true) && !msg.contains("dump", true)) throw e
                log("${component.label}: could not save NPU compile, continuing without cache")
            }
        }
        return env.createSession(modelFile.absolutePath, options(dims))
    }

    /** A saved compile that crashes or gives garbage when run must not be loaded again. */
    override fun onRunFailed(component: Component, modelFile: File, dims: Map<String, Long>) {
        deleteCache(cacheKey(component, modelFile, dims, "plain"))
    }

    private fun options(dims: Map<String, Long>): OrtSession.SessionOptions {
        val o = baseSessionOptions(dims, applyDims = true, threads = 0)
        // The NPU (MDLA) computes in 16-bit. Without USE_FP16 the plugin refuses float32 Conv/MatMul/Gemm,
        // i.e. almost the whole model silently stays on the CPU. Big models are split into <=768 MB parts
        // automatically by the plugin.
        o.addExecutionProvider(devices, mapOf("NEURON_FLAG_USE_FP16" to "1"))
        return o
    }

    private fun cacheKey(component: Component, f: File, dims: Map<String, Long>, variant: String): String {
        // "fp16" = compiled with NEURON_FLAG_USE_FP16 (v1.0 compiles without it are not reused).
        // [variant] is always "plain" now (v1.2-v1.4 tried other plugin options; their names stay unique).
        val sig = "fp16|${f.absolutePath}|${f.length()}|${f.lastModified()}|" +
            dims.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        return component.name.lowercase() + "_" + Integer.toHexString(sig.hashCode()) + "_" + variant
    }

    /** A saved compile = <key>.onnx + <key>.data + the plugin's <key>_neuron_N.bin files. */
    private fun deleteCache(key: String) {
        cacheDir.listFiles()
            ?.filter { it.name == "$key.onnx" || it.name == "$key.data" || it.name.startsWith("${key}_neuron_") }
            ?.forEach { it.delete() }
    }
}

/** Phone GPU (Immortalis-G925) through ONNX Runtime's WebGPU provider, which runs on Vulkan. */
class WebGpuBackend : Backend {
    override val id = "gpu"
    override val label = backendName("gpu")
    override val staticShapes = true

    override fun createSession(env: OrtEnvironment, component: Component, modelFile: File, dims: Map<String, Long>): OrtSession {
        val o = baseSessionOptions(dims, applyDims = true, threads = 0)
        o.addWebGPU(emptyMap())
        return env.createSession(modelFile.absolutePath, o)
    }
}

/**
 * Android's NNAPI. Deprecated by Google since Android 15, but on the vivo X200T (Origin OS 6) it still
 * reaches MediaTek's accelerator: the self-test ran the TAESD decoder ~20x faster than the CPU.
 * CPU_DISABLED stops NNAPI from quietly using its slow CPU reference implementation.
 */
class NnapiBackend : Backend {
    override val id = "nnapi"
    override val label = backendName("nnapi")
    override val staticShapes = true

    override fun createSession(env: OrtEnvironment, component: Component, modelFile: File, dims: Map<String, Long>): OrtSession {
        val o = baseSessionOptions(dims, applyDims = true, threads = 0)
        o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
        return env.createSession(modelFile.absolutePath, o)
    }
}

/** Thrown when the image size is too big for the UNet on the NPU of this phone. */
class NpuSizeLimit : IllegalStateException(
    "the image is too big for the NPU on this phone (it handles the main model up to 256x256), so the CPU is used",
)
