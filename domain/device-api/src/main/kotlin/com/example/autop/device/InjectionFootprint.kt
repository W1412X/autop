/**
 * 注入占位（docs/tech/05 §8.1，N222）：一次注入动作会受哪些自家悬浮窗影响。
 *
 * 注入期间浮层让路不再一律封锁全部窗口，而是按动作类型判定冲突：
 * - 手势（点击/长按/滑动）只与**落点所在窗口**冲突——触摸的窗口归属在 DOWN 时确定，
 *   落点之外、滑动途中经过的窗口都吞不掉这次注入；
 * - 按键类动作（返回/主屏/最近任务/输入法回车等）只与**可聚焦窗口**冲突——
 *   按键只会送到焦点窗口，不可聚焦的悬浮窗收不到；
 * - [All] 是保守兜底（无法给出具体占位时）。
 *
 * 不冲突的窗口保持可见可触摸，避免每次注入都让整屏浮层闪一下。
 */
package com.example.autop.device

/** 注入占位。 */
public sealed interface InjectionFootprint {
    /**
     * 单点手势落点（像素，屏幕坐标）：只有包含该点的可触摸窗口会让路。
     * [marginPx] 为落点容差（坐标误差/窗口抖动），实现侧另加固定安全边距。
     */
    public data class TouchPoint(
        val x: Int,
        val y: Int,
        val marginPx: Int = 0,
    ) : InjectionFootprint

    /** 按键注入：只有可聚焦（可能抢焦点/输入法/返回键）的窗口会让路。 */
    public data object Focus : InjectionFootprint

    /** 全量占位：全部可触摸窗口让路（保守兜底）。 */
    public data object All : InjectionFootprint
}
