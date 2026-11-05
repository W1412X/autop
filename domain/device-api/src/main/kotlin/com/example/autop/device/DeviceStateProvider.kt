/**
 * 设备状态端口（docs/tech/05 §1.3）：电量、网络、锁屏与前台应用。
 */
package com.example.autop.device

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 设备状态读取。 */
public interface DeviceStateProvider {
    /** 当前状态快照。 */
    public fun current(): DeviceState

    /** 状态变化流。 */
    public val changes: Flow<DeviceState>
}

/** 设备状态。 */
@Serializable
public data class DeviceState(
    @SerialName("batteryPercent") val batteryPercent: Int?,
    @SerialName("charging") val charging: Boolean,
    /** wifi|cellular|none|other。 */
    @SerialName("networkType") val networkType: String,
    @SerialName("screenOn") val screenOn: Boolean,
    @SerialName("keyguardLocked") val keyguardLocked: Boolean,
    @SerialName("foregroundPackage") val foregroundPackage: String?,
)
