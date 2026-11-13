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

/**
 * "我的"页二级分类（docs/tech/08 §4.5 两级结构）：一级列表的每一行进入一个二级页。
 *
 * @property id `me/group/{id}` 路由的 id 段。
 */
public enum class MeGroup(
    public val id: String,
) {
    MODEL("model"),
    PERMISSIONS_RUNTIME("runtime"),
    SECURITY("security"),
    TASKS("tasks"),
    APPEARANCE("appearance"),
    ADVANCED("advanced"),
    ABOUT("about"),
    ;

    public companion object {
        /** 按路由 id 查找分类；未知 id 返回 null，由路由层兜底。 */
        public fun fromId(id: String?): MeGroup? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 从一级状态卡跳入二级页后需要定位的行（`me/group/{id}?focus={id}`，定位后短暂高亮）。
 *
 * @property id 路由查询参数值。
 */
public enum class MeFocus(
    public val id: String,
) {
    HOST_PERSISTENT("host_persistent"),
    ;

    public companion object {
        /** 按路由参数值查找定位目标；未知值返回 null。 */
        public fun fromId(id: String?): MeFocus? = entries.firstOrNull { it.id == id }
    }
}

/** 系统设置页跳转目标（一级状态卡的无障碍、通知使用权格子）。 */
public enum class SystemSettingsTarget {
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
}

/** feature-settings 导航回调接口。 */
public interface SettingsNavigator {
    /** 打开"我的"子页 [section]（跳 `me/{id}` 路由；PERMISSIONS 由 app 转健康检查，由 app 实现）。 */
    public fun openSection(section: SettingsSection)

    /** 打开"我的"二级页 [group]；[focus] 非空时滚动到对应行并短暂高亮（由 app 实现）。 */
    public fun openGroup(
        group: MeGroup,
        focus: MeFocus?,
    )

    /** 打开系统设置页 [target]（由 app 实现，失败时不崩溃）。 */
    public fun openSystemSettings(target: SystemSettingsTarget)

    /** 提醒铃声（"任务与提醒"分组的铃声行，app 跳提醒通知渠道的声音设置）。 */
    public fun openReminderSound()

    /** 子页返回上一级（`popBackStack`，由 app 实现）。 */
    public fun navigateBack()
}
