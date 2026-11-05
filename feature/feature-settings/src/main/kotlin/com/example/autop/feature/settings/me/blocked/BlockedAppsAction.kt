/**
 * 屏蔽的应用页用户操作（docs/standards/coding-standards §8：sealed interface）。
 */
package com.example.autop.feature.settings.me.blocked

public sealed interface BlockedAppsAction {
    /** 搜索词变更（触发 `listApps` 过滤）。 */
    public data class SetQuery(
        val value: String,
    ) : BlockedAppsAction

    /** 勾选/取消勾选一个应用。 */
    public data class Toggle(
        val packageName: String,
        val blocked: Boolean,
    ) : BlockedAppsAction

    /** 打开"手动添加包名"弹层。 */
    public data object OpenManual : BlockedAppsAction

    /** 关闭"手动添加包名"弹层。 */
    public data object CloseManual : BlockedAppsAction

    /** 手动添加一个包名（不在启动项列表中的应用）。 */
    public data class AddManual(
        val packageName: String,
    ) : BlockedAppsAction
}
