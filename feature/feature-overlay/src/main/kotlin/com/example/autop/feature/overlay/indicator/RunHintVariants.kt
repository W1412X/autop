/**
 * 控制条标题的状态短语与工具变体文案（纯函数表驱动）：同一状态/工具多种说法，
 * 加权反重复随机抽取（上一次出现的说法降权，其余说法概率恒更大），同段展示期间稳定。
 * 有目标用带目标说法、没目标用无目标说法（不裸显工具名）；仅控制条消费，通知栏不动。
 */
package com.example.autop.feature.overlay.indicator

import com.example.autop.agent.RunState
import com.example.autop.feature.overlay.progress.flowHintOf
import com.example.autop.task.ActiveRunInfo

/** 上一次说法相对其他说法的权重（降权但仍可能抽中）。 */
private const val PREVIOUS_WEIGHT: Float = 1f
private const val OTHER_WEIGHT: Float = 2f

/** 目标占位符。 */
private const val TARGET_SLOT: String = "%s"

/** 思考 / 准备 / 整理上下文的状态短语池。 */
private val THINKING: List<String> = listOf("思考中…", "正在思考…", "想一想…", "理清思路中…", "推理中…", "盘算下一步…")
private val STARTING: List<String> = listOf("准备中…", "即将开始…")
private val COMPACTING: List<String> = listOf("整理上下文中…", "归纳前文…")

/** 工具变体：带目标模板（%s = 目标）与无目标说法（必有，不裸显工具名）。 */
private data class ToolVariants(
    val withTarget: List<String> = emptyList(),
    val noTarget: List<String>,
)

private val TOOLS: Map<String, ToolVariants> =
    mapOf(
        "tool.tap" to
            ToolVariants(
                withTarget = listOf("正在点击「$TARGET_SLOT」", "点按「$TARGET_SLOT」", "去点「$TARGET_SLOT」"),
                noTarget = listOf("点击中…", "正在点击…", "点按中…"),
            ),
        "tool.tap_node" to
            ToolVariants(
                withTarget = listOf("正在点击「$TARGET_SLOT」", "点按「$TARGET_SLOT」"),
                noTarget = listOf("点击中…", "正在点击…"),
            ),
        "tool.input_text" to
            ToolVariants(
                withTarget = listOf("正在输入「$TARGET_SLOT」", "给它填上「$TARGET_SLOT」"),
                noTarget = listOf("输入中…", "正在打字…", "敲字中…"),
            ),
        "tool.scroll" to ToolVariants(noTarget = listOf("滚动中…", "正在翻页…", "翻一翻…")),
        "tool.scroll_node" to ToolVariants(noTarget = listOf("滚动中…", "正在翻页…")),
        "tool.swipe" to ToolVariants(noTarget = listOf("滑动中…", "正在滑动…", "划一下…")),
        "tool.observe" to
            ToolVariants(noTarget = listOf("观察页面中…", "正在看屏幕…", "读取界面中…", "看看现在什么样")),
        "tool.screenshot" to ToolVariants(noTarget = listOf("截屏中…", "看一眼屏幕…", "截图看看")),
        "tool.wait" to
            ToolVariants(
                withTarget = listOf("等「$TARGET_SLOT」出现…"),
                noTarget = listOf("等待中…", "等界面就绪…", "稍等一下…"),
            ),
        "tool.open_app" to
            ToolVariants(
                withTarget = listOf("正在打开「$TARGET_SLOT」", "启动「$TARGET_SLOT」"),
                noTarget = listOf("打开应用中…", "正在启动应用…"),
            ),
        "tool.intent" to ToolVariants(noTarget = listOf("交给系统处理…", "处理系统事务中…")),
        "tool.global_action" to
            ToolVariants(
                withTarget = listOf("${TARGET_SLOT}中…", "正在$TARGET_SLOT…"),
                noTarget = listOf("执行系统动作…"),
            ),
        "tool.restart_app" to ToolVariants(noTarget = listOf("重启应用中…", "正在重启应用…")),
        "tool.list_apps" to
            ToolVariants(
                withTarget = listOf("正在找「$TARGET_SLOT」"),
                noTarget = listOf("查找应用中…", "找找应用…"),
            ),
        "tool.get_device_state" to ToolVariants(noTarget = listOf("读取设备状态中…", "看看设备状态…")),
        "tool.get_device_state" to ToolVariants(noTarget = listOf("读取设备状态中…", "看看设备状态…")),
        "tool.clipboard_get" to ToolVariants(noTarget = listOf("读取剪贴板中…", "看看剪贴板…")),
        "tool.clipboard_set" to ToolVariants(noTarget = listOf("写入剪贴板中…", "存进剪贴板…")),
        "tool.read_notifications" to ToolVariants(noTarget = listOf("读取通知中…", "看看通知…")),
        "tool.ask_user" to ToolVariants(noTarget = listOf("向你提问…", "有问题想问你…")),
        "tool.note" to ToolVariants(noTarget = listOf("记个笔记…", "记录中…")),
        "tool.update_plan" to ToolVariants(noTarget = listOf("更新计划中…", "调整计划…")),
        "tool.finish" to ToolVariants(noTarget = listOf("收尾中…", "整理结果…")),
        "tool.history_recall" to ToolVariants(noTarget = listOf("翻翻历史…", "回顾一下…")),
        "tool.run_flow" to ToolVariants(noTarget = listOf("流程执行中…", "跑流程中…")),
        "tool.save_as_flow" to ToolVariants(noTarget = listOf("保存为流程中…")),
        "tool.patch_flow_node" to ToolVariants(noTarget = listOf("修改流程节点中…")),
        "tool.create_flow" to ToolVariants(noTarget = listOf("创建流程中…")),
        "tool.edit_flow" to ToolVariants(noTarget = listOf("编辑流程中…")),
        "tool.list_tasks" to ToolVariants(noTarget = listOf("看看任务…", "查看任务中…")),
        "tool.create_task" to ToolVariants(noTarget = listOf("创建任务中…", "新建任务…")),
        "tool.update_task" to ToolVariants(noTarget = listOf("更新任务中…", "调整任务…")),
        "tool.notify_user" to ToolVariants(noTarget = listOf("通知你…", "发个提醒…")),
    )

/**
 * 控制条标题：流程提示 > 状态短语（思考/准备/整理）> 工具变体 > 回落基础文案。
 * [previous] 为上一次显示的说法（降权），[roll] 取值 0..1（注入保证可测）。
 */
internal fun hintTitleOf(
    active: ActiveRunInfo,
    previous: String?,
    roll: Float,
): String {
    val flow = flowHintOf(active)
    if (flow != null) return flow
    val pool =
        when (active.state) {
            RunState.SAMPLING -> THINKING
            RunState.STARTING -> STARTING
            RunState.COMPACTING -> COMPACTING
            else -> toolPoolOf(active.toolId, active.toolTarget)
        }
    return pickVariant(pool, previous, roll) ?: active.currentStep ?: "运行中"
}

/** 工具 id 规范化：模型可见名（tap）与工具 id（tool.tap）都归一到工具 id。 */
private fun canonToolId(toolId: String): String = if (toolId.startsWith("tool.")) toolId else "tool.$toolId"

/** 工具变体池：有目标用带目标模板（填目标），没目标用无目标说法；未知工具回落“正在执行 xxx…”，无工具回落空池。 */
private fun toolPoolOf(
    toolId: String?,
    target: String?,
): List<String> {
    val id = toolId?.let { canonToolId(it) }
    val variants = id?.let { TOOLS[it] }
    val name = id?.removePrefix("tool.").orEmpty()
    return when {
        id == null -> emptyList()
        variants == null -> listOf("正在执行 $name…", "$name 进行中…")
        target.isNullOrBlank() || variants.withTarget.isEmpty() -> variants.noTarget
        else -> variants.withTarget.map { it.replace(TARGET_SLOT, target) }
    }
}

/**
 * 加权反重复抽取：上一次说法权重 [PREVIOUS_WEIGHT]、其余 [OTHER_WEIGHT]（概率恒更大）；
 * [roll] 取值 0..1 决定落点。空池返回 null。
 */
internal fun pickVariant(
    pool: List<String>,
    previous: String?,
    roll: Float,
): String? {
    if (pool.isEmpty()) return null
    val weights = pool.map { if (it == previous) PREVIOUS_WEIGHT else OTHER_WEIGHT }
    var rest = roll.coerceIn(0f, 1f) * weights.sum()
    var picked = pool.last()
    for (i in pool.indices) {
        rest -= weights[i]
        if (rest < 0f) {
            picked = pool[i]
            break
        }
    }
    return picked
}
