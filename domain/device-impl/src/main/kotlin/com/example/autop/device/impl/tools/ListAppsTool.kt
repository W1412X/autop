/**
 * `tool.list_apps`（docs/tech/05 §5）：查询已安装应用，每行“名称 包名”。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.device.AppLauncher
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 应用列表工具。 */
@Singleton
public class ListAppsTool
    @Inject
    constructor(
        private val apps: AppLauncher,
    ) : Tool {
        override val id: String = "tool.list_apps"

        override val spec = DeviceToolSpecs.listApps

        override val access: ToolAccess = ToolAccess.READ_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val limit = args.int("limit")?.coerceAtLeast(1) ?: Int.MAX_VALUE
            val lines =
                apps
                    .listApps(args.str("query"))
                    .take(limit)
                    .joinToString("\n") { "${it.label} ${it.packageName}" }
            return textOutput(lines.ifEmpty { "没有匹配的应用" })
        }
    }
