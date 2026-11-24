/**
 * open_app（docs/design/tool_optimize/02 §8、06 §1.3）：名称匹配多个候选时失败并列出候选，
 * 不启动任何应用；单候选/指定 package 启动。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.device.AppInfo
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.DisplayTarget
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
class OpenAppToolTest {
    @Test
    fun `multiple name matches fail with candidates and launch nothing`() =
        runTest {
            val env = ToolTestEnv()
            val launcher =
                FakeAppLauncher(
                    apps =
                        listOf(
                            AppInfo("com.autonavi.minimap", "高德地图"),
                            AppInfo("com.baidu.BaiduMap", "百度地图"),
                        ),
                )
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.openAppTool(launcher)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"name":"地图"}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(DeviceErrorCodes.APP_AMBIGUOUS)
            val hint = out.error?.hint.orEmpty()
            assertThat(hint).contains("没有打开任何应用")
            assertThat(hint).contains("高德地图")
            assertThat(hint).contains("百度地图")
            assertThat(hint).contains("package")
            assertThat(launcher.launched).isEmpty()
        }

    @Test
    fun `single match launches the app`() =
        runTest {
            val env = ToolTestEnv()
            val launcher = FakeAppLauncher(apps = listOf(AppInfo("com.autonavi.minimap", "高德地图")))
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.openAppTool(launcher)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"name":"高德地图"}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            assertThat(launcher.launched).containsExactly("com.autonavi.minimap")
            assertThat(out.content.toString()).contains("已请求打开 高德地图（com.autonavi.minimap）")
        }

    @Test
    fun `background empty display launches when snapshot is unavailable`() =
        runTest {
            val env = ToolTestEnv()
            val launcher = FakeAppLauncher(apps = listOf(AppInfo("com.autonavi.minimap", "高德地图")))
            env.reader.snapshots += Outcome.Err(AppError("no_foreground", "empty display"))
            val tool = env.openAppTool(launcher)
            val display = DisplayTarget.Background(RunId("background-run"), 9, 720, 1600, 320)

            val out =
                withContext(display) {
                    val prepared = okOf(tool.prepare(args("""{"name":"高德地图"}"""), env.ctx()))
                    tool.perform(prepared, env.ctx())
                }

            assertThat(out.ok).isTrue()
            assertThat(launcher.launched).containsExactly("com.autonavi.minimap")
            assertThat(env.reader.snapshots).isEmpty()
        }

    @Test
    fun `background explicit package launches when snapshot is unavailable`() =
        runTest {
            val env = ToolTestEnv()
            val launcher = FakeAppLauncher()
            env.reader.snapshots += Outcome.Err(AppError("no_foreground", "empty display"))
            val tool = env.openAppTool(launcher)
            val display = DisplayTarget.Background(RunId("background-run"), 9, 720, 1600, 320)

            val out =
                withContext(display) {
                    val prepared =
                        okOf(tool.prepare(args("""{"package":"com.autonavi.minimap"}"""), env.ctx()))
                    tool.perform(prepared, env.ctx())
                }

            assertThat(out.ok).isTrue()
            assertThat(launcher.launched).containsExactly("com.autonavi.minimap")
        }

    @Test
    fun `main display keeps snapshot failure and launches nothing`() =
        runTest {
            val env = ToolTestEnv()
            val launcher = FakeAppLauncher(apps = listOf(AppInfo("com.autonavi.minimap", "高德地图")))
            env.reader.snapshots += Outcome.Err(AppError("tree_unavailable", "screen unavailable"))
            val tool = env.openAppTool(launcher)

            val prepared = tool.prepare(args("""{"name":"高德地图"}"""), env.ctx())

            assertThat((prepared as Outcome.Err).error.code).isEqualTo("tree_unavailable")
            assertThat(launcher.launched).isEmpty()
        }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun okOf(outcome: Outcome<PreparedAction>): PreparedAction =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected ok, was ${outcome.error.code}")
        }
}
