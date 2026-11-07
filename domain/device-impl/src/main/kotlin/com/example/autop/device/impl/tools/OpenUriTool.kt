/**
 * `tool.open_uri`（docs/tech/05 §5）：打开链接或 deep link。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.ScreenReader
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** 打开链接工具。 */
@Singleton
public class OpenUriTool
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val apps: AppLauncher,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
    ) : ScreenActionTool {
        override val id: String = "tool.open_uri"

        override val spec = DeviceToolSpecs.openUri

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            // N193 空树降级：打开链接不依赖节点树。
            val snap =
                when (val got = screenReader.snapshotOrDegraded()) {
                    is Outcome.Ok -> got.value
                    is Outcome.Err -> return got
                }
            val uri = args.str("uri").orEmpty()
            if (uri.isBlank()) {
                return Outcome.Err(
                    AppError(
                        code = CommonErrorCodes.INVALID_ARGUMENT,
                        message = "uri 不能为空",
                        hint = "uri 需给出完整链接（如 https://example.com）",
                    ),
                )
            }
            val extra =
                mapOf(
                    EXTRA_URI to uri,
                    EXTRA_SCHEME to uri.substringBefore(':'),
                )
            val subject = subjects.build(ID, snap, null, extra)
            return Outcome.Ok(PreparedAction(subject, uri))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val uri = action.payload as String
            val pre = epilogue.begin(ctx)
            if (!apps.openUri(uri)) {
                return toolError(DeviceErrorCodes.URI_FAILED, "无法打开 $uri")
            }
            val replayArgs = buildJsonObject { put(EXTRA_URI, uri) }
            val out = epilogue.finish(ctx, "已发送打开请求 $uri", null, ReplayInput(ID, pre, replayArgs, gesture = null))
            val fg = out.observation?.packageName
            return out.copy(
                content =
                    out.content +
                        Block.Text("当前前台：${fg ?: "未获取到"}；链接已交给系统处理，是否打开成功以当前界面为准。"),
            )
        }

        private companion object {
            const val ID = "tool.open_uri"
            const val EXTRA_URI = "uri"
            const val EXTRA_SCHEME = "scheme"
        }
    }
