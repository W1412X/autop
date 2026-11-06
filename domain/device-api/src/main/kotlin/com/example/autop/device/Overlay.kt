/**
 * 悬浮窗端口（docs/tech/05 §1.3、§10）：OverlayHost 只管理窗口，
 * 不认识窗口内容；内容由 app 模块注册的 View 工厂提供。
 */
package com.example.autop.device

import kotlinx.coroutines.flow.Flow

/** 悬浮窗位置（映射 WindowManager.LayoutParams.gravity 的九宫格 + 居中）。 */
public enum class OverlayGravity {
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    LEFT,
    CENTER,
    RIGHT,
    BOTTOM_LEFT,
    BOTTOM,
    BOTTOM_RIGHT,
}

/** 悬浮窗布局参数。 */
public data class OverlaySpec(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val touchable: Boolean,
    val focusable: Boolean,
    val gravity: OverlayGravity,
    /** 是否需要 `FLAG_WATCH_OUTSIDE_TOUCH`（触摸探针窗口用，docs/tech/05 §8）。 */
    val watchOutsideTouch: Boolean = false,
    /**
     * 铺满整个屏幕（含状态栏、导航栏与刘海区域）。默认的悬浮窗会被摆在系统栏之内，
     * 需要贴到物理屏幕边缘的内容（如执行期边缘光）设为 true；此时 [width]、[height] 应为 MATCH_PARENT。
     */
    val fullScreen: Boolean = false,
)

/** 悬浮窗宿主：只管窗口（位置、大小、触摸标志、可见性），不认识内容。 */
public interface OverlayHost {
    /** 显示窗口；内容由已注册的 View 工厂按 [id] 提供。 */
    public fun show(
        id: String,
        spec: OverlaySpec,
    )

    /** 更新窗口布局。 */
    public fun update(
        id: String,
        spec: OverlaySpec,
    )

    /** 隐藏窗口。 */
    public fun hide(id: String)

    /** 执行 [block] 期间隐藏全部窗口（截图前调用）。 */
    public suspend fun <T> hiddenDuring(block: suspend () -> T): T
}

/** 悬浮窗权限（备用窗口通道）。 */
public interface OverlayPermission {
    /** 是否已授予。 */
    public fun granted(): Boolean

    /** 权限变化（应用回到前台时重新检测）。 */
    public val changes: Flow<Boolean>
}
