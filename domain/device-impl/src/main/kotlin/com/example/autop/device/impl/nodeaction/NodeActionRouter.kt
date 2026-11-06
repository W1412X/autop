/**
 * 节点动作路由（ADR-0015）：点击、长按、滚动在满足条件时优先走无障碍节点动作，否则交回调用方用手势。
 *
 * 走节点的前提：配置开启；观测与行动都来自 a11y（视觉/YOLO/llm 观测下节点树不可信）；
 * 落点处能选到带有对应动作的节点；该应用没有被记录为屏蔽节点动作。
 * 应用屏蔽判定：节点动作被拒或无效，而随后的手势在同一应用上有效，累计到阈值即记录该应用。
 */
package com.example.autop.device.impl.nodeaction

import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.Clock
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.GestureController
import com.example.autop.device.NodeActionBlockStore
import com.example.autop.device.NodeActionOutcome
import com.example.autop.device.NodeFlags
import com.example.autop.device.ObserveMode
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.NodeActionSelector
import com.example.autop.device.core.NodeQuery
import com.example.autop.device.core.NodeTarget
import com.example.autop.device.core.ScrollDirection
import com.example.autop.device.impl.deviceHistory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 可走节点动作的动作种类；[configId] 与 `device.nodeAction.actions` 对应。 */
internal enum class NodeKind(
    val configId: String,
) {
    CLICK("click"),
    LONG_CLICK("long_click"),
    SCROLL("scroll"),
}

/** 一次路由请求。[point] 是未抖动的目标落点（像素）。 */
internal data class NodeRouteRequest(
    val ctx: ToolContext,
    val snapshot: ScreenSnapshot,
    val point: Point,
    val kind: NodeKind,
    /** 无障碍动作 id（滚动为方向化动作，见 [NodeActionIds.scrollActionOf]）。 */
    val actionId: Int,
)

/** 路由结果。 */
internal sealed interface NodeRoute {
    /** 应用接受了节点动作；调用方不要再补手势（避免重复副作用）。 */
    data class Done(
        val packageName: String,
    ) : NodeRoute

    /** 节点存在但应用拒绝了动作（动作没有执行）；调用方用手势。 */
    data class Rejected(
        val packageName: String,
    ) : NodeRoute

    /** 没有走节点动作，调用方用手势。[packageName] 非空表示是同一应用上的升级重试。 */
    data class Skipped(
        val reason: String,
        val packageName: String? = null,
    ) : NodeRoute
}

/** 无障碍动作 id 与方向映射。 */
internal object NodeActionIds {
    val CLICK: Int = AccessibilityAction.ACTION_CLICK.id
    val LONG_CLICK: Int = AccessibilityAction.ACTION_LONG_CLICK.id

    /**
     * 手指方向 → 滚动动作。`up`（手指上滑，看下方内容）对应“向下滚动内容”动作，其余同理。
     * 与 RecyclerView/ScrollView 的无障碍实现一致，真机验证项见 ADR-0015。
     */
    fun scrollActionOf(direction: ScrollDirection): Int =
        when (direction) {
            ScrollDirection.UP -> AccessibilityAction.ACTION_SCROLL_DOWN.id
            ScrollDirection.DOWN -> AccessibilityAction.ACTION_SCROLL_UP.id
            ScrollDirection.LEFT -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
            ScrollDirection.RIGHT -> AccessibilityAction.ACTION_SCROLL_LEFT.id
        }
}

/** 节点动作路由器。 */
@Singleton
public class NodeActionRouter
    @Inject
    constructor(
        private val gestures: GestureController,
        private val blocks: NodeActionBlockStore,
        private val clock: Clock,
    ) {
        /** 节点动作无效（被拒或无变化）的最近时间，按包名。 */
        private val suspects = ConcurrentHashMap<String, Long>()

        /** “节点无效而手势有效”的证据次数，按包名。 */
        private val evidence = ConcurrentHashMap<String, Int>()

        /** 尝试走节点动作；返回 [NodeRoute.Skipped] 或 [NodeRoute.Rejected] 时调用方用手势。 */
        internal suspend fun attempt(req: NodeRouteRequest): NodeRoute {
            val cfg = req.ctx.config[DeviceConfig.NODE_ACTION]
            val gate = gateReason(req, cfg.enabled, cfg.actions)
            val target = if (gate == null) select(req, cfg.maxAreaRatio) else null
            val pkg = target?.let { it.node.packageName ?: req.snapshot.foregroundPackage }
            return when {
                gate != null -> NodeRoute.Skipped(gate)
                target == null || pkg == null -> NodeRoute.Skipped(REASON_NO_NODE)
                blocks.isBlocked(pkg) -> NodeRoute.Skipped(REASON_BLOCKED, pkg)
                recentlySuspect(pkg, cfg.retryWindowMs) -> NodeRoute.Skipped(REASON_ESCALATE, pkg)
                else -> execute(target, pkg, req.actionId)
            }
        }

        private suspend fun execute(
            target: NodeTarget,
            packageName: String,
            actionId: Int,
        ): NodeRoute =
            when (gestures.performAtPath(target.windowId, target.path, target.node.bounds, actionId)) {
                NodeActionOutcome.DONE -> NodeRoute.Done(packageName)
                NodeActionOutcome.REJECTED ->
                    NodeRoute.Rejected(packageName).also { suspects[packageName] = clock.nowMillis() }
                NodeActionOutcome.STALE -> NodeRoute.Skipped(REASON_STALE, packageName)
            }

        /**
         * 滚动容器：落点处最上层的可滚动节点 bounds；只在 a11y 来源且有节点树时返回，
         * 其余（视觉/llm 观测、空树）返回 null，由调用方用内容区。
         */
        internal fun scrollContainer(
            ctx: ToolContext,
            snapshot: ScreenSnapshot,
            point: Point,
        ): Rect? {
            if (!isA11ySource(ctx) || snapshot.nodes.isEmpty()) return null
            val query = NodeQuery(requiredFlag = NodeFlags.SCROLLABLE)
            return NodeActionSelector.select(snapshot, point, query)?.node?.bounds
        }

        /** 节点动作被应用接受之后：[moved] 为 true 说明节点路径有效（清空疑点与证据）；false 记为疑点；null 不判断。 */
        internal fun afterNodeDone(
            packageName: String,
            moved: Boolean?,
        ) {
            when (moved) {
                true -> {
                    suspects.remove(packageName)
                    evidence.remove(packageName)
                }
                false -> suspects[packageName] = clock.nowMillis()
                null -> Unit
            }
        }

        /**
         * 手势执行之后：若同一应用刚有节点动作无效的疑点，而手势有变化，记一次证据；
         * 证据累计到阈值即记录该应用屏蔽了节点动作。返回 true 表示本次新近判定为屏蔽。
         */
        internal suspend fun afterGesture(
            ctx: ToolContext,
            packageName: String?,
            moved: Boolean?,
        ): Boolean {
            if (packageName == null || moved != true) return false
            val cfg = ctx.config[DeviceConfig.NODE_ACTION]
            if (suspects.remove(packageName) == null) return false
            val count = evidence.merge(packageName, 1, Int::plus) ?: 1
            if (count < cfg.blockAfterEvidence) return false
            evidence.remove(packageName)
            blocks.block(packageName, REASON_GESTURE_OK)
            return true
        }

        /** 当前观测是否来自 a11y（未观测过按默认 a11y）；仅此时节点树与指纹可信。 */
        internal fun isA11ySource(ctx: ToolContext): Boolean =
            (ctx.deviceHistory().lastMode ?: ObserveMode.A11Y) == ObserveMode.A11Y

        /** 指纹变化的可信判定：a11y 来源且有节点树，才能说“屏幕变了/没变”；否则返回 null（不判断）。 */
        internal fun movedOrNull(
            ctx: ToolContext,
            snapshot: ScreenSnapshot,
            preFingerprint: String?,
            postFingerprint: String?,
        ): Boolean? {
            val trusted = isA11ySource(ctx) && snapshot.nodes.isNotEmpty()
            return if (trusted && preFingerprint != null && postFingerprint != null) {
                preFingerprint != postFingerprint
            } else {
                null
            }
        }

        private fun gateReason(
            req: NodeRouteRequest,
            enabled: Boolean,
            actions: List<String>,
        ): String? =
            when {
                !enabled || req.kind.configId !in actions -> REASON_DISABLED
                !isA11ySource(req.ctx) -> REASON_SOURCE
                req.snapshot.nodes.isEmpty() -> REASON_NO_NODE
                else -> null
            }

        private fun select(
            req: NodeRouteRequest,
            maxAreaRatio: Double,
        ): NodeTarget? {
            val screenArea = req.snapshot.screenWidth.toLong() * req.snapshot.screenHeight
            val maxArea = if (req.kind == NodeKind.SCROLL) Long.MAX_VALUE else (screenArea * maxAreaRatio).toLong()
            return NodeActionSelector.select(
                req.snapshot,
                req.point,
                NodeQuery(requiredActionId = req.actionId, maxAreaPx = maxArea),
            )
        }

        private fun recentlySuspect(
            packageName: String,
            windowMs: Long,
        ): Boolean = suspects[packageName]?.let { clock.nowMillis() - it <= windowMs } == true

        internal companion object {
            const val REASON_DISABLED = "disabled"
            const val REASON_SOURCE = "source"
            const val REASON_NO_NODE = "no_node"
            const val REASON_BLOCKED = "blocked"
            const val REASON_ESCALATE = "escalate"
            const val REASON_STALE = "stale"
            const val REASON_GESTURE_OK = "node_failed_gesture_ok"
        }
    }
