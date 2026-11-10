/**
 * 运行状态来源 `state.observe`（agent-harness-overhaul WP3.5.3）：
 * 可用观测方式、不可用方式及原因、坐标基准。
 */
package com.example.autop.device.impl.state

import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.ObserveMode
import com.example.autop.device.impl.ObserveModeResolver
import javax.inject.Inject
import javax.inject.Singleton

/** 观测能力状态来源。 */
@Singleton
public class ObserveStateSource
    @Inject
    constructor(
        private val modes: ObserveModeResolver,
        private val prompts: PromptRepository,
    ) : RuntimeStateSource {
        override val id: String = ID

        override suspend fun entries(req: StateRequest): List<StateEntry> {
            val cfg = req.config[DeviceConfig.OBSERVE]
            val effective = modes.effective(cfg)
            val auto = modes.autoCandidates(cfg)
            val entries = mutableListOf<StateEntry>()
            entries +=
                StateEntry(
                    KEY_MODES,
                    render(
                        PROMPT_MODES,
                        mapOf(
                            VAR_MODES to effective.joinToString(" > ") { it.id },
                            VAR_AUTO to auto.joinToString(" > ") { it.id },
                            VAR_NONE to flag(effective.isEmpty()),
                        ),
                    ),
                )
            for (mode in ObserveMode.entries) {
                val enabled = cfg.modes.of(mode.id).enabled
                val reason = if (enabled) modes.unavailableReason(mode, cfg) else null
                if (reason != null) {
                    entries +=
                        StateEntry(
                            KEY_UNAVAILABLE_PREFIX + mode.id,
                            render(PROMPT_UNAVAILABLE, mapOf(VAR_MODE to mode.id, VAR_REASON to reason)),
                        )
                }
            }
            val contentArea = req.config[DeviceConfig.SERIALIZER].hideSystemDecoration
            entries +=
                StateEntry(
                    KEY_COORD,
                    render(PROMPT_COORD, mapOf(VAR_CONTENT to flag(contentArea), VAR_FULL to flag(!contentArea))),
                )
            return entries
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
            const val ID: String = "state.observe"

            const val KEY_MODES = "modes"
            const val KEY_UNAVAILABLE_PREFIX = "unavailable."
            const val KEY_COORD = "coord"

            private const val PROMPT_MODES = "agent.state.observe.modes"
            private const val PROMPT_UNAVAILABLE = "agent.state.observe.unavailable"
            private const val PROMPT_COORD = "agent.state.observe.coord"

            private const val VAR_MODES = "modes"
            private const val VAR_AUTO = "auto"
            private const val VAR_NONE = "none"
            private const val VAR_MODE = "mode"
            private const val VAR_REASON = "reason"
            private const val VAR_CONTENT = "content"
            private const val VAR_FULL = "full"
        }
    }
