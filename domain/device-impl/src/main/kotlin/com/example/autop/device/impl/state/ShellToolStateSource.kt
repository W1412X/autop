/**
 * 运行状态来源 `state.shell`（ADR-0040 §2.3、shell-exec 设计 §2.3）：仅在完全模式下报告 `run_shell` 是否可用，
 * 不可用时说明原因（总闸关闭或无线注入未连接），让智能体能告诉用户该怎么做。
 */
package com.example.autop.device.impl.state

import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.core.common.RunMode
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.FeaturesConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class ShellToolStateSource
    @Inject
    constructor(
        private val shellInput: ShellInput,
        private val prompts: PromptRepository,
    ) : RuntimeStateSource {
        override val id: String = ID

        override suspend fun entries(req: StateRequest): List<StateEntry> {
            if (RunMode.fromId(req.vars[RUN_MODE_VAR]) != RunMode.FULL) return emptyList()
            val promptId =
                when {
                    !req.config[FeaturesConfig.FEATURES].shellExec -> PROMPT_DISABLED
                    shellInput.state.value != ShellState.Connected -> PROMPT_DISCONNECTED
                    else -> PROMPT_AVAILABLE
                }
            val text =
                prompts
                    .render(promptId, emptyMap())
                    .getOrNull()
                    ?.text
                    .orEmpty()
            return listOf(StateEntry(KEY, text, volatile = true))
        }

        internal companion object {
            const val ID: String = "state.shell"
            const val KEY: String = "shell"
            private const val RUN_MODE_VAR = "runMode"
            private const val PROMPT_AVAILABLE = "agent.state.shell.available"
            private const val PROMPT_DISABLED = "agent.state.shell.disabled"
            private const val PROMPT_DISCONNECTED = "agent.state.shell.disconnected"
        }
    }
