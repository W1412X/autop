/**
 * 触摸盾纯逻辑单测（docs/tech/05 §8，N186）：拦截条件
 * （执行态 ∧ 盾开关 ∧ 无强制解除 ∧ 无进行中注入动作 ∧ 悬浮球开启）与注入放行。
 */
package com.example.autop.device.impl.touch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [shieldTouchable] 矩阵。 */
public class TouchShieldLogicTest {
    @Test
    public fun `active run without action intercepts`() {
        assertTrue(shield(active = true))
    }

    @Test
    public fun `gesture injection suspends interception until action ends`() {
        assertFalse(shield(active = true, actionDepth = 1))
        assertTrue(shield(active = true, actionDepth = 0))
    }

    @Test
    public fun `paused or finished run does not intercept`() {
        assertFalse(shield(active = false))
    }

    @Test
    public fun `overlay disabled keeps shield off as safety valve`() {
        assertFalse(shield(active = true, overlayEnabled = false))
    }

    @Test
    public fun `force release suppresses interception until reset`() {
        // N191（安全关键）：用户中止后即使 Run 状态仍屏蔽也不得拦截，reset 后恢复。
        assertFalse(shield(active = true, forceReleased = true))
        assertTrue(shield(active = true, forceReleased = false))
    }

    @Test
    public fun `shield switch off disables interception while running`() {
        // N215：设置页关闭触摸盾后，运行期不再拦截用户触摸。
        assertFalse(shield(active = true, shieldEnabled = false))
    }

    /** 判定包装：默认执行态、无进行中动作、悬浮球开启、未强制解除、盾开关开。 */
    private fun shield(
        active: Boolean,
        actionDepth: Int = 0,
        overlayEnabled: Boolean = true,
        forceReleased: Boolean = false,
        shieldEnabled: Boolean = true,
    ): Boolean = shieldTouchable(active, actionDepth, overlayEnabled, forceReleased, shieldEnabled)
}
