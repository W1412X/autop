/**
 * [ReplayTargetResolver] 实现（docs/design/observation-modes-coordinate-actions §5.7）：
 * 回放前对当前屏取 a11y 树，按 `_hit`（tag + 文字 + 最近 bounds）或旧 `_locator`
 * 重新定位目标节点，替换工具坐标；找不到时保留原参数并标记 divergence。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.ReplayResolution
import com.example.autop.agent.ReplayTargetResolver
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.device.NodeLocator
import com.example.autop.device.ObserveMode
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.CoordMapper
import com.example.autop.device.core.LocatorMatcher
import com.example.autop.device.core.PixelSize
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.CoordSpaceResolver
import com.example.autop.device.impl.SnapshotSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** 回放目标重解析（坐标工具）。 */
@Singleton
public class ReplayTargetResolverImpl
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val serializer: SnapshotSerializer,
        private val configProvider: ConfigProvider,
        private val contentAreas: ContentAreaProvider,
        private val coords: CoordSpaceResolver,
    ) : ReplayTargetResolver {
        override suspend fun resolve(
            toolId: String,
            args: JsonObject,
        ): Outcome<ReplayResolution> {
            val keys = coordinateKeysOf(toolId)
            val hit = args[HIT_ARG] as? JsonObject
            val locator = args[LOCATOR_ARG]
            if (keys == null || (hit == null && locator == null)) {
                return Outcome.Ok(ReplayResolution(args, diverged = false))
            }
            val snap =
                screenReader.snapshot().getOrNull()
                    ?: return Outcome.Ok(ReplayResolution(args, diverged = true))
            val point = hit?.let { resolveHit(it, snap) } ?: locator?.let { resolveLocator(it, snap) }
            return if (point == null) {
                Outcome.Ok(ReplayResolution(args, diverged = true))
            } else {
                Outcome.Ok(ReplayResolution(replace(args, keys, point), diverged = false))
            }
        }

        /**
         * `_hit`：当前 a11y 树中 tag 相同、文字相同（有文字时）且 bounds 中心最近的节点；
         * 坐标取节点的 `at`，没有 `at`（非可操作节点）时取 bounds 中心。
         */
        private fun resolveHit(
            hit: JsonObject,
            snap: ScreenSnapshot,
        ): Point? {
            val tag = hit.stringOf(TAG_KEY) ?: return null
            val label = labelOf(hit)
            val bounds = rectOf(hit[BOUNDS_KEY]) ?: return null
            val model =
                serializer
                    .serialize(snap, ObserveMode.A11Y, configProvider.current(), listOf(ObserveMode.A11Y))
                    .model
            val best =
                model
                    .flatten()
                    .filter { it.tag == tag && (label == null || labelOf(it) == label) }
                    .minByOrNull { centerDistance2(it.bounds, bounds) }
                    ?: return null
            return best.at ?: centerOf(best.bounds)
        }

        /** 旧记录 `_locator`：按现有定位器匹配当前快照，取节点中心换算为 0–1000。 */
        private fun resolveLocator(
            element: JsonElement,
            snap: ScreenSnapshot,
        ): Point? {
            val locator = runCatching { Json.decodeFromJsonElement<NodeLocator>(element) }.getOrNull() ?: return null
            val node = LocatorMatcher.match(snap, locator) ?: return null
            val mapper = mapperOf(snap)
            return mapper.toNormalized(node.bounds.centerX, node.bounds.centerY)
        }

        private fun mapperOf(snap: ScreenSnapshot): CoordMapper =
            coords.mapper(PixelSize(snap.screenWidth, snap.screenHeight), contentAreas.base() ?: snap.contentArea)

        private fun replace(
            args: JsonObject,
            keys: CoordinateKeys,
            point: Point,
        ): JsonObject =
            JsonObject(
                args.toMutableMap().apply {
                    put(keys.x, JsonPrimitive(point.x))
                    put(keys.y, JsonPrimitive(point.y))
                },
            )

        /** 工具坐标键：拖动用起点的 `x1/y1`，其余用 `x/y`。 */
        private fun coordinateKeysOf(toolId: String): CoordinateKeys? =
            when (toolId) {
                TOOL_TAP, TOOL_INPUT_TEXT, TOOL_SCROLL -> CoordinateKeys(X_KEY, Y_KEY)
                TOOL_SWIPE -> CoordinateKeys(X1_KEY, Y1_KEY)
                else -> null
            }

        private fun labelOf(hit: JsonObject): String? =
            listOf(TEXT_KEY, ICON_KEY, DESC_KEY).firstNotNullOfOrNull { hit.stringOf(it) }

        private fun labelOf(node: ScreenNode): String? =
            node.text?.takeIf { it.isNotEmpty() }
                ?: node.icon?.takeIf { it.isNotEmpty() }
                ?: node.desc?.takeIf { it.isNotEmpty() }

        private fun JsonObject.stringOf(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

        private fun rectOf(element: JsonElement?): Rect? {
            val values = (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: return null
            if (values.size != RECT_SIZE) return null
            return Rect(values[0], values[1], values[2], values.last())
        }

        private fun centerOf(rect: Rect): Point = Point((rect.l + rect.r) / 2, (rect.t + rect.b) / 2)

        private fun centerDistance2(
            a: Rect,
            b: Rect,
        ): Long {
            val dx = ((a.l + a.r) / 2 - (b.l + b.r) / 2).toLong()
            val dy = ((a.t + a.b) / 2 - (b.t + b.b) / 2).toLong()
            return dx * dx + dy * dy
        }

        private data class CoordinateKeys(
            val x: String,
            val y: String,
        )

        private companion object {
            const val HIT_ARG = "_hit"
            const val LOCATOR_ARG = "_locator"
            const val TAG_KEY = "tag"
            const val TEXT_KEY = "text"
            const val ICON_KEY = "icon"
            const val DESC_KEY = "desc"
            const val BOUNDS_KEY = "bounds"
            const val X_KEY = "x"
            const val Y_KEY = "y"
            const val X1_KEY = "x1"
            const val Y1_KEY = "y1"
            const val RECT_SIZE = 4
            const val TOOL_TAP = "tool.tap"
            const val TOOL_SWIPE = "tool.swipe"
            const val TOOL_SCROLL = "tool.scroll"
            const val TOOL_INPUT_TEXT = "tool.input_text"
        }
    }
