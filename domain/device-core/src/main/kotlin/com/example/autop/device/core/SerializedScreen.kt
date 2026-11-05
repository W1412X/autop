/**
 * 序列化结果（docs/design/observation-modes-coordinate-actions §3）：
 * 观测元信息 + 统一屏幕模型 + 本次保留的扁平节点行（建树用）。
 */
package com.example.autop.device.core

import com.example.autop.device.Observation
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel

/** 一次序列化的输出。 */
public data class SerializedScreen(
    val observation: Observation,
    val model: ScreenModel,
)

/** 序列化保留的一行节点（建树前的扁平表示，[depth] 表示层级）。 */
public data class KeptNode(
    val idx: Int,
    /** 相对窗口根的层级。 */
    val depth: Int,
    /** 稳定键（差量配对用）。 */
    val key: String,
    val windowId: Int,
    val tag: String,
    val text: String = "",
    val icon: String? = null,
    val desc: String = "",
    val hint: String = "",
    val state: String = "",
    val cls: String? = null,
    val vid: String? = null,
    val act: List<String> = emptyList(),
    val st: List<String> = emptyList(),
    /** 归一化外接框（0–1000）；折叠伪行为 null。 */
    val bounds: Rect? = null,
    /** 推荐操作点（0–1000，仅 act 非空）。 */
    val at: Point? = null,
    /** 折叠伪行：父列表的 `more` 文案（不含前导“…”）。 */
    val more: String? = null,
    /** 文字来源形态（视觉模式区分 text/hint）。 */
    val labelKind: LabelKind = LabelKind.EMPTY,
)

/** 对窗口的脱敏处理。 */
public enum class RedactionMode {
    NONE,
    HIDE_WINDOW,
}
