/**
 * `tool.tap`（docs/design/observation-modes-coordinate-actions §5.1、§5.2；ADR-0015）：
 * 按内容区 0–1000 坐标点击或长按；按压时长与落点抖动由配置内部处理，不对模型开放。
 * 动作快照有实时无障碍节点树时优先对落点处的节点执行 ACTION_CLICK / ACTION_LONG_CLICK，
 * 否则、或节点动作被拒时，用坐标手势。结果附命中回执（§5.4）、执行/效果回执（§5.9），回放参数带 `_hit`（§5.7）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.GestureController
import com.example.autop.device.InjectionChannel
import com.example.autop.device.InjectionResult
import com.example.autop.device.Point
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.PixelSize
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
import com.example.autop.device.impl.observation.moved
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class TapAction(
    val snapshot: ScreenSnapshot,
    /** 未抖动的目标落点（屏幕像素）：节点选择用它。 */
    val target: Point,
    /** 抖动后的实际落点（屏幕像素）：坐标手势用它。 */
    val point: Point,
    val durationMs: Long,
    val long: Boolean,
    val waitMs: Long?,
    /** 动作前是否有可用节点树（false=命中无法判断）。 */
    val hitKnown: Boolean,
    /** 有节点树但落点不在任何节点内（回执 `hit:none`）。 */
    val hitNone: Boolean,
    val resultText: String,
    val replayArgs: JsonObject,
)

/** 点击工具。 */
@Singleton
@Suppress("LongParameterList") // 装配类：读屏/手势/策略/收尾/坐标/内容区/回执/节点路由均为点击必需，不可再减（N114 口径）
public class TapTool
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
        private val trace: ExecutionTraceRenderer,
        private val prompts: PromptRepository,
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec = DeviceToolSpecs.tap

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val x = args.int("x")
            val y = args.int("y")
            if (x == null || y == null) return invalidArg("必须提供 x、y 坐标（内容区 0–1000）")
            return when (val got = screenReader.snapshotOrDegraded()) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepareWith(got.value, x, y, args, ctx)
            }
        }

        private fun prepareWith(
            snap: ScreenSnapshot,
            x: Int,
            y: Int,
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val area = contentAreas.base()
            val mapper = coords.mapper(PixelSize(snap.screenWidth, snap.screenHeight), area)
            val target = mapper.toScreen(x, y) ?: return coordOutOfRange(coords)
            val modelPoint = Point(x, y)
            val tree = receipts.treeOf(ctx, snap)
            val actual = jitteredTapPoint(target, receipts.bounds(tree, modelPoint, mapper), area, snap, ctx)
            val actualModel = mapper.toNormalized(actual.x, actual.y)
            val long = args.bool("long") == true
            val subject =
                subjects.build(
                    ID,
                    snap,
                    nodeAt(snap, target.x, target.y),
                    emptyMap(),
                    fallbackTree = tree?.model,
                    fallbackPoint = modelPoint,
                )
            val action =
                TapAction(
                    snapshot = snap,
                    target = target,
                    point = actual,
                    durationMs = tapDurationOf(ctx, long),
                    long = long,
                    waitMs = args.long("wait_ms"),
                    hitKnown = tree != null,
                    hitNone = tree != null && receipts.node(tree, modelPoint) == null,
                    resultText = resultTextOf(x, y, long, receipts.receipt(tree, actualModel)),
                    replayArgs = replayArgsOf(args, receipts.describe(tree, actualModel)),
                )
            return Outcome.Ok(PreparedAction(subject, action))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val tap = action.payload as TapAction
            val route = router.attempt(routeRequest(tap, ctx))
            val gesture =
                if (route is NodeRoute.Done) {
                    null
                } else {
                    tapGesture(tap) ?: return errorOutput(injectFailed("点击"))
                }
            val out =
                epilogue.finish(
                    ctx,
                    tap.resultText,
                    tap.waitMs,
                    ReplayInput(ID, tap.replayArgs, gesture?.replay),
                )
            val learned = learn(ctx, route, out.screenDelta.moved())
            val notes =
                buildList {
                    add(executionText(tap, route, gesture?.result))
                    when {
                        !tap.hitKnown -> add(prompts.text(PROMPT_HIT_NO_TREE))
                        tap.hitNone -> add(prompts.text(PROMPT_HIT_NONE))
                    }
                    if (learned) add(prompts.text(PROMPT_NEWLY_BLOCKED))
                }
            return out.copy(content = out.content + notes.map { Block.Text(it) })
        }

        /** 实际执行方式：节点动作，或带通道与回退原因的坐标手势。 */
        private fun executionText(
            tap: TapAction,
            route: NodeRoute,
            gesture: InjectionResult?,
        ): String {
            val label = if (tap.long) ACTION_LONG_CLICK else ACTION_CLICK
            return if (route is NodeRoute.Done) {
                trace.executionLine(ExecMethod.NODE_ACTION, label)
            } else {
                trace.executionLine(
                    ExecMethod.GESTURE,
                    channel = gesture?.channel ?: InjectionChannel.NONE,
                    fallback = trace.routeFallback(route, label),
                )
            }
        }

        private fun routeRequest(
            tap: TapAction,
            ctx: ToolContext,
        ): NodeRouteRequest =
            NodeRouteRequest(
                ctx = ctx,
                snapshot = tap.snapshot,
                point = tap.target,
                kind = if (tap.long) NodeKind.LONG_CLICK else NodeKind.CLICK,
                actionId = if (tap.long) NodeActionIds.LONG_CLICK else NodeActionIds.CLICK,
            )

        /** 把本次变化结论交给路由器学习；返回是否因此新近判定该应用屏蔽了节点动作。 */
        private suspend fun learn(
            ctx: ToolContext,
            route: NodeRoute,
            moved: Boolean?,
        ): Boolean =
            when (route) {
                is NodeRoute.Done -> false.also { router.afterNodeDone(ctx, route.packageName, moved) }
                is NodeRoute.Rejected -> router.afterGesture(ctx, route.packageName, moved)
                is NodeRoute.Skipped -> router.afterGesture(ctx, route.packageName, moved)
            }

        private suspend fun tapGesture(tap: TapAction): GestureShot? {
            val result = gestures.tap(tap.point.x, tap.point.y, tap.durationMs)
            if (!result.ok) return null
            return GestureShot(result, gestureJson(tap.point.x, tap.point.y, tap.durationMs))
        }

        private fun resultTextOf(
            x: Int,
            y: Int,
            long: Boolean,
            receipt: String,
        ): String {
            val verb = if (long) "已长按" else "已点击"
            return "$verb ($x, $y)\n$receipt"
        }

        private companion object {
            const val ID = "tool.tap"

            const val ACTION_CLICK = "ACTION_CLICK"
            const val ACTION_LONG_CLICK = "ACTION_LONG_CLICK"

            const val PROMPT_HIT_NO_TREE = "device.hit.no_tree"
            const val PROMPT_HIT_NONE = "device.hit.none"
            const val PROMPT_NEWLY_BLOCKED = "device.node_action.newly_blocked"
        }
    }
