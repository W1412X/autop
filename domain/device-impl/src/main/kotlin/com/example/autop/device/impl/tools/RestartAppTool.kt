/**
 * `tool.restart_app`（docs/design/flows-recording-recovery.md R6）：强制结束应用后重新打开。
 *
 * 只通过 shell 通道执行 `am force-stop`，包名由 [ShellInput.forceStop] 校验。
 * shell 未连接时直接返回 `device.restart_unavailable`，不做其他替代操作。
 * 受保护包名（restart_rules.json）与本应用自身不允许结束。
 */
package com.example.autop.device.impl.tools

import android.content.Context
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
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.device.core.PackageNames
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import com.example.autop.device.impl.restart.RestartRulesLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class RestartAction(
    val pkg: String,
    val waitMs: Long?,
)

/** 重启应用工具。 */
@Singleton
public class RestartAppTool
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val shell: ShellInput,
        private val apps: AppLauncher,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
        private val rules: RestartRulesLoader,
        private val prompts: PromptRepository,
        @ApplicationContext private val context: Context,
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec = DeviceToolSpecs.restartApp

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> =
            // N193 空树降级：package 缺省时取前台包名，降级快照照常带前台信息。
            when (val got = screenReader.snapshotOrDegraded()) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepareWith(got.value, args, ctx.config)
            }

        private fun prepareWith(
            snap: ScreenSnapshot,
            args: JsonObject,
            config: ConfigSnapshot,
        ): Outcome<PreparedAction> {
            val pkg = args.str("package") ?: snap.foregroundPackage
            if (pkg == null) {
                return Outcome.Err(
                    AppError(code = CommonErrorCodes.INVALID_ARGUMENT, message = "需要 package 参数，或当前前台应用未知"),
                )
            }
            val policy = rules.get()
            if (!PackageNames.PATTERN.matches(pkg) || pkg == context.packageName || pkg in policy.protectedPackages) {
                return Outcome.Err(
                    AppError(code = DeviceErrorCodes.RESTART_DENIED, message = "不允许结束应用 $pkg", hint = "请换一个应用包名"),
                )
            }
            val action = RestartAction(pkg, waitMsOf(args))
            return Outcome.Ok(
                PreparedAction(subjects.build(ID, snap, null, mapOf(EXTRA_PACKAGE to pkg), config), action),
            )
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val payload = action.payload as RestartAction
            val pkg = payload.pkg
            val pre = epilogue.preSample(ctx)
            if (shell.state.value !is ShellState.Connected) {
                return toolError(
                    DeviceErrorCodes.RESTART_UNAVAILABLE,
                    "无法强制结束应用：shell 通道未连接",
                    "请用 global_action back 或 home 返回，再用 open_app 打开 $pkg",
                )
            }
            if (!shell.forceStop(pkg)) {
                return toolError(DeviceErrorCodes.RESTART_UNAVAILABLE, "强制结束 $pkg 失败")
            }
            delay(rules.get().settleMs)
            if (!apps.launch(pkg)) {
                return toolError(DeviceErrorCodes.LAUNCH_FAILED, "重新打开 $pkg 失败")
                    .copy(trace = listOf(stoppedTrace(pkg, failed = true)))
            }
            return epilogue.finish(
                ctx,
                prompts.text(PROMPT_RESULT, mapOf(VAR_PKG to pkg)),
                payload.waitMs,
                // 回放参数带上解析后的包名（含缺省时取的前台应用），否则回放会重启到别的应用。
                ReplayInput(ID, buildJsonObject { put("package", pkg) }, gesture = null),
                pre,
                trace = listOf(stoppedTrace(pkg, failed = false)),
            )
        }

        /** 强制结束执行行；[failed] 时附"重新打开失败"口径（模板 device.restart.stopped）。 */
        private fun stoppedTrace(
            pkg: String,
            failed: Boolean,
        ): String {
            val vars = if (failed) mapOf(VAR_PKG to pkg, VAR_FAILED to "1") else mapOf(VAR_PKG to pkg)
            return prompts.text(PROMPT_STOPPED, vars)
        }

        private companion object {
            const val ID = "tool.restart_app"
            const val EXTRA_PACKAGE = "targetPackage"
            const val PROMPT_RESULT = "device.restart.result"
            const val PROMPT_STOPPED = "device.restart.stopped"
            const val VAR_PKG = "pkg"
            const val VAR_FAILED = "failed"
        }
    }
