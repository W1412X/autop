/**
 * 测试用快照构造器：NodeSpec/WindowSpec 等 DSL 与规则、配置夹具。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType

/** [NodeSnapshot] 的可变构造规格。 */
internal class NodeSpec {
    var parent: Int? = null
    var childIndex: Int = 0
    var children: List<Int> = emptyList()
    var className: String? = null
    var text: String? = null
    var contentDesc: String? = null
    var hint: String? = null
    var stateDesc: String? = null
    var visualConf: Float? = null
    var iconLabel: String? = null
    var viewId: String? = null
    var bounds: Rect = Rect(0, 0, 100, 50)
    var flags: Int = NodeFlags.VISIBLE or NodeFlags.ENABLED
    var actions: List<Int> = emptyList()
    var collectionRows: Int? = null
    var collectionCols: Int? = null
    var inputType: Int? = null
    var packageName: String? = null
    var drawingOrder: Int? = null

    fun build(idx: Int): NodeSnapshot =
        NodeSnapshot(
            idx = idx,
            parent = parent,
            children = children,
            childIndex = childIndex,
            className = className,
            text = text,
            contentDesc = contentDesc,
            hint = hint,
            stateDesc = stateDesc,
            visualConf = visualConf,
            iconLabel = iconLabel,
            viewId = viewId,
            bounds = bounds,
            flags = flags,
            actions = actions,
            collectionRows = collectionRows,
            collectionCols = collectionCols,
            inputType = inputType,
            packageName = packageName,
            drawingOrder = drawingOrder,
        )
}

/** [WindowSnapshot] 的可变构造规格。 */
internal class WindowSpec {
    var type: WindowType = WindowType.APPLICATION
    var layer: Int = 0
    var packageName: String? = "com.example.app"
    var title: String? = null
    var bounds: Rect = Rect(0, 0, 1080, 1920)
    var active: Boolean = true
    var focused: Boolean = true
    var rootIdx: Int? = 1

    fun build(windowId: Int): WindowSnapshot =
        WindowSnapshot(windowId, type, layer, packageName, title, bounds, active, focused, rootIdx)
}

/** [ScreenSnapshot] 的可变构造规格。 */
internal class SnapshotSpec {
    var foregroundPackage: String? = "com.example.app"
    var activity: String? = "com.example.app.MainActivity"
    var screenWidth: Int = 1080
    var screenHeight: Int = 1920
    var contentArea: Rect? = null
    var truncated: Boolean = false

    fun build(
        windows: List<WindowSnapshot>,
        nodes: List<NodeSnapshot>,
    ): ScreenSnapshot =
        ScreenSnapshot(
            capturedAt = 1_700_000_000_000,
            windows = windows,
            nodes = nodes,
            foregroundPackage = foregroundPackage,
            activity = activity,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rotation = 0,
            keyguardLocked = false,
            contentArea = contentArea,
            truncated = truncated,
        )
}

/** [SerializerConfig] 的可变构造规格。 */
internal class ConfigSpec {
    var textMaxChars: Int = 40
    var maxMergedLabels: Int = 4
    var maxListItems: Int = 15
    var hideSystemDecoration: Boolean = true
    var diffMaxRatio: Double = 0.4
    var semanticPoorMinInteractive: Int = 3
    var semanticPoorAreaRatio: Double = 0.5
    var degenerateNodeThreshold: Int = 5
    var uncertainConf: Double = 0.6
    var renderPrompt: (String, Map<String, String>) -> String = { id, vars -> "[$id]${vars["mode"].orEmpty()}" }

    fun build(): SerializerConfig =
        SerializerConfig(
            textMaxChars = textMaxChars,
            maxMergedLabels = maxMergedLabels,
            maxListItems = maxListItems,
            hideSystemDecoration = hideSystemDecoration,
            diffMaxRatio = diffMaxRatio,
            semanticPoorMinInteractive = semanticPoorMinInteractive,
            semanticPoorAreaRatio = semanticPoorAreaRatio,
            degenerateNodeThreshold = degenerateNodeThreshold,
            uncertainConf = uncertainConf,
            renderPrompt = renderPrompt,
        )
}

/** 快照构造与通用夹具。 */
internal object TestScreens {
    val rules: SerializerRules =
        SerializerRules(
            schemaVersion = 4,
            tags =
                listOf(
                    TagRule("visual.TextView", "text"),
                    TagRule("TextView", "text"),
                    TagRule("visual.ImageButton", "icon"),
                    TagRule("visual.Icon", "icon"),
                    TagRule("visual.ImageView", "img"),
                    TagRule("ImageView", "img"),
                    TagRule("visual.Button", "btn"),
                    TagRule("Button", "btn"),
                    TagRule("ImageButton", "btn"),
                    TagRule("visual.EditText", "input"),
                    TagRule("EditText", "input"),
                    TagRule("visual.CheckBox", "checkbox"),
                    TagRule("CheckBox", "checkbox"),
                    TagRule("visual.Switch", "switch"),
                    TagRule("Switch", "switch"),
                    TagRule("visual.SeekBar", "slider"),
                    TagRule("SeekBar", "slider"),
                    TagRule("visual.Item", "item"),
                    TagRule("visual.Tab", "tab"),
                    TagRule("visual.TabWidget", "tabbar"),
                    TagRule("TabWidget", "tabbar"),
                    TagRule("RecyclerView", "list"),
                    TagRule("ListView", "list"),
                    TagRule("ScrollView", "pane"),
                    TagRule("ViewPager", "pager"),
                ),
            visualTags =
                listOf(
                    "text",
                    "icon",
                    "img",
                    "btn",
                    "input",
                    "checkbox",
                    "radio",
                    "switch",
                    "slider",
                    "progress",
                    "item",
                    "tab",
                    "tabbar",
                    "list",
                    "web",
                ),
            defaultTag = DefaultTag(interactive = "ctl", label = "text", container = "group"),
            keepContainerTags = listOf("tabbar"),
            windowTypeLabels =
                mapOf(
                    "APPLICATION" to "",
                    "INPUT_METHOD" to "输入法",
                    "SYSTEM" to "系统",
                    "UNKNOWN" to "",
                ),
            semanticPoorClasses = listOf("WebView", "SurfaceView", "TextureView"),
            viewIdPrefixes = listOf("btn_", "iv_", "tv_"),
            viewIdNoise = listOf("^.{0,2}$", "^\\d+$", "^[a-z]{1,2}\\d*$"),
            volatileLabelPatterns = listOf("\\d+"),
        )

    fun config(block: ConfigSpec.() -> Unit = {}): SerializerConfig = ConfigSpec().apply(block).build()

    fun node(
        idx: Int,
        block: NodeSpec.() -> Unit = {},
    ): NodeSnapshot = NodeSpec().apply(block).build(idx)

    fun window(
        windowId: Int = 1,
        block: WindowSpec.() -> Unit = {},
    ): WindowSnapshot = WindowSpec().apply(block).build(windowId)

    fun snapshot(
        windows: List<WindowSnapshot>,
        nodes: List<NodeSnapshot>,
        block: SnapshotSpec.() -> Unit = {},
    ): ScreenSnapshot = SnapshotSpec().apply(block).build(windows, nodes)

    /** 单窗口一棵树的最简快照：根 idx=1，其余节点挂在根下。 */
    fun simple(vararg children: NodeSnapshot): ScreenSnapshot {
        val root = node(1) { this.children = children.map { it.idx } }
        val fixed = children.map { it.copy(parent = 1) }
        return snapshot(windows = listOf(window { rootIdx = 1 }), nodes = listOf(root) + fixed)
    }

    /** 用默认规则序列化（ownPackage 固定为 com.example.autop）。 */
    fun serialize(
        screen: ScreenSnapshot,
        config: SerializerConfig = config(),
        mode: ObserveMode = ObserveMode.A11Y,
    ): SerializedScreen = ScreenSerializer(rules, config).serialize(screen, mode, { RedactionMode.NONE }, OWN_PACKAGE)

    const val OWN_PACKAGE = "com.example.autop"
}
