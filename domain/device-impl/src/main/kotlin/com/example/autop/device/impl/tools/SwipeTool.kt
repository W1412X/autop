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
import com.example.autop.core.prompt.PromptRepository
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
    val waitMs: Long?,
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
        private val prompts: PromptRepository,
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
            val from = mapper.toScreen(start.x, start.y)
            val to = mapper.toScreen(end.x, end.y)
            if (from == null || to == null) return coordOutOfRange(coords)
            val area = base ?: Rect(0, 0, snap.screenWidth, snap.screenHeight)
            val cfg = ctx.config[DeviceConfig.SCROLL]
            val jittered =
                SwipeJitter.endpoints(from, to, cfg.endpointJitterPx, area) {
                    ThreadLocalRandom.current().nextDouble()
                }
            val durationError = durationErrorOf(args, cfg)
            if (durationError != null) return durationError
            val durationMs = args.long("duration_ms") ?: cfg.swipeDurationMs
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
                    ctx.config,
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
                    waitMs = waitMsOf(args),
                    resultText = resultTextOf(start, end, receipts.receipt(tree, startModel)),
                    replayArgs = replayArgsOf(args, hit),
                )
            return Outcome.Ok(PreparedAction(subject, gesture))
        }

        /** duration_ms 校验（tool_optimize §6）：正数且不超过 swipeMaxDurationMs。 */
        private fun durationErrorOf(
            args: JsonObject,
            cfg: com.example.autop.core.config.DeviceScrollSection,
        ): com.example.autop.core.common.Outcome.Err? {
            val raw = args.long("duration_ms") ?: return null
            if (raw <= 0) return invalidArg("duration_ms 必须大于 0")
            if (raw > cfg.swipeMaxDurationMs) {
                return invalidArg(
                    prompts.text(
                        PROMPT_DURATION_TOO_LONG,
                        mapOf(
                            VAR_MAX to cfg.swipeMaxDurationMs.toString(),
                        ),
                    ),
                )
            }
            return null
        }

        private fun resultTextOf(
            start: Point,
            end: Point,
            receipt: String,
        ): String =
            prompts.text(
                PROMPT_RESULT,
                mapOf(
                    VAR_X1 to start.x.toString(),
                    VAR_Y1 to start.y.toString(),
                    VAR_X2 to end.x.toString(),
                    VAR_Y2 to end.y.toString(),
                    VAR_RECEIPT to receipt,
                ),
            )

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val swipe = action.payload as SwipeGesture
            val pre = epilogue.preSample(ctx)
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
            val traceLine =
                trace.executionLine(
                    ExecMethod.GESTURE,
                    channel = result.channel,
                    shellSkip = result.shellSkip,
                )
            return epilogue.finish(
                ctx,
                swipe.resultText,
                swipe.waitMs,
                ReplayInput(TOOL_ID, swipe.replayArgs, gesture),
                pre,
                trace = listOf(traceLine),
            )
        }

        private companion object {
            const val PROMPT_RESULT = "device.swipe.result"
            const val PROMPT_DURATION_TOO_LONG = "device.swipe.duration_too_long"

            const val VAR_X1 = "x1"
            const val VAR_Y1 = "y1"
            const val VAR_X2 = "x2"
            const val VAR_Y2 = "y2"
            const val VAR_RECEIPT = "receipt"
            const val VAR_MAX = "max"
        }
    }

private const val TOOL_ID = "tool.swipe"

private val COORD_KEYS = listOf("x1", "y1", "x2", "y2")
