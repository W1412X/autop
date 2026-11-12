package com.example.autop.device.impl.tools

import android.app.Application
import android.content.Intent
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.testing.FakeClock
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.NavigationApp
import com.example.autop.device.NavigationApps
import com.example.autop.device.impl.AppIdentity
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.SecureWindowCache
import com.example.autop.device.impl.intent.ActivityStarter
import com.example.autop.device.impl.intent.NavigationAppsRulesLoader
import com.example.autop.device.impl.intent.SystemIntents
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class IntentToolTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `missing kind is rejected with the intent hint`() =
        runTest {
            val env = ToolTestEnv()
            val got = tool(env, nav = FakeNavigationApps()).prepare(JsonObject(emptyMap()), env.ctx())
            val error = (got as Outcome.Err).error
            assertThat(error.code).isEqualTo(DeviceErrorCodes.INVALID_ARGUMENT)
            assertThat(error.hint).contains("intent 需要 kind")
        }

    @Test
    fun `requested map that is not installed names the installed apps`() =
        runTest {
            val env = ToolTestEnv()
            env.queue(a11yScreenOf(emptyList()))
            val nav = FakeNavigationApps(installed = listOf(AMAP))
            val out = perform(tool(env, nav), """{"kind":"navigate","destination":"北京站","map":"baidu"}""", env.ctx())
            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(DeviceErrorCodes.APP_NOT_FOUND)
            assertThat(out.error?.hint).contains("没有安装 百度地图")
            assertThat(out.error?.hint).contains("高德地图")
        }

    @Test
    fun `navigate without any map app and without a geo handler reports no handler`() =
        runTest {
            val env = ToolTestEnv()
            env.queue(a11yScreenOf(emptyList()))
            val out =
                perform(
                    tool(env, FakeNavigationApps()),
                    """{"kind":"navigate","destination":"北京站"}""",
                    env.ctx(),
                )
            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(DeviceErrorCodes.APP_NOT_FOUND)
            assertThat(out.error?.hint).contains("没有应用可以处理 navigate")
        }

    @Test
    fun `ask preference opens the app the user picked`() =
        runTest {
            val env = ToolTestEnv()
            env.provider.setOverrides(preferredMap("ask"))
            queueTwoSnapshots(env)
            val ctx = AskingContext(env.ctx(), Outcome.Ok("百度地图"))
            val out = perform(tool(env, TWO_MAP_APPS), """{"kind":"navigate","destination":"北京站"}""", ctx)
            assertThat(out.ok).isTrue()
            assertThat(out.content.toString()).contains("用户刚刚选择")
            val started = shadowOf(app).nextStartedActivity
            assertThat(started.`package`).isEqualTo(BAIDU.packageName)
            assertThat(started.dataString).startsWith("baidumap://map/direction?")
        }

    @Test
    fun `unanswered ask falls back to the first app by priority and says so`() =
        runTest {
            val env = ToolTestEnv()
            env.provider.setOverrides(preferredMap("ask"))
            queueTwoSnapshots(env)
            val ctx = AskingContext(env.ctx(), Outcome.Err(AppError("test.timeout", "no answer")))
            val out = perform(tool(env, TWO_MAP_APPS), """{"kind":"navigate","destination":"北京站"}""", ctx)
            assertThat(out.ok).isTrue()
            assertThat(out.content.toString()).contains("询问没有得到回答")
            val started: Intent = shadowOf(app).nextStartedActivity
            assertThat(started.`package`).isEqualTo(AMAP.packageName)
        }

    private fun queueTwoSnapshots(env: ToolTestEnv) {
        val screen = a11yScreenOf(emptyList())
        env.queue(screen)
        env.queue(screen)
    }

    private fun preferredMap(value: String): JsonObject =
        Json.parseToJsonElement("""{"device":{"intent":{"preferredMap":"$value"}}}""").jsonObject

    private fun tool(
        env: ToolTestEnv,
        nav: NavigationApps,
        apps: AppLauncher = NoAppLauncher,
    ): IntentTool =
        IntentTool(
            screenReader = env.reader,
            apps = apps,
            navigationApps = nav,
            rulesLoader = NavigationAppsRulesLoader(app),
            intents = SystemIntents(app),
            starter = ActivityStarter(app, NoopLogger, env.prompts),
            subjects = PolicySubjectBuilder(SecureWindowCache(FakeClock(0L))),
            epilogue = env.epilogue,
            prompts = env.prompts,
            appIdentity = AppIdentity(app),
            logger = NoopLogger,
        )

    private suspend fun perform(
        tool: IntentTool,
        args: String,
        ctx: ToolContext,
    ): ToolOutput {
        val prepared = (tool.prepare(Json.parseToJsonElement(args).jsonObject, ctx) as Outcome.Ok).value
        return tool.perform(prepared, ctx)
    }

    /** 询问替身：委托其余能力给真实上下文，只替换 askUser 的回答。 */
    private class AskingContext(
        base: ToolContext,
        private val answer: Outcome<String>,
    ) : ToolContext by base {
        override suspend fun askUser(
            question: String,
            options: List<String>,
            timeoutMs: Long,
        ): Outcome<String> = answer
    }

    private class FakeNavigationApps(
        private val installed: List<NavigationApp> = emptyList(),
    ) : NavigationApps {
        override suspend fun installed(): List<NavigationApp> = installed

        override fun known(): List<NavigationApp> = listOf(AMAP, BAIDU, TENCENT)
    }

    private object NoAppLauncher : AppLauncher {
        override suspend fun listApps(query: String?): List<AppInfo> = emptyList()

        override suspend fun launch(packageName: String): Boolean = false

        override suspend fun openUri(uri: String): Boolean = false

        override suspend fun launchWithError(packageName: String): AppError? = null

        override suspend fun openUriWithError(uri: String): AppError? = null
    }

    private object NoopLogger : Logger {
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

    private companion object {
        val AMAP = NavigationApp("amap", "高德地图", "com.autonavi.minimap")
        val BAIDU = NavigationApp("baidu", "百度地图", "com.baidu.BaiduMap")
        val TENCENT = NavigationApp("tencent", "腾讯地图", "com.tencent.map")
        val TWO_MAP_APPS = FakeNavigationApps(installed = listOf(AMAP, BAIDU))
    }
}
