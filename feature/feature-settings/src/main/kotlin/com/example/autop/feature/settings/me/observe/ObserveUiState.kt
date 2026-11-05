/**
 * 观测方式子页状态（docs/design/observation-modes-coordinate-actions §2.3）：
 * `device.observe.priority` 的顺序与各模式开关，以及各模式当前的能力可用性。
 */
package com.example.autop.feature.settings.me.observe

/** 单个观测模式的当前配置。 */
public data class ObserveModeItem(
    val id: String,
    val enabled: Boolean,
    /** 不可用原因（能力不足，如未连接无障碍服务）；可用为 null。 */
    val unavailableReason: String? = null,
)

/** 观测方式子页状态。 */
public data class ObserveUiState(
    val items: List<ObserveModeItem> = emptyList(),
)
