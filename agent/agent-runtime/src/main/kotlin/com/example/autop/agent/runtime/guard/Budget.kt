/**
 * 运行预算（docs/tech/04 §3.7）：工具回合数、token 总量、时长、截图数，
 * 任一触顶进入 finalRound（模型只能输出总结文本）。
 * 各维度配置值 ≤ 0 表示该维度不限（默认不限）。
 */
package com.example.autop.agent.runtime.guard

import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Clock
import com.example.autop.core.config.AgentBudgetSection
import com.example.autop.llm.Usage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 预算计数器（每次 Run 一个）。
 *
 * 时长维度从构造时刻起算（[startMillis] 默认取构造时的当前时钟值）；
 * 不得依赖默认 0：`Clock.nowMillis()` 是 epoch 毫秒，`now - 0` 会恒超任何上限
 * （N170：曾导致时长维度开跑即触顶）。
 */
internal class Budget(
    private val cfg: AgentBudgetSection,
    private val clock: Clock,
    private val startMillis: Long = clock.nowMillis(),
) {
    private var totalTokens = 0L
    private var toolRounds = 0
    private var screenshots = 0

    /** 优先用服务端 usage，没有时用估算值（docs/tech/04 §3.2）。 */
    fun recordUsage(
        usage: Usage?,
        estimatedTokens: Int,
    ) {
        totalTokens += usage?.let { (it.inputTokens + it.outputTokens).toLong() } ?: estimatedTokens.toLong()
    }

    /** 一轮工具执行记一个 step；`data.screenshot=true` 的结果计入截图数。 */
    fun recordToolRound(outputs: List<ToolOutput>) {
        toolRounds += 1
        screenshots += outputs.count(::isScreenshot)
    }

    /** 当前用量只读快照（运行状态上报用，WP3）。 */
    fun usage(): BudgetUsage =
        BudgetUsage(
            steps = toolRounds,
            tokens = totalTokens,
            durationMs = clock.nowMillis() - startMillis,
            screenshots = screenshots,
        )

    /** 任一维度触顶。 */
    fun exhausted(): Boolean = reason() != null

    /** 触顶的维度（[DIM_STEPS]/[DIM_TOKENS]/[DIM_DURATION]/[DIM_SCREENSHOTS]），未触顶返回 null。配置值 ≤ 0 的维度不限。 */
    fun reason(): String? =
        when {
            cfg.maxSteps > 0 && toolRounds >= cfg.maxSteps -> DIM_STEPS
            cfg.maxTotalTokens > 0 && totalTokens >= cfg.maxTotalTokens -> DIM_TOKENS
            cfg.maxDurationMs > 0 && clock.nowMillis() - startMillis >= cfg.maxDurationMs -> DIM_DURATION
            cfg.maxScreenshots > 0 && screenshots >= cfg.maxScreenshots -> DIM_SCREENSHOTS
            else -> null
        }

    /** 截图工具在结构化结果里自报 `screenshot=true`（docs/tech/04 §3.7）。 */
    private fun isScreenshot(output: ToolOutput): Boolean =
        (output.data as? JsonObject)?.get("screenshot")?.jsonPrimitive?.booleanOrNull ?: false

    internal companion object {
        const val DIM_STEPS: String = "steps"
        const val DIM_TOKENS: String = "tokens"
        const val DIM_DURATION: String = "duration"
        const val DIM_SCREENSHOTS: String = "screenshots"
    }
}

/** 预算用量快照（WP3）。 */
internal data class BudgetUsage(
    val steps: Int,
    val tokens: Long,
    val durationMs: Long,
    val screenshots: Int,
)
