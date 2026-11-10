/**
 * 运行状态来源 `state.input`（agent-harness-overhaul WP3.5.2）：
 * 无障碍服务、shell 注入通道、节点动作屏蔽名单与触摸盾。
 */
package com.example.autop.device.impl.state

import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.UiConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.NodeActionBlockStore
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import javax.inject.Inject
import javax.inject.Singleton

/** 输入通道状态来源。 */
@Singleton
public class InputStateSource
    @Inject
    constructor(
        private val accessibility: AccessibilityStatus,
        private val shell: ShellInput,
        private val deviceStates: DeviceStateProvider,
        private val blocks: NodeActionBlockStore,
        private val prompts: PromptRepository,
    ) : RuntimeStateSource {
        override val id: String = ID

        override suspend fun entries(req: StateRequest): List<StateEntry> {
            val entries = mutableListOf<StateEntry>()
            val connected = accessibility.connected.value
            entries +=
                StateEntry(
                    KEY_ACCESSIBILITY,
                    render(PROMPT_ACCESSIBILITY, mapOf(VAR_ON to flag(connected), VAR_OFF to flag(!connected))),
                )
            entries += StateEntry(KEY_SHELL, render(PROMPT_SHELL, shellVars()))
            val foreground = deviceStates.current().foregroundPackage
            if (foreground != null && blocks.blocked().any { it.packageName == foreground }) {
                entries += StateEntry(KEY_NODE_ACTION_BLOCKED, render(PROMPT_BLOCKED, mapOf(VAR_APP to foreground)))
            }
            val shieldOn = req.config[DeviceConfig.TOUCH].shieldEnabled && req.config[UiConfig.OVERLAY].enabled
            entries +=
                StateEntry(
                    KEY_SHIELD,
                    render(PROMPT_SHIELD, mapOf(VAR_ON to flag(shieldOn), VAR_OFF to flag(!shieldOn))),
                )
            return entries
        }

        private fun shellVars(): Map<String, String> {
            val connected = shell.state.value == ShellState.Connected
            val connecting = shell.state.value == ShellState.Connecting
            val offPaired = shell.state.value == ShellState.Disconnected && shell.paired.value
            val offUnpaired = shell.state.value == ShellState.Disconnected && !shell.paired.value
            return mapOf(
                VAR_CONNECTED to flag(connected),
                VAR_CONNECTING to flag(connecting),
                VAR_OFF_PAIRED to flag(offPaired),
                VAR_OFF_UNPAIRED to flag(offUnpaired),
            )
        }

        private fun flag(on: Boolean): String = if (on) "1" else ""

        private fun render(
            id: String,
            vars: Map<String, String>,
        ): String =
            prompts
                .render(id, vars)
                .getOrNull()
                ?.text
                .orEmpty()

        internal companion object {
            const val ID: String = "state.input"

            const val KEY_ACCESSIBILITY = "accessibility"
            const val KEY_SHELL = "shell"
            const val KEY_NODE_ACTION_BLOCKED = "node_action_blocked"
            const val KEY_SHIELD = "shield"

            private const val PROMPT_ACCESSIBILITY = "agent.state.input.accessibility"
            private const val PROMPT_SHELL = "agent.state.input.shell"
            private const val PROMPT_BLOCKED = "agent.state.input.node_action_blocked"
            private const val PROMPT_SHIELD = "agent.state.input.shield"

            private const val VAR_ON = "on"
            private const val VAR_OFF = "off"
            private const val VAR_CONNECTED = "connected"
            private const val VAR_CONNECTING = "connecting"
            private const val VAR_OFF_PAIRED = "offPaired"
            private const val VAR_OFF_UNPAIRED = "offUnpaired"
            private const val VAR_APP = "app"
        }
    }
