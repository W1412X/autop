/**
 * 版本前后对比（docs/tech/08 §4.4）：行级 LCS diff，产出 [DiffLine]，
 * 删行用 error 底 α0.15、增行用 primary 底 α0.15（颜色由 Screen 依 [DiffLine.kind] 施加）。
 */
package com.example.autop.feature.memory.node

/**
 * 行级 LCS diff；超过 [maxLines] 的文本截断后再比（防止超大文本卡 UI）。
 */
public fun diffLines(
    before: String,
    after: String,
    maxLines: Int = MAX_DIFF_LINES,
): List<DiffLine> {
    val old = before.lines().take(maxLines)
    val new = after.lines().take(maxLines)
    val lcs = lcsTable(old, new)
    val out = mutableListOf<DiffLine>()
    var i = old.size
    var j = new.size
    while (i > 0 || j > 0) {
        when {
            i > 0 && j > 0 && old[i - 1] == new[j - 1] -> {
                out += DiffLine(old[i - 1], DiffLine.Kind.SAME)
                i--
                j--
            }
            j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j]) -> {
                out += DiffLine(new[j - 1], DiffLine.Kind.ADDED)
                j--
            }
            else -> {
                out += DiffLine(old[i - 1], DiffLine.Kind.REMOVED)
                i--
            }
        }
    }
    return out.asReversed()
}

private fun lcsTable(
    old: List<String>,
    new: List<String>,
): Array<IntArray> {
    val table = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in 1..old.size) {
        for (j in 1..new.size) {
            table[i][j] =
                if (old[i - 1] == new[j - 1]) {
                    table[i - 1][j - 1] + 1
                } else {
                    maxOf(table[i - 1][j], table[i][j - 1])
                }
        }
    }
    return table
}

private const val MAX_DIFF_LINES: Int = 300
