/**
 * 差量观测（docs/design/observation-modes-coordinate-actions §3.4、agent-harness-overhaul WP1.5）：
 * 只有 a11y/visual 有差量；用 [ScreenNodeMatcher] 按内容 + 位置容差配对，
 * 换页、换模式或变化比例超阈值时回退完整观测。
 */
package com.example.autop.device.core

import com.example.autop.device.Observation
import com.example.autop.device.ObservationMode
import com.example.autop.device.ObserveMode

/** 差量结果：observation 为输出给模型的观测（差量或完整），match 为配对结果（不可比较时为 null）。 */
public data class DiffOutcome(
    val observation: Observation,
    val match: ScreenNodeMatcher.Result?,
)

/** 统一 JSON 差量生成。 */
public object ScreenJsonDiffer {
    /**
     * 生成差量观测：无基线、换模式、换页或变化比例超过 [SerializerConfig.diffMaxRatio] 时返回完整观测。
     * 配对结果始终返回（不可比较时为 null），供屏幕变化结论消费。
     */
    public fun diff(
        prev: SerializedScreen?,
        cur: SerializedScreen,
        config: SerializerConfig,
    ): DiffOutcome {
        val full = cur.observation
        val base = prev
        if (base == null || !comparable(base, cur)) return DiffOutcome(full, null)
        val match = matchOf(base, cur, config)
        if (match.empty) {
            val text = config.renderPrompt(ScreenJsonWire.PROMPT_DIFF_NONE, emptyMap())
            return DiffOutcome(full.copy(mode = ObservationMode.DIFF, text = text), match)
        }
        val curNodes = cur.model.appFlatten().size
        val ratio = match.count.toDouble() / maxOf(1, curNodes)
        if (ratio > config.diffMaxRatio) return DiffOutcome(full, match)
        return DiffOutcome(
            full.copy(
                mode = ObservationMode.DIFF,
                text = ScreenJsonRenderer.renderDiff(cur.model.mode, match.added, match.changed, match.removed),
            ),
            match,
        )
    }

    /** 单次配对（差量与变化结论共用同一实现）。 */
    public fun matchOf(
        prev: SerializedScreen,
        cur: SerializedScreen,
        config: SerializerConfig,
    ): ScreenNodeMatcher.Result =
        ScreenNodeMatcher.match(
            prev.model.appFlatten(),
            cur.model.appFlatten(),
            config.positionTolerance,
            config.volatilePatterns,
        )

    /** 同一观测模式、同一页面且非 llm 时才生成差量。 */
    private fun comparable(
        base: SerializedScreen,
        cur: SerializedScreen,
    ): Boolean =
        cur.model.mode != ObserveMode.LLM &&
            base.model.mode == cur.model.mode &&
            base.observation.packageName == cur.observation.packageName &&
            base.observation.activity == cur.observation.activity
}
