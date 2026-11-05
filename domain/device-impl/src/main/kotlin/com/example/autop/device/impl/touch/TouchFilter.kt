/**
 * 触摸过滤纯函数（docs/tech/05 §8、questions.md N139）：供探针与单测共用。
 * 两路排除——触摸点落在自家 touchable 窗口矩形内（悬浮球 / 面板），或墙钟时刻
 * 落在自身动作窗口内（GestureController 执行 + 宽限）的触摸不算用户操作。
 */
package com.example.autop.device.impl.touch

import com.example.autop.device.OverlaySpec

/** 触摸点 (x, y) 是否落在任一窗口矩形内（宽高 ≤ 0 的窗口视为不存在）。 */
internal fun insideAny(
    specs: List<OverlaySpec>,
    x: Int,
    y: Int,
): Boolean =
    specs.any {
        it.width > 0 &&
            it.height > 0 &&
            x >= it.x &&
            x < it.x + it.width &&
            y >= it.y &&
            y < it.y + it.height
    }

/** 墙钟时刻是否落在任一自身动作窗口内（闭区间）。 */
internal fun insideSelfWindow(
    windows: List<Pair<Long, Long>>,
    now: Long,
): Boolean = windows.any { now >= it.first && now <= it.second }
