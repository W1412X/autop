/**
 * 悬浮球位置持久化（docs/tech/08 §5.1"位置持久化"）：SharedPreferences 存窗口坐标（px）
 * 与停靠锚（坐标绑死在保存时的屏宽上，旋转/分屏后按锚定边在新屏宽上重摆）。
 */
package com.example.autop.feature.overlay.ball

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 球贴边侧（[NONE] 为未贴边的自由位置）。 */
public enum class BallSide {
    NONE,
    LEFT,
    RIGHT,
}

/** 停靠状态（悬浮球四级形态的前三级；「完全隐藏」走窗口显隐，不占位置状态）。 */
public enum class BallDock {
    /** 自由态：停哪是哪。 */
    NONE,

    /** 半贴边态：约 1/3 露在屏内（好抓取），整体弱化。 */
    EDGE,

    /** 隐身态：屏幕边缘细条（特别小），状态染色。 */
    SLIVER,
}

/**
 * 球窗口位置（px，球左上角；x 可为负表示球部分出屏）。
 *
 * [side]/[dock] 是停靠锚：x 只对保存时的屏宽有效，旋转/分屏改尺寸后按 [side] + [dock]
 * 在新屏宽上重摆，避免球跑到屏幕中央。
 */
public data class BallPosition(
    val x: Int,
    val y: Int,
    val side: BallSide = BallSide.NONE,
    val dock: BallDock = BallDock.NONE,
)

/** 位置读写端口。 */
public interface BallPositionStore {
    /** 读取持久化位置；从未持久化时返回 null。 */
    public fun load(): BallPosition?

    /** 持久化当前位置（停靠/唤出后调用）。 */
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
            val side =
                when (prefs.getString(KEY_SIDE, null)) {
                    SIDE_LEFT -> BallSide.LEFT
                    SIDE_RIGHT -> BallSide.RIGHT
                    SIDE_NONE -> BallSide.NONE
                    else -> if (x < 0) BallSide.LEFT else BallSide.RIGHT
                }
            return BallPosition(x, y, side, dockOf())
        }

        /** 停靠态读取：新版读 [KEY_DOCK]；旧版（hidden 布尔）迁移 hidden→细条、否则自由。 */
        private fun dockOf(): BallDock =
            when (prefs.getString(KEY_DOCK, null)) {
                DOCK_EDGE -> BallDock.EDGE
                DOCK_SLIVER -> BallDock.SLIVER
                DOCK_NONE -> BallDock.NONE
                else -> if (prefs.getBoolean(KEY_HIDDEN, false)) BallDock.SLIVER else BallDock.NONE
            }

        override fun save(position: BallPosition) {
            prefs
                .edit()
                .putInt(KEY_X, position.x)
                .putInt(KEY_Y, position.y)
                .putString(KEY_SIDE, sideKeyOf(position.side))
                .putString(KEY_DOCK, dockKeyOf(position.dock))
                .apply()
        }

        private fun sideKeyOf(side: BallSide): String =
            when (side) {
                BallSide.LEFT -> SIDE_LEFT
                BallSide.RIGHT -> SIDE_RIGHT
                BallSide.NONE -> SIDE_NONE
            }

        private fun dockKeyOf(dock: BallDock): String =
            when (dock) {
                BallDock.NONE -> DOCK_NONE
                BallDock.EDGE -> DOCK_EDGE
                BallDock.SLIVER -> DOCK_SLIVER
            }

        private companion object {
            const val PREFS_NAME = "overlay_ball"
            const val KEY_X = "x"
            const val KEY_Y = "y"
            const val KEY_SIDE = "side"
            const val KEY_DOCK = "dock"

            /** 旧版停靠标记（hidden 布尔）；仅迁移读取用。 */
            const val KEY_HIDDEN = "hidden"
            const val SIDE_LEFT = "left"
            const val SIDE_RIGHT = "right"
            const val SIDE_NONE = "none"
            const val DOCK_NONE = "none"
            const val DOCK_EDGE = "edge"
            const val DOCK_SLIVER = "sliver"
        }
    }
