/**
 * 运行状态来源 `state.tools`（agent-harness-overhaul WP3.5.6）：本次不可用的工具及原因。
 * Run 内构造，持有 [RouterBuilder.build] 过滤出的隐藏列表。
 */
package com.example.autop.agent.runtime.state

import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.agent.runtime.tools.HiddenTool
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository

/** 工具可见性状态来源。 */
internal class ToolVisibilityStateSource(
    private val hidden: List<HiddenTool>,
    private val prompts: PromptRepository,
) : RuntimeStateSource {
    override val id: String = ID

    override suspend fun entries(req: StateRequest): List<StateEntry> {
        if (hidden.isEmpty()) return emptyList()
        val items =
            hidden.joinToString("、") { tool ->
                "${tool.name}（${render(PROMPT_REASON_PREFIX + tool.reason)}）"
            }
        return listOf(StateEntry(KEY_HIDDEN, render(PROMPT_HIDDEN, mapOf(VAR_ITEMS to items))))
    }

    private fun render(
        id: String,
        vars: Map<String, String> = emptyMap(),
    ): String =
        prompts
            .render(id, vars)
            .getOrNull()
            ?.text
            .orEmpty()

    internal companion object {
        const val ID: String = "state.tools"

        const val KEY_HIDDEN = "hidden"

        private const val PROMPT_HIDDEN = "agent.state.tools.hidden"
        private const val PROMPT_REASON_PREFIX = "agent.state.tools.reason."
        private const val VAR_ITEMS = "items"
    }
}
