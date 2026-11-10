/**
 * `tool.open_app`（docs/tech/05 §5）：按包名或名称打开应用；名称匹配多个时返回候选。
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
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class AppAction(
    val targetPackage: String?,
    val candidates: List<AppInfo>,
    val name: String?,
    val replayArgs: JsonObject,
)

/** 打开应用工具。 */
@Singleton
public class OpenAppTool
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val apps: AppLauncher,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
    ) : ScreenActionTool {
        override val id: String = "tool.open_app"

        override val spec = DeviceToolSpecs.openApp

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> =
            // N193 空树降级：应用匹配不依赖节点树；空树应用上的恢复路径（open_app）不能被卡死。
            when (val got = screenReader.snapshotOrDegraded()) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepareWith(got.value, args, ctx.config)
            }

        private suspend fun prepareWith(
            snap: ScreenSnapshot,
            args: JsonObject,
            config: ConfigSnapshot,
        ): Outcome<PreparedAction> {
            val pkg = args.str("package")
            val name = args.str("name")
            val candidates = if (name != null) matchApps(apps.listApps(name), name) else emptyList()
            val targetPkg = pkg ?: candidates.singleOrNull()?.packageName
            val extra = mapOf(EXTRA_TARGET_PACKAGE to (targetPkg ?: name.orEmpty()))
            return when {
                pkg == null && name == null ->
                    Outcome.Err(AppError(code = CommonErrorCodes.INVALID_ARGUMENT, message = "需要 package 或 name 参数"))
                else ->
                    Outcome.Ok(
                        PreparedAction(
                            subjects.build(ID, snap, null, extra, config),
                            AppAction(targetPkg, candidates, name, replayArgsOf(args, hit = null)),
                        ),
                    )
            }
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val app = action.payload as AppAction
            return when (val targetPkg = app.targetPackage) {
                null -> candidatesOutput(app)
                else -> launchTarget(targetPkg, app, ctx)
            }
        }

        /** 启动应用：候选列表路径不是屏幕动作，不记录回放；失败原因（WP7.5）原样带给模型。 */
        private suspend fun launchTarget(
            targetPkg: String,
            app: AppAction,
            ctx: ToolContext,
        ): ToolOutput {
            apps.launchWithError(targetPkg)?.let { return errorOutput(it) }
            val out =
                epilogue.finish(
                    ctx,
                    "已发送启动请求 $targetPkg",
                    null,
                    ReplayInput(ID, app.replayArgs, gesture = null),
                )
            val fg = out.observation?.packageName
            val note =
                if (fg == targetPkg) {
                    "当前前台：$targetPkg（启动已生效）"
                } else {
                    "当前前台：${fg ?: "未获取到"}；启动请求已发出，但尚未确认目标应用已到前台。"
                }
            return out.copy(content = out.content + Block.Text(note))
        }

        /** 名称匹配多个候选：返回列表让模型用 package 参数指定。 */
        private fun candidatesOutput(app: AppAction): ToolOutput {
            if (app.candidates.isEmpty()) {
                return toolError(DeviceErrorCodes.APP_NOT_FOUND, "没有找到应用 ${app.name.orEmpty()}")
            }
            val lines = app.candidates.joinToString("\n") { "${it.label} ${it.packageName}" }
            return textOutput("找到多个应用，请用 package 参数指定：\n$lines")
        }

        /** 名称匹配：完全相等 > 前缀 > 包含，同级保持原顺序。 */
        private fun matchApps(
            all: List<AppInfo>,
            name: String,
        ): List<AppInfo> {
            val exact = all.filter { it.label == name }
            val prefix = all.filter { it.label.startsWith(name) && it.label != name }
            val contains = all.filter { it.label.contains(name) && !it.label.startsWith(name) }
            return exact + prefix + contains
        }

        private companion object {
            const val ID = "tool.open_app"
            const val EXTRA_TARGET_PACKAGE = "targetPackage"
        }
    }
