/**
 * list_apps（docs/design/tool_optimize/02 §12、06 §1.3）：未传 limit 时最多
 * `device.apps.listLimit`（默认 50）条并输出截断说明。
 */
package com.example.autop.device.impl.tools

import com.example.autop.device.AppInfo
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ListAppsToolTest {
    @Test
    fun `without limit shows at most 50 apps and notes the truncation`() =
        runTest {
            val env = ToolTestEnv()
            val tool = ListAppsTool(FakeAppLauncher(apps = apps(60)), env.prompts)

            val out = tool.invoke(JsonObject(emptyMap()), env.ctx())

            assertThat(out.ok).isTrue()
            val lines = textOf(out.content).lines()
            assertThat(lines).hasSize(51) // 50 条应用 + 截断说明
            assertThat(lines[49]).isEqualTo("应用49 com.example.app49")
            assertThat(lines[50]).contains("共 60 个应用，已显示 50 个")
        }

    @Test
    fun `explicit limit narrows the list`() =
        runTest {
            val env = ToolTestEnv()
            val tool = ListAppsTool(FakeAppLauncher(apps = apps(60)), env.prompts)

            val out = tool.invoke(Json.parseToJsonElement("""{"limit":10}""").jsonObject, env.ctx())

            assertThat(out.ok).isTrue()
            val lines = textOf(out.content).lines()
            assertThat(lines).hasSize(11) // 10 条应用 + 截断说明
            assertThat(lines[9]).isEqualTo("应用9 com.example.app9")
            assertThat(lines[10]).contains("共 60 个应用，已显示 10 个")
        }

    private fun textOf(content: List<Block>): String = (content.single() as Block.Text).text

    private fun apps(count: Int): List<AppInfo> = (0 until count).map { AppInfo("com.example.app$it", "应用$it") }
}
