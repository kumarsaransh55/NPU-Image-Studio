package studio.npu.sd

import java.io.File

/**
 * CLIP byte-level BPE tokenizer (same algorithm as HuggingFace CLIPTokenizer) used by Stable Diffusion 1.x.
 * Turns a prompt into exactly 77 token ids: <start> tokens... <end> <pad>...
 */
class ClipTokenizer(
    private val vocab: Map<String, Int>,
    merges: List<String>,
) {
    companion object {
        const val BOS = 49406
        const val EOS = 49407
        const val MAX_LENGTH = 77

        fun load(vocabFile: File, mergesFile: File): ClipTokenizer {
            val vocabJson = MiniJson.parseObject(vocabFile.readText(Charsets.UTF_8))
            val vocab = HashMap<String, Int>(vocabJson.size * 2)
            for ((k, v) in vocabJson) vocab[k] = (v as Number).toInt()
            // Same slice as HF: skip the "#version" header, keep 49152 - 256 - 2 merges.
            val lines = mergesFile.readLines(Charsets.UTF_8)
            val merges = lines.drop(1).filter { it.isNotBlank() }.take(49152 - 256 - 2)
            return ClipTokenizer(vocab, merges)
        }

        private fun bytesToUnicode(): Array<String> {
            val bs = ArrayList<Int>()
            bs.addAll('!'.code..'~'.code)
            bs.addAll('¡'.code..'¬'.code)
            bs.addAll('®'.code..'ÿ'.code)
            val cs = ArrayList<Int>(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val table = Array(256) { "" }
            for (i in bs.indices) table[bs[i]] = String(Character.toChars(cs[i]))
            return table
        }
    }

    private val byteEncoder = bytesToUnicode()
    private val bpeRanks = HashMap<String, Int>(merges.size * 2).apply {
        merges.forEachIndexed { i, line -> put(line, i) } // key is "first second"
    }
    private val cache = HashMap<String, List<String>>()
    private val pattern = Regex(
        """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
        RegexOption.IGNORE_CASE,
    )

    /** Token ids for the text, without start/end tokens. */
    fun encode(text: String): List<Int> {
        val clean = text.replace(Regex("\\s+"), " ").trim().lowercase()
        val ids = ArrayList<Int>()
        for (m in pattern.findAll(clean)) {
            val token = m.value
            val sb = StringBuilder()
            for (b in token.toByteArray(Charsets.UTF_8)) sb.append(byteEncoder[b.toInt() and 0xFF])
            for (piece in bpe(sb.toString())) {
                ids.add(vocab[piece] ?: EOS)
            }
        }
        return ids
    }

    /** 77 ids: BOS + up to 75 prompt tokens + EOS, padded with [padId]. */
    fun tokenize(text: String, padId: Int = EOS): IntArray {
        val body = encode(text).take(MAX_LENGTH - 2)
        val out = IntArray(MAX_LENGTH) { padId }
        out[0] = BOS
        body.forEachIndexed { i, id -> out[i + 1] = id }
        out[body.size + 1] = EOS
        return out
    }

    /** Number of prompt tokens (to warn the user when a prompt is longer than 75 tokens). */
    fun countTokens(text: String): Int = encode(text).size

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        // Split into single code points; last symbol carries the end-of-word marker.
        val chars = ArrayList<String>()
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            chars.add(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        if (chars.isEmpty()) return emptyList()
        chars[chars.size - 1] = chars[chars.size - 1] + "</w>"
        var word: List<String> = chars

        while (word.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestFirst = ""
            var bestSecond = ""
            for (j in 0 until word.size - 1) {
                val r = bpeRanks["${word[j]} ${word[j + 1]}"] ?: continue
                if (r < bestRank) {
                    bestRank = r
                    bestFirst = word[j]
                    bestSecond = word[j + 1]
                }
            }
            if (bestRank == Int.MAX_VALUE) break
            val merged = ArrayList<String>(word.size)
            var k = 0
            while (k < word.size) {
                if (k < word.size - 1 && word[k] == bestFirst && word[k + 1] == bestSecond) {
                    merged.add(bestFirst + bestSecond)
                    k += 2
                } else {
                    merged.add(word[k])
                    k++
                }
            }
            word = merged
        }
        if (cache.size > 5000) cache.clear()
        cache[token] = word
        return word
    }
}
