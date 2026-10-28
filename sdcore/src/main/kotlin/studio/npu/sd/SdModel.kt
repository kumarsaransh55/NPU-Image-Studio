package studio.npu.sd

import java.io.File

enum class ModelType(val id: String, val label: String) {
    LCM("lcm", "LCM (2-8 steps, fast)"),
    SD15("sd15", "Stable Diffusion 1.5 (15-30 steps)"),
    RECTIFIED_FLOW("rectified_flow", "InstaFlow (1-2 steps, fastest)");

    companion object {
        fun fromId(id: String?): ModelType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A Stable Diffusion 1.x model in the standard "diffusers ONNX" folder layout:
 *   tokenizer/vocab.json, tokenizer/merges.txt, text_encoder/model.onnx,
 *   unet/model.onnx (+ model.onnx_data), vae_decoder/model.onnx
 * plus our optional studio_model.json with the model type and default settings.
 */
class SdModel(val dir: File) {
    val vocab = File(dir, "tokenizer/vocab.json")
    val merges = File(dir, "tokenizer/merges.txt")
    val textEncoder = File(dir, "text_encoder/model.onnx")
    val unetSource = File(dir, "unet/model.onnx")
    val vaeDecoder = File(dir, "vae_decoder/model.onnx")

    /** The UNet graph that is run: [unetSource] without the attention's useless "+ 0" (see [OnnxGraphFixer]). */
    val unet: File by lazy { OnnxGraphFixer.prepared(unetSource) }
    val manifestFile = File(dir, MANIFEST)

    private val manifest: Map<String, Any?> by lazy {
        if (manifestFile.isFile) runCatching { MiniJson.parseObject(manifestFile.readText()) }.getOrNull() ?: emptyMap()
        else emptyMap()
    }

    val name: String get() = manifest["name"] as? String ?: dir.name

    /** null = decide from the UNet inputs (LCM models have a "timestep_cond" input). */
    val declaredType: ModelType? get() = ModelType.fromId(manifest["type"] as? String)
    val defaultSteps: Int? get() = (manifest["defaultSteps"] as? Number)?.toInt()
    val defaultGuidance: Float? get() = (manifest["defaultGuidance"] as? Number)?.toFloat()

    fun missingFiles(): List<String> = listOf(vocab, merges, textEncoder, unetSource, vaeDecoder)
        .filter { !it.isFile || it.length() == 0L }
        .map { it.relativeTo(dir).path }

    val isComplete: Boolean get() = missingFiles().isEmpty()

    companion object {
        const val MANIFEST = "studio_model.json"

        /** [type] null = let the app detect it from the UNet when the model is first used. */
        fun writeManifest(dir: File, name: String, type: ModelType?, steps: Int?, guidance: Float?) {
            val parts = mutableListOf(""""name": ${quote(name)}""")
            if (type != null) parts += """"type": "${type.id}""""
            if (steps != null) parts += """"defaultSteps": $steps"""
            if (guidance != null) parts += """"defaultGuidance": $guidance"""
            File(dir, MANIFEST).writeText(parts.joinToString(", ", "{", "}"))
        }

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
