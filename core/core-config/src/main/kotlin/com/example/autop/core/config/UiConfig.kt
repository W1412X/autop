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
    /** 输出语言（`system` 跟随系统 / `zh` 中文 / `en` English）：决定助手面向用户的输出语言。 */
    @SerialName("language") val language: String = "system",
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
    /** 单条消息最多附带的图片数。 */
    @SerialName("attachMaxImages") val attachMaxImages: Int = 4,
    /** 附图压缩后的长边上限（px）：控制 token 与存储体积。 */
    @SerialName("attachMaxEdge") val attachMaxEdge: Int = 1280,
    /** 附图 JPEG 压缩质量（1–100）。 */
    @SerialName("attachJpegQuality") val attachJpegQuality: Int = 85,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (attachMaxImages !in ATTACH_IMAGES_MIN..ATTACH_IMAGES_MAX) {
                add("attachMaxImages 必须在 $ATTACH_IMAGES_MIN~$ATTACH_IMAGES_MAX 之间")
            }
            if (attachMaxEdge !in ATTACH_EDGE_MIN..ATTACH_EDGE_MAX) {
                add("attachMaxEdge 必须在 $ATTACH_EDGE_MIN~$ATTACH_EDGE_MAX 之间")
            }
            if (attachJpegQuality !in ATTACH_QUALITY_MIN..ATTACH_QUALITY_MAX) {
                add("attachJpegQuality 必须在 $ATTACH_QUALITY_MIN~$ATTACH_QUALITY_MAX 之间")
            }
        }
}

private const val ATTACH_IMAGES_MIN = 1
private const val ATTACH_IMAGES_MAX = 9
private const val ATTACH_EDGE_MIN = 256
private const val ATTACH_EDGE_MAX = 4096
private const val ATTACH_QUALITY_MIN = 30
private const val ATTACH_QUALITY_MAX = 100

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
    /** 悬浮球显示方式：auto=静置自动收拢（贴边→细条）；always=保持展开；edge=松手即收边细条。 */
    @SerialName("displayMode") val displayMode: String = "auto",
    /** 球体与贴边细条的透明度（1.0=完全不透明）。 */
    @SerialName("opacity") val opacity: Double = 1.0,
    @SerialName("edgeHideRatio") val edgeHideRatio: Double = 0.8,
    @SerialName("breathMs") val breathMs: Long = 1600,
    @SerialName("doubleTapStops") val doubleTapStops: Boolean = true,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (displayMode !in DISPLAY_MODES) {
                add("displayMode must be one of $DISPLAY_MODES, was '$displayMode'")
            }
            if (opacity !in OPACITY_MIN..OPACITY_MAX) {
                add("opacity must be in $OPACITY_MIN..$OPACITY_MAX, was $opacity")
            }
        }

    public companion object {
        /** 悬浮球显示方式取值。 */
        public val DISPLAY_MODES: Set<String> = setOf("auto", "always", "edge")
    }
}

/** 悬浮球透明度下限/上限。 */
private const val OPACITY_MIN: Double = 0.5
private const val OPACITY_MAX: Double = 1.0

/** `ui.indicator`：执行期状态指示（屏幕边缘光 + 顶部运行控制条；ADR-0014）。颜色取自主题，不在配置里。 */
@Serializable
public data class UiIndicatorSection(
    /** 总开关：关闭后执行期不显示任何指示。 */
    @SerialName("enabled") val enabled: Boolean = true,
    /** 屏幕边缘光。 */
    @SerialName("edgeGlow") val edgeGlow: Boolean = true,
    /** 顶部运行控制条（状态 + 操作；运行期隐藏悬浮球时的唯一常驻入口）。 */
    @SerialName("capsule") val capsule: Boolean = true,
    /** 呼吸周期（毫秒）；动效关闭时不呼吸。 */
    @SerialName("pulseMs") val pulseMs: Long = 1800,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (pulseMs !in INDICATOR_PULSE_MIN_MS..INDICATOR_PULSE_MAX_MS) {
                add("pulseMs must be in $INDICATOR_PULSE_MIN_MS..$INDICATOR_PULSE_MAX_MS, was $pulseMs")
            }
        }
}

/** `ui.indicator.pulseMs` 的取值范围（毫秒）。 */
private const val INDICATOR_PULSE_MIN_MS: Long = 300
private const val INDICATOR_PULSE_MAX_MS: Long = 10000

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
    public val INDICATOR: ConfigKey<UiIndicatorSection> =
        ConfigKey("ui.indicator", UiIndicatorSection.serializer()) { UiIndicatorSection() }
    public val MOTION: ConfigKey<UiMotionSection> =
        ConfigKey("ui.motion", UiMotionSection.serializer()) { UiMotionSection() }
    public val DEBUG: ConfigKey<UiDebugSection> =
        ConfigKey("ui.debug", UiDebugSection.serializer()) { UiDebugSection() }
    public val ONBOARDING: ConfigKey<UiOnboardingSection> =
        ConfigKey("ui.onboarding", UiOnboardingSection.serializer()) { UiOnboardingSection() }

    public val ALL: List<ConfigKey<*>> =
        listOf(APPEARANCE, NAV, CHAT, BANNER, MEMORY, OVERLAY, INDICATOR, MOTION, DEBUG, ONBOARDING)
}
