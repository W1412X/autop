/**
 * 交接压缩（docs/tech/04 §3.6，参考 codex compact.rs）：调轻量模型写交接摘要，
 * 请求超长时从 live 开头删旧重试。基线顺序（WP4.2）：initialContextMsg → 用户消息 → 摘要 → 笔记 → 最后观测。
 */
package com.example.autop.agent.runtime.compaction

import com.example.autop.agent.CompactionInput
import com.example.autop.agent.CompactionResult
import com.example.autop.agent.Compactor
import com.example.autop.agent.RenderItem
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.common.map
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.complete

/** 交接压缩策略。 */
internal class HandoffCompactor(
    private val llm: LlmGateway,
    private val prompts: PromptRepository,
) : Compactor {
    override val id: String = "compaction.handoff"

    private val assembler = BaselineAssembler(prompts)

    override suspend fun compact(input: CompactionInput): Outcome<CompactionResult> {
        val provider =
            llm.forDefault().getOrNull()
                ?: return Outcome.Err(
                    AppError(code = LlmErrorCodes.NOT_CONFIGURED, message = "no model for compaction"),
                )
        val summary = summarizeWithRetry(provider, input)
        return summary.map { build(input, it) }
    }

    /** 请求超长时从 live 开头删旧重试，直到成功或不可再删（docs/tech/04 §3.6）。 */
    private suspend fun summarizeWithRetry(
        provider: LlmProvider,
        input: CompactionInput,
    ): Outcome<String> {
        var live = input.live
        while (true) {
            val summary = summarize(provider, input, live)
            val retryable =
                summary is Outcome.Err &&
                    summary.error.code == LlmErrorCodes.CONTEXT_EXCEEDED &&
                    live.size > MIN_LIVE
            if (!retryable) return summary
            live = dropOldest(live)
        }
    }

    /** 请求 = 当前 system + live + 一条 USER 交接说明，tools=[]。 */
    private suspend fun summarize(
        provider: LlmProvider,
        input: CompactionInput,
        live: List<RenderItem>,
    ): Outcome<String> {
        val handoff =
            prompts
                .render(ID_HANDOFF, emptyMap())
                .getOrNull()
                ?.text
                ?: return Outcome.Err(
                    AppError(code = LlmErrorCodes.BAD_RESPONSE, message = "missing template $ID_HANDOFF"),
                )
        val request =
            LlmRequest(
                system = input.system,
                messages = live.map { it.msg } + Msg(Role.USER, listOf(Block.Text(handoff))),
                tools = emptyList(),
                toolChoice = ToolChoice.NONE,
                maxOutputTokens = provider.profile.maxOutputTokens,
            )
        return when (val completed = provider.complete(request)) {
            is Outcome.Ok -> Outcome.Ok(textOf(completed.value.message))
            is Outcome.Err -> completed
        }
    }

    private fun build(
        input: CompactionInput,
        summary: String,
    ): CompactionResult {
        val (from, to) = assembler.rangeOf(input.live)
        val wrapper =
            prompts
                .render(ID_WRAPPER, wrapperVars(input, from, to, summary))
                .getOrNull()
                ?.text
                ?: ""
        val middle = Msg(Role.USER, listOf(Block.Text(wrapper)))
        // 基线顺序（WP4.2）：initialContextMsg → userMessages → 摘要包装 → notesMsg → lastObservationMsg。
        return CompactionResult(
            baselineMessages =
                listOfNotNull(assembler.initialContextMsg(input)) +
                    assembler.userMessages(input) +
                    middle +
                    listOfNotNull(assembler.notesMsg(input), assembler.lastObservationMsg(input)),
            summaryText = summary,
            coveredFromSeq = from,
            coveredToSeq = to,
        )
    }

    /** optionalVars（plan/trigger）为空时不传。 */
    private fun wrapperVars(
        input: CompactionInput,
        from: Long,
        to: Long,
        summary: String,
    ): Map<String, String> {
        val vars = mutableMapOf("fromSeq" to from.toString(), "toSeq" to to.toString(), "summary" to summary)
        val plan = assembler.planText(input.plan)
        if (plan.isNotBlank()) vars["plan"] = plan
        val trigger = assembler.triggerText(input.runVars)
        if (trigger.isNotBlank()) vars["trigger"] = trigger
        return vars
    }

    /** 删最旧一条；ASSISTANT 带 ToolCall 时连同其后的 TOOL 一起删。 */
    private fun dropOldest(live: List<RenderItem>): List<RenderItem> {
        val first = live.firstOrNull() ?: return live
        val hasCall = first.msg.blocks.any { it is Block.ToolCall }
        var end = 1
        if (hasCall) {
            while (end < live.size && live[end].msg.role == Role.TOOL) end++
        }
        return live.drop(end)
    }

    private fun textOf(msg: Msg): String = msg.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

    private companion object {
        const val ID_HANDOFF = "agent.compaction.handoff"
        const val ID_WRAPPER = "agent.compaction.summary_wrapper"

        /** live 只剩 2 条时停止删旧（docs/tech/04 §3.6）。 */
        const val MIN_LIVE = 2
    }
}
