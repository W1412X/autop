/**
 * 流程执行中的提示行文案（P1-2）：流程名 · 当前节点 · 已完成步数。
 * 进度悬浮窗头部与顶部运行控制条共用（平行展示同源，避免两处漂移）。
 */
package com.example.autop.feature.overlay.progress

import com.example.autop.task.ActiveRunInfo

/** 流程提示文案；无流程 Run 返回 null。 */
internal fun flowHintOf(active: ActiveRunInfo?): String? {
    val flow = active?.flow ?: return null
    val node = flow.progress.currentNodeName ?: flow.progress.currentNodeId ?: return flow.flowName
    val done =
        if (flow.progress.completedNodes > 0) {
            "已完成 ${flow.progress.completedNodes} 步"
        } else {
            null
        }
    return listOfNotNull("流程：${flow.flowName}", node, done).joinToString(" · ")
}
