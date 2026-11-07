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

    /**
     * 对快照里选中的节点执行无障碍动作：按 [windowId] 与根到节点的子序号路径 [path] 在实时树里定位，
     * 只做过期校验（类名与 [expectedBounds] 必须与快照一致），不做别的匹配。不走输入管道。
     */
    public suspend fun performAtPath(
        windowId: Int,
        path: List<Int>,
        expectedBounds: Rect,
        actionId: Int,
    ): NodeActionOutcome

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

    /**
     * shell 注入按键（粘贴/回车等输入管道按键）：同手势通道做盾放行＋自身动作标记；
     * shell 通道未连接返回 false。
     */
    public suspend fun shellKeyEvent(code: Int): Boolean

    /** shell 注入组合键（如 Ctrl+A）；shell 通道未连接返回 false。 */
    public suspend fun shellKeyCombination(codes: List<Int>): Boolean
}

/** [GestureController.performAtPath] 的结果。 */
public enum class NodeActionOutcome {
    /** 应用接受了动作（返回 true）；不代表界面一定变化。 */
    DONE,

    /** 节点存在但应用拒绝了动作（返回 false）；动作没有执行。 */
    REJECTED,

    /** 实时树里找不到对应节点（界面已变化或窗口消失）；动作没有执行。 */
    STALE,
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
