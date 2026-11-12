/**
 * 序列化配置（docs/tech/05 §3、docs/design/observation-modes-coordinate-actions §3）：
 * 由 device-impl 从 DeviceConfig 与 AgentConfig 构造。
 */
package com.example.autop.device.core

/**
 * 序列化配置。
 *
 * [renderPrompt] 渲染 prompt 模板（core 不依赖模板仓库），由 impl 传入
 * `PromptRepository::render` 的包装；模板 id 见 docs/tech/09 §2.4。
 */
public data class SerializerConfig(
    /** 标签最大字符数，超出截断加 `…`；≤0 无限制。 */
    val textMaxChars: Int,
    /** 合并后代标签的最大个数。 */
    val maxMergedLabels: Int,
    /** 可滚动节点折叠阈值；≤0 不折叠。 */
    val maxListItems: Int,
    /**
     * 隐藏系统装饰区（N204）：过滤状态栏/导航栏窗口，坐标基准换算到内容区
     * （与截图裁剪同开关，保持图与树一致）。
     */
    val hideSystemDecoration: Boolean,
    /** 可交互节点少于此值且命中贫乏类名 → 语义贫乏。 */
    val semanticPoorMinInteractive: Int,
    /** 贫乏类名节点面积占屏幕比例阈值。 */
    val semanticPoorAreaRatio: Double,
    /** 有效节点数低于此值 → quality.degenerate（所有观测方式同口径）。 */
    val minEffectiveNodes: Int,
    /** 节点配对的位置容差（0–1000 坐标，中心点每轴允许的偏移）。 */
    val positionTolerance: Int,
    /** 易变标签模式（预编译），配对前去易变片段。 */
    val volatilePatterns: List<Regex>,
    /** 模板渲染（id, vars）→ 文本。 */
    val renderPrompt: (id: String, vars: Map<String, String>) -> String,
)
