package studio.npu.sd

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Removes a useless "+ 0" from the attention layers of SD 1.5 UNets exported from diffusers' baddbmm attention:
 *
 *     scores = Add(Mul(MatMul(q, k), scale), Mul(ConstantOfShape(0), 0))  ->  Softmax
 *
 * Once ONNX Runtime fixes the image size, the zero branch becomes a real tensor of zeros, e.g. [8, 4096, 4096]
 * floats = 512 MB per layer at 512x512. That pushed the saved NPU compile over protobuf's 2 GB limit and
 * wastes memory and time on every chip. The fixed graph feeds the scaled scores straight into the Softmax and
 * drops the Add, the zero branch and the shape math that only fed it; the result is mathematically identical.
 *
 * Only the small graph file is rewritten. Its weights stay in the shared model.onnx_data, so the fixed copy
 * must live in the same folder.
 */
object OnnxGraphFixer {
    /** Part of the fixed file name, so a changed fix is applied again. */
    private const val VERSION = 2

    /** The graph to run for [source]: a fixed copy beside it if the pattern is present, else [source] itself. */
    fun prepared(source: File): File {
        val fixed = File(source.parentFile, source.nameWithoutExtension + "_studio$VERSION.onnx")
        if (fixed.isFile && fixed.lastModified() >= source.lastModified()) return fixed
        if (!source.isFile || source.length() > (64L shl 20)) return source
        return runCatching {
            val out = removeZeroAttentionBias(source.readBytes()) ?: return source
            val tmp = File(fixed.parentFile, fixed.name + ".tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(fixed)) { tmp.delete(); return source }
            fixed
        }.getOrDefault(source)
    }

    /** Returns the fixed model bytes, or null if the model has no such pattern. */
    fun removeZeroAttentionBias(buf: ByteArray): ByteArray? {
        val top = fields(buf, 0, buf.size)
        val graphField = top.lastOrNull { it.field == 7 && it.wire == 2 } ?: return null
        val graphFields = fields(buf, graphField.payloadStart, graphField.end)
        val nodes = graphFields.filter { it.field == 1 && it.wire == 2 }.map { it to readNode(buf, it) }

        val producer = HashMap<String, Node>()
        val consumers = HashMap<String, Int>()
        for ((_, n) in nodes) {
            n.outputs.forEach { producer[it] = n }
            n.inputs.forEach { consumers[it] = (consumers[it] ?: 0) + 1 }
        }
        val graphOutputs = graphFields.filter { it.field == 12 && it.wire == 2 }
            .mapNotNull { f -> fields(buf, f.payloadStart, f.end).firstOrNull { it.field == 1 }?.let { buf.str(it) } }.toSet()

        // Consumers of the Add read the other input directly; the Add and its zero branch go away.
        val drop = HashSet<Node>()
        val rename = HashMap<String, String>()
        for ((_, add) in nodes) {
            if (add.op != "Add" || add.inputs.size != 2 || add.outputs.size != 1 || add.outputs[0] in graphOutputs) continue
            for (zeroSide in 0..1) {
                val mul = producer[add.inputs[zeroSide]] ?: continue
                if (mul.op != "Mul" || mul in drop || consumers[mul.outputs[0]] != 1) continue
                val cos = mul.inputs.mapNotNull { producer[it] }.firstOrNull { it.op == "ConstantOfShape" } ?: continue
                if (!cos.zeroValue || consumers[cos.outputs[0]] != 1 || cos.outputs[0] in graphOutputs) continue
                drop += add
                drop += mul
                rename[add.outputs[0]] = add.inputs[1 - zeroSide]
                break
            }
        }
        if (rename.isEmpty()) return null

        // Remove what only fed the zero branch (its shape computation), so no node is left with unused outputs.
        while (true) {
            val used = HashSet<String>(graphOutputs)
            for ((_, n) in nodes) if (n !in drop) n.inputs.forEach { used += rename[it] ?: it }
            val dead = nodes.map { it.second }.filter { n -> n !in drop && n.outputs.none { it.isNotEmpty() && it in used } }
            if (dead.isEmpty()) break
            drop += dead
        }

        val graph = ByteArrayOutputStream(graphField.end - graphField.payloadStart)
        var nodeIndex = 0
        for (f in graphFields) {
            if (f.field == 1 && f.wire == 2) {
                val n = nodes[nodeIndex++].second
                when {
                    n in drop -> {}
                    n.inputs.any { it in rename } -> writeLengthDelimited(graph, 1, renameInputs(buf, f, rename))
                    else -> graph.write(buf, f.start, f.end - f.start)
                }
            } else {
                graph.write(buf, f.start, f.end - f.start)
            }
        }
        val model = ByteArrayOutputStream(buf.size)
        for (f in top) {
            if (f === graphField) writeLengthDelimited(model, 7, graph.toByteArray())
            else model.write(buf, f.start, f.end - f.start)
        }
        return model.toByteArray()
    }

    private class Field(val field: Int, val wire: Int, val start: Int, val payloadStart: Int, val end: Int)

    private class Node(val name: String, val op: String, val inputs: List<String>, val outputs: List<String>, val zeroValue: Boolean)

    private fun ByteArray.str(f: Field) = String(this, f.payloadStart, f.end - f.payloadStart, Charsets.UTF_8)

    private fun readNode(buf: ByteArray, nf: Field): Node {
        val ins = ArrayList<String>()
        val outs = ArrayList<String>()
        var name = ""
        var op = ""
        var hasValue = false
        var zero = true
        for (f in fields(buf, nf.payloadStart, nf.end)) {
            when {
                f.field == 1 && f.wire == 2 -> ins += buf.str(f)
                f.field == 2 && f.wire == 2 -> outs += buf.str(f)
                f.field == 3 && f.wire == 2 -> name = buf.str(f)
                f.field == 4 && f.wire == 2 -> op = buf.str(f)
                f.field == 5 && f.wire == 2 -> {
                    // AttributeProto: 1 = name, 5 = t (TensorProto). ConstantOfShape's "value" (default 0).
                    val attr = fields(buf, f.payloadStart, f.end)
                    if (attr.firstOrNull { it.field == 1 }?.let { buf.str(it) } == "value") {
                        hasValue = true
                        val t = attr.firstOrNull { it.field == 5 && it.wire == 2 }
                        zero = t != null && tensorIsZero(buf, t)
                    }
                }
            }
        }
        return Node(name, op, ins, outs, zeroValue = !hasValue || zero)
    }

    /** TensorProto with every float_data (4) / raw_data (9) byte zero, and nothing stored externally. */
    private fun tensorIsZero(buf: ByteArray, t: Field): Boolean {
        var sawData = false
        for (f in fields(buf, t.payloadStart, t.end)) {
            when (f.field) {
                4, 9 -> {
                    sawData = true
                    if (f.wire == 2) { for (i in f.payloadStart until f.end) if (buf[i].toInt() != 0) return false }
                    else if (f.wire == 5) { for (i in f.payloadStart until f.payloadStart + 4) if (buf[i].toInt() != 0) return false }
                    else return false
                }
                5, 6, 7, 10, 11, 13 -> return false // other data kinds or external data: don't guess
            }
        }
        return sawData
    }

    /** The NodeProto with its inputs (field 1) renamed; every other field is copied unchanged. */
    private fun renameInputs(buf: ByteArray, nf: Field, rename: Map<String, String>): ByteArray {
        val o = ByteArrayOutputStream(nf.end - nf.payloadStart)
        for (f in fields(buf, nf.payloadStart, nf.end)) {
            val to = if (f.field == 1 && f.wire == 2) rename[buf.str(f)] else null
            if (to != null) writeLengthDelimited(o, 1, to.toByteArray(Charsets.UTF_8))
            else o.write(buf, f.start, f.end - f.start)
        }
        return o.toByteArray()
    }

    private fun writeLengthDelimited(o: ByteArrayOutputStream, field: Int, payload: ByteArray) {
        writeVarint(o, (field.toLong() shl 3) or 2L)
        writeVarint(o, payload.size.toLong())
        o.write(payload)
    }

    private fun writeVarint(o: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            o.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        o.write(v.toInt())
    }

    private fun readVarint(buf: ByteArray, pos: IntArray): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos[0]++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun fields(buf: ByteArray, start: Int, end: Int): List<Field> {
        val out = ArrayList<Field>()
        val p = intArrayOf(start)
        while (p[0] < end) {
            val fieldStart = p[0]
            val key = readVarint(buf, p)
            val field = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            when (wire) {
                0 -> { val s = p[0]; readVarint(buf, p); out += Field(field, wire, fieldStart, s, p[0]) }
                1 -> { val s = p[0]; p[0] += 8; out += Field(field, wire, fieldStart, s, p[0]) }
                5 -> { val s = p[0]; p[0] += 4; out += Field(field, wire, fieldStart, s, p[0]) }
                2 -> {
                    val len = readVarint(buf, p).toInt()
                    val s = p[0]
                    p[0] = s + len
                    out += Field(field, wire, fieldStart, s, p[0])
                }
                else -> throw IllegalStateException("Unsupported protobuf wire type $wire at $fieldStart")
            }
        }
        return out
    }
}
