package com.example.autop.device.impl.state

import com.example.autop.agent.StateRequest
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceState
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.DisplayTarget
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.Screenshot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

class EnvironmentStateSourceTest {
    @Test
    fun `background empty display reports unknown foreground and unlocked screen`() =
        runTest {
            val source = source(Outcome.Err(AppError("no_foreground", "empty display")))
            val display = DisplayTarget.Background(RunId("background-run"), 9, 720, 1600, 320)

            val entries = withContext(display) { source.entries(request()) }

            assertThat(entries.single { it.key == EnvironmentStateSource.KEY_FOREGROUND }.text)
                .isEqualTo("前台应用：未知")
            assertThat(entries.single { it.key == EnvironmentStateSource.KEY_SCREEN }.text)
                .isEqualTo("屏幕：已解锁")
        }

    @Test
    fun `background display uses its own foreground package`() =
        runTest {
            val snapshot = snapshot("com.background.app")
            val source = source(Outcome.Ok(snapshot))
            val display = DisplayTarget.Background(RunId("background-run"), 9, 720, 1600, 320)

            val entries = withContext(display) { source.entries(request()) }

            assertThat(entries.single { it.key == EnvironmentStateSource.KEY_FOREGROUND }.text)
                .isEqualTo("前台应用：com.background.app")
        }

    @Test
    fun `main display keeps physical foreground package`() =
        runTest {
            val entries = source(Outcome.Err(AppError("unused", "unused"))).entries(request())

            assertThat(entries.single { it.key == EnvironmentStateSource.KEY_FOREGROUND }.text)
                .isEqualTo("前台应用：com.example.autop")
        }

    private fun source(snapshot: Outcome<ScreenSnapshot>): EnvironmentStateSource =
        EnvironmentStateSource(
            deviceStates = PhysicalStates,
            clock = FakeClock(0L),
            apps = EmptyApps,
            screenReader = FakeReader(snapshot),
            prompts = FakePromptRepository(),
        )

    private fun request(): StateRequest =
        StateRequest(
            runId = RunId("run-1"),
            threadId = ThreadId("thread-1"),
            config = FakeConfigProvider().current(),
            vars = emptyMap(),
        )

    private fun snapshot(packageName: String): ScreenSnapshot =
        ScreenSnapshot(
            capturedAt = 0L,
            windows = emptyList(),
            nodes = emptyList(),
            foregroundPackage = packageName,
            screenWidth = 720,
            screenHeight = 1600,
            rotation = 0,
            keyguardLocked = false,
            truncated = false,
        )

    private object PhysicalStates : DeviceStateProvider {
        override fun current(): DeviceState =
            DeviceState(
                batteryPercent = 80,
                charging = false,
                networkType = "wifi",
                screenOn = false,
                keyguardLocked = true,
                foregroundPackage = "com.example.autop",
            )

        override val changes: Flow<DeviceState> = emptyFlow()
    }

    private object EmptyApps : AppLauncher {
        override suspend fun listApps(query: String?): List<AppInfo> = emptyList()

        override suspend fun launch(packageName: String): Boolean = false
    }

    private class FakeReader(
        private val result: Outcome<ScreenSnapshot>,
    ) : ScreenReader {
        override suspend fun snapshot(): Outcome<ScreenSnapshot> = result

        override suspend fun screenshot(): Outcome<Screenshot> = error("screenshot is not used")
    }
}
