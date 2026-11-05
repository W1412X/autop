package com.example.autop.device.impl

import com.example.autop.agent.ContextRequest
import com.example.autop.agent.Placement
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceState
import com.example.autop.device.DeviceStateProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 环境上下文（docs/tech/05 §7）：变量注入与片段元数据。 */
class EnvironmentContributorTest {
    private val clock = FakeClock(start = FIXED_2026)

    @Test
    fun `environment part carries variables and prompt version`() =
        runTest {
            val contributor = EnvironmentContributor(FakeStates(state()), clock, FakeApps(), FakePromptRepository())
            val part = contributor.contribute(request())
            assertThat(part).isNotNull()
            assertThat(contributor.id).isEqualTo("context.environment")
            assertThat(part?.placement).isEqualTo(Placement.FIRST_USER)
            assertThat(part?.text).contains("85")
            assertThat(part?.text).contains("com.example.app")
            assertThat(part?.promptIds).containsKey("agent.context.environment")
        }

    @Test
    fun `unknown battery renders fallback and locked flag is empty when unlocked`() =
        runTest {
            val state = state().copy(batteryPercent = null, keyguardLocked = false)
            val contributor = EnvironmentContributor(FakeStates(state), clock, FakeApps(), FakePromptRepository())
            val part = contributor.contribute(request())
            assertThat(part?.text).contains("未知")
            assertThat(part?.promptIds?.get("agent.context.environment")).isAtLeast(1)
        }

    private fun state(): DeviceState =
        DeviceState(
            batteryPercent = 85,
            charging = true,
            networkType = "wifi",
            screenOn = true,
            keyguardLocked = false,
            foregroundPackage = "com.example.app",
        )

    private fun request(): ContextRequest =
        ContextRequest(
            runId = RunId("run-1"),
            threadId = ThreadId("thread-1"),
            config = FakeConfigProvider().current(),
            goal = null,
            vars = emptyMap(),
            isFirstRunOfThread = true,
        )

    private class FakeStates(
        private val state: DeviceState,
    ) : DeviceStateProvider {
        override fun current(): DeviceState = state

        override val changes: Flow<DeviceState> = flowOf(state)
    }

    private class FakeApps : AppLauncher {
        override suspend fun listApps(query: String?): List<AppInfo> = listOf(AppInfo("com.example.app", "示例"))

        override suspend fun launch(packageName: String): Boolean = true

        override suspend fun openUri(uri: String): Boolean = true
    }

    private companion object {
        /** 2026-06-01T00:00Z 附近，稳定的时间断言基线。 */
        const val FIXED_2026 = 1_780_000_000_000L
    }
}
