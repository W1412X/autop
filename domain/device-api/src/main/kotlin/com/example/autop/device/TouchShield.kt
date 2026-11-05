/**
 * 触摸盾端口（docs/tech/05 §8，N186）：Run 执行期屏蔽用户屏幕触摸（点击/滑动/长按
 * 对屏幕不生效），悬浮球与浮层窗口是独立窗口，在盾之上照常交互。
 * 工具注入手势的放行由 device-impl 内部协作（GestureControllerImpl → TouchShieldController），
 * 不经本端口。
 */
package com.example.autop.device

/** 触摸盾。 */
public interface TouchShield {
    /** 设置屏幕触摸屏蔽的开（Run 执行态）/关（暂停、等待回答、结束后）。 */
    public fun setActive(active: Boolean)

    /**
     * 强制解除屏蔽并抑制后续激活（N191，安全关键）：用户中止任务后必须能操作手机，
     * 即使 Run 卡死未收口也不得继续屏蔽；新 Run 开始时 [reset] 恢复正常驱动。
     */
    public fun forceRelease()

    /** 清除 [forceRelease] 的抑制（新 Run 开始时调用）。 */
    public fun reset()
}
