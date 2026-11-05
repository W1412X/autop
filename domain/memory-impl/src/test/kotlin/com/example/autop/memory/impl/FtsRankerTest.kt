/**
 * FtsRanker 测试（docs/tech/06 §2.6/§7）：`matchinfo 'pcnalx'` 布局解析与 BM25 近似排序。
 */
package com.example.autop.memory.impl

import com.example.autop.memory.impl.db.FtsMatchRow
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BM25 近似排序行为测试。 */
public class FtsRankerTest {
    @Test
    public fun `score returns zero for malformed info`() {
        val row = row(info = ByteArray(4))

        assertThat(FtsRanker.score(row)).isEqualTo(0.0)
    }

    @Test
    public fun `score returns zero when column count mismatches`() {
        val info = matchInfo(phraseCount = 1, columnCount = 2, totalRows = 10, cells = listOf(cell()))

        assertThat(FtsRanker.score(row(info = info))).isEqualTo(0.0)
    }

    @Test
    public fun `score grows with term frequency`() {
        val low = matchInfo(1, COLUMN_COUNT, 100, listOf(cell(termFreq = 1), cell(), cell()))
        val high = matchInfo(1, COLUMN_COUNT, 100, listOf(cell(termFreq = 5), cell(), cell()))

        assertThat(FtsRanker.score(row(info = high))).isGreaterThan(FtsRanker.score(row(info = low)))
    }

    @Test
    public fun `title column out-ranks body column for same frequency`() {
        val inTitle = matchInfo(1, COLUMN_COUNT, 100, listOf(cell(), cell(termFreq = 1), cell()))
        val inBody = matchInfo(1, COLUMN_COUNT, 100, listOf(cell(), cell(), cell(termFreq = 1)))

        assertThat(FtsRanker.score(row(info = inTitle))).isGreaterThan(FtsRanker.score(row(info = inBody)))
    }

    @Test
    public fun `rank sorts by score descending with stable path tie-break`() {
        val strong =
            row(path = "/b", info = matchInfo(1, COLUMN_COUNT, 100, listOf(cell(termFreq = 5), cell(), cell())))
        val weak =
            row(path = "/a", info = matchInfo(1, COLUMN_COUNT, 100, listOf(cell(termFreq = 1), cell(), cell())))

        val ranked = FtsRanker.rank(listOf(weak, strong))

        assertThat(ranked.map { it.path }).containsExactly("/b", "/a").inOrder()
    }

    /** 一个 phrase 一个列的 x 三元组：(本行频次、全表频次、含词行数)。 */
    private fun cell(
        termFreq: Int = 0,
        totalFreq: Int = 0,
        docFreq: Int = 0,
    ): IntArray = intArrayOf(termFreq, totalFreq, docFreq)

    /** 按 SQLite `pcnalx` 真实布局构造 matchinfo 字节（小端）。 */
    private fun matchInfo(
        phraseCount: Int,
        columnCount: Int,
        totalRows: Int,
        cells: List<IntArray>,
    ): ByteArray {
        val avgLengths = IntArray(columnCount) { AVG_TOKENS }
        val docLengths = IntArray(columnCount) { DOC_TOKENS }
        val buf =
            ByteBuffer
                .allocate(Int.SIZE_BYTES * (HEADER + 2 * columnCount + cells.size * X_TRIPLE))
                .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(phraseCount).putInt(columnCount).putInt(totalRows)
        avgLengths.forEach { buf.putInt(it) }
        docLengths.forEach { buf.putInt(it) }
        cells.forEach { triple -> triple.forEach { buf.putInt(it) } }
        return buf.array()
    }

    private fun row(
        path: String = "/p",
        info: ByteArray,
    ): FtsMatchRow = FtsMatchRow(path = path, titleLen = DOC_TOKENS, bodyLen = DOC_TOKENS, info = info)

    private companion object {
        const val COLUMN_COUNT: Int = 3
        const val HEADER: Int = 3
        const val X_TRIPLE: Int = 3
        const val AVG_TOKENS: Int = 10
        const val DOC_TOKENS: Int = 10
    }
}
