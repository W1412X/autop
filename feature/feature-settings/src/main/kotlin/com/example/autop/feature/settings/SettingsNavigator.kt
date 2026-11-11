/**
 * feature-settings 的导航回调（docs/tech/08 §3：feature 暴露
 * `NavGraphBuilder.xxxGraph(navigator: XxxNavigator)`，feature 之间不互相引用）。
 *
 * "我的"子页（docs/tech/08 §4.5）由 app 侧实现本接口完成跳转。
 */
package com.example.autop.feature.settings

/** "我的"页子页（docs/tech/08 §4.5 `me/{id}` 路由）。 */
public enum class SettingsSection(
    /** `me/{id}` 的 id 段（app 的 `AutopRoutes.meSection` 用）。 */
    public val id: String,
) {
    MODEL("models"),
    OBSERVE("observe"),
    EMBEDDING("embedding"),
    PERMISSIONS("permissions"),
    SHELL("shell"),
    RULES("rules"),
    BLOCKED_APPS("blocked-apps"),
    AUDIT("audit"),
    TASKS("tasks"),
    BUDGET("budget"),
    ADVANCED("advanced"),
    PROMPTS("prompts"),
    LOGS("logs"),
    PROBE("probe"),
    DEBUG("debug"),
    ;

    /**
     * 该入口是否已落地（未落地的入口行点击显示"即将推出"，见 AppearanceSettingsViewModel）。
     * [PERMISSIONS] 由 app 路由到 `onboarding?mode=health`（docs/tech/08 §4.5）。
     */
    public val hasPage: Boolean
        get() =
            this == MODEL ||
                this == OBSERVE ||
                this == EMBEDDING ||
                this == PERMISSIONS ||
                this == SHELL ||
                this == RULES ||
                this == BLOCKED_APPS ||
                this == AUDIT ||
                this == BUDGET ||
                this == ADVANCED ||
                this == PROMPTS ||
                this == LOGS ||
                this == PROBE ||
                this == DEBUG
}

/** feature-settings 导航回调接口。 */
public interface SettingsNavigator {
    /** 打开"我的"子页 [section]（跳 `me/{id}` 路由；PERMISSIONS 由 app 转健康检查，由 app 实现）。 */
    public fun openSection(section: SettingsSection)

    /** 提醒铃声（"任务与提醒"分组的铃声行，app 跳提醒通知渠道的声音设置）。 */
    public fun openReminderSound()

    /** 子页返回上一级（`popBackStack`，由 app 实现）。 */
    public fun navigateBack()
}
