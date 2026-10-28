package studio.npu.sd

import java.io.File

/**
 * Reads just the node list (name, op type, "perm" length) out of an .onnx file, without loading weights.
 * Used to hand the NPU plugin a list of nodes to leave on the CPU. Only practical for graph files whose
 * weights live in a separate .onnx_data file (like the UNet: model.onnx is ~2 MB).
 */
object OnnxGraphScanner {
    data class NodeInfo(val name: String, val opType: String, val permLength: Int)

    fun scan(file: File, maxBytes: Long = 64L shl 20): List<NodeInfo> {
        require(file.length() <= maxBytes) { "${file.name} is too big to scan (${file.length()} bytes)" }
        val buf = file.readBytes()
        val out = ArrayList<NodeInfo>()
        // ModelProto: field 7 = graph
        forEachField(buf, 0, buf.size) { field, wire, start, end ->
            if (field == 7 && wire == 2) {
                // GraphProto: field 1 = node (repeated)
                forEachField(buf, start, end) { f2, w2, s2, e2 ->
                    if (f2 == 1 && w2 == 2) out.add(readNode(buf, s2, e2))
                }
            }
        }
        return out
    }

    /** Names of Transpose nodes whose "perm" has [permLength] entries. */
    fun transposeNames(file: File, permLength: Int): List<String> =
        scan(file).filter { it.opType == "Transpose" && it.permLength == permLength && it.name.isNotEmpty() }.map { it.name }

    private fun readNode(buf: ByteArray, start: Int, end: Int): NodeInfo {
        var name = ""
        var op = ""
        var perm = -1
        forEachField(buf, start, end) { f, w, s, e ->
            when {
                f == 3 && w == 2 -> name = String(buf, s, e - s, Charsets.UTF_8)
                f == 4 && w == 2 -> op = String(buf, s, e - s, Charsets.UTF_8)
                f == 5 && w == 2 -> readPermLength(buf, s, e)?.let { perm = it }
            }
        }
        return NodeInfo(name, op, perm)
    }

    /** AttributeProto: field 1 = name, field 8 = ints (packed or not). Returns the ints count if name == "perm". */
    private fun readPermLength(buf: ByteArray, start: Int, end: Int): Int? {
        var name = ""
        var count = 0
        forEachField(buf, start, end) { f, w, s, e ->
            if (f == 1 && w == 2) name = String(buf, s, e - s, Charsets.UTF_8)
            if (f == 8) {
                if (w == 0) count++ // one unpacked varint
                else if (w == 2) { // packed varints
                    var p = s
                    while (p < e) {
                        while (buf[p].toInt() and 0x80 != 0) p++
                        p++
                        count++
                    }
                }
            }
        }
        return if (name == "perm") count else null
    }

    /**
     * Calls [onField] for every field in buf[start, end). For length-delimited fields (wire 2) the
     * callback gets the payload range; for others it gets an empty range at the value position.
     */
    private inline fun forEachField(buf: ByteArray, start: Int, end: Int, onField: (Int, Int, Int, Int) -> Unit) {
        var p = start
        while (p < end) {
            var key = 0L
            var shift = 0
            while (true) {
                val b = buf[p++].toInt()
                key = key or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val field = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            when (wire) {
                0 -> {
                    val s = p
                    while (buf[p].toInt() and 0x80 != 0) p++
                    p++
                    onField(field, wire, s, s)
                }
                1 -> { onField(field, wire, p, p); p += 8 }
                5 -> { onField(field, wire, p, p); p += 4 }
                2 -> {
                    var len = 0L
                    var sh = 0
                    while (true) {
                        val b = buf[p++].toInt()
                        len = len or ((b and 0x7F).toLong() shl sh)
                        if (b and 0x80 == 0) break
                        sh += 7
                    }
                    val s = p
                    val e = (p + len).toInt()
                    onField(field, wire, s, e)
                    p = e
                }
                else -> throw IllegalStateException("Unsupported protobuf wire type $wire at $p")
            }
        }
    }
}
