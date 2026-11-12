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
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.AppMatcher
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class AppAction(
    val targetPackage: String?,
    val candidates: List<AppInfo>,
    val name: String?,
    val waitMs: Long?,
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
        private val prompts: PromptRepository,
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
            val candidates = if (name != null) AppMatcher.rank(apps.listApps(name), name) else emptyList()
            val targetPkg = pkg ?: candidates.singleOrNull()?.packageName
            val extra = mapOf(EXTRA_TARGET_PACKAGE to (targetPkg ?: name.orEmpty()))
            return when {
                pkg == null && name == null ->
                    Outcome.Err(AppError(code = CommonErrorCodes.INVALID_ARGUMENT, message = "需要 package 或 name 参数"))
                else ->
                    Outcome.Ok(
                        PreparedAction(
                            subjects.build(ID, snap, null, extra, config),
                            AppAction(targetPkg, candidates, name, waitMsOf(args), replayArgsOf(args, hit = null)),
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

        /** 启动应用：结果只报"已请求打开"，不检查前台（tool_optimize 02 §8）。 */
        private suspend fun launchTarget(
            targetPkg: String,
            app: AppAction,
            ctx: ToolContext,
        ): ToolOutput {
            val pre = epilogue.preSample(ctx)
            apps.launchWithError(targetPkg)?.let { return errorOutput(it) }
            val label = app.candidates.singleOrNull()?.label ?: targetPkg
            return epilogue.finish(
                ctx,
                prompts.text(PROMPT_RESULT, mapOf(VAR_LABEL to label, VAR_PKG to targetPkg)),
                app.waitMs,
                ReplayInput(ID, app.replayArgs, gesture = null),
                pre,
            )
        }

        /** 名称匹配多个候选：失败返回候选列表，让模型用 package 参数指定（tool_optimize D17）。 */
        private fun candidatesOutput(app: AppAction): ToolOutput {
            if (app.candidates.isEmpty()) {
                return toolError(DeviceErrorCodes.APP_NOT_FOUND, "没有找到应用 ${app.name.orEmpty()}")
            }
            val list = app.candidates.joinToString("；") { "${it.label} ${it.packageName}" }
            return toolError(
                DeviceErrorCodes.APP_AMBIGUOUS,
                "multiple apps match '${app.name.orEmpty()}'",
                prompts.text(PROMPT_AMBIGUOUS_HINT, mapOf(VAR_CANDIDATES to list)),
            )
        }

        private companion object {
            const val ID = "tool.open_app"
            const val EXTRA_TARGET_PACKAGE = "targetPackage"
            const val PROMPT_RESULT = "device.open_app.result"
            const val PROMPT_AMBIGUOUS_HINT = "device.open_app.ambiguous_hint"
            const val VAR_LABEL = "label"
            const val VAR_PKG = "pkg"
            const val VAR_CANDIDATES = "candidates"
        }
    }
