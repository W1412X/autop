/**
 * BERT WordPiece 分词（ADR-0028，端侧 embedding）。
 *
 * 行为与 HuggingFace tokenizers 的 `BertNormalizer(clean_text, handle_chinese_chars, 不小写、不去音标)` +
 * `BertPreTokenizer` + `WordPiece` 一致；bge-small-zh-v1.5 的 tokenizer.json 即此配置，测试用其输出做对照。
 *
 * 不做小写：分词器忠实于上游（英文大写字母会变成 [UNK]）。调用方需先规范化，
 * 记忆侧的 `MemoryKeywords.normalize` 已经小写化。
 */
package com.example.autop.llm.onnx

internal class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
) {
    private val unk: Int = requireToken(UNK)
    private val cls: Int = requireToken(CLS)
    private val sep: Int = requireToken(SEP)

    /** 填充 id（batch 内对齐用）。 */
    val padId: Int = vocab[PAD] ?: 0

    /** 编码为 `[CLS] … [SEP]`；词片总数（含特殊符号）不超过 [maxSeqLen]，超出部分截断。 */
    fun encode(
        text: String,
        maxSeqLen: Int,
    ): IntArray {
        val body = tokenize(text)
        val keep = (maxSeqLen - SPECIAL_TOKENS).coerceAtLeast(0)
        val count = minOf(body.size, keep)
        val out = IntArray(count + SPECIAL_TOKENS)
        out[0] = cls
        for (i in 0 until count) out[i + 1] = body[i]
        out[out.size - 1] = sep
        return out
    }

    /** 不含特殊符号的词片 id 序列。 */
    fun tokenize(text: String): IntArray {
        val ids = ArrayList<Int>()
        for (word in normalize(text).split(' ')) {
            if (word.isEmpty()) continue
            for (token in splitPunctuation(word)) ids += wordPiece(token)
        }
        return ids.toIntArray()
    }

    private fun requireToken(token: String): Int = requireNotNull(vocab[token]) { "词表缺少特殊符号 $token" }

    /** clean_text（去控制字符、空白归一）+ 中文字符两侧加空格。 */
    internal fun normalize(text: String): String {
        val out = StringBuilder(text.length + SPACE_PAD)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0 || cp == REPLACEMENT_CHAR || isControl(cp) -> Unit
                isWhitespace(cp) -> out.append(' ')
                isChineseChar(cp) -> out.append(' ').appendCodePoint(cp).append(' ')
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /** 标点各自成为一个词元，其余连续字符保持为一个词元。 */
    private fun splitPunctuation(word: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            if (isPunctuation(cp)) {
                if (current.isNotEmpty()) {
                    tokens += current.toString()
                    current.clear()
                }
                tokens += String(Character.toChars(cp))
            } else {
                current.appendCodePoint(cp)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** 贪心最长匹配；续接片段带 `##` 前缀；任一片段无法匹配则整个词记为 [UNK]。 */
    private fun wordPiece(token: String): List<Int> {
        val cps = token.codePoints().toArray()
        if (cps.size > MAX_INPUT_CHARS_PER_WORD) return listOf(unk)
        val out = ArrayList<Int>()
        var start = 0
        while (start < cps.size) {
            var end = cps.size
            var matched: Int? = null
            while (start < end) {
                val piece = String(cps, start, end - start)
                val id = vocab[if (start > 0) CONTINUATION_PREFIX + piece else piece]
                if (id != null) {
                    matched = id
                    break
                }
                end--
            }
            val id = matched ?: return listOf(unk)
            out += id
            start = end
        }
        return out
    }

    private companion object {
        /** 与上游 `_is_chinese_char` 一致的 CJK 区间（表意文字及扩展、兼容表意文字）。 */
        val cjkRanges: List<IntRange> =
            listOf(
                0x4E00..0x9FFF,
                0x3400..0x4DBF,
                0x20000..0x2A6DF,
                0x2A700..0x2B73F,
                0x2B740..0x2B81F,
                0x2B820..0x2CEAF,
                0xF900..0xFAFF,
                0x2F800..0x2FA1F,
            )

        /** ASCII 标点的码位区间（与上游 `is_ascii_punctuation` 一致，含 `$+<=>^`|~` 等符号）。 */
        val asciiPunctuationRanges: List<IntRange> = listOf(33..47, 58..64, 91..96, 123..126)

        const val PAD = "[PAD]"
        const val UNK = "[UNK]"
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val CONTINUATION_PREFIX = "##"
        const val MAX_INPUT_CHARS_PER_WORD = 100
        const val SPECIAL_TOKENS = 2
        const val REPLACEMENT_CHAR = 0xFFFD
        const val SPACE_PAD = 16

        /** 控制字符（Cc/Cf/Cs/Co/Cn）；制表、换行、回车属于空白而非控制字符。 */
        fun isControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            return when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE,
                Character.PRIVATE_USE, Character.UNASSIGNED,
                -> true
                else -> false
            }
        }

        fun isWhitespace(cp: Int): Boolean =
            cp == ' '.code ||
                cp == '\t'.code ||
                cp == '\n'.code ||
                cp == '\r'.code ||
                Character.getType(cp).toByte() == Character.SPACE_SEPARATOR

        /** CJK 统一表意文字及扩展（与上游 `_is_chinese_char` 的区间一致）。 */
        fun isChineseChar(cp: Int): Boolean = cjkRanges.any { cp in it }

        /** ASCII 标点区间（与上游一致，包括 `$+<=>^`|~` 等符号）与 Unicode 标点类别。 */
        fun isPunctuation(cp: Int): Boolean {
            if (asciiPunctuationRanges.any { cp in it }) return true
            return when (Character.getType(cp).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION,
                -> true
                else -> false
            }
        }
    }
}

/** 一个 batch 的模型输入（行优先，形状 [batch, seqLen]）。 */
internal class EncodedBatch(
    val batch: Int,
    val seqLen: Int,
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray,
)

/** 把变长编码对齐成矩形 batch：短序列用 [padId] 填充，attention_mask 对填充位置置 0。 */
internal fun padBatch(
    encodings: List<IntArray>,
    padId: Int,
): EncodedBatch {
    val batch = encodings.size
    val seqLen = encodings.maxOfOrNull { it.size } ?: 0
    val ids = LongArray(batch * seqLen) { padId.toLong() }
    val mask = LongArray(batch * seqLen)
    encodings.forEachIndexed { row, tokens ->
        tokens.forEachIndexed { col, id ->
            ids[row * seqLen + col] = id.toLong()
            mask[row * seqLen + col] = 1L
        }
    }
    return EncodedBatch(
        batch = batch,
        seqLen = seqLen,
        inputIds = ids,
        attentionMask = mask,
        tokenTypeIds = LongArray(batch * seqLen),
    )
}
