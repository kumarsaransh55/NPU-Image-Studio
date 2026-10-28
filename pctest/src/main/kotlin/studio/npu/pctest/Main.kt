package studio.npu.pctest

import ai.onnxruntime.OrtEnvironment
import studio.npu.sd.AnimationParams
import studio.npu.sd.ClipTokenizer
import studio.npu.sd.CpuBackend
import studio.npu.sd.FallbackSessionProvider
import studio.npu.sd.GenerationListener
import studio.npu.sd.GenerationParams
import studio.npu.sd.OnnxGraphFixer
import studio.npu.sd.OnnxGraphScanner
import studio.npu.sd.RunControl
import studio.npu.sd.SdImage
import studio.npu.sd.SdModel
import studio.npu.sd.StableDiffusionPipeline
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * PC test runner for the engine (CPU only).
 *
 *   gradlew :pctest:run --args="--model <dir> --taesd <taesd.onnx> --prompt 'a cat' --steps 4 --size 512 --out out.png"
 *
 * Extra flags: --tiny (use TAESD), --seed N, --guidance G, --frames N --promptB '...' (morph animation test),
 *              --tokens 'text' (print token ids and exit)
 */
fun main(args: Array<String>) {
    val a = parseArgs(args)
    a["scan"]?.let { path ->
        val nodes = OnnxGraphScanner.scan(File(path))
        val t3 = OnnxGraphScanner.transposeNames(File(path), 3)
        println("nodes=${nodes.size} transposes=${nodes.count { it.opType == "Transpose" }} perm3=${t3.size}")
        t3.take(3).forEach { println("  $it") }
        val fixed = OnnxGraphFixer.removeZeroAttentionBias(File(path).readBytes())
        println("zero-bias fix: " + if (fixed == null) "pattern not found" else "${File(path).length()} -> ${fixed.size} bytes")
        println("prepared graph: ${OnnxGraphFixer.prepared(File(path))}")
        return
    }
    a["tokens"]?.let { text ->
        val dir = File(a.getValue("model"))
        val tok = ClipTokenizer.load(File(dir, "tokenizer/vocab.json"), File(dir, "tokenizer/merges.txt"))
        println(tok.tokenize(text).joinToString(","))
        return
    }

    val env = OrtEnvironment.getEnvironment()
    println("ONNX Runtime ${env.version}")
    val model = SdModel(File(a.getValue("model")))
    require(model.isComplete) { "Model incomplete, missing: ${model.missingFiles()}" }
    val provider = FallbackSessionProvider(env, { listOf(CpuBackend()) }) { e ->
        println("  [${e.component.label}] ${e.backend}: ${e.message}")
    }
    val pipe = StableDiffusionPipeline(env, model, a["taesd"]?.let(::File), provider)
    val size = (a["size"] ?: "512").toInt()
    val out = File(a["out"] ?: "out.png")
    val listener = object : GenerationListener {
        override fun onStatus(text: String) = println("  ... $text")
        override fun onPreview(image: SdImage) {
            save(image, File(out.parentFile ?: File("."), out.nameWithoutExtension + "_preview.png"))
        }
    }
    val control = RunControl()

    val frames = a["frames"]?.toInt()
    if (frames != null) {
        val p = AnimationParams(
            promptA = a.getValue("prompt"), promptB = a["promptB"] ?: "", frames = frames,
            width = size, height = size, steps = (a["steps"] ?: "4").toInt(),
            guidance = (a["guidance"] ?: "8").toFloat(), seedA = (a["seed"] ?: "1").toLong(), seedB = (a["seed"] ?: "1").toLong() + 1,
            tinyDecoder = "tiny" in a,
        )
        pipe.animate(p, control, listener) { i, img ->
            val f = File(out.parentFile ?: File("."), "${out.nameWithoutExtension}_frame$i.png")
            save(img, f)
            println("frame $i -> $f  (${img.stats?.totalMs} ms)")
        }
        return
    }

    val p = GenerationParams(
        prompt = a.getValue("prompt"), negativePrompt = a["negative"] ?: "",
        width = size, height = (a["height"] ?: size.toString()).toInt(),
        steps = (a["steps"] ?: "4").toInt(), guidance = (a["guidance"] ?: "8").toFloat(),
        seed = (a["seed"] ?: "42").toLong(), tinyDecoder = "tiny" in a, previews = "previews" in a,
    )
    val img = pipe.generate(p, control, listener)
    save(img, out)
    println("Saved $out  type=${pipe.modelType}  stats=${img.stats}")
    provider.close()
}

private fun save(img: SdImage, f: File) {
    val bi = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
    bi.setRGB(0, 0, img.width, img.height, img.argb, 0, img.width)
    ImageIO.write(bi, "png", f)
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val k = args[i].removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
            m[k] = args[i + 1]; i += 2
        } else {
            m[k] = "true"; i++
        }
    }
    return m
}
