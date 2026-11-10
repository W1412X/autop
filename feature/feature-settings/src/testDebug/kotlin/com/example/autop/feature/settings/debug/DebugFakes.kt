/**
 * 调试执行器单测的假实现（PolicyEngine / PromptRepository / Tool）。
 */
package com.example.autop.feature.settings.debug

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.RenderedPrompt
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.policy.PolicyDecision
import com.example.autop.policy.PolicyEngine
import com.example.autop.policy.PolicyOverrides
import com.example.autop.policy.PolicySubject
import kotlinx.serialization.json.JsonObject
import java.io.File

/** 调试上下文（真实配置快照 + 内存线程存储）。 */
internal fun debugContext(): ToolContext {
    val config = FakeConfigProvider().current()
    return DebugToolContext(config, DebugThreadStore(File("build/tmp/test-debug")), DebugRunValues())
}

/** 可配置判定的 [PolicyEngine] 假实现。 */
internal class FakePolicyEngine(
    private val decision: PolicyDecision,
) : PolicyEngine {
    override suspend fun evaluate(
        subject: PolicySubject,
        overrides: PolicyOverrides,
        config: com.example.autop.core.config.ConfigSnapshot,
    ): PolicyDecision = decision

    override fun redaction(
        packageName: String?,
        config: com.example.autop.core.config.ConfigSnapshot,
    ): Boolean = false
}

/** 记录渲染变量的 [PromptRepository] 假实现。 */
internal class FakePrompts : PromptRepository {
    val rendered = mutableMapOf<String, Map<String, String>>()

    override fun get(id: String): PromptTemplate =
        PromptTemplate(id, 1, emptySet(), emptySet(), emptyMap(), id, PromptSource.BUILTIN)

    override fun render(
        id: String,
        vars: Map<String, String>,
    ): Outcome<RenderedPrompt> {
        rendered[id] = vars
        return Outcome.Ok(RenderedPrompt(id, 1, "$id $vars"))
    }

    override fun all(): List<PromptTemplate> = emptyList()
}

/** 普通工具假实现（[crash] 时抛异常）。 */
internal open class FakeTool(
    private val crash: Boolean = false,
) : Tool {
    override val id: String = "tool.fake"

    override val spec = ToolSpec(name = "fake", parameters = JsonObject(emptyMap()), descriptionPromptId = "fake")

    override val access: ToolAccess = ToolAccess.READ_SCREEN

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        if (crash) error("boom")
        return ToolOutput(ok = true, content = listOf(Block.Text("fake")))
    }
}

/** 写屏工具假实现：记录 perform 是否被调用。 */
internal class FakeScreenActionTool : ScreenActionTool {
    var performed = false

    override val id: String = "tool.tap"

    override val spec = ToolSpec(name = "tap", parameters = JsonObject(emptyMap()), descriptionPromptId = "tap")

    override val access: ToolAccess = ToolAccess.WRITE_SCREEN

    override suspend fun prepare(
        args: JsonObject,
        ctx: ToolContext,
    ): Outcome<PreparedAction> =
        Outcome.Ok(
            PreparedAction(
                subject = PolicySubject(toolId = id, targetText = "确认付款"),
                payload = Unit,
            ),
        )

    override suspend fun perform(
        action: PreparedAction,
        ctx: ToolContext,
    ): ToolOutput {
        performed = true
        return ToolOutput(ok = true, content = listOf(Block.Text("done")))
    }
}
