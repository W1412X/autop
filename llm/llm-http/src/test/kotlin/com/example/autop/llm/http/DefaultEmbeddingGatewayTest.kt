package com.example.autop.llm.http

import app.cash.turbine.test
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** [DefaultEmbeddingGateway] 单测：`llm.embedding` 选择、未配置为 null、变化发新实例（docs/tech/03 §7）。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultEmbeddingGatewayTest {
    @Test
    fun currentIsNullForDefaultNoneProvider() {
        runTest {
            val gateway = gateway(FakeConfigProvider(), FakeEmbeddingFactory())
            assertThat(gateway.current()).isNull()
        }
    }

    @Test
    fun currentCreatesProviderFromConfig() {
        runTest {
            val config = FakeConfigProvider(embeddingOverrides("fake"))
            val factory = FakeEmbeddingFactory("fake")
            val provider = gateway(config, factory).current()
            assertThat(provider).isNotNull()
            assertThat(provider?.modelId).isEqualTo("fake:m")
            assertThat(factory.configs.single().profileId).isEqualTo(PROFILE_ID)
        }
    }

    @Test
    fun unknownProviderYieldsNull() {
        runTest {
            val config = FakeConfigProvider(embeddingOverrides("unknown"))
            assertThat(gateway(config, FakeEmbeddingFactory()).current()).isNull()
        }
    }

    @Test
    fun configChangeEmitsNewProvider() {
        runTest {
            val config = FakeConfigProvider()
            val gateway = gateway(config, FakeEmbeddingFactory("fake"))
            gateway.changes.test {
                assertThat(awaitItem()).isNull()
                config.setOverrides(embeddingOverrides("fake"))
                val next = awaitItem()
                assertThat(next).isNotNull()
                assertThat(next?.modelId).isEqualTo("fake:m")
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    private fun TestScope.gateway(
        config: FakeConfigProvider,
        factory: FakeEmbeddingFactory,
    ): DefaultEmbeddingGateway =
        DefaultEmbeddingGateway(
            factories = setOf(factory),
            configProvider = config,
            logger = NoopLogger,
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)),
        )

    private fun embeddingOverrides(provider: String) =
        Json
            .parseToJsonElement(
                """{"llm":{"embedding":{"provider":"$provider","profileId":"$PROFILE_ID","model":"m"}}}""",
            ).jsonObject

    private companion object {
        const val PROFILE_ID = "p1"
    }
}
