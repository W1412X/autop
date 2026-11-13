package com.example.autop.core.platform

import android.os.Build
import com.example.autop.core.common.Capability
import com.example.autop.core.common.PlatformCapabilities

/**
 * [PlatformCapabilities] 的 Android 实现（docs/tech/02 §4.1）。
 *
 * `Build.VERSION.SDK_INT` 全工程只允许出现在本类与 device-impl 中；
 * [sdkInt] 供同模块的 DeviceQuirksImpl 做 minSdk 匹配，避免再次读取 SDK_INT。
 * 测试注入 [sdkInt] 验证各能力的版本区间。
 */
public class PlatformCapabilitiesImpl(
    public val sdkInt: Int = Build.VERSION.SDK_INT,
) : PlatformCapabilities {
    override fun has(c: Capability): Boolean =
        when (c) {
            Capability.ACCESSIBILITY_SCREENSHOT -> sdkInt >= SDK_R
            Capability.IME_ENTER_ACTION -> sdkInt >= SDK_R
            Capability.NOTIFICATION_RUNTIME_PERMISSION -> sdkInt >= SDK_T
            Capability.EXACT_ALARM_NEEDS_GRANT -> sdkInt in SDK_S..SDK_S_V2
            Capability.FULL_SCREEN_INTENT_NEEDS_GRANT -> sdkInt >= SDK_U
            Capability.NOTIFICATION_AUTH_REQUIRED_ACTION -> sdkInt >= SDK_S
            Capability.RESTRICTED_SETTINGS -> sdkInt >= SDK_T
            Capability.ACCESSIBILITY_DATA_SENSITIVE -> sdkInt >= SDK_U
            Capability.DYNAMIC_COLOR -> sdkInt >= SDK_S
            Capability.MUTABLE_PENDING_INTENT_FOR_REMOTE_INPUT -> sdkInt >= SDK_S
        }

    private companion object {
        const val SDK_R = 30
        const val SDK_S = 31
        const val SDK_S_V2 = 32
        const val SDK_T = 33
        const val SDK_U = 34
    }
}
