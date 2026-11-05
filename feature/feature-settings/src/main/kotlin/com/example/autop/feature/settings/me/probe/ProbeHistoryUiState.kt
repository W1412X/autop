/**
 * 工具测试记录页状态（N207）：探测历史快照（新→旧，与悬浮面板同源于 ToolProbeService）。
 */
package com.example.autop.feature.settings.me.probe

import com.example.autop.agent.ToolProbeResult

/** 页面状态。 */
public data class ProbeHistoryUiState(
    /** 探测记录（新→旧）。 */
    val results: List<ToolProbeResult> = emptyList(),
)
