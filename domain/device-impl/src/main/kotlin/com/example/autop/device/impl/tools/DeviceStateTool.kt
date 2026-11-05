/**
 * `tool.device_state`（name `get_device_state`，docs/tech/05 §5）：时间与时区、电量、
 * 网络、锁屏、前台应用。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Clock
import com.example.autop.device.DeviceStateProvider
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** 设备状态工具。 */
@Singleton
public class DeviceStateTool
    @Inject
    constructor(
        private val deviceStates: DeviceStateProvider,
        private val clock: Clock,
    ) : Tool {
        override val id: String = "tool.device_state"

        override val spec = DeviceToolSpecs.deviceState

        override val access: ToolAccess = ToolAccess.READ_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val state = deviceStates.current()
            val zone = ZoneId.systemDefault()
            val time = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).format(TIME_FORMAT)
            val text =
                listOf(
                    "时间：$time（时区 ${zone.id}）",
                    "电量：${state.batteryPercent?.toString() ?: "未知"}%${if (state.charging) "（充电中）" else ""}",
                    "网络：${state.networkType}",
                    "锁屏：${if (state.keyguardLocked) "是" else "否"}",
                    "前台应用：${state.foregroundPackage ?: "未知"}",
                ).joinToString("\n")
            return textOutput(text)
        }

        private companion object {
            val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        }
    }
