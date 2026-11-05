/**
 * 悬浮球手势回调（docs/tech/08 §5.1）：由 [com.example.autop.feature.overlay.OverlayBallController] 实现。
 */
package com.example.autop.feature.overlay.ball

/** 悬浮球手势回调（由 OverlayBallController 实现）。 */
public interface BallCallbacks {
    /** 拖动中：移动球窗口到绝对位置（px）。 */
    public fun onMove(
        x: Int,
        y: Int,
    )

    /** 拖动结束：按屏宽吸附并持久化。 */
    public fun onDragEnd(
        ballSizePx: Int,
        screenWidthPx: Int,
        edgeHideRatio: Double,
    )

    /** 首次布局：未摆放位置时给出默认位置（右边缘偏上）。 */
    public fun onFirstLayout(
        screenWidthPx: Int,
        screenHeightPx: Int,
        ballSizePx: Int,
    )

    /** 单击：展开/收起圆弧菜单（docs/tech/08 §5.1，N182）；[screenWidthPx] 保留签名。 */
    public fun onTap(screenWidthPx: Int)

    /** 双击：停止（`ui.overlay.doubleTapStops` 开启且运行中时）。 */
    public fun onDoubleTap()

    /** 无障碍断开态单击：跳系统无障碍设置。 */
    public fun onAccessibilityTap()
}
