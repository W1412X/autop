/**
 * 运行期测试替身（docs/tech/04 §6 测试基建）：FakeTool / 策略 / 平台能力 /
 * 用户交互 / 审计 / 日志等端口的可控实现。
 */
package com.example.autop.agent.runtime.testing

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolAuditSink
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Capability
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.common.RunId
import com.example.autop.llm.Block
import com.example.autop.policy.PolicyDecision
import com.example.autop.policy.PolicyEngine
import com.example.autop.policy.PolicyOverrides
import com.example.autop.policy.PolicySubject
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** 成功的纯文本输出。 */
internal fun okText(text: String): ToolOutput = ToolOutput(ok = true, content = listOf(Block.Text(text)))

/** 可配置的普通工具：结果由 [handler] 给出，可选参数 schema 与执行延迟。 */
internal class FakeTool(
    override val id: String = "tool.fake",
    override val access: ToolAccess = ToolAccess.READ_SCREEN,
    requiredArg: String? = null,
    private val delayMs: Long = 0,
    private val handler: suspend (args: JsonObject, ctx: ToolContext) -> ToolOutput = { _, _ -> okText("fake-ok:$id") },
) : Tool {
    override val spec: ToolSpec =
        toolSpec(name = FAKE_NAME, descriptionPromptId = "test.fake_tool") {
            if (requiredArg != null) string(requiredArg, required = true)
        }

    /** 已执行次数与入参，供断言。 */
    val invocations: MutableList<JsonObject> = mutableListOf()

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        invocations += args
        if (delayMs > 0) delay(delayMs)
        return handler(args, ctx)
    }

    companion object {
        /** 所有 FakeTool 共用的调用名；一个测试里只应放一个 FakeTool 实例。 */
        const val FAKE_NAME: String = "fake_tool"
    }
}

/** 写屏两步工具：prepare 返回 [PreparedAction]，perform 返回 [result]，供策略两步检查测试。 */
internal class FakeScreenTool(
    override val id: String = "tool.fake_screen",
    private val subject: PolicySubject = PolicySubject(toolId = "tool.fake_screen", targetText = "删除"),
    private val result: ToolOutput = okText("screen-ok"),
) : ScreenActionTool {
    override val spec: ToolSpec =
        toolSpec(name = "fake_screen", descriptionPromptId = "test.fake_tool") {}

    override val access: ToolAccess = ToolAccess.WRITE_SCREEN

    /** perform 是否被调用（策略拦截时应为 false）。 */
    var performed: Boolean = false

    override suspend fun prepare(
        args: JsonObject,
        ctx: ToolContext,
    ): Outcome<PreparedAction> = Outcome.Ok(PreparedAction(subject = subject, payload = args))

    override suspend fun perform(
        action: PreparedAction,
        ctx: ToolContext,
    ): ToolOutput {
        performed = true
        return result
    }
}

/** 可编程策略引擎：按 [decide] 返回判定，默认放行。 */
internal class FakePolicyEngine(
    private val decide: (PolicySubject) -> PolicyDecision = { PolicyDecision.Allow },
) : PolicyEngine {
    val evaluated: MutableList<PolicySubject> = mutableListOf()

    override suspend fun evaluate(
        subject: PolicySubject,
        overrides: PolicyOverrides,
        config: com.example.autop.core.config.ConfigSnapshot,
    ): PolicyDecision {
        evaluated += subject
        return decide(subject)
    }

    override fun redaction(
        packageName: String?,
        config: com.example.autop.core.config.ConfigSnapshot,
    ): Boolean = false
}

/** 平台能力：[capabilities] 集合内的能力为可用。 */
internal class FakePlatform(
    private val capabilities: Set<Capability> = Capability.entries.toSet(),
) : PlatformCapabilities {
    override fun has(c: Capability): Boolean = c in capabilities
}

/** 用户交互：按序应答；[timeout] 为真时全部问题都超时。 */
internal class FakeInteraction(
    private val answers: MutableList<String> = mutableListOf(),
    private val timeout: Boolean = false,
) : com.example.autop.agent.UserInteraction {
    val questions: MutableList<String> = mutableListOf()

    fun answer(text: String) {
        answers += text
    }

    override suspend fun ask(
        runId: RunId,
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String> {
        questions += question
        val answer = if (timeout) null else answers.removeFirstOrNull()
        return if (answer == null) {
            Outcome.Err(AppError(code = AgentErrorCodes.ASK_TIMEOUT, message = "ask timeout"))
        } else {
            Outcome.Ok(answer)
        }
    }
}

/** 审计记录收集。 */
internal class RecordingAuditSink : ToolAuditSink {
    data class Record(
        val runId: RunId,
        val toolId: String,
        val targetSummary: String?,
        val decision: PolicyDecision,
        val ok: Boolean?,
    )

    val records: MutableList<Record> = mutableListOf()

    override suspend fun record(
        runId: RunId,
        toolId: String,
        targetSummary: String?,
        decision: PolicyDecision,
        ok: Boolean?,
    ) {
        records += Record(runId, toolId, targetSummary, decision, ok)
    }
}

/** 静默日志。 */
internal object NoopLogger : Logger {
    override fun d(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun i(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun w(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit

    override fun e(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit
}
