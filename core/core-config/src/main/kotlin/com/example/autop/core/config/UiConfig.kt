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
    /** 调色盘选的自定义主题色（ARGB，0 = 未启用；非 0 时优先于 [seedIndex]）。 */
    @SerialName("customSeed") val customSeed: Long = 0,
    @SerialName("fontScale") val fontScale: Double = 1.0,
    @SerialName("iconScale") val iconScale: Double = 1.0,
    @SerialName("density") val density: String = "STANDARD",
    @SerialName("motionEnabled") val motionEnabled: Boolean = true,
    /** 悬浮球与全部表情的球体形态：`SOLID` 实心圆（默认）/ `LINE` 线条开口环。 */
    @SerialName("ballShape") val ballShape: String = "SOLID",
    /** 悬浮球颜色：`THEME` 跟随主题色（默认）/ `BLACK` 固定黑 / `WHITE` 固定白。 */
    @SerialName("ballColor") val ballColor: String = "THEME",
    /** 表情最短驻留（毫秒，0 = 不驻留）：表情切换后至少停留这么久；等待回答、拦截、断开立即显示。 */
    @SerialName("petFaceMinHoldMs") val petFaceMinHoldMs: Long = 2500,
    /** 多会话混合池与思考段的换脸间隔（毫秒）。 */
    @SerialName("petRerollMs") val petRerollMs: Long = 2500,
    /** 运行中是否显示“新建会话”“打开应用”（false 时只在没有运行会话时显示）。 */
    @SerialName("petMenuActionsWhileRunning") val petMenuActionsWhileRunning: Boolean = true,
    /** 输出语言（`system` 跟随系统 / `zh` 中文 / `en` English）：决定助手面向用户的输出语言。 */
    @SerialName("language") val language: String = "system",
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (ballShape !in BALL_SHAPES) add("ballShape must be one of $BALL_SHAPES, was '$ballShape'")
            if (ballColor !in BALL_COLORS) add("ballColor must be one of $BALL_COLORS, was '$ballColor'")
            if (petFaceMinHoldMs !in 0L..PET_HOLD_MAX_MS) {
                add("petFaceMinHoldMs must be in 0..$PET_HOLD_MAX_MS, was $petFaceMinHoldMs")
            }
            if (petRerollMs !in PET_REROLL_MIN_MS..PET_REROLL_MAX_MS) {
                add("petRerollMs must be in $PET_REROLL_MIN_MS..$PET_REROLL_MAX_MS, was $petRerollMs")
            }
        }

    public companion object {
        /** 球体形态取值。 */
        public val BALL_SHAPES: Set<String> = setOf("SOLID", "LINE")

        /** 悬浮球颜色取值。 */
        public val BALL_COLORS: Set<String> = setOf("THEME", "BLACK", "WHITE")
    }
}

/** `ui.appearance.petFaceMinHoldMs` 上限（毫秒）。 */
private const val PET_HOLD_MAX_MS: Long = 10000L

/** `ui.appearance.petRerollMs` 取值范围（毫秒）。 */
private const val PET_REROLL_MIN_MS: Long = 500L
private const val PET_REROLL_MAX_MS: Long = 10000L

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

/** `ui.banner`：一次性提示（Toast）的显示时长。 */
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
    @SerialName("windowType") val windowType: String = "auto",
    /** 悬浮球显示方式：auto=静置自动收拢（贴边→细条）；always=保持展开；edge=松手即收边细条。 */
    @SerialName("displayMode") val displayMode: String = "auto",
    /** 球体与贴边细条的透明度（1.0=完全不透明）。 */
    @SerialName("opacity") val opacity: Double = 1.0,
    @SerialName("edgeHideRatio") val edgeHideRatio: Double = 0.8,
    @SerialName("breathMs") val breathMs: Long = 1600,
    @SerialName("doubleTapStops") val doubleTapStops: Boolean = true,
    /** 悬浮球直径（dp）：窗口、表情、细条、菜单半径与面板定位都按它等比推出。 */
    @SerialName("ballSizeDp") val ballSizeDp: Int = 44,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (displayMode !in DISPLAY_MODES) {
                add("displayMode must be one of $DISPLAY_MODES, was '$displayMode'")
            }
            if (opacity !in OPACITY_MIN..OPACITY_MAX) {
                add("opacity must be in $OPACITY_MIN..$OPACITY_MAX, was $opacity")
            }
            if (ballSizeDp !in BALL_SIZE_MIN_DP..BALL_SIZE_MAX_DP) {
                add("ballSizeDp must be in $BALL_SIZE_MIN_DP..$BALL_SIZE_MAX_DP, was $ballSizeDp")
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

/** 悬浮球直径取值范围（dp，`ui.overlay.ballSizeDp`）。 */
private const val BALL_SIZE_MIN_DP: Int = 32
private const val BALL_SIZE_MAX_DP: Int = 72

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

/**
 * `ui.ballAmbient`：悬浮球环境动画（睡眠、充电、低电量、手势反应、晨间问候）。
 * 阈值与时长见设计预览（ambient-animations-preview）。
 */
@Serializable
public data class UiBallAmbientSection(
    /** 夜间开始的小时（0–23）。夜间时段内球处于睡眠。 */
    @SerialName("nightStartHour") val nightStartHour: Int = 23,
    /** 夜间结束的小时（不含）。区间跨午夜时为 [nightStartHour, 24) 与 [0, nightEndHour)。 */
    @SerialName("nightEndHour") val nightEndHour: Int = 6,
    /** 低电量阈值（%）：低于它且未充电时球显得疲倦。 */
    @SerialName("lowBatteryPercent") val lowBatteryPercent: Int = 20,
    /** 晨间问候时段开始（含，小时）。 */
    @SerialName("morningStartHour") val morningStartHour: Int = 6,
    /** 晨间问候时段结束（不含，小时）。时段内每天第一次触摸播放一次哈欠与招手。 */
    @SerialName("morningEndHour") val morningEndHour: Int = 9,
    /** 点按反应时长（毫秒）。 */
    @SerialName("tapMs") val tapMs: Long = 900,
    /** 长按反应时长（毫秒）。 */
    @SerialName("longMs") val longMs: Long = 1500,
    /** 甩到边缘的惊讶时长（毫秒）。 */
    @SerialName("flingMs") val flingMs: Long = 1000,
    /** 从细条唤出的招手时长（毫秒）。 */
    @SerialName("revealMs") val revealMs: Long = 1000,
    /** 睡眠中被触摸后的唤醒时长（毫秒）：平稳睁眼，不眨眼、不快转。 */
    @SerialName("wakeMs") val wakeMs: Long = 800,
    /** 晨间哈欠时长（毫秒）。 */
    @SerialName("yawnMs") val yawnMs: Long = 1500,
    /** 晨间招手时长（毫秒）。 */
    @SerialName("greetMs") val greetMs: Long = 1200,
    /** 甩出判定速度（px/ms）：松手时拖动的平均速度不低于它即视为甩到边缘。 */
    @SerialName("flingPxPerMs") val flingPxPerMs: Double = 1.0,
    /** 环境状态（睡眠、充电、电量、网络）重算间隔（秒）。 */
    @SerialName("tickSec") val tickSec: Long = 60,
    /** 睡眠时同时飘动的 z 个数（1 到 4）。 */
    @SerialName("zCount") val zCount: Int = 2,
    /** 睡眠时相邻 z 出现的间隔（毫秒，1000 到 5000）。 */
    @SerialName("zIntervalMs") val zIntervalMs: Long = 2000,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (nightStartHour !in 0..HOUR_MAX) add("nightStartHour must be in 0..$HOUR_MAX, was $nightStartHour")
            if (nightEndHour !in 0..HOUR_MAX) add("nightEndHour must be in 0..$HOUR_MAX, was $nightEndHour")
            if (lowBatteryPercent !in 0..PERCENT_MAX) add("lowBatteryPercent must be in 0..$PERCENT_MAX")
            if (morningStartHour !in 0..HOUR_MAX ||
                morningEndHour !in 0..HOUR_MAX ||
                morningStartHour >= morningEndHour
            ) {
                add("morning hours must satisfy 0 <= morningStartHour < morningEndHour <= $HOUR_MAX")
            }
            listOf(
                "tapMs" to tapMs,
                "longMs" to longMs,
                "flingMs" to flingMs,
                "revealMs" to revealMs,
                "wakeMs" to wakeMs,
                "yawnMs" to yawnMs,
                "greetMs" to greetMs,
            ).forEach { (name, value) ->
                if (value !in
                    DURATION_MIN_MS..DURATION_MAX_MS
                ) {
                    add("$name must be in $DURATION_MIN_MS..$DURATION_MAX_MS ms")
                }
            }
            if (flingPxPerMs !in FLING_MIN..FLING_MAX) add("flingPxPerMs must be in $FLING_MIN..$FLING_MAX")
            if (tickSec !in TICK_MIN_SEC..TICK_MAX_SEC) add("tickSec must be in $TICK_MIN_SEC..$TICK_MAX_SEC")
            if (zCount !in Z_COUNT_MIN..Z_COUNT_MAX) add("zCount must be in $Z_COUNT_MIN..$Z_COUNT_MAX, was $zCount")
            if (zIntervalMs !in Z_INTERVAL_MIN_MS..Z_INTERVAL_MAX_MS) {
                add("zIntervalMs must be in $Z_INTERVAL_MIN_MS..$Z_INTERVAL_MAX_MS ms, was $zIntervalMs")
            }
        }
}

private const val HOUR_MAX: Int = 23
private const val PERCENT_MAX: Int = 100
private const val DURATION_MIN_MS: Long = 100
private const val DURATION_MAX_MS: Long = 10_000
private const val FLING_MIN: Double = 0.1
private const val FLING_MAX: Double = 10.0
private const val TICK_MIN_SEC: Long = 10
private const val TICK_MAX_SEC: Long = 600
private const val Z_COUNT_MIN: Int = 1
private const val Z_COUNT_MAX: Int = 4
private const val Z_INTERVAL_MIN_MS: Long = 1_000
private const val Z_INTERVAL_MAX_MS: Long = 5_000

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
    /** 已同意的协议版本（0 = 未同意；见 [UiConfig.LEGAL_AGREEMENT_VERSION]）。 */
    @SerialName("agreedVersion") val agreedVersion: Int = 0,
) : ConfigSection

/** `ui` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object UiConfig {
    /** 当前隐私政策与使用协议版本；协议内容变化时递增，已同意的用户会再次看到协议。 */
    public const val LEGAL_AGREEMENT_VERSION: Int = 1

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
    public val BALL_AMBIENT: ConfigKey<UiBallAmbientSection> =
        ConfigKey("ui.ballAmbient", UiBallAmbientSection.serializer()) { UiBallAmbientSection() }
    public val MOTION: ConfigKey<UiMotionSection> =
        ConfigKey("ui.motion", UiMotionSection.serializer()) { UiMotionSection() }
    public val DEBUG: ConfigKey<UiDebugSection> =
        ConfigKey("ui.debug", UiDebugSection.serializer()) { UiDebugSection() }
    public val ONBOARDING: ConfigKey<UiOnboardingSection> =
        ConfigKey("ui.onboarding", UiOnboardingSection.serializer()) { UiOnboardingSection() }

    public val ALL: List<ConfigKey<*>> =
        listOf(APPEARANCE, NAV, CHAT, BANNER, MEMORY, OVERLAY, INDICATOR, BALL_AMBIENT, MOTION, DEBUG, ONBOARDING)
}
