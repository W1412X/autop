/**
 * 命中回执与策略回退共用的节点树来源（docs/design/observation-modes-coordinate-actions §5.4）：
 * 跟随最近观测所用的方式；最近观测是 a11y 时用动作前的实时 a11y 树（更新），否则用最近观测的树。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.ToolContext
import com.example.autop.device.ObserveMode
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.CoordMapper
import com.example.autop.device.core.HitResolver
import com.example.autop.device.impl.SnapshotSerializer
import com.example.autop.device.impl.deviceHistory
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 一棵命中树及其来源模式（`from`）。 */
internal data class HitTree(
    val model: ScreenModel,
    val from: ObserveMode,
)

/** 命中回执生成器。 */
@Singleton
public class HitReceiptBuilder
    @Inject
    constructor(
        private val serializer: SnapshotSerializer,
    ) {
        /**
         * 命中树跟随最近观测所用的方式：最近观测是 a11y 时用动作前的实时 a11y 树（更新），
         * 否则用最近观测的树；都没有返回 null（回执输出 `none`）。
         */
        internal fun treeOf(
            ctx: ToolContext,
            snapshot: ScreenSnapshot?,
        ): HitTree? {
            val last = ctx.deviceHistory().last
            val realtime =
                snapshot
                    ?.takeIf { it.nodes.isNotEmpty() }
                    ?.let {
                        serializer
                            .serialize(it, ObserveMode.A11Y, ctx.config, listOf(ObserveMode.A11Y))
                            .model
                    }
            return when {
                last == null -> realtime?.let { HitTree(it, ObserveMode.A11Y) }
                last.mode == ObserveMode.A11Y -> (realtime ?: last.screen.model).let { HitTree(it, ObserveMode.A11Y) }
                else -> HitTree(last.screen.model, last.mode)
            }
        }

        /** 落点处命中节点（§5.4）。 */
        internal fun node(
            tree: HitTree?,
            point: Point,
        ): ScreenNode? = tree?.let { HitResolver.resolve(point, it.model) }

        /** 动作结果后的一行命中回执。 */
        internal fun receipt(
            tree: HitTree?,
            point: Point,
        ): String = HitResolver.receipt(point, tree?.model, tree?.from)

        /** 回放参数里的 `_hit` 描述（未命中时为 null）。 */
        internal fun describe(
            tree: HitTree?,
            point: Point,
        ): JsonObject? = HitResolver.describe(point, tree?.model)

        /** 命中节点的像素 bounds（抖动半径与钳制用）；无命中返回 null。 */
        internal fun bounds(
            tree: HitTree?,
            point: Point,
            mapper: CoordMapper,
        ): Rect? {
            val node = node(tree, point) ?: return null
            val topLeft = mapper.toScreen(node.bounds.l, node.bounds.t) ?: return null
            val bottomRight = mapper.toScreen(node.bounds.r, node.bounds.b) ?: return null
            return Rect(
                l = minOf(topLeft.x, bottomRight.x),
                t = minOf(topLeft.y, bottomRight.y),
                r = maxOf(topLeft.x, bottomRight.x) + 1,
                b = maxOf(topLeft.y, bottomRight.y) + 1,
            )
        }
    }
