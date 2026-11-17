/**
 * 悬浮球表情的对外描述（设计稿 `.tmp-icons/ball-face-events.html`）：状态语义、形态、配色与场景。
 *
 * 固定态（[BallFaceMood] 中除 RUNNING_START / RUNNING 外的值）一态一张脸，语义唯一；
 * 表演态（起跑 / 思考 / 工具 / 整理）由 [BallFaceDirector] 在场景池内抽取。
 * 颜色不承载状态：主球与卫星统一用主题色，状态只由眼型与卫星符号表达。
 */
package com.example.autop.core.ui.component.ballface

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** 表情状态。RUNNING_START 只用于首步起跑；RUNNING 按 [BallFaceSpec.scene] 抽表演脸。 */
public enum class BallFaceMood {
    IDLE,
    RUNNING_START,
    RUNNING,
    WAITING,
    PAUSED,
    BLOCKED,
    LOST,
    RECORDING,
    SUCCESS,
    FAILURE,
}

/** 表演场景（决定抽取的表情池，对应设计稿的池分组）。 */
public enum class BallFaceScene {
    /** 起跑：3 个。 */
    STARTING,

    /** 思考（模型采样）：6 个，段内可每 2.5s 轮换。 */
    SAMPLING,

    /** 手势类工具：3 个。 */
    GESTURE,

    /** 文本类工具：3 个。 */
    TEXT,

    /** 视觉类工具：3 个。 */
    VISION,

    /** 应用类工具：3 个。 */
    APP,

    /** 整理（压缩上下文）：2 个。 */
    COMPACTING,

    /** 多会话同时运行：从全部表演脸中随机抽取（每 2.5 秒换一次，相邻不重复）。 */
    MIXED,
}

/** 球体形态：实心圆 / 线条（开口环）。全部表面统一读取设置页的同一份配置。 */
public enum class BallFaceVariant {
    /** 实心圆盘（默认）。 */
    SOLID,

    /** 开口环，环与眼睛用主题色线稿；卫星仍是实心底 + 眼白符号。 */
    LINE,
}

/**
 * 悬浮球颜色（设置页 `ui.appearance.ballColor`）：[THEME] 跟随主题色（默认），[BLACK] / [WHITE] 为固定色。
 * 只作用于 [BallFacePaint.THEME] 配色的表情，亮面、墨色、容器等配色不变。
 */
public enum class BallFaceColor {
    THEME,
    BLACK,
    WHITE,
}

/** 配色模式：跟随主题色 / 墨色 / 单色 / 主题容器。 */
public enum class BallFacePaint {
    THEME,
    INK,
    MONO,
    CONTAINER,

    /** 亮面：圆盘用 surfaceBright（浅色主题近白，深色主题为深色亮面），眼睛与符号用主题色。 */
    INVERSE,
}

/**
 * 表情渲染参数。
 *
 * @property variant 形态；null 表示跟随 `LocalBallShape`（设置页的球体形态）。
 * @property scene 表演场景（仅 mood 为 RUNNING / RUNNING_START 时生效；RUNNING_START 固定为 STARTING）。
 * @property seed 表演抽取的种子，同一事件传同一值，各表面得到同一语义的脸。
 * @property count 会话数字（悬浮球）。运行中显示在卫星上；其余状态显示在卫星角标上；null 不显示。
 * @property tint 强制单色（横幅、通知等跟随文字色的位置）。
 * @property motionEnabled 与 `ui.appearance.motionEnabled` 求与；关闭时只画静态落帧。
 */
@Immutable
public data class BallFaceSpec(
    val mood: BallFaceMood = BallFaceMood.IDLE,
    val variant: BallFaceVariant? = null,
    val paint: BallFacePaint = BallFacePaint.THEME,
    val scene: BallFaceScene = BallFaceScene.SAMPLING,
    val seed: Long = 0L,
    val tint: Color? = null,
    val motionEnabled: Boolean = true,
    val count: Int? = null,
) {
    public companion object {
        /**
         * 眼部基线（1024 坐标）。眼部中心 = 基线 − 512 = +14 设计单位，与设计稿 1408 画布的 718 − 704 一致。
         * 不要直接填设计稿的 718。
         */
        public const val EYE_OFFSET_Y: Float = 526f
    }
}
