/**
 * 运行守卫（docs/tech/04 §3.2/§3.5–§3.7）：上下文阈值与压缩、计划门、熔断观察。
 * 阈值判定取 `max(估算, lastReportedInputTokens + 新增估算)`（§3.5）。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.CompactionInput
import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.agent.RenderItem
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunState
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.runtime.compaction.CompactionOutcome
import com.example.autop.agent.runtime.guard.BreakerEntry
import com.example.autop.agent.runtime.guard.BreakerVerdict
import com.example.autop.agent.runtime.tools.ToolRouter
import com.example.autop.core.common.AppError
import com.example.autop.core.config.AgentConfig
import com.example.autop.llm.Block
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolSchema
import com.example.autop.llm.Usage
import kotlin.math.max

/** 一次压缩的走向。 */
internal sealed interface CompactResult {
    /** 成功或本次失败可容忍：重新渲染后继续。 */
    data object Continue : CompactResult

    /** 连续失败超上限：Run 以 `agent.compaction_failed` 结束（docs/tech/04 §3.6）。 */
    data class End(
        val error: AppError,
    ) : CompactResult
}

/** 运行守卫（每次 Run 一个，携带运行期计数）。 */
internal class RunGuards(
    private val req: RunRequest,
    private val controller: RunController,
    private val renderer: HistoryRenderer,
    private val engines: RunEngines,
    private val router: ToolRouter,
) {
    private var contextWindow: Int = Int.MAX_VALUE
    private var lastReportedInputTokens = 0
    private var addedTokens = 0
    private var planBlocked = false
    private var estimator: TokenEstimator = TokenEstimator(req.config[AgentConfig.TOKENS])

    /** 绑定模型上下文窗口与图片 token 的 quirk 覆盖（docs/tech/04 §3.5）。 */
    fun bindProfile(profile: ModelProfile) {
        contextWindow = profile.contextWindow
        estimator = TokenEstimator(req.config[AgentConfig.TOKENS], profile.quirks[QUIRK_IMAGE_TOKENS]?.toIntOrNull())
    }

    /** 本轮新增内容的估算（阈值判定用）。 */
    fun noteAdded(blocks: List<Block>) {
        addedTokens += estimator.estimateMessage(Msg(Role.ASSISTANT, blocks))
    }

    /** 请求的 token 估算（预算记账用；RunEngines 不再持有 estimator）。 */
    fun estimate(
        system: List<SystemPart>,
        messages: List<Msg>,
        schemas: List<ToolSchema>,
    ): Int = estimator.estimate(system, messages, schemas)

    /** 服务端报告的输入 token；清零新增估算（docs/tech/04 §3.5）。 */
    fun noteReportedInput(usage: Usage?) {
        if (usage == null) return
        lastReportedInputTokens = usage.inputTokens
        addedTokens = 0
    }

    /** 是否超过压缩阈值 `contextWindow × agent.compaction.thresholdRatio`。 */
    fun overThreshold(
        system: List<SystemPart>,
        live: List<RenderItem>,
        schemas: List<ToolSchema>,
    ): Boolean {
        val estimate = estimator.estimate(system, live.map { it.msg }, schemas)
        val effective = max(estimate, lastReportedInputTokens + addedTokens)
        return effective > contextWindow * req.config[AgentConfig.COMPACTION].thresholdRatio
    }

    /** 最近一次压缩是否成功（Done）；压缩成功后 system 需要重新组装（WP4.1）。 */
    var lastCompactionSucceeded: Boolean = false
        private set

    /** 压缩一次：成功写 Baseline；失败按连续次数容忍或结束 Run（docs/tech/04 §3.6）。 */
    suspend fun compact(
        system: List<SystemPart>,
        initialContext: String?,
    ): CompactResult {
        controller.setState(RunState.COMPACTING)
        val events = controller.toolStore.eventsSinceLatestBaseline(req.threadId)
        val live = renderer.live(req.threadId, req.config)
        val input =
            CompactionInput(
                threadId = req.threadId,
                system = system,
                live = live,
                userMessagesNewestFirst = events.userMessagesNewestFirst(),
                plan = events.latestPlanItems(),
                runVars = req.vars,
                lastObservation = live.lastOrNull { it.observation != null },
                config = req.config,
                initialContext = initialContext,
                notes = engines.notes.current(req.threadId),
            )
        val outcome = engines.compaction.compact(input)
        lastCompactionSucceeded = outcome is CompactionOutcome.Done
        if (lastCompactionSucceeded) {
            // Done 时清零上报 token：压缩后阈值判断只看本地估算，直到下一次服务端上报（WP4.3）。
            lastReportedInputTokens = 0
            addedTokens = 0
        }
        return when (outcome) {
            is CompactionOutcome.Done -> CompactResult.Continue
            CompactionOutcome.Tolerated -> CompactResult.Continue
            is CompactionOutcome.Failed -> CompactResult.End(outcome.error)
        }
    }

    /** 计划门（docs/tech/04 §3.2）：仍有未完成项时拦下第一次自然结束，返回剩余项。 */
    suspend fun planGateItems(): List<PlanItem>? {
        val enabled = req.config[AgentConfig.PLAN].blockFinishOnce && !planBlocked
        val open = if (enabled) openItems() else emptyList()
        if (open.isEmpty()) return null
        planBlocked = true
        return open
    }

    /** 未完成（PENDING/IN_PROGRESS）的计划项。 */
    private suspend fun openItems(): List<PlanItem> =
        controller
            .toolStore
            .eventsSinceLatestBaseline(req.threadId)
            .latestPlanItems()
            .filter { it.status == PlanStatus.PENDING || it.status == PlanStatus.IN_PROGRESS }

    /** 熔断观察（docs/tech/04 §3.7）；[calls] 与 [outputs] 同序配对。 */
    fun breakerVerdict(
        calls: List<Block.ToolCall>,
        outputs: List<ToolOutput>,
    ): BreakerVerdict =
        engines.breaker.observe(
            calls.zip(outputs).map { (call, output) ->
                val tool = router.toolFor(call.name)
                BreakerEntry(
                    toolId = tool?.id ?: call.name,
                    ok = output.ok,
                    errorCode = output.error?.code,
                )
            },
        )

    private companion object {
        const val QUIRK_IMAGE_TOKENS = "imageTokens"
    }
}
