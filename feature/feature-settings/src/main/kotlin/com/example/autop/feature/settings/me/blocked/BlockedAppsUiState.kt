/**
 * 屏蔽的应用页状态（docs/tech/08 §4.5 me/blocked-apps：应用多选）。
 * 屏蔽清单即配置 `policy.blockedPackages`（docs/tech/09 §1.5）。
 */
package com.example.autop.feature.settings.me.blocked

/** 应用列表中的一行。 */
public data class BlockedAppRow(
    val packageName: String,
    val label: String,
    /** 是否已屏蔽。 */
    val blocked: Boolean,
)

public data class BlockedAppsUiState(
    /** 已安装启动项 + 手动添加的屏蔽包名（label 为包名原文）。 */
    val apps: List<BlockedAppRow> = emptyList(),
    /** 搜索词（`listApps` 过滤）。 */
    val query: String = "",
    /** 已屏蔽数量。 */
    val blockedCount: Int = 0,
    /** 手动添加包名的弹层是否打开。 */
    val manualOpen: Boolean = false,
    /** 手动输入的包名不合法。 */
    val manualError: Boolean = false,
)
