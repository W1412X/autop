/**
 * 工具自测（N198）：普通工具 invoke / 屏幕动作两步 prepare→perform / 异常转错误 /
 * 非法参数回退 fixture / 未知工具 / 历史上限 / 导出文本；
 * N207：参数元数据（Schema+模板说明+fixture 示例）与完整摘要。
 */
package com.example.autop.agent.runtime.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolParamKind
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.ToolSpecBuilder
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.agent.runtime.testing.okText
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.Clock
import com.example.autop.core.common.LogFiles
import com.example.autop.core.common.Outcome
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.llm.Block
import com.example.autop.policy.PolicySubject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class ToolProberTest {
    private val threadStore = InMemoryThreadStore()
    private val config = FakeConfigProvider()
    private val clock = FakeClock()

    @Test
    fun `probe runs plain tool and records summary`() =
        runTest {
            val tool = ProbeTool(name = "probe_plain", output = okText("hello probe"))
            val prober = proberOf(tool)

            val result = prober.probe("probe_plain", """{"a":1}""")

            assertThat(result.ok).isTrue()
            assertThat(result.summary).contains("hello probe")
            assertThat(result.args).contains("\"a\":1")
            assertThat(prober.history()).containsExactly(result)
        }

    @Test
    fun `probe runs screen action tool through prepare then perform`() =
        runTest {
            val tool = ProbeScreenTool(name = "probe_screen")
            val prober = proberOf(tool)

            val result = prober.probe("probe_screen", null)

            assertThat(result.ok).isTrue()
            assertThat(result.summary).contains("screen-ok")
            assertThat(tool.performed).isTrue()
        }

    @Test
    fun `probe turns tool crash into error result`() =
        runTest {
            val tool = ProbeTool(name = "probe_boom", error = IllegalStateException("boom"))
            val prober = proberOf(tool)

            val result = prober.probe("probe_boom", null)

            assertThat(result.ok).isFalse()
            assertThat(result.summary).contains("agent.tool_crashed")
        }

    @Test
    fun `probe falls back to fixture when args json invalid`() =
        runTest {
            val tool = ProbeTool(name = "probe_plain")
            val prober = proberOf(tool)

            val result = prober.probe("probe_plain", "{ not json")

            // 无该工具的 fixture 条目时回退空对象（params 不合法也会得到有效诊断输出）。
            assertThat(result.args).isEqualTo("{}")
        }

    @Test
    fun `probe returns failure for unknown tool`() =
        runTest {
            val prober = proberOf(ProbeTool(name = "probe_plain"))

            val result = prober.probe("no_such_tool", null)

            assertThat(result.ok).isFalse()
            assertThat(result.summary).contains("未知工具")
        }

    @Test
    fun `targets expose name access and default args`() =
        runTest {
            val prober = proberOf(ProbeTool(name = "probe_plain"), ProbeTool(name = "probe_other"))

            val targets = prober.targets()

            assertThat(targets.map { it.name }).containsExactly("probe_plain", "probe_other").inOrder()
            assertThat(targets.all { it.defaultArgs.isNotBlank() }).isTrue()
        }

    @Test
    fun `history caps at fifty entries`() =
        runTest {
            val prober = proberOf(ProbeTool(name = "probe_plain"))
            repeat(55) { prober.probe("probe_plain", null) }

            assertThat(prober.history()).hasSize(50)
        }

    @Test
    fun `diagnostics text includes history and log lines`() =
        runTest {
            val tool = ProbeTool(name = "probe_plain", output = okText("diag"))
            val prober =
                ToolProber(
                    tools = listOf(tool),
                    threadStore = threadStore,
                    config = config,
                    clock = clock,
                    logFiles = FakeLogFiles("LOG-LINE-1\n"),
                    prompts = FakePromptRepository(),
                )
            prober.probe("probe_plain", null)

            val text = prober.diagnosticsText()

            assertThat(text).contains("== 工具自测记录（旧→新）==")
            assertThat(text).contains("probe_plain")
            assertThat(text).contains("LOG-LINE-1")
        }

    @Test
    fun `targets expose param metadata for the visual editor`() {
        val tool =
            ProbeTool(name = "tap") {
                integer("x", required = true)
                integer("y", required = true)
                boolean("long")
            }
        val prober = proberOf(tool)

        val params = prober.targets().single().params

        assertThat(params.map { it.name }).containsExactly("x", "y", "long").inOrder()
        val x = params.first()
        assertThat(x.kind).isEqualTo(ToolParamKind.INTEGER)
        assertThat(x.required).isTrue()
        assertThat(x.description).contains("横坐标")
        assertThat(x.example).isEqualTo("500")
        assertThat(params.last().kind).isEqualTo(ToolParamKind.BOOLEAN)
        assertThat(params.last().required).isFalse()
    }

    @Test
    fun `nested params carry child field hints`() {
        val tool =
            ProbeTool(name = "wait") {
                obj("until") {
                    string("text_appears")
                }
            }
        val prober = proberOf(tool)

        val until =
            prober
                .targets()
                .single()
                .params
                .single()

        assertThat(until.kind).isEqualTo(ToolParamKind.OBJECT)
        assertThat(until.description).contains("子字段：text_appears(string)")
    }

    @Test
    fun `summary keeps full text without clipping`() =
        runTest {
            val longText = "长输出" + "x".repeat(2_000) + "END-OF-OUTPUT"
            val prober = proberOf(ProbeTool(name = "probe_plain", output = okText(longText)))

            val result = prober.probe("probe_plain", null)

            assertThat(result.summary).contains("END-OF-OUTPUT")
        }

    @Test
    fun `probe extracts debug images from output content`() =
        runTest {
            val image = Block.Image("probe-debug/det-1.png", "image/png", 100, 80, "检测渲染图")
            val output = ToolOutput(ok = true, content = listOf(Block.Text("t"), image))
            val result = proberOf(ProbeTool(name = "probe_img", output = output)).probe("probe_img", null)

            assertThat(result.images).hasSize(1)
            assertThat(result.images.single().fileRef).isEqualTo("probe-debug/det-1.png")
            assertThat(result.images.single().alt).isEqualTo("检测渲染图")
        }

    @Test
    fun `probe context turns on debug images capture`() =
        runTest {
            var debugImages: Boolean? = null
            val tool =
                object : Tool {
                    override val id: String = "tool.probe_ctx"

                    override val spec: ToolSpec =
                        toolSpec(name = "probe_ctx", descriptionPromptId = "test.fake_tool") {}

                    override val access: ToolAccess = ToolAccess.READ_SYSTEM

                    override suspend fun invoke(
                        args: JsonObject,
                        ctx: ToolContext,
                    ): ToolOutput {
                        debugImages = ctx.debugImages
                        return okText("ok")
                    }
                }
            proberOf(tool).probe("probe_ctx", null)

            assertThat(debugImages).isTrue()
        }

    private fun proberOf(vararg tools: Tool): ToolProber =
        ToolProber(
            tools = tools.toList(),
            threadStore = threadStore,
            config = config,
            clock = clock,
            logFiles = FakeLogFiles(""),
            prompts = FakePromptRepository(),
        )
}

/** 可递增时钟（durationMs 稳定可断言）。 */
private class FakeClock : Clock {
    private var now: Long = 1_000

    override fun nowMillis(): Long = now.also { now += 1 }
}

/** 日志文件假件（只读路径用）。 */
private class FakeLogFiles(
    private val text: String,
) : LogFiles {
    override val directoryPath: String = "/fake/logs"

    override fun append(line: String): Unit = Unit

    override fun totalBytes(): Long = text.length.toLong()

    override fun readAll(): String = text

    override fun clear(): Long = 0
}

/** 普通工具假件：返回固定输出或抛固定异常，记录收到的参数；spec 参数结构可定制（N207）。 */
private class ProbeTool(
    private val name: String,
    private val output: ToolOutput = okText("probe-ok"),
    private val error: Throwable? = null,
    params: ToolSpecBuilder.() -> Unit = {},
) : Tool {
    override val id: String = "tool.$name"

    override val spec: ToolSpec =
        toolSpec(
            name = name,
            descriptionPromptId = DESCRIPTION_PROMPT_ID_OF[name] ?: "test.fake_tool",
            block = params,
        )

    override val access: ToolAccess = ToolAccess.READ_SYSTEM

    val invocations: MutableList<JsonObject> = mutableListOf()

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        invocations += args
        error?.let { throw it }
        return output
    }
}

/** 假件的描述模板 id：命中真实模板（tool.*）才取得到参数说明；其余取不到（兜底路径）。 */
private val DESCRIPTION_PROMPT_ID_OF: Map<String, String> =
    mapOf(
        "tap" to "tool.tap",
        "wait" to "tool.wait",
    )

/** 屏幕动作两步工具假件（同 FakeScreenTool 语义，名字可变）。 */
private class ProbeScreenTool(
    private val name: String,
) : ScreenActionTool {
    override val id: String = "tool.$name"

    override val spec: ToolSpec =
        toolSpec(name = name, descriptionPromptId = "test.fake_tool") {}

    override val access: ToolAccess = ToolAccess.WRITE_SCREEN

    var performed: Boolean = false

    override suspend fun prepare(
        args: JsonObject,
        ctx: ToolContext,
    ): Outcome<PreparedAction> =
        Outcome.Ok(
            PreparedAction(
                subject = PolicySubject(toolId = id, targetText = "probe"),
                payload = args,
            ),
        )

    override suspend fun perform(
        action: PreparedAction,
        ctx: ToolContext,
    ): ToolOutput {
        performed = true
        return okText("screen-ok")
    }
}
