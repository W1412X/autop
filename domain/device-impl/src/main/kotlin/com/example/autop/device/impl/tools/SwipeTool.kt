/**
 * `tool.swipe`（docs/design/observation-modes-coordinate-actions §5.1、§5.2）：
 * 坐标同 tap（内容区 0–1000）；端点抖动与轨迹抖动由工具按配置内部处理。
 * 一般的列表滚动优先用 scroll。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.GestureController
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.PixelSize
import com.example.autop.device.core.SwipeJitter
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.CoordSpaceResolver
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷（起止点为抖动后的屏幕像素）。 */
internal data class SwipeGesture(
    val snapshot: ScreenSnapshot,
    val from: Point,
    val to: Point,
    val durationMs: Long,
    val trajectoryJitterPx: Int,
    val resultText: String,
    val replayArgs: JsonObject,
)

/** 滑动手势工具。 */
@Singleton
@Suppress("LongParameterList") // 装配类：读屏/手势/策略/收尾/坐标/内容区/回执/节点路由均为滑动必需
public class SwipeTool
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val gestures: GestureController,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
        private val coords: CoordSpaceResolver,
        private val contentAreas: ContentAreaProvider,
        private val receipts: HitReceiptBuilder,
        private val trace: ExecutionTraceRenderer,
    ) : ScreenActionTool {
        override val id: String = TOOL_ID

        override val spec = DeviceToolSpecs.swipe

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val parsed = COORD_KEYS.map { args.int(it) }
            if (parsed.any { it == null }) {
                return invalidArg("必须提供 x1、y1、x2、y2 坐标（内容区 0–1000）")
            }
            val values = parsed.filterNotNull()
            val start = Point(values[0], values[1])
            val end = Point(values[2], values.last())
            return when (val got = screenReader.snapshotOrDegraded()) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepared(got.value, start, end, args, ctx)
            }
        }

        private fun prepared(
            snap: ScreenSnapshot,
            start: Point,
            end: Point,
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val base = contentAreas.base()
            val mapper = coords.mapper(PixelSize(snap.screenWidth, snap.screenHeight), base)
            val from = mapper.toScreen(start.x, start.y) ?: return coordOutOfRange(coords)
            val to = mapper.toScreen(end.x, end.y) ?: return coordOutOfRange(coords)
            val area = base ?: Rect(0, 0, snap.screenWidth, snap.screenHeight)
            val cfg = ctx.config[DeviceConfig.SCROLL]
            val jittered =
                SwipeJitter.endpoints(from, to, cfg.endpointJitterPx, area) {
                    ThreadLocalRandom.current().nextDouble()
                }
            val rawDuration = args.long("duration_ms")
            if (rawDuration != null && rawDuration <= 0) return invalidArg("duration_ms 必须大于 0")
            val durationMs = rawDuration ?: cfg.swipeDurationMs
            val tree = receipts.treeOf(ctx, snap)
            val startModel = mapper.toNormalized(jittered.first.x, jittered.first.y)
            val hit = receipts.describe(tree, startModel)
            val subject =
                subjects.build(
                    TOOL_ID,
                    snap,
                    nodeAt(snap, from.x, from.y),
                    mapOf(
                        "x1" to start.x.toString(),
                        "y1" to start.y.toString(),
                        "x2" to end.x.toString(),
                        "y2" to end.y.toString(),
                    ),
                    fallbackTree = tree?.model,
                    fallbackPoint = start,
                )
            val gesture =
                SwipeGesture(
                    snapshot = snap,
                    from = jittered.first,
                    to = jittered.second,
                    durationMs = durationMs,
                    trajectoryJitterPx = cfg.trajectoryJitterPx,
                    resultText =
                        "已滑动 (${start.x}, ${start.y}) → (${end.x}, ${end.y})\n${receipts.receipt(tree, startModel)}",
                    replayArgs = replayArgsOf(args, hit),
                )
            return Outcome.Ok(PreparedAction(subject, gesture))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val swipe = action.payload as SwipeGesture
            val result =
                gestures.swipe(
                    swipe.from.x,
                    swipe.from.y,
                    swipe.to.x,
                    swipe.to.y,
                    swipe.durationMs,
                    swipe.trajectoryJitterPx,
                )
            if (!result.ok) return errorOutput(injectFailed("滑动"))
            val gesture =
                gestureJson(swipe.from.x, swipe.from.y, swipe.to.x, swipe.to.y, swipe.durationMs)
            val out =
                epilogue.finish(
                    ctx,
                    swipe.resultText,
                    null,
                    ReplayInput(TOOL_ID, swipe.replayArgs, gesture),
                )
            return out.copy(
                content =
                    out.content + Block.Text(trace.executionLine(ExecMethod.GESTURE, channel = result.channel)),
            )
        }
    }

private const val TOOL_ID = "tool.swipe"

private val COORD_KEYS = listOf("x1", "y1", "x2", "y2")
