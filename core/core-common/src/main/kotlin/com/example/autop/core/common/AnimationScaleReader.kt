package com.example.autop.core.common

/**
 * 系统动画缩放查询端口（docs/tech/08 §1.5）：
 * `Settings.Global.ANIMATOR_DURATION_SCALE ≠ 0` 时系统动画启用。
 * 读取实现在 core-platform；UI 动效开关 = 用户设置 && 本值。
 */
public interface AnimationScaleReader {
    /** 系统动画是否启用。实现不得抛异常，读取失败按启用处理。 */
    public fun isEnabled(): Boolean
}
