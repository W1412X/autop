/**
 * 工具测试面板的展示状态与回调（N198）：状态打包给 [ProbeScreen]，回调由
 * [ProbeController] 实现；参数逐键编辑（N207：值 map 键=参数名，空=不传），
 * 示例回填/重置是 UI 内联行为不占回调。
 */
package com.example.autop.feature.overlay.probe

import com.example.autop.agent.ToolProbeResult
import com.example.autop.agent.ToolProbeTarget

/** 面板展示状态。 */
internal data class ProbeUiState(
    val targets: List<ToolProbeTarget>,
    val selected: ToolProbeTarget?,
    /** 参数逐键编辑值（键=参数名，空串=不传；N207）。 */
    val args: Map<String, String>,
    val running: Boolean,
    val results: List<ToolProbeResult>,
    /** 执行被拦截的原因（面板提示；null=无提示，tool_optimize D20）。 */
    val blocked: ProbeBlocked? = null,
)

/** 执行被拦截的原因（提示文案在 feature-overlay strings，ViewModel 不接触资源）。 */
internal enum class ProbeBlocked {
    /** 调试模式关闭（工具自测只在调试模式下可用）。 */
    DEBUG_OFF,

    /** 有 Run 持有屏幕租约，正在操作屏幕。 */
    RUN_ACTIVE,
}

/** 面板动作回调。 */
internal class ProbeCallbacks(
    val onSelect: (ToolProbeTarget) -> Unit,
    val onArgChange: (String, String) -> Unit,
    val onResetArgs: () -> Unit,
    val onRun: () -> Unit,
    val onExport: () -> Unit,
    val onClose: () -> Unit,
)
