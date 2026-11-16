/**
 * `tool.run_shell`（shell-exec 设计 §3–§8、ADR-0040/0041/0042）：完全模式下以 ADB shell 身份执行一条命令。
 *
 * - 最低档位为完全模式；总闸关闭或无线注入未连接时运行期静默不可见（[isAvailable]）。
 * - 屏幕模式由模型用 `screen` 声明（[requiresScreen]），工具不推断命令意图。
 * - 命中危险规则时执行前确认（[preflight]），确认在占屏与取槽之前。
 * - 同一批里相邻的后台调用可并行（[parallelizable]）。
 */
package com.example.autop.device.impl.tools

import android.content.Context
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunMode
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.FeaturesConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.ShellExec
import com.example.autop.device.ShellExecErrorCodes
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.device.core.UntrustedText
import com.example.autop.device.impl.R
import com.example.autop.device.impl.shell.ShellCommandRules
import com.example.autop.device.impl.shell.ShellDangerRulesLoader
import com.example.autop.device.impl.shell.ShellExecLimits
import com.example.autop.llm.Block
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TOOL_ID = "tool.run_shell"
private const val ARG_COMMAND = "command"
private const val ARG_SCREEN = "screen"
private const val ARG_TIMEOUT = "timeout_ms"

/** 路由层超时在命令时限之外加的余量（回收宽限与调度开销）。 */
private const val ROUTE_SLACK_MS = 5_000L

/** 错误码 → 给模型看的提示模板（稳定 id，见 prompts/tool/run_shell_hint_*.md）。 */
private val HINT_PROMPT_BY_CODE: Map<String, String> =
    mapOf(
        ShellExecErrorCodes.UNAVAILABLE to "tool.run_shell_hint_unavailable",
        ShellExecErrorCodes.INVALID_ARGUMENT to "tool.run_shell_hint_invalid",
        ShellExecErrorCodes.DENIED to "tool.run_shell_hint_denied",
        ShellExecErrorCodes.BUSY to "tool.run_shell_hint_busy",
        ShellExecErrorCodes.TIMEOUT to "tool.run_shell_hint_timeout",
        ShellExecErrorCodes.CANCELLED to "tool.run_shell_hint_cancelled",
        ShellExecErrorCodes.TRANSPORT to "tool.run_shell_hint_transport",
    )

/** `run_shell` 的参数声明（描述文字来自 prompts/tool/run_shell.md）。 */
public object RunShellSpec {
    public val spec: ToolSpec =
        toolSpec(name = "run_shell", descriptionPromptId = TOOL_ID) {
            string(ARG_COMMAND, required = true)
            boolean(ARG_SCREEN, required = true)
            integer(ARG_TIMEOUT, min = 100)
        }
}

/** shell 执行工具。 */
@Singleton
public class RunShellTool
    @Inject
    constructor(
        private val shellExec: ShellExec,
        private val shellInput: ShellInput,
        private val limits: ShellExecLimits,
        private val dangerRules: ShellDangerRulesLoader,
        private val prompts: PromptRepository,
        @ApplicationContext private val context: Context,
    ) : Tool {
        override val id: String = TOOL_ID
        override val spec: ToolSpec = RunShellSpec.spec
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM
        override val minMode: RunMode = RunMode.FULL

        override fun isAvailable(config: ConfigSnapshot): Boolean =
            config[FeaturesConfig.FEATURES].shellExec && shellInput.state.value == ShellState.Connected

        override fun requiresScreen(args: JsonObject): Boolean = args.shellBool(ARG_SCREEN) == true

        override fun parallelizable(args: JsonObject): Boolean = args.shellBool(ARG_SCREEN) == false

        /** 命令时限 + 确认等待 + 槽位等待 + 回收宽限 + 余量，避免路由层提前截断（设计 §3.4）。 */
        override fun timeoutMs(args: JsonObject): Long? =
            timeoutOf(args) +
                limits.confirmTimeoutMs +
                limits.hostAcquireWaitMs +
                limits.jobSlotWaitMs +
                limits.killGraceMs +
                ROUTE_SLACK_MS

        override suspend fun preflight(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput? {
            val command = args.shellStr(ARG_COMMAND) ?: return null
            val match = dangerRules.get().match(command, context.packageName) ?: return null
            val allow = context.getString(R.string.run_shell_option_allow)
            val deny = context.getString(R.string.run_shell_option_deny)
            val modeRes =
                if (args.shellBool(ARG_SCREEN) ==
                    true
                ) {
                    R.string.run_shell_mode_screen
                } else {
                    R.string.run_shell_mode_background
                }
            val question =
                context.getString(
                    R.string.run_shell_confirm,
                    command,
                    categoryLabel(match.labelKey),
                    context.getString(modeRes),
                )
            val answer = ctx.askUser(question, listOf(allow, deny), limits.confirmTimeoutMs)
            return if (answer is Outcome.Ok &&
                answer.value == allow
            ) {
                null
            } else {
                output(RunShellResults.ofNotDispatched(ShellExecErrorCodes.DENIED))
            }
        }

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val command = args.shellStr(ARG_COMMAND).orEmpty()
            ShellCommandRules.problemOf(command, limits.commandMaxBytes)?.let { problem ->
                return output(RunShellResults.ofNotDispatched(ShellExecErrorCodes.INVALID_ARGUMENT), problem)
            }
            val screen = args.shellBool(ARG_SCREEN) == true
            return when (val outcome = shellExec.exec(ctx.runId, command, timeoutOf(args))) {
                is Outcome.Err -> output(RunShellResults.ofNotDispatched(outcome.error.code), outcome.error.message)
                is Outcome.Ok -> output(RunShellResults.ofResult(outcome.value, screen))
            }
        }

        /** 把中间结果组装为工具输出：输出原文包装为不可信信封，失败附提示模板。 */
        private fun output(
            view: RunShellView,
            message: String? = null,
        ): ToolOutput {
            val code = view.errorCode
            return ToolOutput(
                ok = view.ok,
                content =
                    if (view.output.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(Block.Text(UntrustedText.wrap(UntrustedText.SOURCE_SHELL, view.output)))
                    },
                data = view.data,
                error =
                    code?.let {
                        AppError(
                            code = it,
                            message = message ?: it,
                            hint = HINT_PROMPT_BY_CODE[it]?.let { id -> prompts.text(id, emptyMap()) },
                        )
                    },
                effectMayHaveHappened = view.effectMayHaveHappened,
            )
        }

        private fun timeoutOf(args: JsonObject): Long = args.shellLong(ARG_TIMEOUT) ?: limits.defaultTimeoutMs

        /** 规则中的类别资源键 → 本地化文案；找不到资源时退回键名（不影响确认流程）。 */
        private fun categoryLabel(labelKey: String): String {
            val resId = context.resources.getIdentifier(labelKey, "string", context.packageName)
            return if (resId == 0) labelKey else context.getString(resId)
        }
    }

private fun JsonObject.shellStr(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.shellBool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.shellLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
