package studio.npu.x200t.models

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import studio.npu.sd.ModelType
import studio.npu.sd.SdModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where models live on the phone and how they get there.
 *
 * Folder: Android/data/studio.npu.x200t/files/models/<model-id>/  (you can also copy a model
 * folder there from a PC over USB - the app picks it up automatically).
 */
class ModelStore(private val context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun dirFor(id: String) = File(root, id)

    fun model(id: String) = SdModel(dirFor(id))

    /**
     * Every complete model folder (catalog downloads, imports and manual copies). For catalog models every
     * file must also have its full size - a folder still being copied over USB is not "ready" yet.
     */
    fun installed(): List<SdModel> =
        (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.map { SdModel(it) }
            .filter { it.isComplete && hasFullSizes(it) }
            .sortedBy { it.name.lowercase() }

    private fun hasFullSizes(m: SdModel): Boolean {
        val cat = ModelCatalog.byId(m.dir.name) ?: return true
        return cat.files.all { f -> File(m.dir, f.path).length() == f.size }
    }

    /** Bytes already on disk for a catalog model (including partial downloads). */
    fun downloadedBytes(m: CatalogModel): Long {
        val dir = dirFor(m.id)
        return m.files.sumOf { f ->
            val done = File(dir, f.path)
            val part = File(dir, f.path + ".part")
            when {
                done.isFile -> done.length()
                part.isFile -> part.length()
                else -> 0L
            }
        }
    }

    fun freeBytes(): Long = runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(-1)

    fun delete(id: String) {
        dirFor(id).deleteRecursively()
    }

    /** Download (or resume) every file of [m]. [onProgress] gets (bytesDone, bytesTotal). */
    suspend fun download(m: CatalogModel, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val dir = dirFor(m.id).apply { mkdirs() }
        val total = m.totalBytes
        var before = 0L
        for (f in m.files) {
            currentCoroutineContext().ensureActive()
            val dest = File(dir, f.path)
            dest.parentFile?.mkdirs()
            if (dest.isFile && (dest.length() == f.size || f.size == 0L)) {
                before += dest.length()
                onProgress(before, total)
                continue
            }
            val base = before
            downloadFile(m.url(f), dest, f.size) { done -> onProgress(base + done, total) }
            before += dest.length()
        }
        SdModel.writeManifest(dir, m.name, m.type, m.defaultSteps, m.defaultGuidance)
        onProgress(total, total)
    }

    private suspend fun downloadFile(url: String, dest: File, expected: Long, progress: (Long) -> Unit) {
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                fetch(url, part, expected, progress)
                break
            } catch (e: IOException) {
                attempt++
                if (attempt >= 8) throw IOException("Download failed after $attempt tries: ${e.message}", e)
                kotlinx.coroutines.delay(2000L * attempt)
            }
        }
        if (expected > 0 && part.length() != expected) {
            throw IOException("${dest.name}: size ${part.length()} but expected $expected")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not finish ${dest.name}")
    }

    /** One HTTP attempt with resume (Range) and manual redirect handling (Hugging Face uses relative redirects). */
    private suspend fun fetch(url: String, part: File, expected: Long, progress: (Long) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        if (expected > 0 && have == expected) return
        if (expected > 0 && have > expected) { part.delete(); have = 0 }
        var current = URL(url)
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "NPUImageStudio/1.0 (Android)")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                    current = URL(current, loc)
                    if (++redirects > 10) throw IOException("Too many redirects")
                    continue
                }
                if (code == 416) { // range past the end -> already complete
                    return
                }
                if (code != 200 && code != 206) throw IOException("HTTP $code for ${current.host}")
                val append = code == 206
                if (!append) have = 0
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = have
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > (4 shl 20)) {
                                progress(done)
                                lastReport = done
                            }
                        }
                        progress(done)
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Copy a model folder the user picked (diffusers ONNX layout) into app storage.
     * Missing tokenizer files are filled in from the copy bundled in the app (same for all SD 1.x).
     */
    suspend fun importFolder(tree: Uri, name: String, type: ModelType?, onProgress: (String) -> Unit): SdModel =
        withContext(Dispatchers.IO) {
            val src = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Can't open that folder")
            val id = "custom-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "model" }
            val dir = dirFor(id).apply { mkdirs() }
            val needed = listOf(
                "text_encoder/model.onnx", "unet/model.onnx", "unet/model.onnx_data", "vae_decoder/model.onnx",
                "tokenizer/vocab.json", "tokenizer/merges.txt",
            )
            for (rel in needed) {
                currentCoroutineContext().ensureActive()
                val doc = find(src, rel)
                val out = File(dir, rel).apply { parentFile?.mkdirs() }
                if (doc == null) {
                    if (rel.startsWith("tokenizer/")) {
                        context.assets.open(rel).use { i -> out.outputStream().use { i.copyTo(it) } }
                    } else if (rel != "unet/model.onnx_data") {
                        throw IOException("The folder has no $rel")
                    }
                    continue
                }
                onProgress("Copying $rel (${doc.length() / (1 shl 20)} MB)")
                context.contentResolver.openInputStream(doc.uri)!!.use { i ->
                    out.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
                }
            }
            val (steps, guidance) = when (type) {
                ModelType.LCM -> 4 to 8f
                ModelType.RECTIFIED_FLOW -> 1 to 0f
                else -> 20 to 7f
            }
            SdModel.writeManifest(dir, name, type, steps, guidance)
            SdModel(dir)
        }

    private fun find(root: DocumentFile, rel: String): DocumentFile? {
        var cur: DocumentFile = root
        for (part in rel.split('/')) cur = cur.findFile(part) ?: return null
        return if (cur.isFile) cur else null
    }
}
