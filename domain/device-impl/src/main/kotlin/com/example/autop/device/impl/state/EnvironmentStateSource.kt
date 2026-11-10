/**
 * 运行状态来源 `state.environment`（agent-harness-overhaul WP3.5.1）：
 * 时间、电量、网络、前台应用、屏幕锁定。WP3 并入运行状态消息，不再走 system 动态段。
 */
package com.example.autop.device.impl.state

import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.core.common.Clock
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceState
import com.example.autop.device.DeviceStateProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 环境状态来源。 */
@Singleton
public class EnvironmentStateSource
    @Inject
    constructor(
        private val deviceStates: DeviceStateProvider,
        private val clock: Clock,
        private val apps: AppLauncher,
        private val prompts: PromptRepository,
    ) : RuntimeStateSource {
        override val id: String = ID

        override suspend fun entries(req: StateRequest): List<StateEntry> {
            val state = deviceStates.current()
            return listOf(
                StateEntry(KEY_TIME, render(PROMPT_TIME, timeVars()), volatile = true),
                StateEntry(KEY_BATTERY, render(PROMPT_BATTERY, batteryVars(state)), volatile = true),
                StateEntry(KEY_NETWORK, render(PROMPT_NETWORK, mapOf(VAR_NETWORK to state.networkType))),
                StateEntry(KEY_FOREGROUND, render(PROMPT_FOREGROUND, foregroundVars(state))),
                StateEntry(KEY_SCREEN, render(PROMPT_SCREEN, screenVars(state))),
            )
        }

        private fun timeVars(): Map<String, String> {
            val zone = ZoneId.systemDefault()
            val now = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone)
            return mapOf(
                VAR_LOCAL_TIME to now.format(TIME_FORMAT),
                VAR_WEEKDAY to now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA),
                VAR_TIMEZONE to zone.id,
            )
        }

        private fun batteryVars(state: DeviceState): Map<String, String> =
            mapOf(
                VAR_BATTERY to (state.batteryPercent?.toString() ?: "?"),
                VAR_CHARGING to flag(state.charging),
            )

        private suspend fun foregroundVars(state: DeviceState): Map<String, String> {
            val pkg = state.foregroundPackage
            return if (pkg == null) {
                mapOf(VAR_APP to "", VAR_UNKNOWN to "1")
            } else {
                mapOf(VAR_APP to foregroundLabel(pkg), VAR_UNKNOWN to "")
            }
        }

        /** 应用名（包名）；取不到名字时只给包名。 */
        private suspend fun foregroundLabel(packageName: String): String {
            val label = apps.listApps(packageName).firstOrNull { it.packageName == packageName }?.label
            return label?.let { "$it（$packageName）" } ?: packageName
        }

        private fun screenVars(state: DeviceState): Map<String, String> =
            when {
                !state.screenOn -> mapOf(VAR_OFF to "1", VAR_LOCKED to "", VAR_UNLOCKED to "")
                state.keyguardLocked -> mapOf(VAR_OFF to "", VAR_LOCKED to "1", VAR_UNLOCKED to "")
                else -> mapOf(VAR_OFF to "", VAR_LOCKED to "", VAR_UNLOCKED to "1")
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
            const val ID: String = "state.environment"

            const val KEY_TIME = "time"
            const val KEY_BATTERY = "battery"
            const val KEY_NETWORK = "network"
            const val KEY_FOREGROUND = "foreground"
            const val KEY_SCREEN = "screen"

            private const val PROMPT_TIME = "agent.state.env.time"
            private const val PROMPT_BATTERY = "agent.state.env.battery"
            private const val PROMPT_NETWORK = "agent.state.env.network"
            private const val PROMPT_FOREGROUND = "agent.state.env.foreground"
            private const val PROMPT_SCREEN = "agent.state.env.screen"

            private const val VAR_LOCAL_TIME = "localTime"
            private const val VAR_WEEKDAY = "weekday"
            private const val VAR_TIMEZONE = "timezone"
            private const val VAR_BATTERY = "battery"
            private const val VAR_CHARGING = "charging"
            private const val VAR_NETWORK = "network"
            private const val VAR_APP = "app"
            private const val VAR_UNKNOWN = "unknown"
            private const val VAR_OFF = "off"
            private const val VAR_LOCKED = "locked"
            private const val VAR_UNLOCKED = "unlocked"

            private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        }
    }
