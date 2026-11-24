package com.example.autop.device.impl.bgdisplay

import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.config.DeviceBackgroundSection
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.impl.shell.ShellHostClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class BackgroundDisplayManagerTest {
    @Test
    fun `create request failure keeps shared host alive`() =
        runTest {
            val client = ShellHostClient(StandardTestDispatcher(testScheduler), 1_000L, NoopLogger)
            val owner = RunId("background-run")

            val result =
                openBackgroundDisplaySession(
                    owner = owner,
                    config = DeviceBackgroundSection(launchTimeoutMs = 100L),
                    client = client,
                    logger = NoopLogger,
                )

            assertThat(result).isInstanceOf(Outcome.Err::class.java)
            assertThat((result as Outcome.Err).error.code).isEqualTo(DeviceErrorCodes.BG_CHANNEL_UNAVAILABLE)
            assertThat(client.isAlive).isTrue()
        }

    @Test
    fun `cancellation during create request keeps shared host alive`() =
        runTest {
            val client = ShellHostClient(StandardTestDispatcher(testScheduler), 1_000L, NoopLogger)
            val job =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    openBackgroundDisplaySession(
                        owner = RunId("cancelled-background-run"),
                        config = DeviceBackgroundSection(launchTimeoutMs = 100L),
                        client = client,
                        logger = NoopLogger,
                    )
                }

            job.cancelAndJoin()

            assertThat(client.isAlive).isTrue()
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
}
