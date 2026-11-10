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
    ): InjectionResult

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
    ): InjectionResult

    /** 当前焦点输入框（docs/design/observation-modes-coordinate-actions §5.3）；没有返回 null。 */
    public suspend fun focusedInput(): ResolvedNode?

    /** 全局动作。 */
    public suspend fun globalAction(action: GlobalAction): InjectionResult

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

/** 注入通道（实际把事件送进系统的路径）。 */
public enum class InjectionChannel {
    /** 没有注入成功。 */
    NONE,

    /** 无线调试 shell 的 `input` 注入：事件与真手触摸无差别，应用无法识别。 */
    SHELL,

    /** 无障碍服务注入（dispatchGesture / performGlobalAction）：部分应用会识别并丢弃。 */
    ACCESSIBILITY,
}

/** shell 通道未被使用的原因。 */
public enum class ShellSkip {
    /** 通道未连接（未配对/断开）。 */
    NOT_CONNECTED,

    /** 通道已连接但命令失败。 */
    COMMAND_FAILED,
}

/**
 * 注入结果：是否注入成功 + 实际通道。
 * 工具据此告诉模型“这次到底是怎么执行的”，不要在回退后仍声称走了原通道。
 */
public data class InjectionResult(
    val ok: Boolean,
    val channel: InjectionChannel,
    /** channel=ACCESSIBILITY 时，没走 shell 的原因；首选就是无障碍的动作为 null。 */
    val shellSkip: ShellSkip? = null,
) {
    public companion object {
        /** 注入失败（没有任何通道送达）。 */
        public val FAILED: InjectionResult = InjectionResult(ok = false, channel = InjectionChannel.NONE)

        /** shell 通道结果。 */
        public fun shell(ok: Boolean): InjectionResult =
            InjectionResult(ok = ok, channel = if (ok) InjectionChannel.SHELL else InjectionChannel.NONE)

        /** 无障碍通道结果。 */
        public fun accessibility(
            ok: Boolean,
            shellSkip: ShellSkip?,
        ): InjectionResult =
            InjectionResult(
                ok = ok,
                channel = if (ok) InjectionChannel.ACCESSIBILITY else InjectionChannel.NONE,
                shellSkip = shellSkip,
            )
    }
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
