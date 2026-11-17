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
import com.example.autop.core.ui.component.ballface.BallFaceColor
import com.example.autop.core.ui.component.ballface.BallFaceDirector
import com.example.autop.core.ui.component.ballface.BallFaceVariant

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
    /** 调色盘自选主题色（ARGB，0 = 未启用；非 0 时优先于 [seedIndex]）。 */
    public val customSeed: Long = 0,
    public val fontScale: Float = 1f,
    public val iconScale: Float = 1f,
    public val density: UiDensity = UiDensity.STANDARD,
    public val motionEnabled: Boolean = true,
    /** 球体形态（悬浮球、控制条、卡片、横幅统一读取）。 */
    public val ballShape: BallFaceVariant = BallFaceVariant.SOLID,
    /** 悬浮球颜色：跟随主题色或固定黑、白（ADR-0045）。 */
    public val ballColor: BallFaceColor = BallFaceColor.THEME,
    /** 表情换脸间隔（毫秒，ADR-0045）。 */
    public val petRerollMs: Long = BallFaceDirector.THINK_REROLL_MS,
    /** 表情最短驻留（毫秒，ADR-0045）：0 表示不驻留。 */
    public val petFaceMinHoldMs: Long = BallFaceDirector.MIN_HOLD_MS,
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
        customSeed = if (customSeed in CUSTOM_SEED_MIN..CUSTOM_SEED_MAX) customSeed else 0,
        fontScale = fontScale.toFloat(),
        iconScale = iconScale.toFloat(),
        density = UiDensity.entries.firstOrNull { it.name == density } ?: UiDensity.STANDARD,
        motionEnabled = motionEnabled && systemAnimationsEnabled,
        ballShape = BallFaceVariant.entries.firstOrNull { it.name == ballShape } ?: BallFaceVariant.SOLID,
        ballColor = BallFaceColor.entries.firstOrNull { it.name == ballColor } ?: BallFaceColor.THEME,
        petRerollMs = petRerollMs,
        petFaceMinHoldMs = petFaceMinHoldMs,
    )

/** 动态取色最低 API（Android 12 / S）。 */
private const val DYNAMIC_COLOR_MIN_SDK: Int = 31

/** 自定义主题色的合法 ARGB 区间（0 表示未启用，不在此区间内视为无效）。 */
private const val CUSTOM_SEED_MIN: Long = 0xFF000000L
private const val CUSTOM_SEED_MAX: Long = 0xFFFFFFFFL

/**
 * 是否使用动态取色（Monet）色板。
 *
 * 动态取色仅 Android 12+ 可用（docs/tech/08 §1.1）：app 根部已按 `Capability.DYNAMIC_COLOR`
 * 把 [AppearanceSettings.dynamicColor] 闸门在支持的设备上；`@ChecksSdkIntAtLeast`
 * 同时给 lint 提供 API 级别证明（core-ui 不允许直接读 `Build.VERSION.SDK_INT`）。
 */
@ChecksSdkIntAtLeast(api = DYNAMIC_COLOR_MIN_SDK)
public fun AppearanceSettings.usesDynamicColor(): Boolean = dynamicColor
