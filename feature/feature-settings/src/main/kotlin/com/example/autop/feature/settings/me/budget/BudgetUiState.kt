/**
 * 运行预算页状态（docs/tech/08 §4.5"运行预算"）：`agent.budget` 四项上限的
 * 当前值与编辑弹层状态。各维度正数=上限，0=不限（默认不限）。
 */
package com.example.autop.feature.settings.me.budget

import com.example.autop.core.config.AgentBudgetSection

/**
 * 预算维度（写路径 `agent.budget.<jsonKey>`）。
 *
 * @property jsonKey `agent.budget` 段内的字段名。
 * @property unitFactor 编辑单位 → 存储值的倍率（时长按分钟编辑，存储毫秒）。
 */
public enum class BudgetField(
    internal val jsonKey: String,
    internal val unitFactor: Long,
) {
    MAX_STEPS("maxSteps", 1L),
    MAX_TOTAL_TOKENS("maxTotalTokens", 1L),
    MAX_DURATION_MS("maxDurationMs", MINUTES_MS),
    MAX_SCREENSHOTS("maxScreenshots", 1L),
    ;

    /** 从配置段读取本维度的存储值。 */
    internal fun valueOf(section: AgentBudgetSection): Long =
        when (this) {
            MAX_STEPS -> section.maxSteps.toLong()
            MAX_TOTAL_TOKENS -> section.maxTotalTokens
            MAX_DURATION_MS -> section.maxDurationMs
            MAX_SCREENSHOTS -> section.maxScreenshots.toLong()
        }
}

/** 单项预算的当前值（存储值；≤0 = 不限）。 */
public data class BudgetItem(
    val field: BudgetField,
    val value: Long,
)

/**
 * 编辑弹层状态。
 *
 * @property unlimited 勾选"不限预算"（保存为 0）。
 * @property text 数值文本（编辑单位：时长为分钟，其余为原值）。
 * @property invalid 数值文本非法（非负整数以外）。
 */
public data class BudgetEditState(
    val field: BudgetField,
    val unlimited: Boolean,
    val text: String,
    val invalid: Boolean = false,
)

public data class BudgetUiState(
    /** 各维度当前值（[BudgetField] 声明顺序）。 */
    val items: List<BudgetItem> = BudgetField.entries.map { BudgetItem(it, 0L) },
    /** 编辑中的弹层；null = 未打开。 */
    val editing: BudgetEditState? = null,
)

/** 每分钟毫秒数（时长编辑单位换算）。 */
private const val MINUTES_MS: Long = 60_000L
