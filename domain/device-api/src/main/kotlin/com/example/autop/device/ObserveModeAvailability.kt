/**
 * 观测模式的能力可用性（docs/design/observation-modes-coordinate-actions §2.3）：
 * 设置页展示各模式当前能否用；与用户自己的开关无关。
 */
package com.example.autop.device

/** 观测模式可用性。 */
public interface ObserveModeAvailability {
    /** [mode] 不可用的原因；可用返回 null。 */
    public fun unavailableReason(mode: ObserveMode): String?
}
