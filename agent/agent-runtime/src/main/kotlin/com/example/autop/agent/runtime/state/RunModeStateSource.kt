/**
 * 运行状态来源 `state.run_mode`（ADR-0040 §2.3）：本次 Run 的运行模式档位。
 * 档位来自 Run 变量 `runMode`（稳定 id）；缺失或非法时按标准模式，与发送侧的默认值一致。
 */
package com.example.autop.agent.runtime.state

import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.core.common.RunMode
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository

/** 运行模式档位状态来源。 */
internal class RunModeStateSource(
    private val prompts: PromptRepository,
) : RuntimeStateSource {
    override val id: String = ID

    override suspend fun entries(req: StateRequest): List<StateEntry> {
        val tier = RunMode.fromId(req.vars[VAR_RUN_MODE]) ?: RunMode.STANDARD
        val text =
            prompts
                .render("$PROMPT_PREFIX${tier.id}", emptyMap())
                .getOrNull()
                ?.text
                .orEmpty()
        return listOf(StateEntry(KEY, text))
    }

    internal companion object {
        const val ID: String = "state.run_mode"
        const val KEY: String = "mode"

        /** Run 变量键：运行模式三档的稳定 id（与入队变量同名）。 */
        const val VAR_RUN_MODE: String = "runMode"
        private const val PROMPT_PREFIX = "agent.state.run_mode."
    }
}
