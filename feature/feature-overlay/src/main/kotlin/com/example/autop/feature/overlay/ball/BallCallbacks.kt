/**
 * 悬浮球手势回调（docs/tech/08 §5.1）：由 [com.example.autop.feature.overlay.OverlayBallController] 实现。
 */
package com.example.autop.feature.overlay.ball

import com.example.autop.core.ui.component.ballface.BallReaction

/** 悬浮球手势回调（由 OverlayBallController 实现）。 */
public interface BallCallbacks {
    /** 拖动中：移动球窗口到绝对位置（px）。 */
    public fun onMove(
        x: Int,
        y: Int,
    )

    /** 拖动结束：近边缘磁吸半贴边 / 就地停留，持久化并启动静置收拢。 */
    public fun onDragEnd(
        ballSizePx: Int,
        screenWidthPx: Int,
        sliverPx: Int,
        magnetPx: Int,
    )

    /** 首次布局：未摆放位置时给出默认位置（右边缘偏上）。 */
    public fun onFirstLayout(
        screenWidthPx: Int,
        screenHeightPx: Int,
        ballSizePx: Int,
        sliverPx: Int,
    )

    /** 自由态单击：展开/收起圆弧菜单（docs/tech/08 §5.1，N182）；[screenWidthPx] 保留签名。 */
    public fun onTap(screenWidthPx: Int)

    /** 细条/半贴边态单击：唤出到自由态。 */
    public fun onReveal(
        screenWidthPx: Int,
        ballSizePx: Int,
    )

    /** 双击：停止（`ui.overlay.doubleTapStops` 开启且运行中时）。 */
    public fun onDoubleTap()

    /** 无障碍断开态单击：跳系统无障碍设置。 */
    public fun onAccessibilityTap()

    /** 球上的点按、长按或唤出（非拖动）：播放对应的手势反应（睡眠中则为唤醒）。 */
    public fun onTouchGesture(kind: BallReaction)

    /** 拖动中：视线跟随拖动方向（[dxPx]、[dyPx] 为相对拖动起点的位移，单位 px）。 */
    public fun onDragGaze(
        dxPx: Float,
        dyPx: Float,
        ballSizePx: Int,
    )

    /** 松手：[velocityPxPerMs] 为拖动平均速度，达到阈值即为甩到边缘。 */
    public fun onDragRelease(velocityPxPerMs: Float)
}
