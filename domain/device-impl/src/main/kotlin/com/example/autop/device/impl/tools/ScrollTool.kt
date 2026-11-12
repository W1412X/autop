/**
 * `tool.scroll`（docs/design/tool_optimize/02 §4；ADR-0019）：
 * 一步 = 一次人手滑动，距离由可滚动容器的尺寸决定；`steps` 连续多步，不做"到头"判断。
 * 每步都是容器内的手势（shell → 无障碍）；节点滚动用 `scroll_node`。
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
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.GestureController
import com.example.autop.device.NodeFlags
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.NodeActionSelector
import com.example.autop.device.core.NodeQuery
import com.example.autop.device.core.PixelSize
import com.example.autop.device.core.ScrollDirection
import com.example.autop.device.core.ScrollGeometry
import com.example.autop.device.core.ScrollGeometryParams
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
    val waitMs: Long?,
    val replayArgs: JsonObject,
)

/** 一步的执行结果。 */
private data class StepResult(
    val gesture: JsonObject?,
    /** 这一步实际怎么执行的回执（模型可见）。 */
    val trace: String,
    val error: AppError? = null,
)

/** 滚动工具。 */
@Singleton
@Suppress("LongParameterList") // 装配类：读屏/手势/策略/收尾/坐标/内容区/回执均为滚动必需，不可再减
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
        private val trace: ExecutionTraceRenderer,
        private val prompts: PromptRepository,
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
                    ctx.config,
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
                    waitMs = waitMsOf(args),
                    replayArgs = replayArgsOf(args, receipts.describe(tree, Point(x, y))),
                )
            return Outcome.Ok(PreparedAction(subject, action))
        }

        /** steps 连续执行（tool_optimize §4.2）：步间固定停顿，中途失败 trace 写明已完成步数。 */
        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val scroll = action.payload as ScrollAction
            val pre = epilogue.preSample(ctx)
            var snap = scroll.snapshot
            var firstGesture: JsonObject? = null
            val trace = mutableListOf<String>()
            for (i in 1..scroll.steps) {
                if (i > 1) {
                    epilogue.pause(ctx, ctx.config[DeviceConfig.SCROLL].stepDelayMs)
                    snap = screenReader.snapshotOrDegraded().let { (it as? Outcome.Ok)?.value } ?: snap
                }
                val step = runStep(ctx, scroll, snap)
                if (step.error != null) {
                    if (i > 1) {
                        trace +=
                            prompts.text(PROMPT_PARTIAL, mapOf(VAR_DONE to "${i - 1}", VAR_TOTAL to "${scroll.steps}"))
                    }
                    return errorOutput(step.error).copy(trace = trace)
                }
                trace += prompts.text(PROMPT_STEP, mapOf(VAR_N to "$i", VAR_LINE to step.trace))
                if (i == 1) firstGesture = step.gesture
            }
            return epilogue.finish(
                ctx,
                resultText(scroll),
                scroll.waitMs,
                ReplayInput(ID, scroll.replayArgs, firstGesture),
                pre,
                trace,
            )
        }

        /** 一步：滚动容器内的手势滑动（节点滚动用 `scroll_node`，这里不走节点动作）。 */
        private suspend fun runStep(
            ctx: ToolContext,
            scroll: ScrollAction,
            snap: ScreenSnapshot,
        ): StepResult {
            val base = contentAreas.base()
            val mapper = coords.mapper(PixelSize(snap.screenWidth, snap.screenHeight), base)
            val anchor = mapper.toScreen(scroll.anchor.x, scroll.anchor.y)
            if (anchor == null) {
                return StepResult(
                    gesture = null,
                    trace = "",
                    error = AppError(CommonErrorCodes.INVALID_ARGUMENT, "滚动坐标换算失败"),
                )
            }
            val area = base ?: Rect(0, 0, snap.screenWidth, snap.screenHeight)
            val container = scrollContainer(snap, anchor)?.let { ScrollGeometry.intersectOrSelf(it, area) } ?: area
            val shot =
                swipe(ctx, container, anchor, scroll.direction)
                    ?: return StepResult(
                        gesture = null,
                        trace = "",
                        error = injectFailed("滚动手势"),
                    )
            val line =
                trace.executionLine(
                    ExecMethod.GESTURE,
                    channel = shot.result.channel,
                    shellSkip = shot.result.shellSkip,
                )
            return StepResult(shot.replay, line)
        }

        /** 滚动容器 bounds：落点处最上层的可滚动节点；快照没有节点树时 null（用内容区）。 */
        private fun scrollContainer(
            snap: ScreenSnapshot,
            anchor: Point,
        ): Rect? {
            val target = NodeActionSelector.select(snap, anchor, NodeQuery(requiredFlag = NodeFlags.SCROLLABLE))
            return target?.node?.bounds
        }

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

        /** 结果行（模板 device.scroll.result）：方向名、步数、截断与 amount 忽略说明。 */
        private fun resultText(scroll: ScrollAction): String {
            val vars =
                buildMap {
                    put(VAR_DIRECTION, prompts.text(PROMPT_DIR_PREFIX + scroll.direction.id))
                    put(VAR_STEPS, scroll.steps.toString())
                    if (scroll.requestedSteps > scroll.steps) put(VAR_CLAMPED_TO, scroll.steps.toString())
                    if (scroll.amountIgnored) put(VAR_AMOUNT_IGNORED, "1")
                }
            return prompts.text(PROMPT_RESULT, vars)
        }

        private companion object {
            const val ID = "tool.scroll"
            const val EXTRA_DIRECTION = "direction"
            const val CENTER = 500
            const val PROMPT_RESULT = "device.scroll.result"
            const val PROMPT_STEP = "device.scroll.step"
            const val PROMPT_PARTIAL = "device.scroll.partial"
            const val PROMPT_DIR_PREFIX = "device.scroll.dir_"
            const val VAR_DIRECTION = "direction"
            const val VAR_STEPS = "steps"
            const val VAR_CLAMPED_TO = "clampedTo"
            const val VAR_AMOUNT_IGNORED = "amountIgnored"
            const val VAR_N = "n"
            const val VAR_LINE = "line"
            const val VAR_DONE = "done"
            const val VAR_TOTAL = "total"
        }
    }
