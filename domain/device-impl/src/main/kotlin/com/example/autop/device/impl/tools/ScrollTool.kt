/**
 * `tool.scroll`（docs/design/observation-modes-coordinate-actions §5.1；ADR-0015）：
 * 一步 = 一次人手滑动，距离由可滚动容器的尺寸决定；`steps` 连续多步，内容到头时提前停止。
 * 动作快照有实时 a11y 节点树且目标支持方向化滚动动作时走节点动作，否则用容器内的手势。
 * `x,y` 为滚动区域内一点（缺省内容区中心）；方向沿用 `up` = 手指从下往上滑。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.DeviceScrollSection
import com.example.autop.device.GestureController
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.PixelSize
import com.example.autop.device.core.ScrollDirection
import com.example.autop.device.core.ScrollGeometry
import com.example.autop.device.core.ScrollGeometryParams
import com.example.autop.device.core.SwipeJitter
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.CoordSpaceResolver
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.nodeaction.NodeActionIds
import com.example.autop.device.impl.nodeaction.NodeActionRouter
import com.example.autop.device.impl.nodeaction.NodeKind
import com.example.autop.device.impl.nodeaction.NodeRoute
import com.example.autop.device.impl.nodeaction.NodeRouteRequest
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class ScrollAction(
    val snapshot: ScreenSnapshot,
    /** 模型给的滚动区域内一点（0–1000）。 */
    val anchor: Point,
    val direction: ScrollDirection,
    /** 实际执行步数（已钳到上限）。 */
    val steps: Int,
    /** 模型请求的步数（大于 [steps] 时被截断，收尾文本要说明）。 */
    val requestedSteps: Int,
    /** 传了已废弃的 amount（收尾文本说明它被忽略）。 */
    val amountIgnored: Boolean,
    val replayArgs: JsonObject,
)

/** 一步的执行结果。 */
private data class StepResult(
    /** 屏幕是否变化；null 表示不可判断（非 a11y 来源、空树或静默模式）。 */
    val moved: Boolean?,
    val gesture: JsonObject?,
    val newlyBlocked: Boolean,
    /** 这一步实际怎么执行的回执（模型可见）。 */
    val trace: String,
    val error: AppError? = null,
)

/** 滚动工具。 */
@Singleton
@Suppress("LongParameterList") // 装配类：读屏/手势/策略/收尾/坐标/内容区/回执/节点路由均为滚动必需，不可再减（N114 口径）
public class ScrollTool
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val gestures: GestureController,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
        private val coords: CoordSpaceResolver,
        private val contentAreas: ContentAreaProvider,
        private val receipts: HitReceiptBuilder,
        private val router: NodeActionRouter,
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec = DeviceToolSpecs.scroll

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val direction =
                ScrollDirection.of(args.str("direction"))
                    ?: return invalidArg("direction 必须是 up、down、left、right 之一")
            return when (val got = screenReader.snapshotOrDegraded()) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepared(got.value, direction, args, ctx)
            }
        }

        private fun prepared(
            snap: ScreenSnapshot,
            direction: ScrollDirection,
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val x = args.int("x") ?: CENTER
            val y = args.int("y") ?: CENTER
            val base = contentAreas.base()
            val mapper = coords.mapper(PixelSize(snap.screenWidth, snap.screenHeight), base)
            val center = mapper.toScreen(x, y) ?: return coordOutOfRange(coords)
            val tree = receipts.treeOf(ctx, snap)
            val subject =
                subjects.build(
                    ID,
                    snap,
                    nodeAt(snap, center.x, center.y),
                    mapOf(EXTRA_DIRECTION to direction.id),
                    fallbackTree = tree?.model,
                    fallbackPoint = Point(x, y),
                )
            val maxSteps = ctx.config[DeviceConfig.SCROLL].maxSteps
            val requested = args.int("steps") ?: 1
            val action =
                ScrollAction(
                    snapshot = snap,
                    anchor = Point(x, y),
                    direction = direction,
                    steps = requested.coerceIn(1, maxSteps),
                    requestedSteps = requested,
                    amountIgnored = args.containsKey("amount"),
                    replayArgs = replayArgsOf(args, receipts.describe(tree, Point(x, y))),
                )
            return Outcome.Ok(PreparedAction(subject, action))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val scroll = action.payload as ScrollAction
            val pre = epilogue.begin(ctx)
            var snap = scroll.snapshot
            var before = baselineFingerprint(pre?.fingerprint)
            var done = 0
            var reachedEnd = false
            var blocked = false
            var firstGesture: JsonObject? = null
            val traces = mutableListOf<String>()
            while (done < scroll.steps && !reachedEnd) {
                if (done > 0) snap = screenReader.snapshotOrDegraded().let { (it as? Outcome.Ok)?.value } ?: snap
                val step = runStep(ctx, scroll, snap, before)
                if (step.error != null) return errorOutput(step.error)
                done += 1
                traces += "第 $done 步 ${step.trace}"
                blocked = blocked || step.newlyBlocked
                if (done == 1) firstGesture = step.gesture
                reachedEnd = step.moved == false
                if (before != null && !reachedEnd && done < scroll.steps) before = epilogue.fingerprintNow() ?: before
            }
            val replay = ReplayInput(ID, pre, scroll.replayArgs, firstGesture)
            val out = epilogue.finish(ctx, resultText(scroll, done, reachedEnd, traces), null, replay)
            return if (blocked) out.copy(content = out.content + Block.Text(BLOCKED_TEXT)) else out
        }

        /** 一步：优先节点动作；节点无效（被拒或屏幕无变化）时补一次手势——滚动没有副作用，重复无害。 */
        private suspend fun runStep(
            ctx: ToolContext,
            scroll: ScrollAction,
            snap: ScreenSnapshot,
            before: String?,
        ): StepResult {
            val label = NodeActionIds.scrollLabelOf(scroll.direction)
            val base = contentAreas.base()
            val mapper = coords.mapper(PixelSize(snap.screenWidth, snap.screenHeight), base)
            val anchor = mapper.toScreen(scroll.anchor.x, scroll.anchor.y)
            if (anchor == null) {
                return StepResult(
                    null,
                    null,
                    false,
                    "",
                    AppError(CommonErrorCodes.INVALID_ARGUMENT, "滚动坐标换算失败"),
                )
            }
            val route = router.attempt(nodeRequest(ctx, snap, anchor, scroll.direction))
            if (route is NodeRoute.Done) {
                val moved = movedAfterAction(ctx, snap, before)
                router.afterNodeDone(route.packageName, moved)
                if (moved != false) {
                    return StepResult(moved, null, false, nodeTrace(label, moved))
                }
            }
            val area = base ?: Rect(0, 0, snap.screenWidth, snap.screenHeight)
            val container =
                router.scrollContainer(ctx, snap, anchor)?.let { ScrollGeometry.intersectOrSelf(it, area) } ?: area
            val shot =
                swipe(ctx, container, anchor, scroll.direction)
                    ?: return StepResult(
                        null,
                        null,
                        false,
                        "",
                        injectFailed("滚动手势"),
                    )
            val moved = movedAfterAction(ctx, snap, before)
            val learnPkg =
                when (route) {
                    is NodeRoute.Done -> route.packageName
                    is NodeRoute.Rejected -> route.packageName
                    is NodeRoute.Skipped -> route.packageName
                }
            val fallback =
                if (route is NodeRoute.Done) {
                    "节点动作无变化（$label），补一次手势"
                } else {
                    routeFallback(route, label)
                }
            val trace =
                executionLine(ExecMethod.GESTURE, channel = shot.result.channel, fallback = fallback) +
                    effectSuffix(moved)
            return StepResult(moved, shot.replay, router.afterGesture(ctx, learnPkg, moved), trace)
        }

        private fun nodeTrace(
            label: String,
            moved: Boolean?,
        ): String = executionLine(ExecMethod.NODE_ACTION, label) + effectSuffix(moved)

        private fun effectSuffix(moved: Boolean?): String = "；${effectLine(effectOf(moved))}"

        private fun nodeRequest(
            ctx: ToolContext,
            snap: ScreenSnapshot,
            anchor: Point,
            direction: ScrollDirection,
        ): NodeRouteRequest =
            NodeRouteRequest(ctx, snap, anchor, NodeKind.SCROLL, NodeActionIds.scrollActionOf(direction))

        /** 执行容器内的单步手势；返回实际通道与回放参数，注入未送达返回 null。 */
        private suspend fun swipe(
            ctx: ToolContext,
            container: Rect,
            anchor: Point,
            direction: ScrollDirection,
        ): GestureShot? {
            val cfg = ctx.config[DeviceConfig.SCROLL]
            val step = ScrollGeometry.step(container, anchor, direction, paramsOf(cfg))
            val (from, to) =
                SwipeJitter.endpoints(step.from, step.to, cfg.endpointJitterPx, container) {
                    ThreadLocalRandom.current().nextDouble()
                }
            val result = gestures.swipe(from.x, from.y, to.x, to.y, step.durationMs, cfg.trajectoryJitterPx)
            if (!result.ok) return null
            return GestureShot(result, gestureJson(from.x, from.y, to.x, to.y, step.durationMs))
        }

        private fun paramsOf(cfg: DeviceScrollSection): ScrollGeometryParams =
            ScrollGeometryParams(
                cfg.stepRatio,
                cfg.edgeInsetRatio,
                cfg.pixelsPerMs,
                cfg.minDurationMs,
                cfg.maxDurationMs,
            )

        /** 基线指纹：优先动作前采样；quiet（流程）下自补一份，否则 moved 判不出来、到头也不提前停。 */
        private suspend fun baselineFingerprint(preFingerprint: String?): String? =
            preFingerprint ?: epilogue.fingerprintNow()

        /** 动作后等界面稳定，再用探针取指纹与 [before] 比较；静默模式（[before] 为空）或不可信时返回 null。 */
        private suspend fun movedAfterAction(
            ctx: ToolContext,
            snap: ScreenSnapshot,
            before: String?,
        ): Boolean? {
            if (before == null) return null
            epilogue.awaitStable(ctx)
            return router.movedOrNull(ctx, snap, before, epilogue.fingerprintNow())
        }

        private fun resultText(
            scroll: ScrollAction,
            done: Int,
            reachedEnd: Boolean,
            traces: List<String>,
        ): String {
            val steps = if (done > 1) "（共 $done 步）" else ""
            val end = if (reachedEnd) "，已到头，继续向该方向滚动不会有新内容" else ""
            val clamped =
                if (scroll.requestedSteps > scroll.steps) {
                    "；steps 超出上限，已截断到 ${scroll.steps} 步"
                } else {
                    ""
                }
            val ignored = if (scroll.amountIgnored) "；amount 已废弃，本次已忽略（距离由容器尺寸决定）" else ""
            val detail = traces.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "\n") ?: ""
            return "已向${directionName(scroll.direction)}滚动$steps$end$clamped$ignored$detail"
        }

        private fun directionName(direction: ScrollDirection): String =
            when (direction) {
                ScrollDirection.UP -> "上"
                ScrollDirection.DOWN -> "下"
                ScrollDirection.LEFT -> "左"
                ScrollDirection.RIGHT -> "右"
            }

        private companion object {
            const val ID = "tool.scroll"
            const val EXTRA_DIRECTION = "direction"
            const val CENTER = 500
            const val BLOCKED_TEXT = "该应用不响应无障碍节点操作，已记录；之后对它的点击、长按、滚动会自动改用手势。"
        }
    }
