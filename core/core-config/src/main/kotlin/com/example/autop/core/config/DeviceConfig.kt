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
    @SerialName("diffMaxRatio") val diffMaxRatio: Double = 0.4,
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
    /** 生效模式顺序，必须是三种模式的一个排列。 */
    @SerialName("priority") val priority: List<String> = DEFAULT_PRIORITY,
    @SerialName("modes") val modes: DeviceObserveModesSection = DeviceObserveModesSection(),
    @SerialName("llm") val llm: DeviceObserveLlmSection = DeviceObserveLlmSection(),
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (priority.size != MODE_IDS.size || priority.toSet() != MODE_IDS) {
                add("priority must be a permutation of $MODE_IDS, was $priority")
            }
        }

    public companion object {
        /** 三种模式 id（与 [com.example.autop.device.ObserveMode] 一致）。 */
        public val MODE_IDS: Set<String> = setOf("a11y", "visual", "llm")
        public val DEFAULT_PRIORITY: List<String> = listOf("a11y", "visual", "llm")
    }
}

/** `device.observe.modes`：各模式开关。 */
@Serializable
public data class DeviceObserveModesSection(
    @SerialName("a11y") val a11y: DeviceObserveModeSection = DeviceObserveModeSection(),
    @SerialName("visual") val visual: DeviceObserveModeSection = DeviceObserveModeSection(),
    @SerialName("llm") val llm: DeviceObserveModeSection = DeviceObserveModeSection(),
)

/** 单个观测模式开关。 */
@Serializable
public data class DeviceObserveModeSection(
    @SerialName("enabled") val enabled: Boolean = true,
) : ConfigSection

/** `device.observe.llm`：llm 观测模式参数。 */
@Serializable
public data class DeviceObserveLlmSection(
    @SerialName("maxEdge") val maxEdge: Int = 1280,
    @SerialName("maxElements") val maxElements: Int = 120,
    @SerialName("retries") val retries: Int = 1,
    /** 单次识别请求上限（毫秒）；视觉大模型首 token 慢，30s 偏紧，放到 2 分钟。 */
    @SerialName("timeoutMs") val timeoutMs: Long = 120000,
    @SerialName("cacheTtlMs") val cacheTtlMs: Long = 10000,
    @SerialName("minAreaRatio") val minAreaRatio: Double = 0.0001,
    @SerialName("dupIou") val dupIou: Double = 0.7,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (maxEdge <= 0) add("maxEdge must be > 0, was $maxEdge")
            if (maxElements <= 0) add("maxElements must be > 0, was $maxElements")
            if (retries < 0) add("retries must be >= 0, was $retries")
            if (timeoutMs <= 0) add("timeoutMs must be > 0, was $timeoutMs")
            if (cacheTtlMs < 0) add("cacheTtlMs must be >= 0, was $cacheTtlMs")
            if (minAreaRatio < 0.0) add("minAreaRatio must be >= 0, was $minAreaRatio")
            if (dupIou <= 0.0 || dupIou > 1.0) add("dupIou must be within (0,1], was $dupIou")
        }
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
    /** 树退化判定的有效节点数下限（仅作为 quality 信号报告）。 */
    @SerialName("degenerateNodeThreshold") val degenerateNodeThreshold: Int = 5,
    @SerialName("diffEnabled") val diffEnabled: Boolean = true,
    /** ROI 复检区域比例（相对屏幕尺寸）。 */
    @SerialName("roiRatio") val roiRatio: Double = 0.5,
    @SerialName("minConfidence") val minConfidence: Double = 0.35,
    /** 视觉行存疑阈值（新方案 §2.7）：visualConf 低于此值的行标“存疑”。 */
    @SerialName("uncertainConf") val uncertainConf: Double = 0.6,
    /** basic=检测+OCR；full=+caption；vlm=+小 VLM。 */
    @SerialName("modelPack") val modelPack: String = "basic",
    /** 视觉解析整体预算（截图+检测+状态分类+建树）；超时按解析失败报错，不自动换模式（ADR-0010）。 */
    @SerialName("timeoutMs") val timeoutMs: Long = 30000,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (degenerateNodeThreshold < THRESHOLD_MIN) {
                add("degenerateNodeThreshold must be >= $THRESHOLD_MIN, was $degenerateNodeThreshold")
            }
            if (roiRatio !in RATIO_MIN..RATIO_MAX) add("roiRatio must be within $RATIO_MIN..$RATIO_MAX, was $roiRatio")
            if (minConfidence !in RATIO_MIN..RATIO_MAX) {
                add("minConfidence must be within $RATIO_MIN..$RATIO_MAX, was $minConfidence")
            }
            if (uncertainConf !in RATIO_MIN..RATIO_MAX) {
                add("uncertainConf must be within $RATIO_MIN..$RATIO_MAX, was $uncertainConf")
            }
            if (modelPack !in PACKS) add("modelPack must be one of $PACKS, was '$modelPack'")
            if (timeoutMs < TIMEOUT_MIN) add("timeoutMs must be >= $TIMEOUT_MIN, was $timeoutMs")
        }

    public companion object {
        /** 支持的模型档位。 */
        public val PACKS: Set<String> = setOf("basic", "full", "vlm")
        private const val THRESHOLD_MIN: Int = 1
        private const val RATIO_MIN: Double = 0.0
        private const val RATIO_MAX: Double = 1.0
        private const val TIMEOUT_MIN: Long = 1000
    }
}

/** `device.vision.rescan`：视觉两阶段补扫（vp9 §4.3–§4.7）。 */
@Serializable
public data class DeviceVisionRescanSection(
    @SerialName("enabled") val enabled: Boolean = true,
    /** 每帧补扫框上限（缓存命中不计名额）。 */
    @SerialName("maxBoxes") val maxBoxes: Int = 6,
    /** 裁剪外扩比例（×框短边，至少 4px，vp9 §4.4）。 */
    @SerialName("padRatio") val padRatio: Double = 0.1,
    /** det 放大目标字高（px）。 */
    @SerialName("targetTextPx") val targetTextPx: Int = 32,
    /** det 放大倍率上限。 */
    @SerialName("maxScale") val maxScale: Double = 3.0,
    /** 低置信补扫阈值（C 类候选，vp9 §4.3）。 */
    @SerialName("lowConf") val lowConf: Double = 0.6,
    /** 行重叠判定阈值（vp9 §4.5；文字唯一归属不变量 ④ 同值）。 */
    @SerialName("dupIou") val dupIou: Double = 0.5,
    /** 跨框行判定重叠阈值（vp9 §3.2 A 步）。 */
    @SerialName("spanOverlap") val spanOverlap: Double = 0.3,
    /** 补扫块拼图开关（vp9 §4.4）。 */
    @SerialName("mosaic") val mosaic: Boolean = true,
    /** 拼图块间隔（px）。 */
    @SerialName("mosaicGap") val mosaicGap: Int = 24,
    /** input 提示语亮度差阈值（vp9 §4.6）。 */
    @SerialName("hintContrast") val hintContrast: Int = 60,
    /** 框级缓存容量（vp9 §4.7）。 */
    @SerialName("cacheSize") val cacheSize: Int = 64,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (maxBoxes !in BOXES_MIN..BOXES_MAX) add("maxBoxes must be within $BOXES_MIN..$BOXES_MAX, was $maxBoxes")
            if (padRatio !in PAD_MIN..PAD_MAX) add("padRatio must be within $PAD_MIN..$PAD_MAX, was $padRatio")
            if (targetTextPx !in TARGET_MIN..TARGET_MAX) {
                add("targetTextPx must be within $TARGET_MIN..$TARGET_MAX, was $targetTextPx")
            }
            if (maxScale !in SCALE_MIN..SCALE_MAX) add("maxScale must be within $SCALE_MIN..$SCALE_MAX, was $maxScale")
            if (lowConf !in RATIO_MIN..RATIO_MAX) add("lowConf must be within $RATIO_MIN..$RATIO_MAX, was $lowConf")
            if (dupIou !in IOU_MIN..IOU_MAX) add("dupIou must be within $IOU_MIN..$IOU_MAX, was $dupIou")
            if (spanOverlap !in IOU_MIN..IOU_MAX) add("spanOverlap must be within $IOU_MIN..$IOU_MAX, was $spanOverlap")
            if (mosaicGap !in GAP_MIN..GAP_MAX) add("mosaicGap must be within $GAP_MIN..$GAP_MAX, was $mosaicGap")
            if (hintContrast !in CONTRAST_MIN..CONTRAST_MAX) {
                add("hintContrast must be within $CONTRAST_MIN..$CONTRAST_MAX, was $hintContrast")
            }
            if (cacheSize !in CACHE_MIN..CACHE_MAX) {
                add("cacheSize must be within $CACHE_MIN..$CACHE_MAX, was $cacheSize")
            }
        }

    public companion object {
        public const val BOXES_MIN: Int = 0
        public const val BOXES_MAX: Int = 20
        public const val PAD_MIN: Double = 0.0
        public const val PAD_MAX: Double = 0.5
        public const val TARGET_MIN: Int = 16
        public const val TARGET_MAX: Int = 64
        public const val SCALE_MIN: Double = 1.0
        public const val SCALE_MAX: Double = 4.0
        public const val RATIO_MIN: Double = 0.0
        public const val RATIO_MAX: Double = 1.0
        public const val IOU_MIN: Double = 0.1
        public const val IOU_MAX: Double = 0.9
        public const val GAP_MIN: Int = 8
        public const val GAP_MAX: Int = 64
        public const val CONTRAST_MIN: Int = 0
        public const val CONTRAST_MAX: Int = 255
        public const val CACHE_MIN: Int = 0
        public const val CACHE_MAX: Int = 512
    }
}

/** `device.vision.rec`：OCR 识别双档级联（docs/design/ocr-accuracy-uplift 阶段 B）。 */
@Serializable
public data class DeviceVisionRecSection(
    /** 档位：mobile=只用快档；cascade=快档＋低置信行升级精档；server=全量精档。 */
    @SerialName("mode") val mode: String = "cascade",
    /** 行置信度（CTC 行分）低于此值升级精档重识。 */
    @SerialName("upgradeConf") val upgradeConf: Double = 0.9,
    /** 每帧升级名额（精档重识次数上限，控 P95）。 */
    @SerialName("maxUpgrades") val maxUpgrades: Int = 6,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (mode !in MODES) add("mode must be one of $MODES, was '$mode'")
            if (upgradeConf !in UPGRADE_CONF_MIN..UPGRADE_CONF_MAX) {
                add("upgradeConf must be within $UPGRADE_CONF_MIN..$UPGRADE_CONF_MAX, was $upgradeConf")
            }
            if (maxUpgrades !in UPGRADES_MIN..UPGRADES_MAX) {
                add("maxUpgrades must be within $UPGRADES_MIN..$UPGRADES_MAX, was $maxUpgrades")
            }
        }

    public companion object {
        /** 支持的级联档位。 */
        public val MODES: Set<String> = setOf("mobile", "cascade", "server")
        private const val UPGRADE_CONF_MIN: Double = 0.0
        private const val UPGRADE_CONF_MAX: Double = 1.0
        private const val UPGRADES_MIN: Int = 0
        private const val UPGRADES_MAX: Int = 20
    }
}

/** `device.vision.icon`：视觉图标分类（vp9 §5/§7，icon_cls）。 */
@Serializable
public data class DeviceVisionIconSection(
    @SerialName("enabled") val enabled: Boolean = true,
    /** 推理的检测类别键（vp9 §5.1）：icon/button/input/tab/item。 */
    @SerialName("categories") val categories: List<String> = DEFAULT_CATEGORIES,
    /** 图标裁图最小宽（§5.2：文字列块宽 < minIconSide×框高 不推理）。 */
    @SerialName("minIconSide") val minIconSide: Double = 0.6,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (categories.any { it.isBlank() }) add("categories must not contain blank entries")
            if (minIconSide !in 0.0..2.0) add("minIconSide must be within 0.0..2.0, was $minIconSide")
        }

    public companion object {
        public val DEFAULT_CATEGORIES: List<String> = listOf("icon", "button", "input", "tab", "item")
    }
}

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
    /** 执行期触摸盾总开关（关闭后任务运行中不屏蔽用户触摸，docs/tech/05 §8.1，N215）。 */
    @SerialName("shieldEnabled") val shieldEnabled: Boolean = true,
    /** 触摸盾放行后等窗口重排落地的上限（docs/tech/05 §8.1，N214）。 */
    @SerialName("shieldRelayoutTimeoutMs") val shieldRelayoutTimeoutMs: Long = 500,
) : ConfigSection

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
            if (endpointJitterPx < 0) add("endpointJitterPx must be >= 0, was $endpointJitterPx")
            if (trajectoryJitterPx < 0) add("trajectoryJitterPx must be >= 0, was $trajectoryJitterPx")
        }
}

/** 节点面积上限比例的下限：低于此值会把大多数真实控件排除在外。 */
private const val MIN_NODE_AREA_RATIO = 0.1

/**
 * `device.nodeAction`：优先走无障碍节点动作（点击、长按、滚动）的开关与保护参数。
 * 只在观测与行动都来自 a11y 时生效；应用屏蔽节点动作时按应用记录并改用手势。
 */
@Serializable
public data class DeviceNodeActionSection(
    @SerialName("enabled") val enabled: Boolean = true,
    /** 允许走节点动作的动作种类：`click`、`long_click`、`scroll`。 */
    @SerialName("actions") val actions: List<String> = DEFAULT_ACTIONS,
    /** 超过屏幕该比例的节点视为全屏容器（如点击外部关闭的遮罩），不作为目标。 */
    @SerialName("maxAreaRatio") val maxAreaRatio: Double = 0.6,
    /** 节点动作无效后，该时间内对同一应用的再次操作升级为手势。 */
    @SerialName("retryWindowMs") val retryWindowMs: Long = 8000,
    /** “节点无效而手势有效”的证据累计到该次数，即判定应用屏蔽了节点动作。 */
    @SerialName("blockAfterEvidence") val blockAfterEvidence: Int = 2,
    /** 屏蔽记录有效天数，到期后重新尝试节点动作（应用可能已更新）。 */
    @SerialName("blockTtlDays") val blockTtlDays: Int = 30,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            val unknown = actions.filter { it !in ACTION_IDS }
            if (unknown.isNotEmpty()) add("actions contains unknown ids $unknown, allowed $ACTION_IDS")
            if (maxAreaRatio !in MIN_NODE_AREA_RATIO..1.0) {
                add("maxAreaRatio must be within $MIN_NODE_AREA_RATIO..1.0, was $maxAreaRatio")
            }
            if (retryWindowMs < 0) add("retryWindowMs must be >= 0, was $retryWindowMs")
            if (blockAfterEvidence < 1) add("blockAfterEvidence must be >= 1, was $blockAfterEvidence")
            if (blockTtlDays < 1) add("blockTtlDays must be >= 1, was $blockTtlDays")
        }

    public companion object {
        /** 动作种类 id（与节点动作路由一致）。 */
        public val ACTION_IDS: Set<String> = setOf("click", "long_click", "scroll")
        public val DEFAULT_ACTIONS: List<String> = listOf("click", "long_click", "scroll")
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
    public val VISION_RESCAN: ConfigKey<DeviceVisionRescanSection> =
        ConfigKey("device.vision.rescan", DeviceVisionRescanSection.serializer()) { DeviceVisionRescanSection() }
    public val VISION_REC: ConfigKey<DeviceVisionRecSection> =
        ConfigKey("device.vision.rec", DeviceVisionRecSection.serializer()) { DeviceVisionRecSection() }
    public val OVERLAY: ConfigKey<DeviceOverlaySection> =
        ConfigKey("device.overlay", DeviceOverlaySection.serializer()) { DeviceOverlaySection() }
    public val TOUCH: ConfigKey<DeviceTouchSection> =
        ConfigKey("device.touch", DeviceTouchSection.serializer()) { DeviceTouchSection() }
    public val TAP: ConfigKey<DeviceTapSection> =
        ConfigKey("device.tap", DeviceTapSection.serializer()) { DeviceTapSection() }
    public val SCROLL: ConfigKey<DeviceScrollSection> =
        ConfigKey("device.scroll", DeviceScrollSection.serializer()) { DeviceScrollSection() }
    public val NODE_ACTION: ConfigKey<DeviceNodeActionSection> =
        ConfigKey("device.nodeAction", DeviceNodeActionSection.serializer()) { DeviceNodeActionSection() }
    public val WAIT: ConfigKey<DeviceWaitSection> =
        ConfigKey("device.wait", DeviceWaitSection.serializer()) { DeviceWaitSection() }
    public val NOTIFICATIONS: ConfigKey<DeviceNotificationsSection> =
        ConfigKey("device.notifications", DeviceNotificationsSection.serializer()) { DeviceNotificationsSection() }
    public val REPLAY: ConfigKey<DeviceReplaySection> =
        ConfigKey("device.replay", DeviceReplaySection.serializer()) { DeviceReplaySection() }

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
            VISION_RESCAN,
            VISION_REC,
            OVERLAY,
            TOUCH,
            TAP,
            SCROLL,
            NODE_ACTION,
            WAIT,
            NOTIFICATIONS,
            REPLAY,
        )
}
