@file:Suppress("ThrowsCount", "MagicNumber") // 读取节点的参数错误

package com.example.autop.flow.core

import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.ObserveNode
import com.example.autop.flow.Pick
import com.example.autop.flow.ReadAttr
import com.example.autop.flow.ReadFrom
import com.example.autop.flow.ReadNode
import com.example.autop.flow.WaitFor
import com.example.autop.flow.WaitNode
import com.example.autop.flow.WaitTimeoutAction
import com.example.autop.flow.core.expr.Values
import com.example.autop.flow.core.screen.TargetMatch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 屏幕类节点：observe / read / wait。 */
internal class ScreenNodes(
    private val env: NodeEnv,
) {
    suspend fun observe(
        node: ObserveNode,
        frame: Frame,
    ): Attempt {
        val observed = env.screens.screen(node.mode, frame.flow.settings.observeMode, force = true)
        val screen = observed.screen
        val active = screen.windows.firstOrNull { it.active } ?: screen.windows.firstOrNull()
        val out =
            JsonObject(
                mapOf(
                    "mode" to JsonPrimitive(screen.mode.id),
                    "app" to (active?.app?.let { JsonPrimitive(it) } ?: JsonNull),
                    "page" to (active?.page?.let { JsonPrimitive(it) } ?: JsonNull),
                    "nodes" to JsonPrimitive(screen.nodeCount),
                    "text" to JsonPrimitive(env.screens.pageText(screen)),
                ),
            )
        frame.store(node.out, out)
        return Attempt.Ok(output = out, text = out["page"]?.let { Values.str(it) })
    }

    suspend fun read(
        node: ReadNode,
        frame: Frame,
    ): Attempt {
        val value: JsonElement =
            when (node.from) {
                ReadFrom.TARGET -> readTarget(node, frame, all = false)
                ReadFrom.LIST -> readTarget(node, frame, all = true)
                ReadFrom.PAGE -> JsonPrimitive(env.screens.pageText(screenOf(frame)))
                ReadFrom.REGION -> JsonPrimitive(regionText(node, frame))
                ReadFrom.SCREENSHOT -> JsonPrimitive(env.ports.observe.screenshot(node.region))
            }
        frame.store(node.out, value)
        return Attempt.Ok(output = value, text = Values.str(value).takeIf { value is JsonPrimitive })
    }

    private suspend fun screenOf(frame: Frame): ScreenModel =
        env.screens.screen(null, frame.flow.settings.observeMode).screen

    private suspend fun regionText(
        node: ReadNode,
        frame: Frame,
    ): String {
        val region =
            node.region
                ?: throw com.example.autop.flow.core.expr
                    .FlowException(FlowErrorCodes.INVALID, "region 读取需要 region")
        if (region.size != REGION_SIZE) return ""
        return screenOf(frame)
            .flatten()
            .filter { n ->
                val cx = (n.bounds.l + n.bounds.r) / 2
                val cy = (n.bounds.t + n.bounds.b) / 2
                cx in region[0]..region[2] && cy in region[1]..region[3]
            }.flatMap { listOfNotNull(it.text, it.desc) }
            .joinToString("\n")
    }

    private suspend fun readTarget(
        node: ReadNode,
        frame: Frame,
        all: Boolean,
    ): JsonElement {
        val target =
            node.target
                ?: throw com.example.autop.flow.core.expr
                    .FlowException(FlowErrorCodes.INVALID, "read 需要 target")
        val effective = if (all) target.copy(pick = Pick.ALL, actionable = target.actionable ?: false) else target
        val match =
            env.screens.find(
                frame,
                effective,
                wait =
                    node.attr != ReadAttr.EXISTS && node.attr != ReadAttr.COUNT,
            )
        val nodes = (match as? TargetMatch.Found)?.hits?.mapNotNull { it.node }.orEmpty()
        if (match is TargetMatch.Ambiguous) {
            throw com.example.autop.flow.core.expr.FlowException(
                FlowErrorCodes.TARGET_AMBIGUOUS,
                "目标匹配到 ${match.count} 个元素",
            )
        }
        return when (node.attr) {
            ReadAttr.EXISTS -> JsonPrimitive(nodes.isNotEmpty())
            ReadAttr.COUNT -> JsonPrimitive(nodes.size)
            else -> {
                if (nodes.isEmpty()) {
                    throw com.example.autop.flow.core.expr.FlowException(
                        FlowErrorCodes.TARGET_NOT_FOUND,
                        "找不到目标：${target.value ?: target.tag}",
                    )
                }
                val values = nodes.map { attrOf(it, node.attr) }
                if (all) JsonArray(values) else values.first()
            }
        }
    }

    private fun attrOf(
        n: ScreenNode,
        attr: ReadAttr,
    ): JsonElement =
        when (attr) {
            ReadAttr.TEXT -> JsonPrimitive(n.text.orEmpty())
            ReadAttr.DESC -> JsonPrimitive(n.desc.orEmpty())
            ReadAttr.HINT -> JsonPrimitive(n.hint.orEmpty())
            ReadAttr.STATE -> JsonPrimitive(n.state.orEmpty())
            ReadAttr.CHECKED -> JsonPrimitive("checked" in n.st)
            ReadAttr.BOUNDS ->
                JsonArray(
                    listOf(n.bounds.l, n.bounds.t, n.bounds.r, n.bounds.b).map { JsonPrimitive(it) },
                )
            ReadAttr.EXISTS -> JsonPrimitive(true)
            ReadAttr.COUNT -> JsonPrimitive(1)
        }

    // ---- wait ----------------------------------------------------------------------------------

    suspend fun wait(
        node: WaitNode,
        frame: Frame,
    ): Attempt {
        val timeout = node.timeoutMs ?: env.runCfg.waitTimeoutMs
        val ok =
            when (node.waitFor) {
                WaitFor.TIME -> {
                    delay(node.ms?.let { env.rangeMs(it) } ?: 0)
                    true
                }
                WaitFor.NOTIFICATION -> notification(node, frame, timeout)
                WaitFor.UNTIL -> poll(frame, timeout, refresh = false) { Values.truthy(frame.eval(requireValue(node))) }
                else -> pollScreen(node, frame, timeout)
            }
        if (node.waitFor != WaitFor.TIME && node.waitFor != WaitFor.UNTIL) env.screens.markDirty()
        frame.store(node.out, JsonPrimitive(ok))
        if (ok || node.onTimeout == WaitTimeoutAction.CONTINUE) return Attempt.Ok(output = JsonPrimitive(ok))
        return Attempt.Failed(FlowErrorCodes.WAIT_TIMEOUT, "等待超时（${timeout}ms）：${describe(node)}")
    }

    private fun describe(node: WaitNode): String = node.value ?: node.target?.value ?: node.waitFor.name

    private fun requireValue(node: WaitNode): String =
        node.value ?: throw com.example.autop.flow.core.expr
            .FlowException(FlowErrorCodes.INVALID, "wait 需要 value")

    private suspend fun notification(
        node: WaitNode,
        frame: Frame,
        timeout: Long,
    ): Boolean {
        val found =
            env.ports.notifications.await(
                packageName = node.packageName?.let { frame.templates.renderString(it) },
                contains = node.contains?.let { frame.templates.renderString(it) },
                sender = node.sender?.let { frame.templates.renderString(it) },
                sinceMs = env.ports.clock.nowMs(),
                timeoutMs = if (timeout > 0) timeout else Long.MAX_VALUE / 2,
            )
        return found != null
    }

    private suspend fun pollScreen(
        node: WaitNode,
        frame: Frame,
        timeout: Long,
    ): Boolean {
        val mode = frame.flow.settings.observeMode
        val value = node.value?.let { frame.templates.renderString(it) }
        if (node.waitFor == WaitFor.CHANGE || node.waitFor == WaitFor.STABLE) {
            env.screens.screen(null, mode, force = true)
            env.screens.markDirty(action = true)
        }
        return poll(frame, timeout, refresh = true) {
            val screen = env.screens.screen(null, mode, force = true)
            when (node.waitFor) {
                WaitFor.TEXT -> env.screens.pageText(screen.screen).contains(value.orEmpty(), ignoreCase = true)
                WaitFor.GONE -> !env.screens.pageText(screen.screen).contains(value.orEmpty(), ignoreCase = true)
                WaitFor.ELEMENT -> targetState(node, frame) is TargetMatch.Found
                WaitFor.ELEMENT_GONE -> targetState(node, frame) is TargetMatch.NotFound
                WaitFor.CHANGE -> env.screens.changedSince(node.since ?: "lastAction")
                WaitFor.STABLE -> stable()
                WaitFor.APP -> appIs(node, frame, value)
                WaitFor.COND -> env.conditions.eval(requireNotNull(node.cond) { "wait.cond 需要 cond" }, frame)
                WaitFor.TIME, WaitFor.NOTIFICATION, WaitFor.UNTIL -> true
            }
        }
    }

    private suspend fun targetState(
        node: WaitNode,
        frame: Frame,
    ): TargetMatch {
        val target =
            node.target
                ?: throw com.example.autop.flow.core.expr
                    .FlowException(FlowErrorCodes.INVALID, "wait 需要 target")
        return env.screens.find(frame, target, wait = false)
    }

    private fun stable(): Boolean {
        val unchanged = !env.screens.changedSince("lastAction")
        env.screens.markDirty(action = true)
        return unchanged
    }

    private suspend fun appIs(
        node: WaitNode,
        frame: Frame,
        value: String?,
    ): Boolean {
        val fg =
            env.ports.device
                .state()
                .foregroundPackage
        val screen = env.screens.screen(null, frame.flow.settings.observeMode).screen
        val appOk = value == null || fg == value || screen.windows.any { it.app == value }
        val page = node.page?.let { frame.templates.renderString(it) } ?: return appOk
        return appOk && screen.windows.any { it.page?.contains(page) == true }
    }

    private suspend fun poll(
        frame: Frame,
        timeoutMs: Long,
        refresh: Boolean,
        check: suspend () -> Boolean,
    ): Boolean {
        val mode = frame.flow.settings.observeMode
        val interval = if (refresh) env.screens.pollIntervalMs(mode) else env.runCfg.pollMs
        val end = if (timeoutMs > 0) env.ports.clock.nowMs() + timeoutMs else Long.MAX_VALUE
        while (true) {
            if (check()) return true
            if (env.ports.clock.nowMs() >= end) return false
            delay(interval)
        }
    }

    private companion object {
        const val REGION_SIZE = 4
    }
}
