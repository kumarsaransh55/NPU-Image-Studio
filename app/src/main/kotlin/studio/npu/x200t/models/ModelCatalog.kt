package studio.npu.x200t.models

import studio.npu.sd.ModelType

data class CatalogFile(val path: String, val size: Long)

data class CatalogModel(
    val id: String,
    val name: String,
    val description: String,
    val repo: String,
    val type: ModelType,
    val defaultSteps: Int,
    val defaultGuidance: Float,
    val license: String,
    val files: List<CatalogFile>,
    val compact: Boolean = false,
    val revision: String = "main",
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    fun url(f: CatalogFile) = "https://huggingface.co/$repo/resolve/$revision/${f.path}"
}

/**
 * Ready-made Stable Diffusion 1.5 models converted to ONNX with fp32 floating-point inputs/outputs.
 * Compact exports use fp16 weights internally; the pipeline still supplies and receives fp32 tensors.
 * All are open community models; none of them includes a "safety checker" filter.
 * File sizes are from the Hugging Face API (Oct 2026).
 */
object ModelCatalog {
    private fun sd15Files(unetGraph: Long, unetData: Long) = listOf(
        CatalogFile("tokenizer/vocab.json", 1_059_962),
        CatalogFile("tokenizer/merges.txt", 524_619),
        CatalogFile("text_encoder/model.onnx", 492_588_002),
        CatalogFile("unet/model.onnx", unetGraph),
        CatalogFile("vae_decoder/model.onnx", 198_078_038),
        CatalogFile("unet/model.onnx_data", unetData),
    )

    private fun compactFiles(unet: Long) = listOf(
        CatalogFile("tokenizer/vocab.json", 1_059_962),
        CatalogFile("tokenizer/merges.txt", 524_619),
        CatalogFile("text_encoder/model.onnx", 246_323_042),
        CatalogFile("unet/model.onnx", unet),
        CatalogFile("vae_decoder/model.onnx", 198_078_716),
        CatalogFile("LICENSE.md", 14_385),
    )

    val models = listOf(
        CatalogModel(
            id = "segmind-tiny-sd",
            name = "Segmind Tiny-SD",
            description = "Smallest download. Distilled SD 1.5 for lighter local generation; less detail than full models. " +
                "Classic ~20-step model, not an LCM; smaller does not necessarily mean faster. NPU support is unverified.",
            repo = "danyalmalik/tiny-sd-ONNX-WebGPU",
            type = ModelType.SD15, defaultSteps = 20, defaultGuidance = 7.5f,
            license = "CreativeML OpenRAIL-M",
            files = compactFiles(647_336_426),
            compact = true,
            revision = "bcb34e52bbb548d5d4bbb3b210c9fa1cd248a9ed",
        ),
        CatalogModel(
            id = "bk-sdm-small",
            name = "BK-SDM Small",
            description = "Compact SD 1.5 from Nota AI, with a pruned main model. " +
                "Classic ~20-step generation; a smaller download, not a few-step speed model. NPU support is unverified.",
            repo = "danyalmalik/bk-sdm-small-2m-ONNX-WebGPU",
            type = ModelType.SD15, defaultSteps = 20, defaultGuidance = 7.5f,
            license = "CreativeML OpenRAIL-M",
            files = compactFiles(965_284_722),
            compact = true,
            revision = "96a7f8a4d398898d882960e67aa71c9961ec312c",
        ),
        CatalogModel(
            id = "lcm-dreamshaper-v7",
            name = "DreamShaper v7 LCM",
            description = "Best start. Fast 4-step model (Latent Consistency). All-round: people, art, photos, fantasy.",
            repo = "TheyCallMeHex/LCM-Dreamshaper-V7-ONNX",
            type = ModelType.LCM, defaultSteps = 4, defaultGuidance = 8f,
            license = "MIT (LCM) / CreativeML OpenRAIL-M (DreamShaper)",
            files = sd15Files(1_948_508, 3_438_411_520),
        ),
        CatalogModel(
            id = "instaflow-0.9b",
            name = "InstaFlow 0.9B (1 step)",
            description = "Fastest: one UNet step per image. Good for live typing. Quality is lower than the others.",
            repo = "TheyCallMeHex/InstaFlow-0.9B-ONNX",
            type = ModelType.RECTIFIED_FLOW, defaultSteps = 1, defaultGuidance = 0f,
            license = "CreativeML OpenRAIL-M",
            files = sd15Files(1_948_104, 3_438_083_840),
        ),
        CatalogModel(
            id = "epicrealism",
            name = "epiCRealism",
            description = "Photo-realistic SD 1.5 fine-tune. Classic model: ~20 steps + negative prompt, so ~10x slower than LCM.",
            repo = "TheyCallMeHex/epiCRealism-ONNX",
            type = ModelType.SD15, defaultSteps = 20, defaultGuidance = 7f,
            license = "CreativeML OpenRAIL-M",
            files = sd15Files(1_950_755, 3_438_083_840),
        ),
        CatalogModel(
            id = "deliberate-v3",
            name = "Deliberate v3",
            description = "Popular all-round SD 1.5 fine-tune. Classic model: ~20 steps + negative prompt.",
            repo = "TheyCallMeHex/Deliberate-V3-ONNX",
            type = ModelType.SD15, defaultSteps = 20, defaultGuidance = 7f,
            license = "CreativeML OpenRAIL-M",
            files = sd15Files(1_948_104, 3_438_083_840),
        ),
    )

    fun byId(id: String) = models.firstOrNull { it.id == id }
}
