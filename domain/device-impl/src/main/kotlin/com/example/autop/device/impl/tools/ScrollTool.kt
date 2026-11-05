/**
 * `tool.scroll`（docs/design/observation-modes-coordinate-actions §5.1、§5.2）：
 * 只用手势滑动；`x,y` 为滚动区域内一点（缺省内容区中心），方向语义沿用
 * `up` = 手指从下往上滑。不再使用 `ACTION_SCROLL_*`，也不自动选可滚动节点。
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
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.GestureController
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.PixelSize
import com.example.autop.device.core.SwipeJitter
import com.example.autop.device.core.clampTo
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.CoordSpaceResolver
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class ScrollAction(
    val from: Point,
    val to: Point,
    val durationMs: Long,
    val trajectoryJitterPx: Int,
    val direction: String,
    val replayArgs: JsonObject,
)

/** 滚动工具。 */
@Singleton
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
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec = DeviceToolSpecs.scroll

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val direction = args.str("direction")
            if (direction == null || direction !in DIRECTIONS) {
                return invalidArg("direction 必须是 up、down、left、right 之一")
            }
            return when (val got = screenReader.snapshotOrDegraded()) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepared(got.value, direction, args, ctx)
            }
        }

        private fun prepared(
            snap: ScreenSnapshot,
            direction: String,
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val x = args.int("x") ?: CENTER
            val y = args.int("y") ?: CENTER
            val base = contentAreas.base()
            val mapper = coords.mapper(PixelSize(snap.screenWidth, snap.screenHeight), base)
            val center = mapper.toScreen(x, y) ?: return coordOutOfRange(coords)
            val area = base ?: Rect(0, 0, snap.screenWidth, snap.screenHeight)
            val cfg = ctx.config[DeviceConfig.SCROLL]
            val ratio = if (args.str("amount") == AMOUNT_HALF) cfg.halfRatio else cfg.pageRatio
            val points = swipePoints(center, direction, ratio, area)
            val jittered =
                SwipeJitter.endpoints(
                    points.first,
                    points.second,
                    cfg.endpointJitterPx,
                    area,
                ) { ThreadLocalRandom.current().nextDouble() }
            val tree = receipts.treeOf(ctx, snap)
            val subject =
                subjects.build(
                    ID,
                    snap,
                    nodeAt(snap, center.x, center.y),
                    mapOf(EXTRA_DIRECTION to direction),
                    fallbackTree = tree?.model,
                    fallbackPoint = Point(x, y),
                )
            val action =
                ScrollAction(
                    from = jittered.first,
                    to = jittered.second,
                    durationMs = cfg.swipeDurationMs,
                    trajectoryJitterPx = cfg.trajectoryJitterPx,
                    direction = direction,
                    replayArgs = replayArgsOf(args, receipts.describe(tree, Point(x, y))),
                )
            return Outcome.Ok(PreparedAction(subject, action))
        }

        /** 滑动几何：中点沿反方向起滑，距离 = 比例 × 基准区对应边长 ÷ 2，起止点钳在基准区内。 */
        private fun swipePoints(
            center: Point,
            direction: String,
            ratio: Double,
            area: Rect,
        ): Pair<Point, Point> {
            val dx = (area.width * ratio / 2).toInt()
            val dy = (area.height * ratio / 2).toInt()
            val (from, to) =
                when (direction) {
                    "up" -> Point(center.x, center.y + dy) to Point(center.x, center.y - dy)
                    "down" -> Point(center.x, center.y - dy) to Point(center.x, center.y + dy)
                    "left" -> Point(center.x + dx, center.y) to Point(center.x - dx, center.y)
                    else -> Point(center.x - dx, center.y) to Point(center.x + dx, center.y)
                }
            return from.clampTo(area) to to.clampTo(area)
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val scroll = action.payload as ScrollAction
            val pre = epilogue.begin()
            val ok =
                gestures.swipe(
                    scroll.from.x,
                    scroll.from.y,
                    scroll.to.x,
                    scroll.to.y,
                    scroll.durationMs,
                    scroll.trajectoryJitterPx,
                )
            if (!ok) {
                return errorOutput(AppError(code = DeviceErrorCodes.GESTURE_CANCELLED, message = "滚动手势被取消"))
            }
            val gesture =
                gestureJson(scroll.from.x, scroll.from.y, scroll.to.x, scroll.to.y, scroll.durationMs)
            return epilogue.finish(
                ctx,
                "已向${directionName(scroll.direction)}滚动",
                null,
                ReplayInput(ID, pre, scroll.replayArgs, gesture),
            )
        }

        private fun directionName(direction: String): String =
            when (direction) {
                "up" -> "上"
                "down" -> "下"
                "left" -> "左"
                else -> "右"
            }

        private companion object {
            const val ID = "tool.scroll"
            const val EXTRA_DIRECTION = "direction"
            const val CENTER = 500
            const val AMOUNT_HALF = "half"
            val DIRECTIONS = setOf("up", "down", "left", "right")
        }
    }
