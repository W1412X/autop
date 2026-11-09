/**
 * 视觉屏幕解析端口（docs/design/visual-screen-parsing §5.1）：
 * 无障碍节点树被屏蔽/退化时，从截图合成伪节点树兜底。
 */
package com.example.autop.device

/** 输入帧（纯数据；impl 内部解码 Bitmap）。 */
public data class VisualFrame(
    /** JPEG/PNG 原始字节。 */
    val imageBytes: ByteArray,
    val width: Int,
    val height: Int,
    /** Surface.ROTATION_* 值。 */
    val rotation: Int,
    val capturedAt: Long,
    /** 内容区（像素，N204）：装饰区检测框/OCR 行过滤基准；null=不过滤。 */
    val contentArea: Rect? = null,
)

/** 合成结果：伪节点列表＋根节点 idx（窗口元信息由采集侧组装）。 */
public data class VisualTree(
    val rootIdx: Int,
    val nodes: List<NodeSnapshot>,
)

/** 视觉屏幕解析器：截图 → 伪节点树。 */
public interface VisualScreenParser {
    /** 解析器是否可用（`device.vision.enabled` 且检测/OCR 模型就绪，docs/design/observation-modes-coordinate-actions §2.1）。 */
    public val available: Boolean

    /** 解析一帧；返回 null 表示本帧不可解析（secure 黑帧/解析失败）。 */
    public suspend fun synthesize(frame: VisualFrame): VisualTree?

    /**
     * 预热：后台创建 ONNX 会话（模型资产读取＋会话初始化）。
     * 首次调用不做冷启动加载，避免首次观测在解析预算内超时；失败不抛错（按未就绪处理）。
     */
    public suspend fun warmUp()
}
