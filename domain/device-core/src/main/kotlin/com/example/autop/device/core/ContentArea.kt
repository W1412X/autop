/**
 * 屏幕内容区解析（N204，docs/tech/05 §3.2 步骤 0）：屏幕减去状态栏/导航栏等系统装饰
 * 条带后的区域。坐标换算、截图裁剪与节点过滤的统一基准——模型看到的树与图都是内容区，
 * 坐标空间（0–1000 归一化）也随之以内容区为基准（ADR-0006 修订）。
 */
package com.example.autop.device.core

import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType

/** 内容区推导（纯函数）。 */
public object ContentArea {
    /** 由快照推导（采集侧写入 `ScreenSnapshot.contentArea`，各消费方直接取字段即可）。 */
    public fun of(snapshot: ScreenSnapshot): Rect = of(snapshot.screenWidth, snapshot.screenHeight, snapshot.windows)

    /**
     * 从窗口列表推导：系统装饰窗口（[isSystemDecoration]）贴屏幕边的条带从四边裁掉
     * （竖屏状态栏在顶、导航栏在底；横屏导航栏可在左/右，按横/竖条带方向分别判定）。
     * 条带须"贯穿大部且薄"：方向判定（宽/高贯穿 ≥2/3）＋厚度上限（≤1/4），
     * 通知面板展开中间态等"半膨胀"异常条带跳过不裁。无装饰窗口或结果退化时为全屏。
     */
    public fun of(
        screenWidth: Int,
        screenHeight: Int,
        windows: List<WindowSnapshot>,
    ): Rect {
        var left = 0
        var top = 0
        var right = screenWidth
        var bottom = screenHeight
        for (window in windows) {
            if (!window.isSystemDecoration()) continue
            val bounds = window.bounds
            // 条带方向：横条（贴顶/底）宽须贯穿屏幕大部且薄；竖条（贴左/右）高须贯穿大部且薄。
            val horizontalStrip = isHorizontalStrip(bounds, screenWidth, screenHeight)
            val verticalStrip = isVerticalStrip(bounds, screenWidth, screenHeight)
            if (horizontalStrip && bounds.t <= 0) top = maxOf(top, bounds.b)
            if (horizontalStrip && bounds.b >= screenHeight) bottom = minOf(bottom, bounds.t)
            if (verticalStrip && bounds.l <= 0) left = maxOf(left, bounds.r)
            if (verticalStrip && bounds.r >= screenWidth) right = minOf(right, bounds.l)
        }
        if (right - left <= 0 || bottom - top <= 0) {
            return Rect(0, 0, screenWidth, screenHeight)
        }
        return Rect(left, top, right, bottom)
    }

    /** 横条带（贴顶/底）：宽贯穿屏幕大部且薄（厚度上限防“半膨胀”中间态误裁）。 */
    private fun isHorizontalStrip(
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean =
        bounds.width * STRIP_MIN_DENOM >= screenWidth * STRIP_MIN_NUM &&
            bounds.height * STRIP_MAX_DENOM <= screenHeight * STRIP_MAX_NUM

    /** 竖条带（贴左/右）：高贯穿屏幕大部且薄（横屏侧导航栏按方向单独判定）。 */
    private fun isVerticalStrip(
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean =
        bounds.height * STRIP_MIN_DENOM >= screenHeight * STRIP_MIN_NUM &&
            bounds.width * STRIP_MAX_DENOM <= screenWidth * STRIP_MAX_NUM

    private const val STRIP_MIN_NUM: Int = 2
    private const val STRIP_MIN_DENOM: Int = 3
    private const val STRIP_MAX_NUM: Int = 1
    private const val STRIP_MAX_DENOM: Int = 4
}

/**
 * 系统装饰窗口（状态栏/导航栏）：无障碍 API 无专属窗口类型（都是 TYPE_SYSTEM），
 * 按窗口 title 判定（系统 UI 固定为 StatusBar / NavigationBar）。
 * 通知面板、音量/权限对话框等真实交互面不是装饰窗口，保留。
 */
public fun WindowSnapshot.isSystemDecoration(): Boolean {
    if (type != WindowType.SYSTEM) return false
    val title = title?.lowercase().orEmpty()
    return title.contains(TITLE_STATUS_BAR) || title.contains(TITLE_NAVIGATION_BAR)
}

private const val TITLE_STATUS_BAR: String = "statusbar"
private const val TITLE_NAVIGATION_BAR: String = "navigationbar"
