/**
 * 悬浮球表情的对外描述（设计稿 `.tmp-icons/ball-face-events.html`）：状态语义、形态、配色与场景。
 *
 * 状态固定态（[BallFaceMood] 中除 IDLE / RUNNING_START / RUNNING 外的值）一态一张脸，语义唯一；
 * 表演态（空闲 / 起跑 / 思考 / 工具 / 整理）由 [BallFaceDirector] 在场景池内抽取。
 * 空闲池只放中性表情，不含任何状态语义的表情。
 * 颜色不承载状态：主球与卫星统一用主题色，状态只由眼型与卫星符号表达。
 */
package com.example.autop.core.ui.component.ballface

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
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

    /** 空闲：中性表情池（不含任何状态语义的表情），每 5–10 秒随机轮换一次。 */
    IDLE,

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
 * 睡眠时浮动 z 的样式。[count] 为同时存在的 z 个数，[intervalSec] 为相邻 z 出现的间隔（秒）。
 * 数值来自 `ui.ballAmbient`（zCount、zIntervalMs）。
 */
public data class BallZStyle(
    val count: Int = 2,
    val intervalSec: Float = 2f,
)

/**
 * 常态池的数据上下文：由悬浮层与表情切换同节奏（5–10 秒）采样，决定各表情的权重。
 * 全部为 false 时只有基础脸在池中。
 */
public data class BallPoolContext(
    /** 夜间时段（睡眠脸的权重上升）。 */
    val night: Boolean = false,
    /** 晨间时段（哈欠、招手的权重上升）。 */
    val morning: Boolean = false,
    /** 充电中（充电脸的权重上升）。 */
    val charging: Boolean = false,
    /** 低电量且未充电（疲倦脸的权重上升）。 */
    val lowBattery: Boolean = false,
    /** 网络断开（扫视、抬头找的权重上升，表示在找信号）。 */
    val offline: Boolean = false,
    /** 最近有过触摸（点按、拖动、唤出、招手的权重上升）。 */
    val recentTouch: Boolean = false,
)

/**
 * 空闲时的手势反应（瞬时，结束后回到环境状态或空闲表情）。卫星一律慢绕，不做快转。
 */
public enum class BallReaction {
    /** 点按：眯眼一下。 */
    TAP,

    /** 长按：左眼睁大、右眼眯起，视线斜向左上。 */
    LONG_PRESS,

    /** 拖动中：视线跟随拖动方向（由 [BallFaceSpec.gazeDirection] 给出）。 */
    DRAG,

    /** 甩到边缘：睁大双眼并轻微抖动。 */
    FLING,

    /** 从细条唤出：右眼睁开、左眼眯起，轻轻晃头。 */
    REVEAL,

    /** 睡眠中被触摸：平稳睁眼，不眨眼、不快转。 */
    WAKE,

    /** 晨间哈欠：眼睛半闭、视线抬起。 */
    YAWN,

    /** 晨间招手：单眼眯起，轻轻晃头。 */
    GREET,
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
 * @property poolContext 常态池的数据上下文，决定空闲时各表情的权重（只在 IDLE 下生效）。
 * @property reaction 空闲时的手势反应；只在 IDLE 下生效，优先于环境状态。
 * @property offline 网络断开：卫星每隔一段时间停住，像在找信号。
 * @property gazeDirection 拖动时的视线方向，每轴 -1 到 1（相对球半径）；只在 IDLE 且 [reaction] 为 DRAG 时生效。
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
    val poolContext: BallPoolContext = BallPoolContext(),
    /** 睡眠 z 的数量与出现间隔（只在睡眠时生效）。 */
    val zStyle: BallZStyle = BallZStyle(),
    val reaction: BallReaction? = null,
    val offline: Boolean = false,
    val gazeDirection: Offset? = null,
) {
    public companion object {
        /**
         * 眼部基线（1024 坐标）。眼部中心 = 基线 − 512 = +14 设计单位，与设计稿 1408 画布的 718 − 704 一致。
         * 不要直接填设计稿的 718。
         */
        public const val EYE_OFFSET_Y: Float = 526f
    }
}
