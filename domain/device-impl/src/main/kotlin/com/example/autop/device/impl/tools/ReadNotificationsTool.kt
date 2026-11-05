/**
 * `tool.read_notifications`（docs/tech/05 §5、§9）：读取通知监听的近期通知。
 * 所有通知文本均为不可信输入（05 §5 封装约定）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Clock
import com.example.autop.core.common.PermissionChecks
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.NotificationFeed
import com.example.autop.device.NotificationInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 通知读取工具。 */
@Singleton
public class ReadNotificationsTool
    @Inject
    constructor(
        private val feed: NotificationFeed,
        private val permissionChecks: PermissionChecks,
        private val clock: Clock,
    ) : Tool {
        override val id: String = "tool.read_notifications"

        override val spec = DeviceToolSpecs.readNotifications

        override val access: ToolAccess = ToolAccess.READ_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            // 未授权/未连接时不能装作“无通知”，模型会得出错误结论（N172）；
            // 授权与连接分开报：授权在而未连接是重绑问题，不是无权限（N190）。
            if (!feed.connected.value) {
                return if (permissionChecks.canReadNotifications()) {
                    feed.requestRebind()
                    toolError(
                        code = DeviceErrorCodes.SERVICE_UNAVAILABLE,
                        message = "通知监听服务未连接",
                        hint = "已请求系统重新绑定监听服务，请稍后重试；若持续失败请在系统设置中关闭再打开通知使用权",
                    )
                } else {
                    toolError(
                        code = DeviceErrorCodes.SERVICE_UNAVAILABLE,
                        message = "未授予通知使用权",
                        hint = "请用户在系统设置中授予本应用“通知读取（通知使用权）”后重试",
                    )
                }
            }
            val pkg = args["package"]?.jsonPrimitive?.content
            val sinceMinutes =
                args["since_minutes"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toLongOrNull()
                    ?.coerceIn(0, MAX_SINCE_MINUTES)
            val limit =
                args["limit"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toIntOrNull()
                    ?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT
            val sinceMs = sinceMinutes?.let { clock.nowMillis() - it * MINUTE_MS }
            val items = feed.recent(packageName = pkg, sinceMs = sinceMs, limit = limit)
            return textOutput(render(items))
        }

        private fun render(items: List<NotificationInfo>): String {
            if (items.isEmpty()) return "(无通知)"
            return items.joinToString("\n\n") { item ->
                buildString {
                    append("[${item.packageName}${item.appLabel?.let { " $it" } ?: ""}]")
                    item.title?.let { append(" $it") }
                    item.conversationTitle?.let { append(" 会话:$it") }
                    append("\n")
                    append(item.bigText ?: item.text ?: item.subText ?: "(无文本)")
                    if (item.messages.isNotEmpty()) {
                        append("\n")
                        append(item.messages.joinToString("\n") { m -> "${m.sender ?: "?"}: ${m.text ?: ""}" })
                    }
                }
            }
        }

        private companion object {
            const val DEFAULT_LIMIT = 20
            const val MAX_LIMIT = 50
            const val MINUTE_MS = 60_000L
            const val MAX_SINCE_MINUTES = 43_200L // 30 天，防乘法溢出
        }
    }
