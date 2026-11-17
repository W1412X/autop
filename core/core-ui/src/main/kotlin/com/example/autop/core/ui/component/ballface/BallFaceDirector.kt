/**
 * 表情导演（纯函数，无状态）：表演场景 → 候选池；池内用种子抽取，相邻两次不重复。
 *
 * 同一事件的种子相同，所以悬浮球、控制条、卡片、横幅各自抽取时得到同一场景下的同一套候选，
 * 语义不会打架。种子与抽取完全可复现，单测用固定种子锁定抽取序列。
 */
package com.example.autop.core.ui.component.ballface

import kotlin.random.Random

/** 导演的时序参数。换脸间隔的实际值来自设置页（`LocalBallRerollMs`，ADR-0045），这里只是默认值。 */
public object BallFaceDirector {
    /** 默认的换脸间隔（毫秒）：思考段与混合池超过该时长则在池内重抽一次。 */
    public const val THINK_REROLL_MS: Long = 2_500L

    /** 默认的表情最短驻留（毫秒）：切换后至少停留这么久（ADR-0045）。 */
    public const val MIN_HOLD_MS: Long = 2_500L

    /** 表情淡入淡出时长（毫秒）：换眼型、换符号时的交叉淡入；动效关闭时为 0。 */
    public const val CROSSFADE_MS: Int = 200

    /** 场景 → 候选池（设计稿 POOLS 原序）。 */
    public fun poolOf(scene: BallFaceScene): List<BallFaceExpression> =
        when (scene) {
            BallFaceScene.STARTING ->
                listOf(BallFaceExpressions.startWide, BallFaceExpressions.startTilt, BallFaceExpressions.startOpen)
            BallFaceScene.SAMPLING ->
                listOf(
                    BallFaceExpressions.focus,
                    BallFaceExpressions.thinkUp,
                    BallFaceExpressions.thinkClosed,
                    BallFaceExpressions.thinkSquint,
                    BallFaceExpressions.thinkCurious,
                    BallFaceExpressions.peek,
                )
            BallFaceScene.GESTURE ->
                listOf(
                    BallFaceExpressions.gestureWatch,
                    BallFaceExpressions.gestureTrace,
                    BallFaceExpressions.gestureConfirm,
                )
            BallFaceScene.TEXT ->
                listOf(BallFaceExpressions.textDown, BallFaceExpressions.textSide, BallFaceExpressions.textType)
            BallFaceScene.VISION ->
                listOf(
                    BallFaceExpressions.visionScan,
                    BallFaceExpressions.visionStare,
                    BallFaceExpressions.visionSquint,
                )
            BallFaceScene.APP ->
                listOf(BallFaceExpressions.appLook, BallFaceExpressions.appTilt, BallFaceExpressions.appFar)
            BallFaceScene.COMPACTING -> listOf(BallFaceExpressions.compact, BallFaceExpressions.compactBreath)
            BallFaceScene.MIXED -> PERFORMANCE_SCENES.flatMap { poolOf(it) }
        }

    /**
     * 池内抽取：以 [seed] 初始化随机源（可复现），候选剔除 [previous] 后取一个，保证不与上一张相同。
     * 池中只有一张或 [previous] 不在池中时，直接在整池内抽。
     */
    public fun pick(
        scene: BallFaceScene,
        seed: Long,
        previous: BallFaceExpression? = null,
    ): BallFaceExpression {
        val pool = poolOf(scene)
        val candidates = pool.filter { it != previous }.ifEmpty { pool }
        return candidates[Random(seed).nextInt(candidates.size)]
    }

    /**
     * 事件种子：同一 Run 的同一事件序号得到同一值。各表面（悬浮球、卡片、横幅）用同一输入抽取，
     * 语义场景一致。[runKey] 取 Run 标识（例如 RunId.value），hashCode 在各进程间稳定。
     */
    public fun seedOfEvent(
        runKey: String,
        eventIndex: Int,
    ): Long = runKey.hashCode().toLong() * EVENT_MIX + eventIndex

    /** 同一事件内轮换（思考段重抽）的种子：事件种子与轮换序号混合。 */
    public fun seedOf(
        eventSeed: Long,
        reroll: Int,
    ): Long = eventSeed * SEED_MIX + reroll

    /** 表演场景（不含 MIXED，MIXED 由它们合并而成）。 */
    private val PERFORMANCE_SCENES: List<BallFaceScene> =
        listOf(
            BallFaceScene.STARTING,
            BallFaceScene.SAMPLING,
            BallFaceScene.GESTURE,
            BallFaceScene.TEXT,
            BallFaceScene.VISION,
            BallFaceScene.APP,
            BallFaceScene.COMPACTING,
        )

    private const val SEED_MIX: Long = 31L
    private const val EVENT_MIX: Long = 1_000L
}
