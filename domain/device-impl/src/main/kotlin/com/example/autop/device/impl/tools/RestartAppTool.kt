/**
 * `tool.restart_app`（docs/design/flows-recording-recovery.md R6）：强制结束应用后重新打开。
 *
 * 只通过 shell 通道执行 `am force-stop`，包名由 [ShellInput.forceStop] 校验。
 * shell 未连接时直接返回 `device.restart_unavailable`，不做其他替代操作。
 * 受保护包名（系统界面）与本应用自身不允许结束。
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
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import com.example.autop.device.impl.popup.PopupRulesLoader
import com.example.autop.llm.Block
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

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
        private val rules: PopupRulesLoader,
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
                is Outcome.Ok -> prepareWith(got.value, args)
            }

        private fun prepareWith(
            snap: ScreenSnapshot,
            args: JsonObject,
        ): Outcome<PreparedAction> {
            val pkg = args.str("package") ?: snap.foregroundPackage
            if (pkg == null) {
                return Outcome.Err(
                    AppError(code = CommonErrorCodes.INVALID_ARGUMENT, message = "需要 package 参数，或当前前台应用未知"),
                )
            }
            val policy = rules.get()
            if (!PACKAGE_NAME.matches(pkg) || pkg == context.packageName || pkg in policy.protectedPackages) {
                return Outcome.Err(
                    AppError(code = DeviceErrorCodes.RESTART_DENIED, message = "不允许结束应用 $pkg", hint = "请换一个应用包名"),
                )
            }
            return Outcome.Ok(PreparedAction(subjects.build(ID, snap, null, mapOf(EXTRA_PACKAGE to pkg)), pkg))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val pkg = action.payload as String
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
            delay(rules.get().restartSettleMs)
            if (!apps.launch(pkg)) {
                return toolError(DeviceErrorCodes.LAUNCH_FAILED, "重新打开 $pkg 失败")
            }
            val out =
                epilogue.finish(
                    ctx,
                    "已发送重启请求 $pkg（force-stop 已执行，随后请求启动）",
                    null,
                    // 回放参数带上解析后的包名（含缺省时取的前台应用），否则回放会重启到别的应用。
                    ReplayInput(ID, buildJsonObject { put("package", pkg) }, gesture = null),
                )
            val fg = out.observation?.packageName
            val note =
                if (fg == pkg) {
                    "当前前台：$pkg（重启已生效）"
                } else {
                    "当前前台：${fg ?: "未获取到"}；重启请求已发出，但尚未确认应用已回到前台。"
                }
            return out.copy(content = out.content + Block.Text(note))
        }

        private companion object {
            const val ID = "tool.restart_app"
            const val EXTRA_PACKAGE = "targetPackage"
            val PACKAGE_NAME = Regex("[A-Za-z0-9._]+")
        }
    }
