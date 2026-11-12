/**
 * 导航应用选择（intent-tool §5.2）：纯函数。输入是请求的 map、用户偏好、已安装应用与规则表，
 * 输出唯一的选择结果；“每次询问”由调用方（IntentTool）执行。
 */
package com.example.autop.device.core

import com.example.autop.device.NavigationApp
import com.example.autop.device.core.navigation.NavigationRules

/** 选择结果。 */
public sealed interface NavChoice {
    /** 直接使用 [app]，[reason] 说明依据。 */
    public data class Use(
        val app: NavigationApp,
        val reason: NavReason,
    ) : NavChoice

    /** 偏好为 `ask` 且可选应用不少于 2 个：由调用方询问用户。 */
    public data class Ask(
        val options: List<NavigationApp>,
    ) : NavChoice

    /** 请求的 map 未安装（或不支持该出行方式）。 */
    public data class RequestedNotInstalled(
        val requestedId: String,
    ) : NavChoice

    /** 没有可用的导航应用，调用方改用 `geo:` 兜底。 */
    public data object NoneInstalled : NavChoice
}

/** 选择依据。 */
public enum class NavReason {
    REQUESTED,
    PREFERRED,
    AUTO,
    ONLY_ONE,
}

/** 导航应用选择的纯函数实现。 */
public object NavigationChooser {
    /** 偏好值：按优先级自动选择。 */
    public const val PREFERENCE_AUTO: String = "auto"

    /** 偏好值：每次询问。 */
    public const val PREFERENCE_ASK: String = "ask"

    /**
     * 选择顺序：请求的 map > 偏好指定的应用 > 无应用 > 偏好 `ask` > 自动（优先级第一）。
     * [installed] 须已按 priority 升序；只考虑支持 [mode] 的应用。
     */
    public fun choose(
        requestedId: String?,
        preferred: String,
        installed: List<NavigationApp>,
        mode: NavMode,
        rules: NavigationRules,
    ): NavChoice {
        val usable = usableFor(installed, mode, rules)
        return when {
            requestedId != null -> chooseRequested(requestedId, usable)
            usable.any { it.id == preferred } -> NavChoice.Use(usable.first { it.id == preferred }, NavReason.PREFERRED)
            usable.isEmpty() -> NavChoice.NoneInstalled
            preferred == PREFERENCE_ASK -> chooseAsked(usable)
            else -> NavChoice.Use(usable.first(), NavReason.AUTO)
        }
    }

    private fun chooseRequested(
        requestedId: String,
        usable: List<NavigationApp>,
    ): NavChoice {
        val app = usable.firstOrNull { it.id == requestedId }
        return if (app != null) {
            NavChoice.Use(app, NavReason.REQUESTED)
        } else {
            NavChoice.RequestedNotInstalled(requestedId)
        }
    }

    private fun chooseAsked(usable: List<NavigationApp>): NavChoice =
        if (usable.size >= 2) {
            NavChoice.Ask(usable)
        } else {
            NavChoice.Use(usable.first(), NavReason.ONLY_ONE)
        }

    private fun usableFor(
        installed: List<NavigationApp>,
        mode: NavMode,
        rules: NavigationRules,
    ): List<NavigationApp> {
        val supported = rules.apps.filter { it.uri.modes.containsKey(mode.id) }.map { it.id }.toSet()
        return installed.filter { it.id in supported }
    }
}
