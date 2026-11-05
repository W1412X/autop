/**
 * shell 注入通道端口（N199）：经无线调试 ADB 以 shell 身份注入输入事件。
 *
 * 无障碍注入（dispatchGesture）的事件带 `FLAG_IS_ACCESSIBILITY_EVENT`，应用可识别后丢弃
 * （微信等反自动化手段）；shell 注入走系统 InputManager，事件与真手触摸无差别，应用无法屏蔽。
 * 通道未连接（未开无线调试/未配对）时调用方回落无障碍注入。
 */
package com.example.autop.device

import com.example.autop.core.common.Outcome
import kotlinx.coroutines.flow.StateFlow

/** shell 注入通道状态。 */
public sealed interface ShellState {
    /** 未连接（无线调试未开启、未配对或连接被断开）。 */
    public data object Disconnected : ShellState

    /** 正在发现端口/建立连接。 */
    public data object Connecting : ShellState

    /** 已连接 adbd，注入可用。 */
    public data object Connected : ShellState
}

/** shell 注入通道管理与注入。 */
public interface ShellInput {
    public val state: StateFlow<ShellState>

    /** 是否已完成配对（持久化）；后台保活（[ShellKeepAlive]）据此决定是否维持连接。 */
    public val paired: StateFlow<Boolean>

    /**
     * 用无线调试的配对码完成配对（一次性，系统设置「使用配对码配对设备」弹窗里的
     * 端口与 6 位码）。成功后自动尝试连接注入通道。
     */
    public suspend fun pair(
        pairingPort: Int,
        pairCode: String,
    ): Outcome<Unit>

    /** 重新发现端口并尝试连接（无线调试开启后调用）。 */
    public suspend fun connect(): Boolean

    /** 坐标点击（屏幕像素）。 */
    public suspend fun tap(
        x: Int,
        y: Int,
        durationMs: Long,
    ): Boolean

    /** 滑动（屏幕像素）。 */
    public suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean

    /** 注入按键（KeyEvent.KEYCODE_*）。 */
    public suspend fun keyEvent(code: Int): Boolean

    /** 注入组合键（KeyEvent.KEYCODE_*，如 Ctrl+A 全选）。 */
    public suspend fun keyCombination(vararg codes: Int): Boolean
}
