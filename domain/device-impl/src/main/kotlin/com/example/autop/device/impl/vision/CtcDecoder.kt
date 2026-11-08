/**
 * CTC 解码增强（docs/design/ocr-accuracy-uplift 阶段 A）：beam search＋词典重打分纠形近字；
 * 行级置信度＝贪心路径逐字概率均值（PaddleOCR score 口径，与解码结果无关，供级联选行）。
 * 输入为 rec 输出 [1,T,C] 平铺；概率行（softmax 后）与 logit 行均可（按行自适应识别）。纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

import kotlin.math.exp
import kotlin.math.ln

/** 词典词长范围（单字不成词，避免误伤）。 */
private const val WORD_MIN: Int = 2
private const val WORD_MAX: Int = 8

/** 每时间步保留的非 blank 候选类数（18385 类全展开太贵，截断到 top-K）。 */
private const val TOP_K: Int = 16

/** log(0) 替身。 */
private const val NEG_INF: Double = -1.0E9

/** 概率行判定的求和容差。 */
private const val PROB_SUM_TOL: Double = 0.02

/** 概率行单值上限容差（softmax 后允许略超 1 的浮点毛刺）。 */
private const val PROB_MAX_TOL: Float = 1.001f

/** 概率下限（防 ln(0)）。 */
private const val PROB_MIN: Double = 1.0E-10

/** CTC 解码产物：文本＋行级置信度（0..1，无发射行为 0）。 */
internal data class CtcResult(
    val text: String,
    val confidence: Double,
)

/** CTC 解码参数：beam 宽度＋词典重打分。 */
internal data class CtcDecodeOptions(
    val beamWidth: Int = CtcDecoder.DEFAULT_BEAM_WIDTH,
    val lexicon: Set<String> = emptySet(),
    val lmWeight: Double = CtcDecoder.DEFAULT_LM_WEIGHT,
)

/** CTC 解码器（贪心 / beam search＋词典重打分）。 */
internal object CtcDecoder {
    /** 默认 beam 宽度（≤1 走贪心）。 */
    const val DEFAULT_BEAM_WIDTH: Int = 5

    /** 默认词典重打分权重（每个命中词字符的对数加分）。 */
    const val DEFAULT_LM_WEIGHT: Double = 0.5

    /** 解码一行：beam≥2 走 beam search＋词典重打分，否则贪心；置信度恒为贪心路径分数。 */
    fun decode(
        logits: FloatArray,
        timesteps: Int,
        classes: Int,
        charset: List<String>,
        options: CtcDecodeOptions = CtcDecodeOptions(),
    ): CtcResult {
        val confidence = confidence(logits, timesteps, classes)
        val text =
            if (options.beamWidth <= 1 || charset.isEmpty() || timesteps <= 0) {
                PpOcrCodec.ctcDecode(logits, timesteps, classes, charset)
            } else {
                beamSearch(logits, timesteps, classes, charset, options)
            }
        return CtcResult(text, confidence)
    }

    /** 行级置信度（PaddleOCR score）：贪心路径上各 emit 字符概率的均值。 */
    fun confidence(
        logits: FloatArray,
        timesteps: Int,
        classes: Int,
    ): Double {
        val row = DoubleArray(classes)
        var last = -1
        var sum = 0.0
        var count = 0
        for (t in 0 until timesteps) {
            rowLogProbs(logits, t * classes, classes, row)
            val best = argmax(row)
            if (best != 0 && best != last) {
                sum += exp(row[best])
                count++
            }
            last = best
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** 词典装载：去空白后按行入集（命中加成只认 [WORD_MIN] 以上词）。 */
    fun lexiconOf(text: String?): Set<String> =
        text
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.length >= WORD_MIN }
            ?.toSet()
            ?: emptySet()

    /** 长行切窗识别的文本拼接：相邻窗按最长公共前后缀（≤ [SEAM_MAX] 字符）去重后拼接。 */
    fun stitch(parts: List<String>): String {
        val out = StringBuilder()
        for (part in parts) {
            if (part.isNotEmpty()) {
                val seam = if (out.isEmpty()) 0 else seamOf(out, part)
                out.append(part, seam, part.length)
            }
        }
        return out.toString()
    }

    /** beam 条目：前缀的 blank / 非 blank 两条 log 概率＋末位 token（CTC 重复合并判定）。 */
    private class Beam(
        var pBlank: Double,
        var pNonBlank: Double,
        var last: String,
    )

    /** 相邻窗文本的最大重叠字符数（超出不成词，防误并）。 */
    private const val SEAM_MAX: Int = 6

    /** [part] 与已拼接文本尾部的最大公共前后缀长度（重叠去重）。 */
    private fun seamOf(
        out: CharSequence,
        part: String,
    ): Int {
        var k = minOf(SEAM_MAX, out.length, part.length)
        while (k > 0 && !endsWith(out, part, k)) k--
        return k
    }

    /** [out] 尾部 k 字符是否等于 [part] 头部 k 字符。 */
    private fun endsWith(
        out: CharSequence,
        part: String,
        k: Int,
    ): Boolean {
        for (j in 0 until k) {
            if (out[out.length - k + j] != part[j]) return false
        }
        return true
    }

    /** CTC prefix beam search（log 域）＋终选词典重打分。 */
    private fun beamSearch(
        logits: FloatArray,
        timesteps: Int,
        classes: Int,
        charset: List<String>,
        options: CtcDecodeOptions,
    ): String {
        val row = DoubleArray(classes)
        val top = ArrayList<Pair<Int, Double>>(TOP_K)
        var beams = mapOf("" to Beam(0.0, NEG_INF, ""))
        for (t in 0 until timesteps) {
            rowLogProbs(logits, t * classes, classes, row)
            topK(row, top)
            val tokens = top.mapNotNull { (c, lp) -> charset.getOrNull(c - 1)?.takeIf { it.isNotEmpty() }?.to(lp) }
            val next = HashMap<String, Beam>()
            for ((prefix, beam) in beams) {
                val total = logAdd(beam.pBlank, beam.pNonBlank)
                extend(next, prefix, beam.last, isBlank = true, total + row[0])
                extendTokens(next, prefix, beam, total, tokens)
            }
            beams = prune(next, options.beamWidth)
        }
        return bestFinal(beams, options)
    }

    /** 单前缀的 token 扩展（CTC 重复合并 / 续字两条分支）。 */
    private fun extendTokens(
        next: MutableMap<String, Beam>,
        prefix: String,
        beam: Beam,
        total: Double,
        tokens: List<Pair<String, Double>>,
    ) {
        for ((token, lp) in tokens) {
            if (beam.last == token) {
                extend(next, prefix, token, isBlank = false, beam.pNonBlank + lp)
                extend(next, prefix + token, token, isBlank = false, beam.pBlank + lp)
            } else {
                extend(next, prefix + token, token, isBlank = false, total + lp)
            }
        }
    }

    /** 终选：声学 log 概率＋词典加成取最大。 */
    private fun bestFinal(
        beams: Map<String, Beam>,
        options: CtcDecodeOptions,
    ): String {
        var best = ""
        var bestScore = NEG_INF
        for ((prefix, beam) in beams) {
            val score = logAdd(beam.pBlank, beam.pNonBlank) + options.lmWeight * lexBoost(prefix, options.lexicon)
            if (score > bestScore) {
                bestScore = score
                best = prefix
            }
        }
        return best
    }

    /** 合并一条扩展到 [next]（同前缀的 blank / 非 blank 概率分开累计）。 */
    private fun extend(
        next: MutableMap<String, Beam>,
        prefix: String,
        last: String,
        isBlank: Boolean,
        logProb: Double,
    ) {
        val beam = next.getOrPut(prefix) { Beam(NEG_INF, NEG_INF, last) }
        if (isBlank) {
            beam.pBlank = logAdd(beam.pBlank, logProb)
        } else {
            beam.pNonBlank = logAdd(beam.pNonBlank, logProb)
        }
    }

    /** 截断到 beam 宽度（按前缀总 log 概率降序）。 */
    private fun prune(
        beams: Map<String, Beam>,
        beamWidth: Int,
    ): Map<String, Beam> =
        beams.entries
            .sortedByDescending { logAdd(it.value.pBlank, it.value.pNonBlank) }
            .take(beamWidth)
            .associate { it.key to it.value }

    /** 词典加成：最长匹配贪心覆盖的词字符数（[WORD_MIN] 以下不成词）。 */
    private fun lexBoost(
        text: String,
        lexicon: Set<String>,
    ): Double {
        if (lexicon.isEmpty()) return 0.0
        var boost = 0.0
        var i = 0
        while (i < text.length) {
            val matched = longestMatch(text, i, lexicon)
            if (matched > 0) boost += matched
            i += if (matched > 0) matched else 1
        }
        return boost
    }

    /** [from] 起的最长词典词长（无命中为 0）。 */
    private fun longestMatch(
        text: String,
        from: Int,
        lexicon: Set<String>,
    ): Int {
        val maxLen = minOf(WORD_MAX, text.length - from)
        for (len in maxLen downTo WORD_MIN) {
            if (text.substring(from, from + len) in lexicon) return len
        }
        return 0
    }
}

/** 单时间步 log 概率：概率行直接取 ln，logit 行走 log-softmax（行内平移不影响 argmax）。 */
private fun rowLogProbs(
    logits: FloatArray,
    from: Int,
    classes: Int,
    out: DoubleArray,
) {
    if (isProbRow(logits, from, classes)) {
        for (i in 0 until classes) out[i] = ln(maxOf(logits[from + i].toDouble(), PROB_MIN))
        return
    }
    var max = logits[from].toDouble()
    for (i in 1 until classes) max = maxOf(max, logits[from + i].toDouble())
    var sum = 0.0
    for (i in 0 until classes) sum += exp(logits[from + i].toDouble() - max)
    val norm = max + ln(sum)
    for (i in 0 until classes) out[i] = logits[from + i].toDouble() - norm
}

/** 行内各值均落在 [0,1] 且求和≈1 视为概率行（rec 输出自带 softmax 时）。 */
private fun isProbRow(
    logits: FloatArray,
    from: Int,
    classes: Int,
): Boolean {
    var sum = 0.0
    for (i in 0 until classes) {
        val v = logits[from + i]
        if (v < 0f || v > PROB_MAX_TOL) return false
        sum += v
    }
    return sum >= 1.0 - PROB_SUM_TOL && sum <= 1.0 + PROB_SUM_TOL
}

/** 行内最大值索引（平铺行首相对索引）。 */
private fun argmax(row: DoubleArray): Int {
    var best = 0
    for (i in 1 until row.size) {
        if (row[i] > row[best]) best = i
    }
    return best
}

/** 单步非 blank top-K（按 log 概率降序插入维护，避免每步全排序）。 */
private fun topK(
    row: DoubleArray,
    out: MutableList<Pair<Int, Double>>,
) {
    out.clear()
    for (c in 1 until row.size) {
        val lp = row[c]
        if (out.size == TOP_K) {
            if (lp <= out[TOP_K - 1].second) continue
            out.removeAt(TOP_K - 1)
        }
        var i = out.size
        out.add(c to lp)
        while (i > 0 && out[i - 1].second < lp) {
            out[i] = out[i - 1]
            i--
        }
        out[i] = c to lp
    }
}

/** log 域加法（log-sum-exp）。 */
private fun logAdd(
    a: Double,
    b: Double,
): Double =
    when {
        a <= NEG_INF -> b
        b <= NEG_INF -> a
        a > b -> a + ln(1.0 + exp(b - a))
        else -> b + ln(1.0 + exp(a - b))
    }
