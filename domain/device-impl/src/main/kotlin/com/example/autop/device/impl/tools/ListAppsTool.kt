/**
 * `tool.list_apps`（docs/tech/05 §5）：查询已安装应用，每行“名称 包名”。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.prompt.PromptRepository
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
        private val prompts: PromptRepository,
    ) : Tool {
        override val id: String = "tool.list_apps"

        override val spec = DeviceToolSpecs.listApps

        override val access: ToolAccess = ToolAccess.READ_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val requestedLimit = args.int("limit")
            val limit = requestedLimit?.coerceAtLeast(1) ?: Int.MAX_VALUE
            val lines =
                apps
                    .listApps(args.str("query"))
                    .take(limit)
                    .joinToString("\n") { "${it.label} ${it.packageName}" }
            // limit 被钳制时说明实际用了多少（WP7.10）。
            val clampedNote =
                if (requestedLimit != null && requestedLimit != limit) {
                    "\n" +
                        prompts.text(
                            PROMPT_ARG_CLAMPED,
                            mapOf(
                                VAR_NAME to ARG_LIMIT,
                                VAR_REQUESTED to requestedLimit.toString(),
                                VAR_USED to limit.toString(),
                            ),
                        )
                } else {
                    ""
                }
            return textOutput(lines.ifEmpty { "没有匹配的应用" } + clampedNote)
        }

        private companion object {
            const val PROMPT_ARG_CLAMPED = "agent.tool.arg_clamped"
            const val ARG_LIMIT = "limit"
            const val VAR_NAME = "name"
            const val VAR_REQUESTED = "requested"
            const val VAR_USED = "used"
        }
    }
