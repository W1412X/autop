/**
 * 环境上下文（docs/tech/05 §7）：`context.environment`，placement = FIRST_USER，
 * 模板 `agent.context.environment`，变量为时间、电量、网络、前台应用与锁屏状态。
 */
package com.example.autop.device.impl

import com.example.autop.agent.ContextContributor
import com.example.autop.agent.ContextPart
import com.example.autop.agent.ContextRequest
import com.example.autop.agent.Placement
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

/** 当前设备环境的上下文片段。 */
@Singleton
public class EnvironmentContributor
    @Inject
    constructor(
        private val deviceStates: DeviceStateProvider,
        private val clock: Clock,
        private val apps: AppLauncher,
        private val prompts: PromptRepository,
    ) : ContextContributor {
        override val id: String = CONTRIBUTOR_ID

        override suspend fun contribute(req: ContextRequest): ContextPart {
            val rendered = prompts.render(PROMPT_ENV_ID, varsOf(deviceStates.current()))
            val text = rendered.getOrNull()?.text.orEmpty()
            return ContextPart(
                placement = Placement.FIRST_USER,
                text = text,
                promptIds = mapOf(PROMPT_ENV_ID to (rendered.getOrNull()?.version ?: 0)),
            )
        }

        private suspend fun varsOf(state: DeviceState): Map<String, String> {
            val now = clock.nowMillis()
            val dateTime = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            return mapOf(
                "localTime" to dateTime.format(TIME_FORMAT),
                "weekday" to dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA),
                "timezone" to ZoneId.systemDefault().id,
                "battery" to (state.batteryPercent?.toString() ?: UNKNOWN),
                "charging" to if (state.charging) FLAG_ON else FLAG_OFF,
                "network" to state.networkType,
                "foregroundApp" to foregroundLabel(state.foregroundPackage),
                "locked" to if (state.keyguardLocked) FLAG_ON else FLAG_OFF,
            )
        }

        /** 前台应用：`名称（包名）`；无法解析名称时只给包名。 */
        private suspend fun foregroundLabel(packageName: String?): String {
            if (packageName == null) return UNKNOWN
            val label = apps.listApps(packageName).firstOrNull { it.packageName == packageName }?.label
            return if (label == null) packageName else "$label（$packageName）"
        }

        private companion object {
            const val CONTRIBUTOR_ID = "context.environment"
            const val PROMPT_ENV_ID = "agent.context.environment"
            const val UNKNOWN = "未知"
            const val FLAG_ON = "1"
            const val FLAG_OFF = ""
            val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        }
    }
