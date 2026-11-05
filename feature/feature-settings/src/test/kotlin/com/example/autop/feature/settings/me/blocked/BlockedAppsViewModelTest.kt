/**
 * [BlockedAppsViewModel] 单测：已安装应用与手动屏蔽包名合并、
 * 开关与手动添加 → `policy.blockedPackages` 整表覆盖写入（docs/tech/08 §4.5 me/blocked-apps）。
 */
package com.example.autop.feature.settings.me.blocked

import com.example.autop.core.config.PolicyConfig
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.feature.settings.me.RecordingConfigEditor
import com.example.autop.feature.settings.me.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedAppsViewModelTest {
    private val provider = FakeConfigProvider()
    private val editor = RecordingConfigEditor()
    private val logger = RecordingLogger()
    private val launcher = FakeAppLauncher(listOf(AppInfo("com.b.app", "B 应用"), AppInfo("com.a.app", "A 应用")))
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `installed apps merge with manual blocked packages`() =
        runTest(testDispatcher) {
            provider.setOverrides(blockedOverrides("com.a.app", "com.manual.app"))
            val state = createViewModel().uiState.value
            assertThat(state.apps.map { it.packageName })
                .containsExactly("com.a.app", "com.b.app", "com.manual.app")
                .inOrder()
            assertThat(state.apps.map { it.blocked }).containsExactly(true, false, true).inOrder()
            assertThat(state.blockedCount).isEqualTo(2)
        }

    @Test
    fun `toggle adds package and rewrites whole list`() =
        runTest(testDispatcher) {
            provider.setOverrides(blockedOverrides("com.a.app"))
            val viewModel = createViewModel()
            viewModel.onAction(BlockedAppsAction.Toggle("com.b.app", blocked = true))
            val (path, written) = editor.writes.single()
            assertThat(path).isEqualTo(PolicyConfig.POLICY.path + ".blockedPackages")
            assertThat(packagesOf(written as JsonArray)).containsExactly("com.a.app", "com.b.app").inOrder()
        }

    @Test
    fun `toggle off removes package`() =
        runTest(testDispatcher) {
            provider.setOverrides(blockedOverrides("com.a.app", "com.b.app"))
            val viewModel = createViewModel()
            viewModel.onAction(BlockedAppsAction.Toggle("com.a.app", blocked = false))
            assertThat(packagesOf(editor.writes.single().second as JsonArray)).containsExactly("com.b.app")
        }

    @Test
    fun `manual add rejects invalid package name`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(BlockedAppsAction.OpenManual)
            viewModel.onAction(BlockedAppsAction.AddManual("not a package"))
            assertThat(viewModel.uiState.value.manualError).isTrue()
            assertThat(viewModel.uiState.value.manualOpen).isTrue()
            assertThat(editor.writes).isEmpty()
        }

    @Test
    fun `manual add accepts valid package and closes sheet`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(BlockedAppsAction.OpenManual)
            viewModel.onAction(BlockedAppsAction.AddManual(" com.c.app "))
            assertThat(viewModel.uiState.value.manualOpen).isFalse()
            assertThat(packagesOf(editor.writes.single().second as JsonArray)).containsExactly("com.c.app")
        }

    @Test
    fun `search query refreshes installed apps`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(BlockedAppsAction.SetQuery("A 应用"))
            assertThat(launcher.lastQuery).isEqualTo("A 应用")
            assertThat(
                viewModel.uiState.value.apps
                    .map { it.packageName },
            ).containsExactly("com.a.app")
        }

    private fun createViewModel() =
        BlockedAppsViewModel(provider, editor, launcher, TestDispatcherProvider(testDispatcher), logger)
}

/** 记录查询词的 [AppLauncher] 假实现。 */
private class FakeAppLauncher(
    private val apps: List<AppInfo>,
) : AppLauncher {
    var lastQuery: String? = null

    override suspend fun listApps(query: String?): List<AppInfo> {
        lastQuery = query
        return if (query.isNullOrBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(query) || it.packageName.contains(query) }
        }
    }

    override suspend fun launch(packageName: String): Boolean = true

    override suspend fun openUri(uri: String): Boolean = true
}

private fun blockedOverrides(vararg packages: String): JsonObject =
    buildJsonObject {
        put(
            "policy",
            buildJsonObject { put("blockedPackages", JsonArray(packages.map(::JsonPrimitive))) },
        )
    }

private fun packagesOf(array: JsonArray): List<String> = array.map { (it as JsonPrimitive).content }
