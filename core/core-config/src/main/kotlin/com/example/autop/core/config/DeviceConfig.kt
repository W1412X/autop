/**
 * `device` 领域的配置段与键（docs/tech/09 §1.3）。
 * 段默认值与 `core-config-impl/assets/config/device.json` 必须一致（有测试）。
 */
package com.example.autop.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `device.capture`：屏幕抓取。 */
@Serializable
public data class DeviceCaptureSection(
    @SerialName("timeoutMs") val timeoutMs: Long = 1500,
    @SerialName("maxNodes") val maxNodes: Int = 3000,
    @SerialName("emptyTreeRetries") val emptyTreeRetries: Int = 3,
    @SerialName("emptyTreeRetryDelayMs") val emptyTreeRetryDelayMs: Long = 200,
) : ConfigSection

/** `device.events`：无障碍事件缓冲。 */
@Serializable
public data class DeviceEventsSection(
    @SerialName("buffer") val buffer: Int = 256,
) : ConfigSection

/** `device.stabilize`：等待界面稳定。 */
@Serializable
public data class DeviceStabilizeSection(
    @SerialName("quietMs") val quietMs: Long = 300,
    @SerialName("maxWaitMs") val maxWaitMs: Long = 3000,
    @SerialName("pollMs") val pollMs: Long = 50,
    @SerialName("firstEventTimeoutMs") val firstEventTimeoutMs: Long = 800,
) : ConfigSection

/** `device.serializer`：节点序列化与语义贫乏判定。 */
@Serializable
public data class DeviceSerializerSection(
    /** 标签最大字符数，超出截断加 `…`；≤0 无限制（N209）。 */
    @SerialName("textMaxChars") val textMaxChars: Int = -1,
    @SerialName("maxMergedLabels") val maxMergedLabels: Int = 4,
    /** 可滚动节点折叠阈值；≤0 不折叠（N209）。 */
    @SerialName("maxListItems") val maxListItems: Int = -1,
    @SerialName("semanticPoorMinInteractive") val semanticPoorMinInteractive: Int = 3,
    @SerialName("semanticPoorAreaRatio") val semanticPoorAreaRatio: Double = 0.5,
    /**
     * 隐藏系统装饰区（N204）：节点树过滤状态栏/导航栏窗口＋截图裁到内容区＋坐标基准
     * 换算到可见区（两端同开同关，保证图与树一致）；需要模型看状态栏时可关。
     */
    @SerialName("hideSystemDecoration") val hideSystemDecoration: Boolean = true,
) : ConfigSection

/** `device.observe`：三种观测模式的优先级与可用性（docs/design/observation-modes-coordinate-actions §2.3）。 */
@Serializable
public data class DeviceObserveSection(
    /**
     * 生效模式顺序。a11y 与 visual 各出现一次；列表里的其它条目（如旧版本残留的 `llm`）被忽略。
     */
    @SerialName("priority") val priority: List<String> = DEFAULT_PRIORITY,
    @SerialName("modes") val modes: DeviceObserveModesSection = DeviceObserveModesSection(),
    @SerialName("sufficiency") val sufficiency: DeviceObserveSufficiencySection = DeviceObserveSufficiencySection(),
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            MODE_IDS.forEach { id ->
                val count = priority.count { it == id }
                if (count != 1) add("priority must list '$id' exactly once, was $priority")
            }
        }

    public companion object {
        /** 观测模式 id（与 [com.example.autop.device.ObserveMode] 一致）。 */
        public val MODE_IDS: Set<String> = setOf("a11y", "visual")
        public val DEFAULT_PRIORITY: List<String> = listOf("a11y", "visual")
    }
}

/** `device.observe.modes`：各模式开关。 */
@Serializable
public data class DeviceObserveModesSection(
    @SerialName("a11y") val a11y: DeviceObserveModeSection = DeviceObserveModeSection(),
    @SerialName("visual") val visual: DeviceObserveModeSection = DeviceObserveModeSection(),
) {
    /** 按模式 id 取开关（config 模块不依赖 device-api 的 ObserveMode）。 */
    public fun of(mode: String): DeviceObserveModeSection =
        when (mode) {
            "a11y" -> a11y
            "visual" -> visual
            else -> error("unknown observe mode id: $mode")
        }
}

/** 单个观测模式开关。 */
@Serializable
public data class DeviceObserveModeSection(
    @SerialName("enabled") val enabled: Boolean = true,
    /** 是否参与 observe 不传 mode 时的自动选择（agent-harness-overhaul D3）。 */
    @SerialName("auto") val auto: Boolean = true,
) : ConfigSection

/** `device.observe.sufficiency`：判定一次观测“信息不足”的阈值（WP1.3）。 */
@Serializable
public data class DeviceObserveSufficiencySection(
    /** 有效节点（面积>0 且有文字/描述/可交互）少于此数视为信息不足。 */
    @SerialName("minEffectiveNodes") val minEffectiveNodes: Int = 5,
) : ConfigSection {
    override fun validate(): List<String> =
        if (minEffectiveNodes >= 1) emptyList() else listOf("minEffectiveNodes must be >= 1, was $minEffectiveNodes")
}

/** `device.screenshot`：截取与缩放。 */
@Serializable
public data class DeviceScreenshotSection(
    @SerialName("maxEdge") val maxEdge: Int = 1024,
    @SerialName("jpegQuality") val jpegQuality: Int = 80,
    @SerialName("minIntervalMs") val minIntervalMs: Long = 1000,
    @SerialName("retryDelayMs") val retryDelayMs: Long = 400,
    @SerialName("retries") val retries: Int = 3,
    @SerialName("vision") val vision: DeviceVisionSection = DeviceVisionSection(),
) : ConfigSection

/** `device.screenshot.vision`：协议视觉块缩图规则（R1/A5，docs/adr/0006）。 */
@Serializable
public data class DeviceVisionSection(
    @SerialName("blockSize") val blockSize: Int = 28,
    @SerialName("maxBlocks") val maxBlocks: Int = 1500,
    @SerialName("maxLongEdge") val maxLongEdge: Int = 1568,
) : ConfigSection

/** `device.vision`：视觉屏幕解析兜底（docs/design/visual-screen-parsing §8）。 */
@Serializable
public data class DeviceVisionParseSection(
    @SerialName("enabled") val enabled: Boolean = true,
    /** ROI 复检区域比例（相对屏幕尺寸）。 */
    @SerialName("roiRatio") val roiRatio: Double = 0.5,
    @SerialName("minConfidence") val minConfidence: Double = 0.35,
    /** basic=检测+OCR；full=+caption；vlm=+小 VLM。 */
    @SerialName("modelPack") val modelPack: String = "basic",
    /** 视觉解析整体预算（截图+检测+状态分类+建树）；超时按解析失败报错，不自动换模式（ADR-0010）。 */
    @SerialName("timeoutMs") val timeoutMs: Long = 30000,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (roiRatio !in RATIO_MIN..RATIO_MAX) add("roiRatio must be within $RATIO_MIN..$RATIO_MAX, was $roiRatio")
            if (minConfidence !in RATIO_MIN..RATIO_MAX) {
                add("minConfidence must be within $RATIO_MIN..$RATIO_MAX, was $minConfidence")
            }
            if (modelPack !in PACKS) add("modelPack must be one of $PACKS, was '$modelPack'")
            if (timeoutMs < TIMEOUT_MIN) add("timeoutMs must be >= $TIMEOUT_MIN, was $timeoutMs")
        }

    public companion object {
        /** 支持的模型档位。 */
        public val PACKS: Set<String> = setOf("basic", "full", "vlm")
        private const val RATIO_MIN: Double = 0.0
        private const val RATIO_MAX: Double = 1.0
        private const val TIMEOUT_MIN: Long = 1000
    }
}

/** `device.vision.icon`：视觉图标分类（icon_cls；逐叶子全量推理，拒识即无图标）。 */
@Serializable
public data class DeviceVisionIconSection(
    @SerialName("enabled") val enabled: Boolean = true,
) : ConfigSection

/** `device.change`：屏幕变化判定（WP1.5、WP2.4）。 */
@Serializable
public data class DeviceChangeSection(
    /** 节点配对的位置容差（0–1000 坐标，中心点每轴允许的偏移）。 */
    @SerialName("positionTolerance") val positionTolerance: Int = 12,
    /** 画面运动判定：aHash 汉明距离上限（≤ 视为画面未动）。 */
    @SerialName("frameMaxHamming") val frameMaxHamming: Int = 3,
    /** 画面运动判定：SSIM 下限（≥ 视为画面未动）。 */
    @SerialName("frameMinSsim") val frameMinSsim: Double = 0.97,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (positionTolerance !in 0..POSITION_TOLERANCE_MAX) {
                add("positionTolerance must be within 0..$POSITION_TOLERANCE_MAX, was $positionTolerance")
            }
            if (frameMaxHamming !in 0..FRAME_MAX_HAMMING_MAX) {
                add("frameMaxHamming must be within 0..$FRAME_MAX_HAMMING_MAX, was $frameMaxHamming")
            }
            if (frameMinSsim !in 0.0..1.0) add("frameMinSsim must be within 0..1, was $frameMinSsim")
        }
}

private const val POSITION_TOLERANCE_MAX: Int = 100
private const val FRAME_MAX_HAMMING_MAX: Int = 64

/** `device.overlay`：悬浮窗隐藏稳定。 */
@Serializable
public data class DeviceOverlaySection(
    @SerialName("hideSettleMs") val hideSettleMs: Long = 120,
) : ConfigSection

/** `device.touch`：触摸检测（用户是否在用手机）与执行期触摸盾。 */
@Serializable
public data class DeviceTouchSection(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("sources") val sources: List<String> = listOf("outside_touch", "a11y_events"),
    @SerialName("selfActionGraceMs") val selfActionGraceMs: Long = 400,
    /** 自身动作窗口上限（动作开始即登记，覆盖动作全程与等待稳定，WP7.3）。 */
    @SerialName("selfActionMaxMs") val selfActionMaxMs: Long = 15000,
    /** 执行期触摸盾总开关（关闭后任务运行中不屏蔽用户触摸，docs/tech/05 §8.1，N215）。 */
    @SerialName("shieldEnabled") val shieldEnabled: Boolean = true,
    /** 触摸盾放行后等窗口重排落地的上限（docs/tech/05 §8.1，N214）。 */
    @SerialName("shieldRelayoutTimeoutMs") val shieldRelayoutTimeoutMs: Long = 500,
) : ConfigSection {
    override fun validate(): List<String> =
        if (selfActionMaxMs >= SELF_ACTION_MAX_MS_MIN) {
            emptyList()
        } else {
            listOf("selfActionMaxMs must be >= $SELF_ACTION_MAX_MS_MIN, was $selfActionMaxMs")
        }
}

private const val SELF_ACTION_MAX_MS_MIN: Long = 1000
private const val MAX_TAP_JITTER_RATIO = 0.5

/** `device.tap`：点击手势时长与落点抖动（拟人化，工具内部使用）。 */
@Serializable
public data class DeviceTapSection(
    @SerialName("durationMs") val durationMs: Long = 100,
    @SerialName("longPressMs") val longPressMs: Long = 600,
    @SerialName("jitterMaxPx") val jitterMaxPx: Int = 10,
    @SerialName("jitterRatio") val jitterRatio: Double = 0.15,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (durationMs <= 0) add("durationMs must be > 0, was $durationMs")
            if (longPressMs <= 0) add("longPressMs must be > 0, was $longPressMs")
            if (jitterMaxPx < 0) add("jitterMaxPx must be >= 0, was $jitterMaxPx")
            if (jitterRatio !in 0.0..MAX_TAP_JITTER_RATIO) {
                add("jitterRatio must be within 0.0..$MAX_TAP_JITTER_RATIO, was $jitterRatio")
            }
        }
}

/** 滚动单步占容器边长比例的合法范围。 */
private const val MIN_SCROLL_STEP_RATIO = 0.1

/** 滚动安全边距占容器边长比例的上限（两侧各一份，必须小于 0.5）。 */
private const val MAX_SCROLL_INSET_RATIO = 0.4

/**
 * `device.scroll`：滚动单步几何与抖动。
 * 一步 = 一次人手滑动，距离取容器边长 × [stepRatio]，起止点落在容器内并留 [edgeInsetRatio] 安全边距；
 * 内容不够时由模型多次调用或用 `steps` 连续多步（上限 [maxSteps]）。
 */
@Serializable
public data class DeviceScrollSection(
    @SerialName("stepRatio") val stepRatio: Double = 0.5,
    @SerialName("edgeInsetRatio") val edgeInsetRatio: Double = 0.1,
    @SerialName("pixelsPerMs") val pixelsPerMs: Double = 1.5,
    @SerialName("minDurationMs") val minDurationMs: Long = 250,
    @SerialName("maxDurationMs") val maxDurationMs: Long = 900,
    @SerialName("maxSteps") val maxSteps: Int = 5,
    @SerialName("swipeDurationMs") val swipeDurationMs: Long = 350,
    /** scroll、scroll_node 多步之间的停顿毫秒数（智能体调用）。 */
    @SerialName("stepDelayMs") val stepDelayMs: Long = 300,
    /** swipe 的 duration_ms 上限。 */
    @SerialName("swipeMaxDurationMs") val swipeMaxDurationMs: Long = 3000,
    @SerialName("endpointJitterPx") val endpointJitterPx: Int = 12,
    @SerialName("trajectoryJitterPx") val trajectoryJitterPx: Int = 8,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (stepRatio !in MIN_SCROLL_STEP_RATIO..1.0) {
                add("stepRatio must be within $MIN_SCROLL_STEP_RATIO..1.0, was $stepRatio")
            }
            if (edgeInsetRatio !in 0.0..MAX_SCROLL_INSET_RATIO) {
                add("edgeInsetRatio must be within 0.0..$MAX_SCROLL_INSET_RATIO, was $edgeInsetRatio")
            }
            if (pixelsPerMs <= 0.0) add("pixelsPerMs must be > 0, was $pixelsPerMs")
            if (minDurationMs <= 0) add("minDurationMs must be > 0, was $minDurationMs")
            if (maxDurationMs < minDurationMs) add("maxDurationMs must be >= minDurationMs, was $maxDurationMs")
            if (maxSteps < 1) add("maxSteps must be >= 1, was $maxSteps")
            if (swipeDurationMs <= 0) add("swipeDurationMs must be > 0, was $swipeDurationMs")
            if (stepDelayMs !in STEP_DELAY_MS_RANGE) {
                add("stepDelayMs must be within $STEP_DELAY_MS_RANGE, was $stepDelayMs")
            }
            if (swipeMaxDurationMs !in swipeDurationMs..SWIPE_MAX_DURATION_MS_MAX) {
                add(
                    "swipeMaxDurationMs must be within $swipeDurationMs..$SWIPE_MAX_DURATION_MS_MAX, " +
                        "was $swipeMaxDurationMs",
                )
            }
            if (endpointJitterPx < 0) add("endpointJitterPx must be >= 0, was $endpointJitterPx")
            if (trajectoryJitterPx < 0) add("trajectoryJitterPx must be >= 0, was $trajectoryJitterPx")
        }

    internal companion object {
        val STEP_DELAY_MS_RANGE: LongRange = 0L..5000L
        const val SWIPE_MAX_DURATION_MS_MAX: Long = 10000
    }
}

/** `device.wait`：wait 工具轮询。 */
@Serializable
public data class DeviceWaitSection(
    @SerialName("pollMs") val pollMs: Long = 500,
    @SerialName("defaultTimeoutMs") val defaultTimeoutMs: Long = 10000,
) : ConfigSection

/** `device.notifications`：通知读取。 */
@Serializable
public data class DeviceNotificationsSection(
    @SerialName("ignoreOngoing") val ignoreOngoing: Boolean = true,
    @SerialName("maxMessages") val maxMessages: Int = 5,
    @SerialName("recentBuffer") val recentBuffer: Int = 200,
) : ConfigSection

/** `device.replay`：回放数据记录（R1/A6）。 */
@Serializable
public data class DeviceReplaySection(
    @SerialName("maxKeys") val maxKeys: Int = 200,
) : ConfigSection

/** `device.intent`：intent 工具（系统意图）的参数与导航应用偏好（intent-tool §10.1）。 */
@Serializable
public data class DeviceIntentSection(
    /** 导航应用偏好：`auto`、`ask` 或导航规则表里的应用 id；未知值按 `auto` 处理。 */
    @SerialName("preferredMap") val preferredMap: String = "auto",
    /** “每次询问”等待用户回答的时间（毫秒）。 */
    @SerialName("askTimeoutMs") val askTimeoutMs: Long = 60000,
    /** timer 的 seconds 上限。 */
    @SerialName("timerMaxSeconds") val timerMaxSeconds: Int = 86400,
    /** calendar 缺省时长（分钟）。 */
    @SerialName("calendarDefaultMinutes") val calendarDefaultMinutes: Int = 60,
    /** share、sms_draft 的 text 与 calendar 的 description 最大字符数；也约束 kind=uri 的 uri 长度。 */
    @SerialName("textMaxChars") val textMaxChars: Int = 4000,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (preferredMap.isBlank()) add("preferredMap must not be blank")
            if (askTimeoutMs !in ASK_TIMEOUT_MS_RANGE) {
                add("askTimeoutMs must be within $ASK_TIMEOUT_MS_RANGE, was $askTimeoutMs")
            }
            if (timerMaxSeconds !in TIMER_MAX_SECONDS_RANGE) {
                add("timerMaxSeconds must be within $TIMER_MAX_SECONDS_RANGE, was $timerMaxSeconds")
            }
            if (calendarDefaultMinutes !in CALENDAR_DEFAULT_MINUTES_RANGE) {
                add(
                    "calendarDefaultMinutes must be within $CALENDAR_DEFAULT_MINUTES_RANGE, " +
                        "was $calendarDefaultMinutes",
                )
            }
            if (textMaxChars !in TEXT_MAX_CHARS_RANGE) {
                add("textMaxChars must be within $TEXT_MAX_CHARS_RANGE, was $textMaxChars")
            }
        }
}

private val ASK_TIMEOUT_MS_RANGE: LongRange = 5000L..600000L
private val TIMER_MAX_SECONDS_RANGE: IntRange = 1..604800
private val CALENDAR_DEFAULT_MINUTES_RANGE: IntRange = 1..1440
private val TEXT_MAX_CHARS_RANGE: IntRange = 1..20000

/** `device.action`：智能体屏幕动作后的固定等待（tool_optimize D2）；流程调用不使用。 */
@Serializable
public data class DeviceActionSection(
    /** 动作后的默认等待毫秒数；模型可用工具参数 wait_ms 覆盖。 */
    @SerialName("delayMs") val delayMs: Long = 5000,
    /** wait_ms 与 delayMs 的上限。 */
    @SerialName("maxDelayMs") val maxDelayMs: Long = 30000,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (maxDelayMs !in MAX_DELAY_RANGE) add("maxDelayMs must be within $MAX_DELAY_RANGE, was $maxDelayMs")
            if (delayMs !in 0..maxDelayMs) add("delayMs must be within 0..maxDelayMs, was $delayMs")
        }

    internal companion object {
        val MAX_DELAY_RANGE = 1_000L..60_000L
    }
}

/** `device.input`：input_text 的时序参数（tool_optimize 02 §3）。 */
@Serializable
public data class DeviceInputSection(
    /** 给出坐标时，点击输入框后等待焦点切换的毫秒数（智能体调用；流程调用改为等界面稳定）。 */
    @SerialName("focusDelayMs") val focusDelayMs: Long = 500,
    /** shell 粘贴后恢复原剪贴板前的等待毫秒数（粘贴按键是异步的）。 */
    @SerialName("clipboardRestoreDelayMs") val clipboardRestoreDelayMs: Long = 400,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (focusDelayMs !in INPUT_DELAY_RANGE) {
                add("focusDelayMs must be within $INPUT_DELAY_RANGE, was $focusDelayMs")
            }
            if (clipboardRestoreDelayMs !in INPUT_DELAY_RANGE) {
                add("clipboardRestoreDelayMs must be within $INPUT_DELAY_RANGE, was $clipboardRestoreDelayMs")
            }
        }

    internal companion object {
        val INPUT_DELAY_RANGE: LongRange = 0L..5000L
    }
}

/** `device.shell`：shell 注入通道（tool_optimize 02 §15）。 */
@Serializable
public data class DeviceShellSection(
    /** 单条 shell 命令的超时毫秒数；超时后关闭连接，命令按未执行处理。 */
    @SerialName("commandTimeoutMs") val commandTimeoutMs: Long = 5000,
) : ConfigSection {
    override fun validate(): List<String> =
        if (commandTimeoutMs in COMMAND_TIMEOUT_RANGE) {
            emptyList()
        } else {
            listOf("commandTimeoutMs must be within $COMMAND_TIMEOUT_RANGE, was $commandTimeoutMs")
        }

    internal companion object {
        val COMMAND_TIMEOUT_RANGE: LongRange = 500L..60000L
    }
}

/** `device.apps`：应用列表（tool_optimize 02 §12）。 */
@Serializable
public data class DeviceAppsSection(
    /** list_apps 未传 limit 时最多返回的条数。 */
    @SerialName("listLimit") val listLimit: Int = 50,
) : ConfigSection {
    override fun validate(): List<String> =
        if (listLimit in LIST_LIMIT_RANGE) {
            emptyList()
        } else {
            listOf("listLimit must be within $LIST_LIMIT_RANGE, was $listLimit")
        }

    internal companion object {
        val LIST_LIMIT_RANGE: IntRange = 1..1000
    }
}

/** `device` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object DeviceConfig {
    public val CAPTURE: ConfigKey<DeviceCaptureSection> =
        ConfigKey("device.capture", DeviceCaptureSection.serializer()) { DeviceCaptureSection() }
    public val EVENTS: ConfigKey<DeviceEventsSection> =
        ConfigKey("device.events", DeviceEventsSection.serializer()) { DeviceEventsSection() }
    public val STABILIZE: ConfigKey<DeviceStabilizeSection> =
        ConfigKey("device.stabilize", DeviceStabilizeSection.serializer()) { DeviceStabilizeSection() }
    public val SERIALIZER: ConfigKey<DeviceSerializerSection> =
        ConfigKey("device.serializer", DeviceSerializerSection.serializer()) { DeviceSerializerSection() }
    public val SCREENSHOT: ConfigKey<DeviceScreenshotSection> =
        ConfigKey("device.screenshot", DeviceScreenshotSection.serializer()) { DeviceScreenshotSection() }
    public val OBSERVE: ConfigKey<DeviceObserveSection> =
        ConfigKey("device.observe", DeviceObserveSection.serializer()) { DeviceObserveSection() }
    public val VISION: ConfigKey<DeviceVisionParseSection> =
        ConfigKey("device.vision", DeviceVisionParseSection.serializer()) { DeviceVisionParseSection() }
    public val VISION_ICON: ConfigKey<DeviceVisionIconSection> =
        ConfigKey("device.vision.icon", DeviceVisionIconSection.serializer()) { DeviceVisionIconSection() }
    public val CHANGE: ConfigKey<DeviceChangeSection> =
        ConfigKey("device.change", DeviceChangeSection.serializer()) { DeviceChangeSection() }
    public val OVERLAY: ConfigKey<DeviceOverlaySection> =
        ConfigKey("device.overlay", DeviceOverlaySection.serializer()) { DeviceOverlaySection() }
    public val TOUCH: ConfigKey<DeviceTouchSection> =
        ConfigKey("device.touch", DeviceTouchSection.serializer()) { DeviceTouchSection() }
    public val TAP: ConfigKey<DeviceTapSection> =
        ConfigKey("device.tap", DeviceTapSection.serializer()) { DeviceTapSection() }
    public val SCROLL: ConfigKey<DeviceScrollSection> =
        ConfigKey("device.scroll", DeviceScrollSection.serializer()) { DeviceScrollSection() }
    public val WAIT: ConfigKey<DeviceWaitSection> =
        ConfigKey("device.wait", DeviceWaitSection.serializer()) { DeviceWaitSection() }
    public val NOTIFICATIONS: ConfigKey<DeviceNotificationsSection> =
        ConfigKey("device.notifications", DeviceNotificationsSection.serializer()) { DeviceNotificationsSection() }
    public val REPLAY: ConfigKey<DeviceReplaySection> =
        ConfigKey("device.replay", DeviceReplaySection.serializer()) { DeviceReplaySection() }
    public val INTENT: ConfigKey<DeviceIntentSection> =
        ConfigKey("device.intent", DeviceIntentSection.serializer()) { DeviceIntentSection() }
    public val ACTION: ConfigKey<DeviceActionSection> =
        ConfigKey("device.action", DeviceActionSection.serializer()) { DeviceActionSection() }
    public val INPUT: ConfigKey<DeviceInputSection> =
        ConfigKey("device.input", DeviceInputSection.serializer()) { DeviceInputSection() }
    public val SHELL: ConfigKey<DeviceShellSection> =
        ConfigKey("device.shell", DeviceShellSection.serializer()) { DeviceShellSection() }
    public val APPS: ConfigKey<DeviceAppsSection> =
        ConfigKey("device.apps", DeviceAppsSection.serializer()) { DeviceAppsSection() }

    public val ALL: List<ConfigKey<*>> =
        listOf(
            CAPTURE,
            EVENTS,
            STABILIZE,
            SERIALIZER,
            SCREENSHOT,
            OBSERVE,
            VISION,
            VISION_ICON,
            CHANGE,
            OVERLAY,
            TOUCH,
            TAP,
            SCROLL,
            WAIT,
            NOTIFICATIONS,
            REPLAY,
            INTENT,
            ACTION,
            INPUT,
            SHELL,
            APPS,
        )
}
