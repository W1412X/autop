/**
 * 执行回执（docs/design/observation-modes-coordinate-actions §5.9）：
 * 工具必须告诉模型“实际怎么执行、为什么回退、效果如何”，
 * 不能静默替 Agent 做决定，也不能把回退或未验证说成成功。
 */
package com.example.autop.device.impl.tools

import com.example.autop.device.InjectionChannel
import com.example.autop.device.InjectionResult
import com.example.autop.device.impl.nodeaction.NodeRoute
import kotlinx.serialization.json.JsonObject

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

/** 动作效果三态：能确认变化、能确认没变、无法确认。 */
internal enum class ExecEffect {
    CHANGED,
    UNCHANGED,
    UNKNOWN,
}

/** 执行方式回执（一行，模型可见）。[fallback] 说明为什么没走首选方式。 */
internal fun executionLine(
    method: ExecMethod,
    actionLabel: String? = null,
    channel: InjectionChannel = InjectionChannel.NONE,
    fallback: String? = null,
): String {
    val how =
        when (method) {
            ExecMethod.NODE_ACTION ->
                "执行：无障碍节点动作" + actionLabel?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            ExecMethod.GESTURE -> "执行：坐标手势（${channelName(channel)}）"
            ExecMethod.GLOBAL_ACTION -> "执行：全局动作（${channelName(channel)}）"
            ExecMethod.SHELL_KEY -> "执行：shell 键盘注入" + actionLabel?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        }
    val why = fallback?.takeIf { it.isNotBlank() }?.let { "；$it" }.orEmpty()
    return how + why
}

/** 效果回执（一行，模型可见）。[note] 是为什么以及下一步建议。 */
internal fun effectLine(
    effect: ExecEffect,
    note: String? = null,
): String {
    val suffix = note?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
    return when (effect) {
        ExecEffect.CHANGED -> "效果：界面已变化"
        ExecEffect.UNCHANGED -> "效果：屏幕没有变化（device.no_effect）$suffix"
        ExecEffect.UNKNOWN -> "效果：无法确认$suffix"
    }
}

/** 节点路由被跳过/被拒时，给模型解释为什么最终用了手势；Done 返回 null。 */
internal fun routeFallback(
    route: NodeRoute,
    actionLabel: String,
): String? =
    when (route) {
        is NodeRoute.Done -> null
        is NodeRoute.Rejected -> "节点动作被应用拒绝（$actionLabel），本次改用坐标手势"
        is NodeRoute.Skipped -> "未走节点动作：${route.reason.describe()}"
    }

/** 指纹比较结果 → 效果三态。 */
internal fun effectOf(moved: Boolean?): ExecEffect =
    when (moved) {
        true -> ExecEffect.CHANGED
        false -> ExecEffect.UNCHANGED
        null -> ExecEffect.UNKNOWN
    }

private fun channelName(channel: InjectionChannel): String =
    when (channel) {
        InjectionChannel.SHELL -> "shell 注入"
        InjectionChannel.ACCESSIBILITY -> "无障碍注入"
        InjectionChannel.NONE -> "注入通道未知"
    }
