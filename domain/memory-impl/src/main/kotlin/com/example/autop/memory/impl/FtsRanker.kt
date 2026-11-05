/**
 * FTS4 排序（docs/tech/06 §2.6）：FTS4 无内置 bm25，用 `matchinfo(memory_fts, 'pcnalx')`
 * 自行计算 BM25 近似（k1=1.2、b=0.75，标题列权重高于正文列）。
 */
package com.example.autop.memory.impl

import com.example.autop.memory.impl.db.FtsMatchRow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BM25 近似排序（纯函数，JVM 可测）。 */
public object FtsRanker {
    /** `matchinfo(..., 'pcnalx')` 输出中列的个数：path、titleTokens、bodyTokens。 */
    public const val COLUMN_COUNT: Int = 3

    private const val BM25_K1: Double = 1.2
    private const val BM25_B: Double = 0.75
    private const val IDF_SMOOTHING: Double = 0.5

    /** matchinfo 头部整数个数（p、c、n）。 */
    private const val HEADER_INTS: Int = 3

    /** a、l 两段各占 columnCount 个整数（列平均词数、本行各列词数）。 */
    private const val LENGTH_INTS_PER_COLUMN: Int = 2

    /** x 段每 phrase 每列的整数个数（本行频次、全表频次、含词行数）。 */
    private const val X_INTS_PER_COLUMN: Int = 3

    /** 列权重：path、titleTokens、bodyTokens。 */
    private val COLUMN_WEIGHTS: DoubleArray = doubleArrayOf(1.5, 2.0, 1.0)

    /** 按 BM25 近似给一条命中打分；解析失败返回 0.0。 */
    public fun score(row: FtsMatchRow): Double {
        val buf = ByteBuffer.wrap(row.info).order(ByteOrder.LITTLE_ENDIAN)
        if (row.info.size < Int.SIZE_BYTES * HEADER_INTS) return 0.0
        val phraseCount = buf.int
        val columnCount = buf.int
        val totalRows = buf.int
        val expectedInts =
            HEADER_INTS + LENGTH_INTS_PER_COLUMN * columnCount +
                phraseCount * columnCount * X_INTS_PER_COLUMN
        val valid = columnCount == COLUMN_COUNT && totalRows > 0 && row.info.size >= Int.SIZE_BYTES * expectedInts
        return if (!valid) 0.0 else compute(buf, phraseCount, columnCount, totalRows)
    }

    /**
     * 解析 `pcnalx` 布局：`a`（列平均词数）、`l`（本行各列词数）后，
     * x 段按 (本行频次、全表频次、含词行数) 排列（SQLite matchinfo 文档）。
     */
    private fun compute(
        buf: ByteBuffer,
        phraseCount: Int,
        columnCount: Int,
        totalRows: Int,
    ): Double {
        val avgLengths = DoubleArray(columnCount) { buf.int.toDouble().coerceAtLeast(1.0) }
        val docLengths = IntArray(columnCount) { buf.int }
        var score = 0.0
        repeat(phraseCount) {
            for (col in 0 until columnCount) {
                val termFreq = buf.int
                buf.int // 全表频次：BM25 近似用不到，只消费保持游标对齐
                val docFreq = buf.int
                if (termFreq <= 0) continue
                val idf = Math.log(1.0 + (totalRows - docFreq + IDF_SMOOTHING) / (docFreq + IDF_SMOOTHING))
                val norm = BM25_K1 * (1.0 - BM25_B + BM25_B * docLengths[col] / avgLengths[col])
                val weight = COLUMN_WEIGHTS.getOrElse(col) { 1.0 }
                score += weight * idf * (termFreq * (BM25_K1 + 1.0)) / (termFreq + norm)
            }
        }
        return score
    }

    /** 排序：分数降序，同分按 path 稳定。 */
    public fun rank(rows: List<FtsMatchRow>): List<FtsMatchRow> =
        rows
            .map { it to score(it) }
            .sortedWith(compareByDescending<Pair<FtsMatchRow, Double>> { it.second }.thenBy { it.first.path })
            .map { it.first }
}
