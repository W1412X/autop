/**
 * 屏幕读取端口（docs/tech/05 §1.3）：快照与截图。
 */
package com.example.autop.device

import com.example.autop.core.common.Outcome

/** 屏幕读取：无障碍快照 + 截图（截图节流由 impl 负责）。 */
public interface ScreenReader {
    /**
     * 抓取当前无障碍快照；服务未连接等返回 `device.service_unavailable` 等错误。
     * 视觉/llm 观测不经过本方法（docs/design/observation-modes-coordinate-actions §2.2）。
     */
    public suspend fun snapshot(): Outcome<ScreenSnapshot>

    /** 截取原图（未缩放）。 */
    public suspend fun screenshot(): Outcome<Screenshot>

    /**
     * 尽力快照（N193）：采集失败（如应用屏蔽无障碍节点致空树）时降级为无节点快照
     * （屏幕尺寸/前台信息照常），供不依赖节点树的坐标手势工具换算坐标、生成策略主体。
     * 默认回退 [snapshot]。
     */
    public suspend fun snapshotOrDegraded(): Outcome<ScreenSnapshot> = snapshot()
}

/** 一次截图结果；[handle] 为 impl 私有的 Bitmap，外部不得强转使用。 */
public data class Screenshot(
    val width: Int,
    val height: Int,
    val jpegBytes: ByteArray? = null,
    val handle: Any,
)
