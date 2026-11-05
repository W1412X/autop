/**
 * 工具共用的输出、坐标与回放辅助（docs/tech/05 §4、§5、§13）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.TapJitter
import com.example.autop.device.impl.CoordSpaceResolver
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.roundToInt

/** 失败输出：错误进 [ToolOutput.error]，正文给模型看（含 hint，模型才拿得到纠错建议）。 */
internal fun toolError(
    code: String,
    message: String,
    hint: String? = null,
): ToolOutput = errorOutput(AppError(code = code, message = message, hint = hint))

/**
 * 把 [Outcome.Err] 转成失败输出（正文含 hint；格式与 `agent.tool.error_result` 一致）。
 * 模型可见正文 = message + “建议：”行（hint 只放 [AppError] 模型拿不到，N172）。
 */
internal fun errorOutput(error: AppError): ToolOutput {
    val visible =
        if (error.hint.isNullOrBlank()) {
            error.message
        } else {
            "${error.message}\n建议：${error.hint}"
        }
    return ToolOutput(ok = false, content = listOf(Block.Text(visible)), error = error)
}

/** 成功文本输出。 */
internal fun textOutput(text: String): ToolOutput = ToolOutput(ok = true, content = listOf(Block.Text(text)))

/** 参数缺失/类型错误（docs/design/observation-modes-coordinate-actions §5.1）。 */
internal fun invalidArg(message: String): Outcome.Err =
    Outcome.Err(AppError(code = CommonErrorCodes.INVALID_ARGUMENT, message = message))

/** 回放参数：工具参数原样，另加命中回执 `_hit`（§5.7）。 */
internal fun replayArgsOf(
    args: JsonObject,
    hit: JsonObject?,
): JsonObject =
    buildJsonObject {
        for ((key, value) in args) {
            if (key != "id" && !key.startsWith(INTERNAL_ARG_PREFIX)) put(key, value)
        }
        if (hit != null) put(HIT_ARG, hit)
    }

/** 手势的实际坐标与时长（屏幕像素，docs/tech/05 §13 的 `gesture`）。 */
internal fun gestureJson(
    x: Int,
    y: Int,
    durationMs: Long,
): JsonObject =
    buildJsonObject {
        put("x", x)
        put("y", y)
        put("durationMs", durationMs)
    }

/** 滑动手势的实际起止点与时长（屏幕像素）。 */
internal fun gestureJson(
    fromX: Int,
    fromY: Int,
    toX: Int,
    toY: Int,
    durationMs: Long,
): JsonObject =
    buildJsonObject {
        put("fromX", fromX)
        put("fromY", fromY)
        put("toX", toX)
        put("toY", toY)
        put("durationMs", durationMs)
    }

/** 坐标超范围错误（§1）：不执行，带 `device.hint.coord_out_of_range`。 */
internal fun coordOutOfRange(coords: CoordSpaceResolver): Outcome.Err =
    Outcome.Err(
        AppError(
            code = CommonErrorCodes.INVALID_ARGUMENT,
            message = "坐标超出范围",
            hint = coords.outOfRangeHint(),
        ),
    )

/** 点击/长按的手势时长（`device.tap.*`）。 */
internal fun tapDurationOf(
    ctx: ToolContext,
    long: Boolean,
): Long {
    val cfg = ctx.config[DeviceConfig.TAP]
    return if (long) cfg.longPressMs else cfg.durationMs
}

/**
 * 点击落点抖动（§5.2）：半径取命中节点短边 × 比例与上限的较小值；
 * 无命中节点时取上限一半；结果钳在命中节点与基准区内。
 */
internal fun jitteredTapPoint(
    target: Point,
    hitBounds: Rect?,
    area: Rect?,
    snap: ScreenSnapshot,
    ctx: ToolContext,
): Point {
    val cfg = ctx.config[DeviceConfig.TAP]
    val radius =
        if (hitBounds != null) {
            val ratioRadius = (minOf(hitBounds.width, hitBounds.height) * cfg.jitterRatio).roundToInt()
            minOf(cfg.jitterMaxPx, ratioRadius)
        } else {
            cfg.jitterMaxPx / 2
        }
    val base = area ?: Rect(0, 0, snap.screenWidth, snap.screenHeight)
    return TapJitter.point(target.x, target.y, radius, hitBounds, base) {
        ThreadLocalRandom.current().nextDouble()
    }
}

/** 命中坐标（像素）的最上层（面积最小）可见节点。 */
internal fun nodeAt(
    snapshot: ScreenSnapshot,
    x: Int,
    y: Int,
): NodeSnapshot? =
    snapshot.nodes
        .filter { it.has(NodeFlags.VISIBLE) && contains(it, x, y) }
        .minByOrNull { it.bounds.area }

/** 位标志判断。 */
internal fun NodeSnapshot.has(flag: Int): Boolean = flags and flag != 0

private fun contains(
    node: NodeSnapshot,
    x: Int,
    y: Int,
): Boolean = x >= node.bounds.l && x < node.bounds.r && y >= node.bounds.t && y < node.bounds.b

/** 回放参数里的命中描述键名（`_hit`，docs/design/observation-modes-coordinate-actions §5.7）。 */
private const val HIT_ARG = "_hit"

/** 内部参数前缀：回放展示时隐藏。 */
private const val INTERNAL_ARG_PREFIX = "_"
