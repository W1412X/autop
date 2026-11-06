/**
 * `tool.scroll`（docs/design/observation-modes-coordinate-actions §5.1；ADR-0015）：
 * 一步 = 一次人手滑动，距离由可滚动容器的尺寸决定；`steps` 连续多步，内容到头时提前停止。
 * 观测与行动都来自 a11y 且容器支持方向化滚动动作时走节点动作，否则用容器内的手势。
 * `x,y` 为滚动区域内一点（缺省内容区中心）；方向沿用 `up` = 手指从下往上滑。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.DeviceScrollSection
import com.example.autop.device.DeviceErrorCodes
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
    val steps: Int,
    val replayArgs: JsonObject,
)

/** 一步的执行结果。 */
private data class StepResult(
    /** 屏幕是否变化；null 表示不可判断（非 a11y 来源、空树或静默模式）。 */
    val moved: Boolean?,
    val gesture: JsonObject?,
    val newlyBlocked: Boolean,
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
            val action =
                ScrollAction(
                    snapshot = snap,
                    anchor = Point(x, y),
                    direction = direction,
                    steps = (args.int("steps") ?: 1).coerceIn(1, maxSteps),
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
            var before = pre?.fingerprint
            var done = 0
            var reachedEnd = false
            var blocked = false
            var firstGesture: JsonObject? = null
            while (done < scroll.steps && !reachedEnd) {
                if (done > 0) snap = screenReader.snapshotOrDegraded().let { (it as? Outcome.Ok)?.value } ?: snap
                val step = runStep(ctx, scroll, snap, before)
                if (step.error != null) return errorOutput(step.error)
                done += 1
                blocked = blocked || step.newlyBlocked
                if (done == 1) firstGesture = step.gesture
                reachedEnd = step.moved == false
                if (before != null && !reachedEnd && done < scroll.steps) before = epilogue.fingerprintNow() ?: before
            }
            val replay = ReplayInput(ID, pre, scroll.replayArgs, firstGesture)
            val out = epilogue.finish(ctx, resultText(scroll, done, reachedEnd), null, replay)
            return if (blocked) out.copy(content = out.content + Block.Text(BLOCKED_TEXT)) else out
        }

        /** 一步：优先节点动作；节点无效（被拒或屏幕无变化）时补一次手势——滚动没有副作用，重复无害。 */
        private suspend fun runStep(
            ctx: ToolContext,
            scroll: ScrollAction,
            snap: ScreenSnapshot,
            before: String?,
        ): StepResult {
            val base = contentAreas.base()
            val mapper = coords.mapper(PixelSize(snap.screenWidth, snap.screenHeight), base)
            val anchor = mapper.toScreen(scroll.anchor.x, scroll.anchor.y) ?: return StepResult(null, null, false)
            val route = router.attempt(nodeRequest(ctx, snap, anchor, scroll.direction))
            if (route is NodeRoute.Done) {
                val moved = movedAfterAction(ctx, snap, before)
                router.afterNodeDone(route.packageName, moved)
                if (moved != false) return StepResult(moved, null, false)
            }
            val area = base ?: Rect(0, 0, snap.screenWidth, snap.screenHeight)
            val container =
                router.scrollContainer(ctx, snap, anchor)?.let { ScrollGeometry.intersectOrSelf(it, area) } ?: area
            val gesture =
                swipe(ctx, container, anchor, scroll.direction)
                    ?: return StepResult(null, null, false, AppError(DeviceErrorCodes.GESTURE_CANCELLED, "滚动手势被取消"))
            val moved = movedAfterAction(ctx, snap, before)
            val learnPkg =
                when (route) {
                    is NodeRoute.Done -> route.packageName
                    is NodeRoute.Rejected -> route.packageName
                    is NodeRoute.Skipped -> route.packageName
                }
            return StepResult(moved, gesture, router.afterGesture(ctx, learnPkg, moved))
        }

        private fun nodeRequest(
            ctx: ToolContext,
            snap: ScreenSnapshot,
            anchor: Point,
            direction: ScrollDirection,
        ): NodeRouteRequest =
            NodeRouteRequest(ctx, snap, anchor, NodeKind.SCROLL, NodeActionIds.scrollActionOf(direction))

        /** 执行容器内的单步手势；返回手势 JSON，被取消返回 null。 */
        private suspend fun swipe(
            ctx: ToolContext,
            container: Rect,
            anchor: Point,
            direction: ScrollDirection,
        ): JsonObject? {
            val cfg = ctx.config[DeviceConfig.SCROLL]
            val step = ScrollGeometry.step(container, anchor, direction, paramsOf(cfg))
            val (from, to) =
                SwipeJitter.endpoints(step.from, step.to, cfg.endpointJitterPx, container) {
                    ThreadLocalRandom.current().nextDouble()
                }
            val ok = gestures.swipe(from.x, from.y, to.x, to.y, step.durationMs, cfg.trajectoryJitterPx)
            return if (ok) gestureJson(from.x, from.y, to.x, to.y, step.durationMs) else null
        }

        private fun paramsOf(cfg: DeviceScrollSection): ScrollGeometryParams =
            ScrollGeometryParams(
                cfg.stepRatio,
                cfg.edgeInsetRatio,
                cfg.pixelsPerMs,
                cfg.minDurationMs,
                cfg.maxDurationMs,
            )

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
        ): String {
            val steps = if (done > 1) "（共 $done 步）" else ""
            val end = if (reachedEnd) "，已到头，继续向该方向滚动不会有新内容" else ""
            return "已向${directionName(scroll.direction)}滚动$steps$end"
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
