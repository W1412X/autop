/**
 * 节点动作路由（ADR-0015）：点击、长按、滚动在满足条件时优先走无障碍节点动作，否则交回调用方用手势。
 *
 * 走节点的前提：配置开启；本次动作使用的快照里有实时无障碍节点树（视觉/llm 只影响观测文本，
 * 动作快照仍来自实时 a11y 采集）；落点处能选到带有对应动作的节点；该应用没有被记录为屏蔽节点动作。
 * 效果证据来自工具收尾的变化结论（ScreenDelta，agent-harness-overhaul D4），本类只消费。
 * 应用屏蔽判定：节点动作被拒或无效，而随后的手势在同一应用上有效，累计到阈值即记录该应用。
 */
package com.example.autop.device.impl.nodeaction

import com.example.autop.agent.ToolContext
import com.example.autop.core.common.Clock
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.GestureController
import com.example.autop.device.NodeActionBlockStore
import com.example.autop.device.NodeActionOutcome
import com.example.autop.device.NodeFlags
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.NodeActionSelector
import com.example.autop.device.core.NodeQuery
import com.example.autop.device.core.NodeTarget
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
        val reason: SkipReason,
        val packageName: String? = null,
    ) : NodeRoute
}

/** 没走节点动作的原因（模型可见回执据此解释回退）。 */
internal enum class SkipReason {
    /** 配置关闭或该动作未启用。 */
    DISABLED,

    /** 落点处没有带对应动作的节点。 */
    NO_NODE,

    /** 选中的节点面积过大，疑似整页容器。 */
    AREA,

    /** 该应用被记录为不响应节点动作。 */
    BLOCKED,

    /** 该应用刚有节点动作无效的疑点，这次先用一次手势。 */
    ESCALATE,

    /** 节点在实时树里已失效。 */
    STALE,
    ;

    /** 给模型看的解释。 */
    fun describe(): String =
        when (this) {
            DISABLED -> "节点动作未启用"
            NO_NODE -> "落点处没有可执行该动作的节点"
            AREA -> "落点节点面积过大，疑似整页容器"
            BLOCKED -> "该应用的节点动作此前多次无效，已改用手势"
            ESCALATE -> "该应用的节点动作刚失败过，这次先用坐标手势"
            STALE -> "节点已失效（界面可能已变化）"
        }
}

/** 节点动作路由器（coordinate-node-action-split 后已无调用方，清理见 WP7）。 */
@Singleton
public class NodeActionRouter
    @Inject
    constructor(
        private val gestures: GestureController,
        private val blocks: NodeActionBlockStore,
        private val clock: Clock,
    ) {
        /** “节点无效而手势有效”的证据次数，按包名（跨 Run 单例：学习结果跨 Run 生效）。 */
        private val evidence = ConcurrentHashMap<String, Int>()

        /** 本 Run 的节点动作疑点（agent-harness-overhaul WP2.6）。 */
        private fun suspects(ctx: ToolContext): MutableMap<String, Long> = ctx.deviceHistory().nodeActionSuspects

        /** 尝试走节点动作；返回 [NodeRoute.Skipped] 或 [NodeRoute.Rejected] 时调用方用手势。 */
        internal suspend fun attempt(req: NodeRouteRequest): NodeRoute {
            val cfg = req.ctx.config[DeviceConfig.NODE_ACTION]
            if (!cfg.enabled || req.kind.configId !in cfg.actions) return NodeRoute.Skipped(SkipReason.DISABLED)
            if (req.snapshot.nodes.isEmpty()) return NodeRoute.Skipped(SkipReason.NO_NODE)
            val selection = select(req, cfg.maxAreaRatio)
            val target = (selection as? Selection.Found)?.target
            if (target == null) {
                val reason = if (selection == Selection.TooLarge) SkipReason.AREA else SkipReason.NO_NODE
                return NodeRoute.Skipped(reason)
            }
            val pkg = target.node.packageName ?: req.snapshot.foregroundPackage
            return when {
                pkg == null -> NodeRoute.Skipped(SkipReason.NO_NODE)
                blocks.isBlocked(pkg) -> NodeRoute.Skipped(SkipReason.BLOCKED, pkg)
                recentlySuspect(req.ctx, pkg, cfg.retryWindowMs) -> NodeRoute.Skipped(SkipReason.ESCALATE, pkg)
                else -> execute(req.ctx, target, pkg, req.actionId)
            }
        }

        private suspend fun execute(
            ctx: ToolContext,
            target: NodeTarget,
            packageName: String,
            actionId: Int,
        ): NodeRoute =
            when (gestures.performAtPath(target.windowId, target.path, target.node.bounds, actionId)) {
                NodeActionOutcome.DONE -> NodeRoute.Done(packageName)
                NodeActionOutcome.REJECTED ->
                    NodeRoute.Rejected(packageName).also { suspects(ctx)[packageName] = clock.nowMillis() }
                NodeActionOutcome.STALE -> NodeRoute.Skipped(SkipReason.STALE, packageName)
            }

        /**
         * 滚动容器：落点处最上层的可滚动节点 bounds；只在有 a11y 节点树时返回
         * （节点动作只存在于 a11y 快照，这是能力判断），其余返回 null，由调用方用内容区。
         */
        internal fun scrollContainer(
            snapshot: ScreenSnapshot,
            point: Point,
        ): Rect? {
            if (snapshot.nodes.isEmpty()) return null
            val query = NodeQuery(requiredFlag = NodeFlags.SCROLLABLE)
            return NodeActionSelector.select(snapshot, point, query)?.node?.bounds
        }

        /** 节点动作被应用接受之后：[moved] 为 true 说明节点路径有效（清空疑点与证据）；false 记为疑点；null 不判断。 */
        internal fun afterNodeDone(
            ctx: ToolContext,
            packageName: String,
            moved: Boolean?,
        ) {
            val suspects = suspects(ctx)
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
            if (suspects(ctx).remove(packageName) == null) return false
            val count = evidence.merge(packageName, 1, Int::plus) ?: 1
            if (count < cfg.blockAfterEvidence) return false
            evidence.remove(packageName)
            blocks.block(packageName, REASON_GESTURE_OK)
            return true
        }

        /** 选择落点处的节点；区分“没有节点”和“节点过大”，回执能说明具体原因。 */
        private fun select(
            req: NodeRouteRequest,
            maxAreaRatio: Double,
        ): Selection {
            val target =
                NodeActionSelector.select(
                    req.snapshot,
                    req.point,
                    NodeQuery(requiredActionId = req.actionId),
                ) ?: return Selection.None
            if (req.kind == NodeKind.SCROLL) return Selection.Found(target)
            val screenArea = req.snapshot.screenWidth.toLong() * req.snapshot.screenHeight
            val maxArea = (screenArea * maxAreaRatio).toLong()
            val hitNode = target.node.bounds
            val tooLarge = hitNode.area.toLong() > maxArea
            return if (tooLarge) Selection.TooLarge else Selection.Found(target)
        }

        private fun recentlySuspect(
            ctx: ToolContext,
            packageName: String,
            windowMs: Long,
        ): Boolean = suspects(ctx)[packageName]?.let { clock.nowMillis() - it <= windowMs } == true

        private sealed interface Selection {
            data object None : Selection

            data object TooLarge : Selection

            data class Found(
                val target: NodeTarget,
            ) : Selection
        }

        internal companion object {
            const val REASON_GESTURE_OK = "node_failed_gesture_ok"
        }
    }
