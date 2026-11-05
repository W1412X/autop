/**
 * 单个 AccessibilityNodeInfo → NodeSnapshot 的字段读取（docs/tech/05 §2.3）。
 * API < 33 的 recycle 统一由 [useNode] 处理。
 */
package com.example.autop.device.impl

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import android.graphics.Rect as AndroidRect

/** 在 [block] 中使用 [node]，结束后立即丢弃引用（API < 33 调用 recycle）。 */
internal inline fun <T> useNode(
    node: AccessibilityNodeInfo,
    block: (AccessibilityNodeInfo) -> T,
): T =
    try {
        block(node)
    } finally {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            node.recycle()
        }
    }

/** 节点字段读取。 */
internal object NodeSnapshotReader {
    /** 读取节点快照（先序序号 [idx]、父序号 [parent]、在父节点中的位置 [childIndex]）；children 由采集器回填。 */
    fun read(
        node: AccessibilityNodeInfo,
        idx: Int,
        parent: Int?,
        childIndex: Int,
    ): NodeSnapshot {
        val bounds = AndroidRect()
        node.getBoundsInScreen(bounds)
        return NodeSnapshot(
            idx = idx,
            parent = parent,
            children = emptyList(),
            childIndex = childIndex,
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDesc = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            stateDesc = node.stateDescription?.toString(),
            viewId = node.viewIdResourceName,
            bounds = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            flags = flagsOf(node),
            actions = node.actionList.map { it.id },
            collectionRows = node.collectionInfo?.rowCount,
            collectionCols = node.collectionInfo?.columnCount,
            inputType = if (node.isEditable) node.inputType else null,
            packageName = node.packageName?.toString(),
            drawingOrder = node.drawingOrder,
        )
    }

    /** 状态位（docs/tech/05 §2.3）：isHeading API 28+、isAccessibilityDataSensitive API 34+。 */
    fun flagsOf(node: AccessibilityNodeInfo): Int {
        var flags = 0
        flags = flag(flags, node.isClickable, NodeFlags.CLICKABLE)
        flags = flag(flags, node.isLongClickable, NodeFlags.LONG_CLICKABLE)
        flags = flag(flags, node.isEditable, NodeFlags.EDITABLE)
        flags = flag(flags, node.isScrollable, NodeFlags.SCROLLABLE)
        flags = flag(flags, node.isCheckable, NodeFlags.CHECKABLE)
        flags = flag(flags, node.isChecked, NodeFlags.CHECKED)
        flags = flag(flags, node.isSelected, NodeFlags.SELECTED)
        flags = flag(flags, node.isFocused, NodeFlags.FOCUSED)
        flags = flag(flags, node.isEnabled, NodeFlags.ENABLED)
        flags = flag(flags, node.isVisibleToUser, NodeFlags.VISIBLE)
        flags = flag(flags, node.isPassword, NodeFlags.PASSWORD)
        flags = flag(flags, node.isFocusable, NodeFlags.FOCUSABLE)
        flags = flag(flags, node.isHeading, NodeFlags.HEADING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flag(flags, node.isAccessibilityDataSensitive, NodeFlags.DATA_SENSITIVE)
        }
        return flags
    }

    private fun flag(
        flags: Int,
        on: Boolean,
        bit: Int,
    ): Int = if (on) flags or bit else flags
}
