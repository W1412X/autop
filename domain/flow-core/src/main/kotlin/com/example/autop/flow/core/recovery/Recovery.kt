/**
 * 操作恢复阶梯（docs/design/flows-recording-recovery.md R4–R5）。
 *
 * 阶梯第 k 次重试执行“适用的第 k 个动作”；不适用的动作跳过且不占次数。
 * 动作只通过端口（工具、观测）工作，本包不引入 Android 依赖。
 */
package com.example.autop.flow.core.recovery

import com.example.autop.core.config.FlowRecoverySection
import com.example.autop.flow.Anchor
import com.example.autop.flow.Condition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.ToolNode
import com.example.autop.flow.core.FlowPorts
import com.example.autop.flow.core.Frame
import com.example.autop.flow.core.ScreenService
import com.example.autop.flow.core.screen.TargetMatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 失败发生的阶段（docs/design/flows-precondition-selfrepair.md P2）。 */
internal enum class FailureStage {
    /** 前置条件不成立，动作未执行。 */
    PRECONDITION,

    /** 动作执行了但失败或未生效。 */
    ACTION,
}

/** 替代点击的临时提示：只对紧随其后的一次工具调用生效，调用时消费并清空。 */
internal class RecoveryHints {
    /** 节点 bounds 内的比例点（0–1）。 */
    var pointRatio: Pair<Double, Double>? = null
}

/** 一次恢复动作的上下文。 */
internal class RecoveryContext(
    val node: ToolNode,
    val attempt: Int,
    val errorCode: String,
    val stage: FailureStage,
    val preKind: Condition?,
    val frame: Frame,
    val ports: FlowPorts,
    val screens: ScreenService,
    val cfg: FlowRecoverySection,
    val hints: RecoveryHints,
)

/** 恢复动作（稳定 id 为 `recovery.*`）。 */
internal interface RecoveryAction {
    val id: String

    /** 当前情形下是否适用；不适用的动作不占用阶梯次数。 */
    suspend fun applicable(ctx: RecoveryContext): Boolean

    /** 执行动作；返回值只用于记录，阶梯不会因此停止。 */
    suspend fun run(ctx: RecoveryContext): Boolean
}

/** 恢复动作目录：id → 动作。 */
internal class RecoveryCatalog(
    private val actions: List<RecoveryAction>,
) {
    fun byId(id: String): RecoveryAction? = actions.firstOrNull { it.id == id }

    companion object {
        /** 内置动作集合。 */
        fun builtin(): RecoveryCatalog =
            RecoveryCatalog(
                listOf(
                    WaitStableAction(),
                    AltClickAction(),
                    ScrollIntoViewAction(),
                    DismissPopupAction(),
                    BackAndRetryAction(),
                    ReopenAppAction(),
                    RestartAppAction(),
                ),
            )
    }
}

/** 执行阶梯中的一步。 */
internal class RecoveryRunner(
    private val catalog: RecoveryCatalog,
    private val ports: FlowPorts,
    private val screens: ScreenService,
    private val cfg: FlowRecoverySection,
    private val hints: RecoveryHints,
) {
    /**
     * 执行阶梯第 [attempt] 步（从 1 开始）。返回执行的动作 id；没有适用动作时返回 null。
     * [stage] 决定阶梯动作的适用性（前置阶段没有动作可换点、元素类条件可滚动寻找）；
     * [preKind] 为前置条件本身，用于推导临时锚点。取消信号直接向上抛出。
     */
    suspend fun step(
        node: ToolNode,
        frame: Frame,
        ladder: List<String>,
        attempt: Int,
        errorCode: String,
        stage: FailureStage,
        preKind: Condition? = null,
    ): String? {
        val ctx = RecoveryContext(node, attempt, errorCode, stage, preKind, frame, ports, screens, cfg, hints)
        val applicable = ladder.mapNotNull { catalog.byId(it) }.filter { it.applicable(ctx) }
        val action = applicable.getOrNull(attempt - 1)
        if (action == null) {
            ports.trace.log("info", "recovery skipped node=${node.id} attempt=$attempt")
            return null
        }
        val ok = action.run(ctx)
        ports.trace.log("info", "recovery ${action.id} node=${node.id} attempt=$attempt ok=$ok")
        return action.id
    }
}

/** 从条件推导临时锚点：app → anchor.app；screenText / element → anchor.text / target。 */
internal fun deriveAnchor(cond: Condition): Anchor? =
    when (cond) {
        is Condition.App -> Anchor(app = cond.value)
        is Condition.ScreenText -> Anchor(text = cond.value)
        is Condition.Element -> Anchor(target = cond.target)
        // 复合条件取第一个可推导的子条件；any / not 语义不确定，不推导。
        is Condition.All -> cond.of.firstNotNullOfOrNull { deriveAnchor(it) }
        else -> null
    }

/**
 * 生效的锚点：节点自身锚点优先，为空时从前置条件推导临时锚点。
 * 推导出的锚点只用于恢复判定，不写回流程定义。
 */
internal fun effectiveAnchor(ctx: RecoveryContext): Anchor? = ctx.node.anchor ?: ctx.preKind?.let { deriveAnchor(it) }

/** 判断锚点是否仍成立（应用、文字、元素均需满足已配置的项）。 */
internal suspend fun anchorHolds(
    anchor: Anchor,
    ctx: RecoveryContext,
): Boolean {
    val observed = ctx.screens.screen(null, ctx.frame.flow.settings.observeMode, force = true)
    anchor.app?.let { if (ctx.screens.foregroundApp(observed.screen) != it) return false }
    anchor.text?.let {
        val text = ctx.frame.templates.renderString(it)
        if (!ctx.screens.pageText(observed.screen).contains(text)) return false
    }
    anchor.target?.let {
        if (ctx.screens.find(ctx.frame, it, wait = false) == TargetMatch.NotFound) return false
    }
    return true
}

private const val TOOL_TAP = "tap"
private const val TOOL_WAIT = "wait"
private const val TOOL_SCROLL = "scroll"
private const val TOOL_DISMISS_POPUP = "dismiss_popup"
private const val TOOL_GLOBAL_ACTION = "global_action"
private const val TOOL_OPEN_APP = "open_app"
private const val TOOL_RESTART_APP = "restart_app"
private const val ARG_MS = "ms"
private const val ARG_DIRECTION = "direction"
private const val ARG_ACTION = "action"
private const val ARG_PACKAGE = "package"
private const val ACTION_BACK = "back"

/** 等待页面稳定。 */
internal class WaitStableAction : RecoveryAction {
    override val id: String = "recovery.wait_stable"

    override suspend fun applicable(ctx: RecoveryContext): Boolean = true

    override suspend fun run(ctx: RecoveryContext): Boolean =
        ctx.ports.tools
            .call(TOOL_WAIT, JsonObject(mapOf(ARG_MS to JsonPrimitive(ctx.cfg.waitStableMs))), ctx.node.id)
            .ok
}

/** 换一个点击位置（节点内的比例点）。仅动作失败阶段适用：前置阶段没有动作可换点。 */
internal class AltClickAction : RecoveryAction {
    override val id: String = "recovery.alt_click"

    override suspend fun applicable(ctx: RecoveryContext): Boolean =
        ctx.stage == FailureStage.ACTION &&
            ctx.node.tool == TOOL_TAP &&
            ctx.node.target != null &&
            ctx.cfg.altClickOffsets.isNotEmpty()

    override suspend fun run(ctx: RecoveryContext): Boolean {
        val offsets = ctx.cfg.altClickOffsets
        val p = offsets[(ctx.attempt - 1) % offsets.size]
        ctx.hints.pointRatio = p[0] to p[1]
        return true
    }
}

/** 目标未找到时滚动寻找（奇数次向下，偶数次向上）。前置阶段对元素类条件不成立同样适用。 */
internal class ScrollIntoViewAction : RecoveryAction {
    override val id: String = "recovery.scroll_into_view"

    override suspend fun applicable(ctx: RecoveryContext): Boolean =
        if (ctx.stage == FailureStage.ACTION) {
            ctx.node.target != null && ctx.errorCode == FlowErrorCodes.TARGET_NOT_FOUND
        } else {
            ctx.preKind is Condition.Element
        }

    override suspend fun run(ctx: RecoveryContext): Boolean {
        val direction = if (ctx.attempt % 2 == 1) "down" else "up"
        return ctx.ports.tools
            .call(TOOL_SCROLL, JsonObject(mapOf(ARG_DIRECTION to JsonPrimitive(direction))), ctx.node.id)
            .ok
    }
}

/** 关闭遮挡的弹窗。 */
internal class DismissPopupAction : RecoveryAction {
    override val id: String = "recovery.dismiss_popup"

    override suspend fun applicable(ctx: RecoveryContext): Boolean = true

    override suspend fun run(ctx: RecoveryContext): Boolean =
        ctx.ports.tools
            .call(TOOL_DISMISS_POPUP, JsonObject(emptyMap()), ctx.node.id)
            .ok
}

/** 返回一次；若已离开锚点应用则重新打开该应用。仅在锚点不成立时适用。 */
internal class BackAndRetryAction : RecoveryAction {
    override val id: String = "recovery.back_and_retry"

    override suspend fun applicable(ctx: RecoveryContext): Boolean {
        val anchor = effectiveAnchor(ctx) ?: return false
        return !anchorHolds(anchor, ctx)
    }

    override suspend fun run(ctx: RecoveryContext): Boolean {
        val back =
            ctx.ports.tools
                .call(TOOL_GLOBAL_ACTION, JsonObject(mapOf(ARG_ACTION to JsonPrimitive(ACTION_BACK))), ctx.node.id)
                .ok
        val app = effectiveAnchor(ctx)?.app ?: return back
        val observed = ctx.screens.screen(null, ctx.frame.flow.settings.observeMode, force = true)
        if (ctx.screens.foregroundApp(observed.screen) != app) {
            ctx.ports.tools.call(TOOL_OPEN_APP, JsonObject(mapOf(ARG_PACKAGE to JsonPrimitive(app))), ctx.node.id)
        }
        return back
    }
}

/** 重新打开锚点应用。 */
internal class ReopenAppAction : RecoveryAction {
    override val id: String = "recovery.reopen_app"

    override suspend fun applicable(ctx: RecoveryContext): Boolean = effectiveAnchor(ctx)?.app != null

    override suspend fun run(ctx: RecoveryContext): Boolean {
        val app = effectiveAnchor(ctx)?.app ?: return false
        return ctx.ports.tools
            .call(TOOL_OPEN_APP, JsonObject(mapOf(ARG_PACKAGE to JsonPrimitive(app))), ctx.node.id)
            .ok
    }
}

/** 强制结束并重启锚点应用（需 `flow.recovery.allowRestartApp`）。 */
internal class RestartAppAction : RecoveryAction {
    override val id: String = "recovery.restart_app"

    override suspend fun applicable(ctx: RecoveryContext): Boolean =
        ctx.cfg.allowRestartApp && effectiveAnchor(ctx)?.app != null

    override suspend fun run(ctx: RecoveryContext): Boolean {
        val app = effectiveAnchor(ctx)?.app ?: return false
        return ctx.ports.tools
            .call(TOOL_RESTART_APP, JsonObject(mapOf(ARG_PACKAGE to JsonPrimitive(app))), ctx.node.id)
            .ok
    }
}
