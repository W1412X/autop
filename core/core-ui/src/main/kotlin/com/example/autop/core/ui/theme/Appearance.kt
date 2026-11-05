/**
 * 外观设置（docs/tech/08 §1.5）：`ui.appearance` 配置段的 UI 侧表示。
 *
 * 配置段是持久化数据（String/Double 字段），本文件是主题消费的类型化视图；
 * 由 [toAppearanceSettings] 在 app 根部一次性映射。
 */
package com.example.autop.core.ui.theme

import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.runtime.Immutable
import com.example.autop.core.config.UiAppearanceSection

/** 深色模式选项。 */
public enum class DarkMode {
    /** 跟随系统。 */
    SYSTEM,

    /** 浅色。 */
    LIGHT,

    /** 深色。 */
    DARK,
}

/** 布局密度选项。 */
public enum class UiDensity {
    /** 紧凑。 */
    COMPACT,

    /** 标准。 */
    STANDARD,

    /** 宽松。 */
    COMFORTABLE,
}

/** 类型化外观设置（[AutopTheme] 入参）。 */
@Immutable
public data class AppearanceSettings(
    public val darkMode: DarkMode = DarkMode.SYSTEM,
    public val dynamicColor: Boolean = false,
    public val seedIndex: Int = 0,
    public val fontScale: Float = 1f,
    public val iconScale: Float = 1f,
    public val density: UiDensity = UiDensity.STANDARD,
    public val motionEnabled: Boolean = true,
)

/**
 * `ui.appearance` 配置段 → [AppearanceSettings]。
 *
 * @param dynamicColorSupported 设备是否支持动态取色（Android 12+，app 根部按能力判定）。
 * @param systemAnimationsEnabled 系统动画缩放是否 ≠ 0（core-platform 读取）。
 */
public fun UiAppearanceSection.toAppearanceSettings(
    dynamicColorSupported: Boolean = false,
    systemAnimationsEnabled: Boolean = true,
): AppearanceSettings =
    AppearanceSettings(
        darkMode = DarkMode.entries.firstOrNull { it.name == darkMode } ?: DarkMode.SYSTEM,
        dynamicColor = dynamicColor && dynamicColorSupported,
        seedIndex = seedIndex.coerceIn(0, SeedColors.COUNT - 1),
        fontScale = fontScale.toFloat(),
        iconScale = iconScale.toFloat(),
        density = UiDensity.entries.firstOrNull { it.name == density } ?: UiDensity.STANDARD,
        motionEnabled = motionEnabled && systemAnimationsEnabled,
    )

/** 动态取色最低 API（Android 12 / S）。 */
private const val DYNAMIC_COLOR_MIN_SDK: Int = 31

/**
 * 是否使用动态取色（Monet）色板。
 *
 * 动态取色仅 Android 12+ 可用（docs/tech/08 §1.1）：app 根部已按 `Capability.DYNAMIC_COLOR`
 * 把 [AppearanceSettings.dynamicColor] 闸门在支持的设备上；`@ChecksSdkIntAtLeast`
 * 同时给 lint 提供 API 级别证明（core-ui 不允许直接读 `Build.VERSION.SDK_INT`）。
 */
@ChecksSdkIntAtLeast(api = DYNAMIC_COLOR_MIN_SDK)
public fun AppearanceSettings.usesDynamicColor(): Boolean = dynamicColor
