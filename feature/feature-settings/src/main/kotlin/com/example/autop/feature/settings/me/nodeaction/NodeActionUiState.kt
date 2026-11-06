/**
 * 节点操作页状态（ADR-0015）：总开关（`device.nodeAction.enabled`）与被记录为屏蔽节点动作的应用。
 */
package com.example.autop.feature.settings.me.nodeaction

/** 一条屏蔽记录行。 */
public data class NodeActionBlockRow(
    val packageName: String,
    /** 应用名；查不到时为包名。 */
    val label: String,
    /** 记录时间（毫秒）。 */
    val blockedAt: Long,
)

public data class NodeActionUiState(
    /** 优先使用无障碍节点动作的总开关。 */
    val enabled: Boolean = true,
    val blocked: List<NodeActionBlockRow> = emptyList(),
)
