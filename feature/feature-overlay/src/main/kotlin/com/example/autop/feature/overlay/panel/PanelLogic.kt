/**
 * 面板纯逻辑（docs/tech/08 §5.2）：面板贴靠球侧的位置计算（进度悬浮窗共用）。
 */
package com.example.autop.feature.overlay.panel

/**
 * 面板 x：靠球侧弹出（球右侧放得下则在右，否则在左），钳制在屏幕内。
 *
 * @param ballX 球窗口左上角 x（px）。
 * @param gapPx 球与面板的间距（px）。
 */
internal fun panelXOf(
    ballX: Int,
    ballSizePx: Int,
    panelWidthPx: Int,
    screenWidthPx: Int,
    gapPx: Int,
): Int {
    val rightX = ballX + ballSizePx + gapPx
    val leftX = ballX - panelWidthPx - gapPx
    val x = if (rightX + panelWidthPx <= screenWidthPx) rightX else leftX
    return x.coerceIn(0, (screenWidthPx - panelWidthPx).coerceAtLeast(0))
}

/** 面板 y：与球顶部对齐，不越出屏幕上缘。 */
internal fun panelYOf(ballY: Int): Int = ballY.coerceAtLeast(0)
