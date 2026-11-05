/**
 * 版本前后对比的一行差异（docs/tech/08 §4.4）。
 */
package com.example.autop.feature.memory.node

/** 一行差异。 */
public data class DiffLine(
    val text: String,
    val kind: Kind,
) {
    /** 行类型。 */
    public enum class Kind {
        /** 未变。 */
        SAME,

        /** 新增（after 有 before 无）。 */
        ADDED,

        /** 删除（before 有 after 无）。 */
        REMOVED,
    }
}
