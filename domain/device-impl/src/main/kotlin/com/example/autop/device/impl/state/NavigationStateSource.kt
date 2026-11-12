/**
 * 运行状态来源 `state.navigation`（intent-tool §9.3）：报告已安装的导航应用与用户的导航偏好，
 * 让模型知道 intent 的 navigate 会用哪个地图。非 volatile：每轮按当前安装状态重算。
 */
package com.example.autop.device.impl.state

import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.NavigationApp
import com.example.autop.device.NavigationApps
import com.example.autop.device.core.NavigationChooser
import javax.inject.Inject
import javax.inject.Singleton

/** 导航应用状态来源。 */
@Singleton
public class NavigationStateSource
    @Inject
    constructor(
        private val navigationApps: NavigationApps,
        private val prompts: PromptRepository,
    ) : RuntimeStateSource {
        override val id: String = ID

        override suspend fun entries(req: StateRequest): List<StateEntry> {
            val installed = navigationApps.installed()
            val preferred = req.config[DeviceConfig.INTENT].preferredMap
            val vars =
                buildMap<String, String> {
                    putInstalled(installed)
                    putPreferred(preferred, installed)
                }
            return listOf(StateEntry(KEY_MAPS, render(vars)))
        }

        private fun MutableMap<String, String>.putInstalled(installed: List<NavigationApp>) {
            if (installed.isEmpty()) {
                put(VAR_NONE, ONE)
            } else {
                put(VAR_APPS, installed.joinToString("、") { it.label })
            }
        }

        /** 偏好：auto（含无法识别的值）、ask，或具体应用 id（未安装时标注，并说明将自动选择）。 */
        private fun MutableMap<String, String>.putPreferred(
            preferred: String,
            installed: List<NavigationApp>,
        ) {
            when (preferred) {
                NavigationChooser.PREFERENCE_ASK -> put(VAR_PREFERRED_ASK, ONE)
                NavigationChooser.PREFERENCE_AUTO -> put(VAR_PREFERRED_AUTO, ONE)
                else -> {
                    val known = navigationApps.known().firstOrNull { it.id == preferred }
                    if (known == null) {
                        put(VAR_PREFERRED_AUTO, ONE)
                    } else {
                        put(VAR_PREFERRED_APP, known.label)
                        if (installed.none { it.id == preferred }) put(VAR_PREFERRED_MISSING, ONE)
                    }
                }
            }
        }

        private fun render(vars: Map<String, String>): String =
            prompts
                .render(PROMPT_NAVIGATION, vars)
                .getOrNull()
                ?.text
                .orEmpty()

        internal companion object {
            const val ID: String = "state.navigation"

            const val KEY_MAPS = "maps"

            private const val PROMPT_NAVIGATION = "agent.state.navigation"
            private const val ONE = "1"
            private const val VAR_NONE = "none"
            private const val VAR_APPS = "apps"
            private const val VAR_PREFERRED_AUTO = "preferredAuto"
            private const val VAR_PREFERRED_ASK = "preferredAsk"
            private const val VAR_PREFERRED_APP = "preferredApp"
            private const val VAR_PREFERRED_MISSING = "preferredMissing"
        }
    }
