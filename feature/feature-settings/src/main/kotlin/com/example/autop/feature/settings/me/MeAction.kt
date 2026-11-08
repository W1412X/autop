/**
 * "我的"页用户操作（docs/standards/coding-standards §8：sealed interface，
 * 用户操作统一通过 `onAction(action)` 交给 ViewModel）。
 */
package com.example.autop.feature.settings.me

import com.example.autop.feature.settings.SettingsSection

public sealed interface MeAction {
    /** 深色模式（`SYSTEM` / `LIGHT` / `DARK`）。 */
    public data class SetDarkMode(
        val value: String,
    ) : MeAction

    /** 动态取色开关。 */
    public data class SetDynamicColor(
        val enabled: Boolean,
    ) : MeAction

    /** 主题色（SeedColors 索引）。 */
    public data class SetSeedIndex(
        val value: Int,
    ) : MeAction

    /** 文字大小（docs/tech/08 §1.2 档位）。 */
    public data class SetFontScale(
        val value: Double,
    ) : MeAction

    /** 图标大小（docs/tech/08 §1.3 档位）。 */
    public data class SetIconScale(
        val value: Double,
    ) : MeAction

    /** 布局密度（`COMPACT` / `STANDARD` / `COMFORTABLE`）。 */
    public data class SetDensity(
        val value: String,
    ) : MeAction

    /** 动效开关。 */
    public data class SetMotionEnabled(
        val enabled: Boolean,
    ) : MeAction

    /** 输出语言（`ui.appearance.language`：system / zh / en）。 */
    public data class SetLanguage(
        val value: String,
    ) : MeAction

    /** 悬浮窗通道（auto / accessibility / application，docs/tech/05 §10）。 */
    public data class SetOverlayWindowType(
        val value: String,
    ) : MeAction

    /** 悬浮球是否显示（`ui.overlay.enabled`）。 */
    public data class SetOverlayEnabled(
        val enabled: Boolean,
    ) : MeAction

    /** 悬浮球显示方式（`ui.overlay.displayMode`：auto / always / edge）。 */
    public data class SetOverlayDisplayMode(
        val value: String,
    ) : MeAction

    /** 常驻后台开关（`task.host.persistent`，docs/tech/07 §4.5）。 */
    public data class SetHostPersistent(
        val enabled: Boolean,
    ) : MeAction

    /** 任务结束后返回原应用开关（`task.returnToOriginApp`，docs/tech/07 §4.5）。 */
    public data class SetReturnToOriginApp(
        val enabled: Boolean,
    ) : MeAction

    /** 条件触发总开关（`task.conditions.enabled`，docs/tech/07 §4.2）。 */
    public data class SetConditionsEnabled(
        val enabled: Boolean,
    ) : MeAction

    /** 执行期触摸盾总开关（`device.touch.shieldEnabled`，N215）。 */
    public data class SetShieldEnabled(
        val enabled: Boolean,
    ) : MeAction

    /** 默认稍后分钟数（`task.reminder.snoozeMinutes`，docs/tech/07 §4.4）。 */
    public data class SetSnoozeMinutes(
        val value: Int,
    ) : MeAction

    /** 提醒铃声（路由层转 SettingsNavigator.openReminderSound）。 */
    public data object OpenReminderSound : MeAction

    /** 导出数据（写 `Download/autop/`）。 */
    public data object ExportData : MeAction

    /** 导入数据（[uri] 为 ACTION_OPEN_DOCUMENT 返回的文档地址）。 */
    public data class ImportData(
        val uri: String,
    ) : MeAction

    /** 打开"我的"子页（未落地的分组提示"即将推出"，导航回调照常发出）。 */
    public data class OpenSection(
        val section: SettingsSection,
    ) : MeAction

    /** 关闭横幅提示。 */
    public data object DismissNotice : MeAction
}
