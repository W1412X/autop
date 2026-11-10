/**
 * 运行期测试装配（docs/tech/04 §6）：用真实 DefaultAgentSession + 测试替身跑完整 Run，
 * 事件与状态经 [store]/[RunHandle] 断言。
 */
package com.example.autop.agent.runtime.testing

import com.example.autop.agent.AgentSession
import com.example.autop.agent.Compactor
import com.example.autop.agent.ContextContributor
import com.example.autop.agent.HistoryRenderRule
import com.example.autop.agent.ReplayPlan
import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayTargetResolver
import com.example.autop.agent.RunHandle
import com.example.autop.agent.RunRequest
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAuditSink
import com.example.autop.agent.runtime.DefaultAgentSession
import com.example.autop.agent.runtime.RuntimeExtensions
import com.example.autop.agent.runtime.RuntimePorts
import com.example.autop.agent.runtime.RuntimeServices
import com.example.autop.agent.runtime.rules.ImageRetentionRule
import com.example.autop.agent.runtime.rules.LongTextTruncateRule
import com.example.autop.agent.runtime.rules.ObservationDegradeRule
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.AgentToolsSection
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.policy.PolicyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 扩展点与端口的测试替身集合（参数对象，coding-standards §1：参数超限用参数对象）。 */
internal data class HarnessFakes(
    val rules: Set<HistoryRenderRule> = emptySet(),
    val compactors: Set<Compactor> = emptySet(),
    val contributors: Set<ContextContributor> = emptySet(),
    val policy: PolicyEngine = FakePolicyEngine(),
    val audit: ToolAuditSink? = null,
    /** ask_user 端口；null = 按超时处理。 */
    val interaction: com.example.autop.agent.UserInteraction? = null,
    /** 回放前状态采样（docs/tech/04 §3.12）；null = 跳过回放前缀。 */
    val replayProbe: ReplayStateProbe? = null,
    /** 回放目标重解析（§5.7）；null = 按记录坐标执行。 */
    val replayTargets: ReplayTargetResolver? = null,
)

/** 运行期测试装配。 */
internal class RuntimeHarness(
    tools: Set<Tool> = emptySet(),
    val llm: ScriptedLlmProvider = ScriptedLlmProvider(),
    configOverrides: JsonObject = JsonObject(emptyMap()),
    val fakes: HarnessFakes = HarnessFakes(),
) {
    private val clock = FakeClock(1_000L)

    val store = InMemoryThreadStore(clock)

    private val config = FakeConfigProvider(configOverrides)
    private val enabledIds: List<String> = AgentToolsSection.DEFAULT_ENABLED + tools.map { it.id }

    val session: AgentSession =
        DefaultAgentSession(
            services = RuntimeServices(store, FakeLlmGateway(llm), TestPromptRepository(), config, clock),
            extensions =
                RuntimeExtensions(
                    tools = tools,
                    enrichers = emptySet(),
                    contributors = fakes.contributors,
                    stateSources = emptySet(),
                    rules = fakes.rules,
                    compactors = fakes.compactors,
                ),
            ports =
                RuntimePorts(
                    fakes.policy,
                    FakePlatform(),
                    fakes.interaction,
                    fakes.audit,
                    NoopLogger,
                    fakes.replayProbe,
                    fakes.replayTargets,
                ),
        )

    /** 建空线程。 */
    suspend fun newThread(title: String? = null): ThreadId = store.createThread(title, null)

    /** 启动一次 Run；[scope] 由 runTest 提供。[replay] 为回放计划（docs/tech/04 §3.12）。 */
    suspend fun start(
        scope: CoroutineScope,
        threadId: ThreadId? = null,
        input: List<Block> = listOf(Block.Text("你好")),
        toolFilter: Set<String>? = null,
        replay: ReplayPlan? = null,
    ): RunHandle =
        session.start(
            request =
                RunRequest(
                    runId = RunId("run-1"),
                    threadId = threadId ?: newThread(),
                    input = input,
                    goal = null,
                    vars = emptyMap(),
                    config = config.layered(enabledOverlay()),
                    toolFilter = toolFilter,
                    replay = replay,
                ),
            scope = scope,
        )

    /** `agent.tools.enabled` 覆盖：默认启用集 + 注册的测试工具。 */
    private fun enabledOverlay(): JsonObject =
        buildJsonObject {
            put(
                "agent",
                buildJsonObject {
                    put(
                        "tools",
                        buildJsonObject {
                            put("enabled", JsonArray(enabledIds.map(::JsonPrimitive)))
                        },
                    )
                },
            )
        }
}

/** 取线程内全部载荷。 */
internal fun InMemoryThreadStore.payloads(threadId: ThreadId): List<com.example.autop.agent.EventPayload> =
    payloadsOf(threadId)

/** 三个内置渲染规则（docs/tech/04 §3.4），使用真实模板。 */
internal fun defaultRenderRules(): Set<HistoryRenderRule> {
    val prompts = TestPromptRepository()
    return setOf(ObservationDegradeRule(prompts), ImageRetentionRule(prompts), LongTextTruncateRule(prompts))
}

/**
 * 嵌套配置覆盖树：`overridesOf("agent", "history", "truncate" to mapOf("maxChars" to 100))`。
 * 前面的参数是路径段，后面可跟多个叶子键值对；值支持 String/Number/Boolean/Map（Map 递归构建）。
 */
internal fun overridesOf(vararg pathAndLeaves: Any): JsonObject {
    val path = pathAndLeaves.takeWhile { it is String }.map { it as String }
    val leaves = pathAndLeaves.drop(path.size).map { it as Pair<*, *> }
    require(path.isNotEmpty() && leaves.isNotEmpty()) { "路径段 + 至少一个叶子键值对" }
    var node = leavesObject(leaves)
    for (segment in path.reversed()) {
        node = buildJsonObject { put(segment, node) }
    }
    return node
}

private fun leavesObject(leaves: List<Pair<*, *>>): JsonObject =
    buildJsonObject {
        for ((rawKey, rawValue) in leaves) {
            val key = rawKey as String
            when (rawValue) {
                is String -> put(key, rawValue)
                is Number -> put(key, rawValue)
                is Boolean -> put(key, rawValue)
                is Map<*, *> ->
                    @Suppress("UNCHECKED_CAST")
                    put(key, leavesObject(rawValue.entries.map { it.key to it.value }))
                else -> error("unsupported override value: $rawValue")
            }
        }
    }
