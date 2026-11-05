/**
 * 观测与定位器（docs/tech/05 §1.2、docs/design/observation-modes-coordinate-actions §3）：
 * 观测结果元信息；短 ID 定位信息仅保留给旧回放数据。
 */
package com.example.autop.device

import kotlinx.serialization.Serializable

/** 短 ID 对应节点的定位信息（旧回放 `_locator` 兼容，docs/tech/05 §3.5）。 */
@Serializable
public data class NodeLocator(
    val shortId: String,
    val windowId: Int,
    val viewId: String?,
    val text: String?,
    val desc: String?,
    val className: String?,
    /** 从窗口根到节点的 childIndex 路径。 */
    val path: List<Int>,
    val bounds: Rect,
    /** 稳定键，见 docs/tech/05 §3.4。 */
    val key: String,
)

/** 一次屏幕观测：给模型看的 JSON 文本与元信息。 */
public data class Observation(
    /** 观测文本（统一 JSON，可能带建议换模式尾行）。 */
    val text: String,
    /** 完整或差量。 */
    val mode: ObservationMode,
    /** 观测方式。 */
    val observeMode: ObserveMode,
    val fingerprint: String,
    val packageName: String?,
    val activity: String?,
    /** 渲染降级时使用，如“微信 / LauncherUI，42 个节点”。 */
    val summaryLine: String,
    val interactiveCount: Int,
    /** 本次观测节点数。 */
    val nodeCount: Int,
    /** 语义贫乏（docs/tech/05 §3.7）。 */
    val semanticPoor: Boolean,
)

/** 观测模式：完整序列化或差量（docs/tech/05 §3.6）。 */
public enum class ObservationMode {
    FULL,
    DIFF,
}
