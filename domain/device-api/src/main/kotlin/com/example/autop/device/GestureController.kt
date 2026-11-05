/**
 * 动作执行端口（docs/tech/05 §1.3）：节点动作、手势与全局动作。
 */
package com.example.autop.device

/** 动作执行。 */
public interface GestureController {
    /** 对目标节点执行无障碍动作（如 ACTION_CLICK）。 */
    public suspend fun performNodeAction(
        target: ResolvedNode,
        actionId: Int,
        args: Map<String, Any?> = emptyMap(),
    ): Boolean

    /** 点击（或长按）坐标点；落点抖动由工具计算后传入（§5.2）。 */
    public suspend fun tap(
        x: Int,
        y: Int,
        durationMs: Long = 0,
    ): Boolean

    /** 滑动手势。
     * @param jitterPx 无障碍手势通道的轨迹抖动半径（像素，工具按配置内部计算）
     */
    public suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long = 300,
        jitterPx: Int = 0,
    ): Boolean

    /** 当前焦点输入框（docs/design/observation-modes-coordinate-actions §5.3）；没有返回 null。 */
    public suspend fun focusedInput(): ResolvedNode?

    /** 全局动作。 */
    public suspend fun globalAction(action: GlobalAction): Boolean
}

/** 全局动作（映射 AccessibilityService.GLOBAL_ACTION_*）。 */
public enum class GlobalAction {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    LOCK_SCREEN,
}

/** 由 impl 根据定位器在最新快照中找到的节点；impl 内部持有对应的真实节点。 */
public interface ResolvedNode {
    public val snapshot: NodeSnapshot

    public val windowId: Int

    /** 实时读取当前文本（append 拼接用）；读不到返回 null 回退快照文本。 */
    public fun currentText(): String? = null

    /** 对真实节点执行无障碍动作；节点失效返回 false。 */
    public fun performAction(
        actionId: Int,
        args: Map<String, Any?> = emptyMap(),
    ): Boolean
}
