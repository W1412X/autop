package com.example.autop.device.impl.state

import com.example.autop.agent.StateRequest
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.NavigationApp
import com.example.autop.device.NavigationApps
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class NavigationStateSourceTest {
    private val known =
        listOf(
            NavigationApp("amap", "高德地图", "com.autonavi.minimap"),
            NavigationApp("baidu", "百度地图", "com.baidu.BaiduMap"),
            NavigationApp("tencent", "腾讯地图", "com.tencent.map"),
        )

    @Test
    fun `reports installed apps and the automatic preference`() =
        runTest {
            val text = mapsText(installedIds = setOf("amap", "baidu"), preferredMap = "auto")
            assertThat(text).contains("已安装 高德地图、百度地图")
            assertThat(text).contains("用户设置：自动")
        }

    @Test
    fun `no installed app says the system geo link is the only option`() =
        runTest {
            val text = mapsText(installedIds = emptySet(), preferredMap = "auto")
            assertThat(text).contains("没有检测到地图应用")
        }

    @Test
    fun `ask preference is reported as asking each time`() =
        runTest {
            val text = mapsText(installedIds = setOf("amap"), preferredMap = "ask")
            assertThat(text).contains("每次询问用户")
        }

    @Test
    fun `preferred app that is not installed is marked as falling back`() =
        runTest {
            val text = mapsText(installedIds = setOf("amap"), preferredMap = "tencent")
            assertThat(text).contains("腾讯地图")
            assertThat(text).contains("（未安装，将自动选择）")
        }

    private suspend fun mapsText(
        installedIds: Set<String>,
        preferredMap: String,
    ): String {
        val apps = FakeNavigationApps(known, installedIds)
        val source = NavigationStateSource(navigationApps = apps, prompts = FakePromptRepository())
        val overrides = intentOverrides(preferredMap)
        val req =
            StateRequest(
                runId = RunId("run-1"),
                threadId = ThreadId("thread-1"),
                config = FakeConfigProvider(overrides).current(),
                vars = emptyMap(),
            )
        return source.entries(req).single { it.key == NavigationStateSource.KEY_MAPS }.text
    }

    private fun intentOverrides(preferredMap: String): JsonObject =
        Json
            .parseToJsonElement("""{"device":{"intent":{"preferredMap":"$preferredMap"}}}""")
            .jsonObject

    private class FakeNavigationApps(
        private val apps: List<NavigationApp>,
        private val installedIds: Set<String>,
    ) : NavigationApps {
        override suspend fun installed(): List<NavigationApp> = apps.filter { it.id in installedIds }

        override fun known(): List<NavigationApp> = apps
    }
}
