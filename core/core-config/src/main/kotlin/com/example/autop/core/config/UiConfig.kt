/**
 * `ui` 领域的配置段与键（docs/tech/09 §1.7）。
 * 段默认值与 `core-config-impl/assets/config/ui.json` 必须一致（有测试）。
 */
package com.example.autop.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `ui.appearance`：外观。 */
@Serializable
public data class UiAppearanceSection(
    @SerialName("darkMode") val darkMode: String = "SYSTEM",
    @SerialName("dynamicColor") val dynamicColor: Boolean = false,
    @SerialName("seedIndex") val seedIndex: Int = 0,
    @SerialName("fontScale") val fontScale: Double = 1.0,
    @SerialName("iconScale") val iconScale: Double = 1.0,
    @SerialName("density") val density: String = "STANDARD",
    @SerialName("motionEnabled") val motionEnabled: Boolean = true,
) : ConfigSection

/** `ui.nav`：底部导航。 */
@Serializable
public data class UiNavSection(
    @SerialName("showLabels") val showLabels: Boolean = false,
) : ConfigSection

/** `ui.chat`：对话页。 */
@Serializable
public data class UiChatSection(
    @SerialName("inputMaxLines") val inputMaxLines: Int = 6,
    @SerialName("stepGroupThreshold") val stepGroupThreshold: Int = 3,
    /** 发送后让出屏幕（N174：默认关——任务不一定需要操作手机，发送后不把用户赶出应用）。 */
    @SerialName("backgroundOnSend") val backgroundOnSend: Boolean = false,
    /** 新会话标题按首条消息自动生成（LLM 精炼失败时用消息截断占位）。 */
    @SerialName("autoTitle") val autoTitle: Boolean = true,
) : ConfigSection

/** `ui.banner`：顶部横幅提示。 */
@Serializable
public data class UiBannerSection(
    @SerialName("durationMs") val durationMs: Long = 3000,
) : ConfigSection

/** `ui.memory`：记忆页。 */
@Serializable
public data class UiMemorySection(
    @SerialName("searchDebounceMs") val searchDebounceMs: Long = 300,
) : ConfigSection

/** `ui.overlay`：悬浮球（05 §10）。 */
@Serializable
public data class UiOverlaySection(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("windowType") val windowType: String = "auto",
    /** 贴边隐藏比例（N217：收边只露一小点，点按露头全部唤出）。 */
    @SerialName("edgeHideRatio") val edgeHideRatio: Double = 0.8,
    @SerialName("breathMs") val breathMs: Long = 1600,
    @SerialName("doubleTapStops") val doubleTapStops: Boolean = true,
    @SerialName("attachScreenDefault") val attachScreenDefault: Boolean = true,
) : ConfigSection

/** `ui.motion`：动效时长。 */
@Serializable
public data class UiMotionSection(
    @SerialName("expandMs") val expandMs: Long = 200,
    @SerialName("shimmerMs") val shimmerMs: Long = 900,
) : ConfigSection

/** `ui.debug`：调试日志。 */
@Serializable
public data class UiDebugSection(
    @SerialName("logBufferSize") val logBufferSize: Int = 2000,
    /** debug 日志开关：true 时任何构建都输出 d 级日志（questions.md N165）。 */
    @SerialName("verboseLogging") val verboseLogging: Boolean = false,
) : ConfigSection

/** `ui.onboarding`：引导与健康检查（WP11）。 */
@Serializable
public data class UiOnboardingSection(
    @SerialName("completed") val completed: Boolean = false,
) : ConfigSection

/** `ui` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object UiConfig {
    public val APPEARANCE: ConfigKey<UiAppearanceSection> =
        ConfigKey("ui.appearance", UiAppearanceSection.serializer()) { UiAppearanceSection() }
    public val NAV: ConfigKey<UiNavSection> =
        ConfigKey("ui.nav", UiNavSection.serializer()) { UiNavSection() }
    public val CHAT: ConfigKey<UiChatSection> =
        ConfigKey("ui.chat", UiChatSection.serializer()) { UiChatSection() }
    public val BANNER: ConfigKey<UiBannerSection> =
        ConfigKey("ui.banner", UiBannerSection.serializer()) { UiBannerSection() }
    public val MEMORY: ConfigKey<UiMemorySection> =
        ConfigKey("ui.memory", UiMemorySection.serializer()) { UiMemorySection() }
    public val OVERLAY: ConfigKey<UiOverlaySection> =
        ConfigKey("ui.overlay", UiOverlaySection.serializer()) { UiOverlaySection() }
    public val MOTION: ConfigKey<UiMotionSection> =
        ConfigKey("ui.motion", UiMotionSection.serializer()) { UiMotionSection() }
    public val DEBUG: ConfigKey<UiDebugSection> =
        ConfigKey("ui.debug", UiDebugSection.serializer()) { UiDebugSection() }
    public val ONBOARDING: ConfigKey<UiOnboardingSection> =
        ConfigKey("ui.onboarding", UiOnboardingSection.serializer()) { UiOnboardingSection() }

    public val ALL: List<ConfigKey<*>> =
        listOf(APPEARANCE, NAV, CHAT, BANNER, MEMORY, OVERLAY, MOTION, DEBUG, ONBOARDING)
}
