/**
 * 屏幕快照模型（docs/tech/05 §1.1）：纯数据，由 impl 从 AccessibilityNodeInfo 采集，
 * device-core 只处理快照。
 */
package com.example.autop.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 屏幕矩形（像素坐标）。 */
@Serializable
public data class Rect(
    @SerialName("l") val l: Int,
    @SerialName("t") val t: Int,
    @SerialName("r") val r: Int,
    @SerialName("b") val b: Int,
) {
    public val width: Int
        get() = r - l

    public val height: Int
        get() = b - t

    public val area: Int
        get() = maxOf(0, width) * maxOf(0, height)

    public val centerX: Int
        get() = (l + r) / 2

    public val centerY: Int
        get() = (t + b) / 2
}

/** 节点状态位定义（协议性位定义，允许常量写在代码里）。 */
public object NodeFlags {
    public const val CLICKABLE: Int = 1
    public const val LONG_CLICKABLE: Int = 2
    public const val EDITABLE: Int = 4
    public const val SCROLLABLE: Int = 8
    public const val CHECKABLE: Int = 16
    public const val CHECKED: Int = 32
    public const val SELECTED: Int = 64
    public const val FOCUSED: Int = 128
    public const val ENABLED: Int = 256
    public const val VISIBLE: Int = 512
    public const val PASSWORD: Int = 1024
    public const val FOCUSABLE: Int = 2048
    public const val HEADING: Int = 4096
    public const val DATA_SENSITIVE: Int = 8192
}

/** 一个无障碍节点的快照。 */
@Serializable
public data class NodeSnapshot(
    /** 本次快照内的序号（先序遍历）。 */
    @SerialName("idx") val idx: Int,
    /** 父节点 idx。 */
    @SerialName("parent") val parent: Int? = null,
    @SerialName("children") val children: List<Int>,
    /** 在父节点中的位置。 */
    @SerialName("childIndex") val childIndex: Int,
    /** 完整类名，如 android.widget.Button。 */
    @SerialName("className") val className: String? = null,
    @SerialName("text") val text: String? = null,
    @SerialName("contentDesc") val contentDesc: String? = null,
    /** API 26+。 */
    @SerialName("hint") val hint: String? = null,
    /** API 30+ stateDescription。 */
    @SerialName("stateDesc") val stateDesc: String? = null,
    /** viewIdResourceName。 */
    @SerialName("viewId") val viewId: String? = null,
    /** 屏幕坐标。 */
    @SerialName("bounds") val bounds: Rect,
    /** [NodeFlags] 位。 */
    @SerialName("flags") val flags: Int,
    /** AccessibilityAction id 列表。 */
    @SerialName("actions") val actions: List<Int> = emptyList(),
    @SerialName("collectionRows") val collectionRows: Int? = null,
    @SerialName("collectionCols") val collectionCols: Int? = null,
    @SerialName("inputType") val inputType: Int? = null,
    @SerialName("packageName") val packageName: String? = null,
    /** R1/A4：兄弟间绘制顺序（越大越靠上），供点击点避让遮挡；旧数据为 null 视作 0。 */
    @SerialName("drawingOrder") val drawingOrder: Int? = null,
    /**
     * 视觉识别置信度（0..1，vp8c §2.7）：仅视觉兜底伪节点有值；兼容扩展，旧数据/真树节点为 null
     * （AutopJson explicitNulls=false，null 不落盘）。
     */
    @SerialName("visualConf") val visualConf: Float? = null,
    /**
     * 图标分类显示名（vp9 §1.5 `icon` 属性）：仅视觉叶子节点有值（图标分类器识别出的
     * 显示名，如“搜索”）；a11y 节点/未识别为 null（AutopJson explicitNulls=false，null 不落盘）。
     */
    @SerialName("iconLabel") val iconLabel: String? = null,
)

/** 窗口类型（AccessibilityWindowInfo.type 映射）。 */
@Serializable
public enum class WindowType {
    APPLICATION,
    INPUT_METHOD,
    SYSTEM,
    ACCESSIBILITY_OVERLAY,
    SPLIT_DIVIDER,
    MAGNIFICATION,
    UNKNOWN,
}

/** 一个窗口的快照。 */
@Serializable
public data class WindowSnapshot(
    @SerialName("windowId") val windowId: Int,
    @SerialName("type") val type: WindowType,
    @SerialName("layer") val layer: Int,
    @SerialName("packageName") val packageName: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("bounds") val bounds: Rect,
    @SerialName("active") val active: Boolean,
    @SerialName("focused") val focused: Boolean,
    @SerialName("rootIdx") val rootIdx: Int? = null,
)

/** 一次屏幕抓取的完整快照。 */
@Serializable
public data class ScreenSnapshot(
    @SerialName("capturedAt") val capturedAt: Long,
    @SerialName("windows") val windows: List<WindowSnapshot>,
    /** 所有窗口的节点，idx 全局唯一。 */
    @SerialName("nodes") val nodes: List<NodeSnapshot>,
    @SerialName("foregroundPackage") val foregroundPackage: String? = null,
    /** 最近一次 TYPE_WINDOW_STATE_CHANGED 的 className（是 Activity 时）。 */
    @SerialName("activity") val activity: String? = null,
    @SerialName("screenWidth") val screenWidth: Int,
    @SerialName("screenHeight") val screenHeight: Int,
    @SerialName("rotation") val rotation: Int,
    @SerialName("keyguardLocked") val keyguardLocked: Boolean,
    /**
     * 内容区（N204）：屏幕减去状态栏/导航栏系统装饰区后的矩形（像素），坐标换算与截图
     * 裁剪的基准；null 视作全屏（降级快照/旧数据）。
     */
    @SerialName("content") val contentArea: Rect? = null,
    /** 节点数达到上限被截断。 */
    @SerialName("truncated") val truncated: Boolean,
)
