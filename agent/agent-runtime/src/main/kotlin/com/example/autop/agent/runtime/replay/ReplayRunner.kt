/**
 * 任务回放执行（docs/tech/04 §3.12，R1/C1）：按定位器逐步回放，每步执行前
 * 等待前置状态出现（`task.replay.preStateWaitMs` 内按 `pollMs` 轮询
 * [ReplayStateProbe]，`StateSimilarity` 达阈值即通过）；对不上或执行失败时
 * 在该步停下，把进度交回主循环（模板 `agent.replay.diverged`）。
 */
package com.example.autop.agent.runtime.replay

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.Divergence
import com.example.autop.agent.EventPayload
import com.example.autop.agent.MessageOrigin
import com.example.autop.agent.ReplayOutcome
import com.example.autop.agent.ReplayPlan
import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayTargetResolver
import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.llm.Block
import com.example.autop.llm.StopReason
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** 回放一步的执行结果（工具链跳过 schema 校验后的真实结果）。 */
internal data class ReplayStepResult(
    val output: ToolOutput,
    val durationMs: Long,
)

/**
 * 回放执行器（每次 Run 一个）。
 *
 * @param plan 回放计划（脚本 + 执行参数）。
 * @param probe 当前屏幕采样端口（device-impl）。
 * @param config Run 配置快照（采样用，WP2.8）。
 * @param resolver 目标重解析端口（§5.7）；null 时按记录坐标执行。
 * @param toolNameOf 工具 id → 模型可见工具名（写回放合成消息用）。
 * @param emit 写持久化事件（AssistantMessage(origin=REPLAY) 与 ToolResultEvent）。
 * @param executeStep 执行一步（跳过 schema 校验，锁/策略/审计照常）。
 */
internal class ReplayRunner(
    private val plan: ReplayPlan,
    private val probe: ReplayStateProbe,
    private val config: ConfigSnapshot,
    private val resolver: ReplayTargetResolver?,
    private val toolNameOf: (String) -> String?,
    private val emit: suspend (EventPayload) -> Unit,
    private val executeStep: suspend (callId: String, action: ReplayableAction) -> ReplayStepResult,
) {
    /**
     * 逐步回放。全部完成返回 `diverged = null`；在第 `stepIndex` 步
     * （0 基）对不上或执行失败时返回该步的 [Divergence]。
     */
    @Suppress("ReturnCount") // 逐步回放逐级早退（状态不匹配/未知工具/执行失败）是自然结构
    suspend fun run(): ReplayOutcome {
        val total = plan.script.steps.size
        plan.script.steps.forEachIndexed { index, step ->
            val best = waitForPreState(step.pre)
            if (!best.passed) {
                return ReplayOutcome(index, total, Divergence(index, REASON_STATE_MISMATCH, best.score))
            }
            val callId = callIdOf(index)
            val name =
                toolNameOf(step.toolId)
                    ?: return ReplayOutcome(index, total, Divergence(index, REASON_UNKNOWN_TOOL, null))
            val resolution = resolver?.resolve(step.toolId, step.replayArgs)?.getOrNull()
            val action = if (resolution == null) step else step.copy(replayArgs = resolution.args)
            emit(assistantStep(callId, name, action))
            val result = executeStep(callId, action)
            emit(toolResult(callId, action, result))
            if (!result.output.ok) {
                val reason = result.output.error?.code ?: REASON_EXECUTE_FAILED
                return ReplayOutcome(index, total, Divergence(index, reason, null))
            }
            if (resolution?.diverged == true) {
                return ReplayOutcome(index, total, Divergence(index, REASON_TARGET_NOT_FOUND, null))
            }
        }
        return ReplayOutcome(total, total, null)
    }

    /** 在 `preStateWaitMs` 内按 `pollMs` 轮询采样，返回是否通过与最高相似度。 */
    private suspend fun waitForPreState(expected: ReplayStateSample): Match {
        val cfg = plan.config
        var waited = 0L
        var best = 0.0
        while (true) {
            val sample = probe.sample(expected.observeMode, config).getOrNull()
            if (sample != null) {
                val s = StateSimilarity.score(expected, sample, cfg.keysWeight, cfg.appWeight)
                best = maxOf(best, s)
                if (s >= cfg.threshold) return Match(true, best)
            }
            if (waited >= cfg.preStateWaitMs) return Match(false, best)
            delay(cfg.pollMs)
            waited += cfg.pollMs
        }
    }

    /** 回放合成的助手消息：工具调用以普通形式出现在历史里，模型能看到做过什么。 */
    private fun assistantStep(
        callId: String,
        name: String,
        step: ReplayableAction,
    ): AssistantMessage =
        AssistantMessage(
            blocks = listOf(Block.ToolCall(id = callId, name = name, argsJson = displayArgs(step.replayArgs))),
            usage = null,
            stopReason = StopReason.TOOL_USE,
            modelProfileId = REPLAY_MODEL_ID,
            origin = MessageOrigin.REPLAY,
        )

    private fun toolResult(
        callId: String,
        step: ReplayableAction,
        result: ReplayStepResult,
    ): ToolResultEvent {
        val output = result.output
        return ToolResultEvent(
            callId = callId,
            toolId = step.toolId,
            ok = output.ok,
            blocks = output.content,
            data = output.data,
            errorCode = output.error?.code,
            observation = output.observation,
            durationMs = result.durationMs,
            fullBlocks = output.fullContent,
            replay = output.replay,
            screenDelta = output.screenDelta,
        )
    }

    /** 内部参数（`_` 前缀，如 `_locator`）不进历史展示。 */
    private fun displayArgs(args: JsonObject): String =
        JsonObject(args.filterKeys { !it.startsWith(INTERNAL_ARG_PREFIX) }).toString()

    private fun callIdOf(index: Int): String = "$CALL_ID_PREFIX$index"

    /** 等待结果。 */
    private data class Match(
        val passed: Boolean,
        val score: Double,
    )

    internal companion object {
        const val REASON_STATE_MISMATCH = "state_mismatch"
        const val REASON_EXECUTE_FAILED = "execute_failed"
        const val REASON_UNKNOWN_TOOL = "unknown_tool"
        const val REASON_TARGET_NOT_FOUND = "target_not_found"

        /** 剩余步骤摘要（diverged 提示的 `remaining` 变量）：每行工具名 + 参数摘要。 */
        fun remainingText(
            plan: ReplayPlan,
            fromIndex: Int,
        ): String =
            plan.script.steps.drop(fromIndex).joinToString("\n") { step ->
                val args = displayArgsStatic(step.replayArgs)
                "${step.toolId} $args"
            }

        private fun displayArgsStatic(args: JsonObject): String =
            JsonObject(args.filterKeys { !it.startsWith(INTERNAL_ARG_PREFIX) }).toString()

        private const val INTERNAL_ARG_PREFIX = "_"
        private const val CALL_ID_PREFIX = "rp_"
        private const val REPLAY_MODEL_ID = "replay"
    }
}
