/**
 * 弹窗识别（docs/design/flows-recording-recovery.md R6）：纯算法，只依赖 a11y 快照数据。
 *
 * 规则：
 * 1. 不占满屏幕的 APPLICATION / SYSTEM 窗口视为弹窗候选（面积比例在 [PopupRules] 范围内）。
 * 2. 只在弹窗窗口内部的可点击节点里找关闭按钮：先按描述、再按文字。
 * 3. 文字命中 avoidTexts（支付、授权、卸载……）的节点永远不点。
 * 关键词全部来自 `assets/rules/popup_rules.json`，本文件不含任何界面文字。
 */
package com.example.autop.device.core.popup

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `popup_rules.json` 的内容（顶层带 schemaVersion）。 */
@Serializable
public data class PopupRules(
    @SerialName("schemaVersion") val schemaVersion: Int = CURRENT_POPUP_RULES_SCHEMA_VERSION,
    @SerialName("closeTexts") val closeTexts: List<String> = emptyList(),
    @SerialName("closeDescs") val closeDescs: List<String> = emptyList(),
    @SerialName("avoidTexts") val avoidTexts: List<String> = emptyList(),
    @SerialName("minPopupAreaRatio") val minPopupAreaRatio: Double = 0.05,
    @SerialName("maxPopupAreaRatio") val maxPopupAreaRatio: Double = 0.95,
    /** 禁止强制结束的包名（系统界面等）。 */
    @SerialName("protectedPackages") val protectedPackages: List<String> = emptyList(),
    /** 强制结束后等待多久再重新打开应用（毫秒）。 */
    @SerialName("restartSettleMs") val restartSettleMs: Long = 800,
)

/** 当前 popup_rules.json 的 schemaVersion。 */
public const val CURRENT_POPUP_RULES_SCHEMA_VERSION: Int = 1

/** 弹窗识别结果。 */
public data class PopupPick(
    val node: NodeSnapshot,
    /** 命中的依据：`desc` 或 `text`。 */
    val reason: String,
)

/** 弹窗关闭按钮查找。 */
public object PopupFinder {
    /**
     * 找到第一个可关闭的弹窗按钮；没有弹窗或没有合适按钮返回 null。
     * [windowScoped] 为 true（a11y 快照）时只在非全屏窗口内找；
     * false（视觉伪树只有一个全屏窗口）跳过窗口筛选，在全部可见可点击节点里找。
     */
    public fun find(
        snap: ScreenSnapshot,
        rules: PopupRules,
        windowScoped: Boolean,
    ): PopupPick? {
        val candidates =
            if (windowScoped) {
                val screenArea = snap.screenWidth.toDouble() * snap.screenHeight
                if (screenArea <= 0.0) return null
                val popups =
                    snap.windows
                        .filter { it.type == WindowType.APPLICATION || it.type == WindowType.SYSTEM }
                        .map { it.bounds }
                        .filter { inAreaRange(it.area() / screenArea, rules) }
                if (popups.isEmpty()) return null
                snap.nodes.filter { n ->
                    isVisibleClickable(n) && popups.any { n.bounds.inside(it) } && !avoided(n, rules)
                }
            } else {
                snap.nodes.filter { n -> isVisibleClickable(n) && !avoided(n, rules) }
            }
        return candidates.firstOrNull { matches(it.contentDesc, rules.closeDescs) }?.let { PopupPick(it, REASON_DESC) }
            ?: candidates.firstOrNull { matches(it.text, rules.closeTexts) }?.let { PopupPick(it, REASON_TEXT) }
    }

    private fun inAreaRange(
        ratio: Double,
        rules: PopupRules,
    ): Boolean = ratio >= rules.minPopupAreaRatio && ratio <= rules.maxPopupAreaRatio

    private fun isVisibleClickable(n: NodeSnapshot): Boolean =
        (n.flags and NodeFlags.VISIBLE) != 0 && (n.flags and NodeFlags.CLICKABLE) != 0

    private fun avoided(
        n: NodeSnapshot,
        rules: PopupRules,
    ): Boolean {
        val labels = listOfNotNull(n.text, n.contentDesc)
        return labels.any { label -> rules.avoidTexts.any { label.contains(it, ignoreCase = true) } }
    }

    private fun matches(
        value: String?,
        keywords: List<String>,
    ): Boolean {
        val v = value?.trim() ?: return false
        return keywords.any { v.equals(it, ignoreCase = true) }
    }

    /** 节点是否完全位于矩形内。 */
    private fun Rect.inside(outer: Rect): Boolean = l >= outer.l && t >= outer.t && r <= outer.r && b <= outer.b

    private fun Rect.area(): Double = (r - l).toDouble() * (b - t)

    private const val REASON_DESC = "desc"
    private const val REASON_TEXT = "text"
}
