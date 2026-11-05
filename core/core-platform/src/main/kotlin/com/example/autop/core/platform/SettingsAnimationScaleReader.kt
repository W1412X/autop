package com.example.autop.core.platform

import android.content.Context
import android.provider.Settings
import com.example.autop.core.common.AnimationScaleReader

/**
 * [AnimationScaleReader] 的 Android 实现（docs/tech/08 §1.5）：
 * 读 `Settings.Global.ANIMATOR_DURATION_SCALE`，≠ 0 视为系统动画启用；
 * 读取失败按默认值 1（启用）处理，不抛异常。
 */
public class SettingsAnimationScaleReader(
    private val context: Context,
) : AnimationScaleReader {
    override fun isEnabled(): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            DEFAULT_SCALE,
        ) != 0f

    private companion object {
        const val DEFAULT_SCALE: Float = 1f
    }
}
