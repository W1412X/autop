/**
 * 悬浮球位置持久化（docs/tech/08 §5.1"位置持久化"）：SharedPreferences 存窗口坐标（px）
 * 与贴边锚（N217：坐标绑死在保存时的屏宽上，旋转后必须按锚定边在新屏宽上重摆）。
 */
package com.example.autop.feature.overlay.ball

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 球贴边侧（拖动松手吸附到的左右边；[NONE] 为未吸附的瞬时位置）。 */
public enum class BallSide {
    NONE,
    LEFT,
    RIGHT,
}

/**
 * 球窗口位置（px，相对 gravity TOP_LEFT 的偏移；x 可为负表示贴边半隐藏）。
 *
 * [side]/[hidden] 是贴边锚（N217）：x 只对保存时的屏宽有效，旋转/分屏改尺寸后
 * 按 [side] + [hidden] 在新屏宽上重摆，避免球跑到屏幕中央。
 */
public data class BallPosition(
    val x: Int,
    val y: Int,
    val side: BallSide = BallSide.NONE,
    val hidden: Boolean = false,
)

/** 位置读写端口。 */
public interface BallPositionStore {
    /** 读取持久化位置；从未持久化时返回 null。 */
    public fun load(): BallPosition?

    /** 持久化当前位置（吸附/唤出后调用）。 */
    public fun save(position: BallPosition)
}

/** [BallPositionStore] 的 SharedPreferences 实现。 */
@Singleton
public class SharedPrefsBallPositionStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : BallPositionStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun load(): BallPosition? {
            if (!prefs.contains(KEY_X)) return null
            val x = prefs.getInt(KEY_X, 0)
            val y = prefs.getInt(KEY_Y, 0)
            // 旧版只存 x/y，吸附即半隐藏（docs/tech/08 §5.1 旧口径）：迁移为「贴边 + 隐藏」。
            val side =
                when (prefs.getString(KEY_SIDE, null)) {
                    SIDE_LEFT -> BallSide.LEFT
                    SIDE_RIGHT -> BallSide.RIGHT
                    SIDE_NONE -> BallSide.NONE
                    else -> if (x < 0) BallSide.LEFT else BallSide.RIGHT
                }
            val hidden = if (prefs.contains(KEY_HIDDEN)) prefs.getBoolean(KEY_HIDDEN, false) else true
            return BallPosition(x, y, side, hidden)
        }

        override fun save(position: BallPosition) {
            prefs
                .edit()
                .putInt(KEY_X, position.x)
                .putInt(KEY_Y, position.y)
                .putString(KEY_SIDE, sideKeyOf(position.side))
                .putBoolean(KEY_HIDDEN, position.hidden)
                .apply()
        }

        private fun sideKeyOf(side: BallSide): String =
            when (side) {
                BallSide.LEFT -> SIDE_LEFT
                BallSide.RIGHT -> SIDE_RIGHT
                BallSide.NONE -> SIDE_NONE
            }

        private companion object {
            const val PREFS_NAME = "overlay_ball"
            const val KEY_X = "x"
            const val KEY_Y = "y"
            const val KEY_SIDE = "side"
            const val KEY_HIDDEN = "hidden"
            const val SIDE_LEFT = "left"
            const val SIDE_RIGHT = "right"
            const val SIDE_NONE = "none"
        }
    }
