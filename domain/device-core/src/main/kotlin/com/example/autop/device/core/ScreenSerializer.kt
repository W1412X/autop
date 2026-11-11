/**
 * 屏幕序列化入口（docs/design/observation-modes-coordinate-actions §3）：
 * 快照 → 统一 `ScreenModel` → JSON 观测文本；差量由 [ScreenJsonDiffer] 生成。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Observation
import com.example.autop.device.ObservationMode
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType

/**
 * 序列化器：沿用节点树处理逻辑（[NodeTreeBuilder]），坐标一律为内容区 0–1000；
 * 输出 JSON（[ScreenJsonRenderer]）。
 */
public class ScreenSerializer(
    private val rules: SerializerRules,
    private val config: SerializerConfig,
) {
    /**
     * 序列化 [snapshot]：[mode] 决定 a11y/visual 字段与节点选取；[redaction] 决定窗口脱敏；
     * [ownPackage] 为自身包名（窗口过滤）；[effectiveModes] 用于 `quality.suggest`；
     * [noAppWindow] 只有 a11y 可能为真（快照里没有任何应用窗口）；
     * [idAssigner] 非空时给 a11y 可操作节点分配 id（观测入口传，回放探针等不传）。
     */
    public fun serialize(
        snapshot: ScreenSnapshot,
        mode: ObserveMode,
        redaction: (WindowSnapshot) -> RedactionMode,
        ownPackage: String,
        effectiveModes: List<ObserveMode> = listOf(mode),
        noAppWindow: Boolean = false,
        cached: Boolean = false,
        idAssigner: NodeIdAssigner? = null,
    ): SerializedScreen {
        val index = SnapshotIndex(snapshot)
        val geometry = TapPointResolver.Geometry(snapshot)
        val builder = NodeTreeBuilder(config, rules, compileVolatilePatterns(rules.volatileLabelPatterns), geometry)
        // N204：坐标基准与节点过滤的内容区（hideSystemDecoration 开时；与截图裁剪同开关）。
        val area = if (config.hideSystemDecoration) snapshot.contentArea else null
        val windows =
            snapshot.windows
                .filter { it.type != WindowType.ACCESSIBILITY_OVERLAY && it.packageName != ownPackage }
                .filter { !config.hideSystemDecoration || !it.isSystemDecoration() }
                .sortedByDescending { it.layer }
        val screen = PixelSize(snapshot.screenWidth, snapshot.screenHeight)
        val focused =
            snapshot.nodes.firstOrNull {
                it.flags and NodeFlags.FOCUSED != 0 &&
                    it.flags and NodeFlags.EDITABLE != 0
            }
        val drafts =
            windows.map { window ->
                draftOf(window, DraftContext(snapshot, index, area, screen, focused, builder), redaction, mode)
            }
        val poor = semanticPoor(snapshot)
        val built =
            ScreenModelBuilder.fromDrafts(
                mode = mode,
                drafts = drafts,
                degenerate = degenerate(snapshot),
                poor = poor,
                effectiveModes = effectiveModes,
                noAppWindow = noAppWindow,
                truncated = snapshot.truncated,
                cached = cached,
            )
        val model = idAssigner?.assign(built, snapshot.foregroundPackage, snapshot.activity) ?: built
        val suggestion = suggestionOf(model, config)
        val text =
            ScreenJsonRenderer.render(
                model = model,
                coordText = config.renderPrompt(ScreenJsonWire.PROMPT_COORD, emptyMap()),
                suggestion = suggestion,
            )
        return SerializedScreen(observationOf(snapshot, windows, model, text, mode, poor), model)
    }

    /** 信息不足时的建议尾行（统一口径，所有方式一致）。 */
    private fun suggestionOf(
        model: ScreenModel,
        config: SerializerConfig,
    ): String? =
        model.quality.insufficient?.let { code ->
            config.renderPrompt(
                ScreenJsonWire.PROMPT_INSUFFICIENT,
                mapOf(
                    VAR_REASON to
                        config.renderPrompt(
                            ScreenJsonWire.PROMPT_INSUFFICIENT_REASON_PREFIX + code,
                            emptyMap(),
                        ),
                    VAR_SUGGEST to (model.quality.suggest?.id ?: ""),
                ),
            )
        }

    private fun observationOf(
        snapshot: ScreenSnapshot,
        windows: List<WindowSnapshot>,
        model: ScreenModel,
        text: String,
        mode: ObserveMode,
        poor: Boolean,
    ): Observation =
        Observation(
            text = text,
            mode = ObservationMode.FULL,
            observeMode = mode,
            // 只对应用窗口做指纹：系统状态栏时钟/网速等易变节点会让“屏幕未变化”永远判不出来。
            fingerprint = ScreenFingerprint.of(snapshot.foregroundPackage, snapshot.activity, model.appFlatten()),
            packageName = snapshot.foregroundPackage,
            activity = snapshot.activity,
            summaryLine = summaryLine(snapshot, windows, model.quality.nodes),
            interactiveCount = model.quality.interactive,
            nodeCount = model.quality.nodes,
            semanticPoor = mode == ObserveMode.A11Y && poor,
        )

    private data class DraftContext(
        val snapshot: ScreenSnapshot,
        val index: SnapshotIndex,
        val area: Rect?,
        val screen: PixelSize,
        val focused: NodeSnapshot?,
        val builder: NodeTreeBuilder,
    )

    private fun draftOf(
        window: WindowSnapshot,
        context: DraftContext,
        redaction: (WindowSnapshot) -> RedactionMode,
        mode: ObserveMode,
    ): WindowDraft {
        val hidden = redaction(window) == RedactionMode.HIDE_WINDOW
        // R1/A1：输入法窗口不序列化节点，整个折叠（按窗口类型判断，不写死包名）。
        val ime = window.type == WindowType.INPUT_METHOD
        val rows =
            if (hidden || ime) {
                emptyList()
            } else {
                context.builder.build(window, context.index, context.area, context.screen, mode)
            }
        return WindowDraft(
            app = window.title ?: window.packageName.orEmpty(),
            // activity 是前台 Activity，只属于前台应用窗口，避免误导。
            page =
                context.snapshot.activity
                    ?.substringAfterLast('.')
                    ?.takeIf { it.isNotEmpty() && window.packageName == context.snapshot.foregroundPackage },
            type = rules.windowTypeLabels[window.type.name]?.takeIf { it.isNotEmpty() },
            kind = window.type,
            active = window.active,
            focus = focusOf(window, context),
            collapsed = ime,
            redacted = hidden,
            rows = rows,
        )
    }

    /** 步骤 12：焦点输入框的归一化 bounds（属于本窗口时）。 */
    private fun focusOf(
        window: WindowSnapshot,
        context: DraftContext,
    ): Rect? {
        val focused = context.focused ?: return null
        if (context.index.windowIdOf(focused.idx) != window.windowId) return null
        return normalizedBox(focused.bounds, context.area, context.screen)
    }

    /** 像素 bounds → 内容区 0–1000（跨界钳制）。 */
    private fun normalizedBox(
        bounds: Rect,
        area: Rect?,
        screen: PixelSize,
    ): Rect? {
        val width = area?.width ?: screen.width
        val height = area?.height ?: screen.height
        if (width <= 0 || height <= 0) return null
        val left = (bounds.l - (area?.l ?: 0)).coerceIn(0, width)
        val top = (bounds.t - (area?.t ?: 0)).coerceIn(0, height)
        val right = (bounds.r - (area?.l ?: 0)).coerceIn(0, width)
        val bottom = (bounds.b - (area?.t ?: 0)).coerceIn(0, height)
        return Rect(
            left * COORD_SCALE / width,
            top * COORD_SCALE / height,
            right * COORD_SCALE / width,
            bottom * COORD_SCALE / height,
        )
    }

    /** §3.5：有效节点数（有面积且有文字/描述/交互位）不足阈值。 */
    private fun degenerate(snapshot: ScreenSnapshot): Boolean =
        snapshot.nodes.count { isEffective(it) } < config.minEffectiveNodes

    private fun isEffective(node: NodeSnapshot): Boolean =
        node.bounds.area > 0 &&
            (node.text != null || node.contentDesc != null || node.flags and NodeText.INTERACTIVE_ANY != 0)

    /** §3.5：语义贫乏（docs/tech/05 §3.7）。 */
    private fun semanticPoor(snapshot: ScreenSnapshot): Boolean {
        val screenArea = snapshot.screenWidth.toDouble() * snapshot.screenHeight
        val poorClassOnScreen =
            screenArea > 0 &&
                snapshot.nodes.any { node ->
                    val name = node.className?.substringAfterLast('.').orEmpty()
                    rules.semanticPoorClasses.any { name.endsWith(it) } &&
                        node.bounds.area / screenArea > config.semanticPoorAreaRatio
                }
        val interactive =
            snapshot.nodes.count { node ->
                node.bounds.area > 0 && node.flags and NodeText.INTERACTIVE_ANY != 0
            }
        return interactive < config.semanticPoorMinInteractive && poorClassOnScreen
    }

    private fun summaryLine(
        snapshot: ScreenSnapshot,
        windows: List<WindowSnapshot>,
        nodeCount: Int,
    ): String {
        val appName = windows.firstOrNull { it.active }?.title ?: snapshot.foregroundPackage.orEmpty()
        val activityShort = snapshot.activity?.substringAfterLast('.').orEmpty()
        // 用序列化后的节点数（与 quality.nodes 同口径），避免摘要与正文数字不一致。
        return "$appName / $activityShort，$nodeCount 个节点"
    }

    private companion object {
        const val COORD_SCALE = 1000
        const val VAR_REASON = "reason"
        const val VAR_SUGGEST = "suggest"
    }
}
