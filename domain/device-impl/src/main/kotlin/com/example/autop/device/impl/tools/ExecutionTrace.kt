/**
 * 执行回执（agent-harness-overhaul WP2.5）：工具必须告诉模型“实际怎么执行、为什么回退”，
 * 不能静默替 Agent 做决定。文案全部走 prompt 模板（附录 A.3）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.InjectionChannel
import com.example.autop.device.InjectionResult
import com.example.autop.device.ShellSkip
import com.example.autop.device.impl.nodeaction.NodeRoute
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 模板渲染（设备回执用）：不存在/渲染失败返回空串。 */
internal fun PromptRepository.text(
    id: String,
    vars: Map<String, String> = emptyMap(),
): String = render(id, vars).getOrNull()?.text.orEmpty()

/** 坐标手势成功后的结果：实际通道 + 回放参数。 */
internal data class GestureShot(
    val result: InjectionResult,
    val replay: JsonObject,
)

/** 实际执行方式：无障碍节点动作或坐标手势。 */
internal enum class ExecMethod {
    NODE_ACTION,
    GESTURE,
    GLOBAL_ACTION,

    /** shell 键盘注入（剪贴板粘贴、回车等，只经 shell 通道）。 */
    SHELL_KEY,
}

/** 执行方式回执（一行，模型可见）。[fallback] 说明为什么没走首选方式。 */
@Singleton
public class ExecutionTraceRenderer
    @Inject
    constructor(
        private val prompts: PromptRepository,
    ) {
        internal fun executionLine(
            method: ExecMethod,
            actionLabel: String? = null,
            channel: InjectionChannel = InjectionChannel.NONE,
            fallback: String? = null,
            shellSkip: ShellSkip? = null,
        ): String {
            val action = actionLabel?.takeIf { it.isNotBlank() }.orEmpty()
            val how =
                when (method) {
                    ExecMethod.NODE_ACTION -> prompts.text(PROMPT_NODE_ACTION, mapOf(VAR_ACTION to action))
                    ExecMethod.GESTURE -> gestureLike(PROMPT_GESTURE, channel, shellSkip)
                    ExecMethod.GLOBAL_ACTION -> gestureLike(PROMPT_GLOBAL_ACTION, channel, shellSkip)
                    ExecMethod.SHELL_KEY -> prompts.text(PROMPT_SHELL_KEY, mapOf(VAR_ACTION to action))
                }
            val why = fallback?.takeIf { it.isNotBlank() }?.let { "；$it" }.orEmpty()
            return how + why
        }

        /** 节点路由被跳过/被拒时，给模型解释为什么最终用了手势；Done 返回 null。 */
        internal fun routeFallback(
            route: NodeRoute,
            actionLabel: String,
        ): String? =
            when (route) {
                is NodeRoute.Done -> null
                is NodeRoute.Rejected -> prompts.text(PROMPT_ROUTE_REJECTED, mapOf(VAR_ACTION to actionLabel))
                is NodeRoute.Skipped -> prompts.text(PROMPT_ROUTE_SKIPPED, mapOf(VAR_REASON to route.reason.describe()))
            }

        private fun gestureLike(
            id: String,
            channel: InjectionChannel,
            shellSkip: ShellSkip?,
        ): String =
            prompts.text(
                id,
                mapOf(
                    VAR_CHANNEL to channelName(channel),
                    VAR_EXTRA to shellSkip?.let { prompts.text(PROMPT_SHELL_SKIP_PREFIX + it.id) }.orEmpty(),
                ),
            )

        private fun channelName(channel: InjectionChannel): String =
            when (channel) {
                InjectionChannel.SHELL -> prompts.text(PROMPT_CHANNEL_SHELL)
                InjectionChannel.ACCESSIBILITY -> prompts.text(PROMPT_CHANNEL_ACCESSIBILITY)
                InjectionChannel.NONE -> prompts.text(PROMPT_CHANNEL_NONE)
            }

        private companion object {
            const val PROMPT_NODE_ACTION = "device.trace.node_action"
            const val PROMPT_GESTURE = "device.trace.gesture"
            const val PROMPT_GLOBAL_ACTION = "device.trace.global_action"
            const val PROMPT_SHELL_KEY = "device.trace.shell_key"
            const val PROMPT_CHANNEL_SHELL = "device.trace.channel.shell"
            const val PROMPT_CHANNEL_ACCESSIBILITY = "device.trace.channel.accessibility"
            const val PROMPT_CHANNEL_NONE = "device.trace.channel.none"
            const val PROMPT_SHELL_SKIP_PREFIX = "device.trace.shell_skip."
            const val PROMPT_ROUTE_REJECTED = "device.trace.route.rejected"
            const val PROMPT_ROUTE_SKIPPED = "device.trace.route.skipped"

            const val VAR_ACTION = "action"
            const val VAR_CHANNEL = "channel"
            const val VAR_EXTRA = "extra"
            const val VAR_REASON = "reason"
        }
    }

/** shell 跳过原因 → 模板后缀（WP7.4：统一用 DAPI 的 [ShellSkip]，COMMAND_FAILED 对应 `device.trace.shell_skip.failed`）。 */
private val ShellSkip.id: String
    get() =
        when (this) {
            ShellSkip.NOT_CONNECTED -> "not_connected"
            ShellSkip.COMMAND_FAILED -> "failed"
        }
