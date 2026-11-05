/**
 * `tool.tap`（docs/design/observation-modes-coordinate-actions §5.1、§5.2）：
 * 按内容区 0–1000 坐标点击；按压时长与落点抖动由配置内部处理，不对模型开放。
 * 结果附命中回执（§5.4），回放参数附 `_hit`（§5.7）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.GestureController
import com.example.autop.device.Point
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.PixelSize
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.CoordSpaceResolver
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class TapAction(
    /** 抖动后的实际落点（屏幕像素）。 */
    val point: Point,
    val durationMs: Long,
    val waitMs: Long?,
    val resultText: String,
    val replayArgs: JsonObject,
)

/** 点击工具。 */
@Singleton
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
            val actual =
                jitteredTapPoint(target, receipts.bounds(tree, modelPoint, mapper), area, snap, ctx)
            val actualModel = mapper.toNormalized(actual.x, actual.y)
            val hit = receipts.describe(tree, actualModel)
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
                    point = actual,
                    durationMs = tapDurationOf(ctx, long),
                    waitMs = args.long("wait_ms"),
                    resultText = resultTextOf(x, y, long, receipts.receipt(tree, actualModel)),
                    replayArgs = replayArgsOf(args, hit),
                )
            return Outcome.Ok(PreparedAction(subject, action))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val tap = action.payload as TapAction
            val pre = epilogue.begin()
            val ok = gestures.tap(tap.point.x, tap.point.y, tap.durationMs)
            if (!ok) {
                return errorOutput(AppError(code = DeviceErrorCodes.GESTURE_CANCELLED, message = "点击手势被取消"))
            }
            val gesture = gestureJson(tap.point.x, tap.point.y, tap.durationMs)
            return epilogue.finish(ctx, tap.resultText, tap.waitMs, ReplayInput(ID, pre, tap.replayArgs, gesture))
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
        }
    }
