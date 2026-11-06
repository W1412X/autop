package com.example.autop.flow.impl.ports

import com.example.autop.agent.AgentSession
import com.example.autop.agent.RunLineage
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.UserInteraction
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.DeviceState
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.NotificationFeed
import com.example.autop.device.NotificationInfo
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowStateStore
import com.example.autop.flow.FlowStore
import com.example.autop.flow.core.AgentContext
import com.example.autop.flow.core.AgentPort
import com.example.autop.flow.core.AgentResult
import com.example.autop.flow.core.DevicePort
import com.example.autop.flow.core.FlowResolver
import com.example.autop.flow.core.NotificationPort
import com.example.autop.flow.core.StateAddress
import com.example.autop.flow.core.StatePort
import com.example.autop.flow.core.UserPort
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.llm.Block
import com.example.autop.llm.ModelRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 状态端口：把解释器的作用域地址映射到 `flow_state`。 */
internal class StatePortImpl(
    private val store: FlowStateStore,
) : StatePort {
    override suspend fun get(
        scope: StateAddress,
        key: String,
    ): JsonElement? = store.get(scope.scope, key)

    override suspend fun set(
        scope: StateAddress,
        key: String,
        value: JsonElement,
        ttlMs: Long?,
    ) = store.set(scope.scope, key, value, ttlMs)

    override suspend fun delete(
        scope: StateAddress,
        key: String,
    ) = store.delete(scope.scope, key)
}

/** 等待通知：订阅 `NotificationFeed.posted`，按包名/正文/发送者过滤。 */
internal class NotificationPortImpl(
    private val feed: NotificationFeed,
) : NotificationPort {
    override suspend fun await(
        packageName: String?,
        contains: String?,
        sender: String?,
        sinceMs: Long,
        timeoutMs: Long,
    ): NotificationInfo? =
        withTimeoutOrNull(timeoutMs) {
            feed.posted.first { matches(it, packageName, contains, sender, sinceMs) }
        }

    private fun matches(
        n: NotificationInfo,
        packageName: String?,
        contains: String?,
        sender: String?,
        sinceMs: Long,
    ): Boolean {
        if (n.postedAt < sinceMs) return false
        if (!packageName.isNullOrEmpty() && n.packageName != packageName) return false
        if (!contains.isNullOrEmpty()) {
            val body = listOfNotNull(n.title, n.text, n.bigText, n.subText).joinToString("\n")
            if (!body.contains(contains, ignoreCase = true)) return false
        }
        if (!sender.isNullOrEmpty()) {
            val names = listOfNotNull(n.title, n.conversationTitle) + n.messages.mapNotNull { it.sender }
            if (names.none { it.contains(sender, ignoreCase = true) }) return false
        }
        return true
    }
}

/** 设备状态。 */
internal class DevicePortImpl(
    private val provider: DeviceStateProvider,
) : DevicePort {
    override fun state(): DeviceState = provider.current()
}

/** 执行前确认：经 `UserInteraction` 向用户提问（选项文案取自模板）。 */
internal class UserPortImpl(
    private val interaction: UserInteraction,
    private val prompts: PromptRepository,
    private val runId: RunId,
) : UserPort {
    override suspend fun confirm(
        nodeId: String,
        nodeName: String?,
        message: String?,
        timeoutMs: Long,
    ): Boolean? {
        val accept = text(ACCEPT, emptyMap())
        val skip = text(SKIP, emptyMap())
        val question = text(QUESTION, mapOf("node" to (nodeName ?: nodeId), "message" to message.orEmpty()))
        return when (val answer = interaction.ask(runId, question, listOf(accept, skip), timeoutMs)) {
            is Outcome.Ok -> answer.value.trim() == accept
            is Outcome.Err -> null
        }
    }

    private fun text(
        id: String,
        vars: Map<String, String>,
    ): String = (prompts.render(id, vars) as? Outcome.Ok)?.value?.text.orEmpty()

    private companion object {
        const val QUESTION = "flow.confirm.question"
        const val ACCEPT = "flow.confirm.accept"
        const val SKIP = "flow.confirm.skip"
    }
}

/** 子流程解析。 */
internal class FlowResolverImpl(
    private val store: FlowStore,
) : FlowResolver {
    override suspend fun resolve(nameOrId: String): FlowDefinition? = store.resolve(nameOrId)?.definition
}

/** 角色字符串 → [ModelRole]。 */
internal fun roleOf(raw: String?): ModelRole? =
    when (raw?.lowercase()) {
        "main" -> ModelRole.MAIN
        "vision" -> ModelRole.VISION
        "light" -> ModelRole.LIGHT
        else -> null
    }

/**
 * 智能体端口：为 `agent` 节点与 handoff 启动子 Run（独立线程、独立 runId），
 * 登记谱系使其 ask_user 落到根流程 Run 上。
 */
internal class AgentPortImpl(
    private val session: AgentSession,
    private val store: ThreadStore,
    private val lineage: RunLineage,
    private val ids: IdGenerator,
    private val configProvider: ConfigProvider,
    private val baseConfig: ConfigSnapshot,
    private val parentRunId: RunId,
    private val scope: CoroutineScope,
    private val prompts: PromptRepository,
) : AgentPort {
    override suspend fun run(
        nodeId: String,
        goal: String,
        tools: List<String>?,
        maxSteps: Int?,
        maxDurationMs: Long?,
        role: String?,
        context: AgentContext?,
    ): AgentResult {
        // 空目标表示“隐式交回”：使用模板 flow.recovery.handoff_goal 生成目标（flows-recording-recovery.md R4）。
        val text = goal.ifBlank { renderHandoffGoal(nodeId, context) }
        val childRun = RunId(ids.newId())
        val thread = store.createThread(title = text.take(TITLE_CHARS), parent = null)
        lineage.register(childRun, parentRunId)
        try {
            val request =
                RunRequest(
                    runId = childRun,
                    threadId = thread,
                    input = listOf(Block.Text(text)),
                    goal = text,
                    vars = mapOf(RUN_MODE_KEY to RUN_MODE_BACKGROUND),
                    config = budgetConfig(maxSteps, maxDurationMs),
                    toolFilter = tools?.toSet(),
                    modelRole = roleOf(role) ?: ModelRole.MAIN,
                )
            val finished = session.start(request, scope).await()
            val ok = finished.status == RunOutcome.SUCCEEDED
            return AgentResult(ok, finished.summary.orEmpty(), finished.outputs, finished.errorCode.takeIf { !ok })
        } finally {
            lineage.unregister(childRun)
        }
    }

    /** 叠加子 Run 的步数/时长预算（null 沿用全局配置）。 */
    private fun budgetConfig(
        maxSteps: Int?,
        maxDurationMs: Long?,
    ): ConfigSnapshot {
        if (maxSteps == null && maxDurationMs == null) return baseConfig
        val budget =
            buildJsonObject {
                maxSteps?.let { put("maxSteps", it) }
                maxDurationMs?.let { put("maxDurationMs", it) }
            }
        val overlay = JsonObject(mapOf("agent" to JsonObject(mapOf("budget" to budget))))
        return configProvider.layered(overlay)
    }

    /** 渲染隐式交回的目标：上下文缺项时给空串（模板缺变量会渲染失败）。 */
    private fun renderHandoffGoal(
        nodeId: String,
        context: AgentContext?,
    ): String {
        val vars =
            mapOf(
                "nodeId" to nodeId,
                "flowName" to context?.flowName.orEmpty(),
                "nodeName" to (context?.nodeName ?: nodeId),
                "failure" to (context?.failureMessage ?: context?.failureCode.orEmpty()),
                "expectation" to context?.expectation.orEmpty(),
                "anchor" to context?.anchorHint.orEmpty(),
                "precondition" to context?.preconditionDetail.orEmpty(),
                "executed" to (context?.executedSteps.orEmpty().joinToString("\n")),
                "screen" to context?.screenSummary.orEmpty(),
            )
        return (prompts.render(HANDOFF_GOAL_PROMPT, vars) as? Outcome.Ok)?.value?.text.orEmpty()
    }

    private companion object {
        const val HANDOFF_GOAL_PROMPT = "flow.recovery.handoff_goal"
        const val RUN_MODE_KEY = "runMode"
        const val RUN_MODE_BACKGROUND = "background"
        const val TITLE_CHARS = 20
    }
}

/** 构造参数不合法等需要抛出的统一入口。 */
internal fun invalid(message: String): Nothing = throw FlowException(FlowErrorCodes.INVALID, message)

/** 文本值小工具。 */
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
