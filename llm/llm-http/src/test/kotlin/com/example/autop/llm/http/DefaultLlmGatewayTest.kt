package com.example.autop.llm.http

import com.example.autop.core.testing.FakeModelProfileStore
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmRequestLog
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelRole
import com.example.autop.llm.ModelRoles
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.complete
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [DefaultLlmGateway] 单测：角色映射、错误码、provider 缓存与配置失效（docs/tech/10 WP3）。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultLlmGatewayTest {
    @Test
    fun forRoleFailsWhenRoleUnassigned() {
        runTest {
            val gateway = gateway(FakeModelProfileStore())
            val outcome = gateway.forRole(ModelRole.MAIN)
            assertThat(outcome.unwrapError().code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
        }
    }

    @Test
    fun forRoleReturnsProviderForAssignedRole() {
        runTest {
            val store = FakeModelProfileStore()
            store.upsert(profile(PROFILE_A))
            store.setRoles(ModelRoles(main = PROFILE_A))
            val outcome = gateway(store, factory("openai")).forRole(ModelRole.MAIN)
            assertThat(outcome.unwrap().profile.id).isEqualTo(PROFILE_A)
        }
    }

    @Test
    fun forProfileFailsForUnknownId() {
        runTest {
            val outcome = gateway(FakeModelProfileStore()).forProfile("missing")
            assertThat(outcome.unwrapError().code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
        }
    }

    @Test
    fun forProfileFailsWhenProtocolUnsupported() {
        runTest {
            val store = FakeModelProfileStore()
            store.upsert(profile(PROFILE_A, protocol = "unknown"))
            val outcome = gateway(store, factory("openai")).forProfile(PROFILE_A)
            assertThat(outcome.unwrapError().code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
        }
    }

    @Test
    fun forProfileCachesProviderInstance() {
        runTest {
            val store = FakeModelProfileStore()
            store.upsert(profile(PROFILE_A))
            val gateway = gateway(store, factory("openai"))
            val first = gateway.forProfile(PROFILE_A).unwrap()
            val second = gateway.forProfile(PROFILE_A).unwrap()
            assertThat(second).isSameInstanceAs(first)
        }
    }

    @Test
    fun profileUpdateRecreatesProvider() {
        runTest {
            val store = FakeModelProfileStore()
            store.upsert(profile(PROFILE_A))
            val gateway = gateway(store, factory("openai"))
            val first = gateway.forProfile(PROFILE_A).unwrap()
            store.upsert(profile(PROFILE_A, model = "updated"))
            val second = gateway.forProfile(PROFILE_A).unwrap()
            assertThat(second).isNotSameInstanceAs(first)
            assertThat(second.profile.model).isEqualTo("updated")
        }
    }

    /** 采样请求经装饰 provider 记入 [LlmRequestLog]（docs/tech/08 §4.6）。 */
    @Test
    fun providerRecordsRequestIntoLog() {
        runTest {
            val store = FakeModelProfileStore()
            store.upsert(profile(PROFILE_A))
            val log = InMemoryLlmRequestLog()
            val provider = gateway(store, factory("openai"), log).forProfile(PROFILE_A).unwrap()
            val request =
                LlmRequest(
                    system = listOf(SystemPart("system text")),
                    messages = listOf(Msg(Role.USER, listOf(Block.Text("hi")))),
                    maxOutputTokens = 16,
                )

            val completed = provider.complete(request).unwrap()

            val last = log.last()
            checkNotNull(last)
            assertThat(last.modelId).isEqualTo(PROFILE_A)
            assertThat(
                last.request.system
                    .single()
                    .text,
            ).isEqualTo("system text")
            assertThat(last.usage).isEqualTo(completed.usage)
        }
    }

    private fun TestScope.gateway(
        store: FakeModelProfileStore,
        factory: FakeLlmProviderFactory = FakeLlmProviderFactory("openai"),
        log: LlmRequestLog = InMemoryLlmRequestLog(),
    ): DefaultLlmGateway =
        DefaultLlmGateway(
            factories = setOf(factory),
            store = store,
            requestLog = log,
            logger = NoopLogger,
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)),
        )

    private fun factory(protocolId: String): FakeLlmProviderFactory = FakeLlmProviderFactory(protocolId)

    private fun profile(
        id: String,
        protocol: String = "openai",
        model: String = "model-$id",
    ): ModelProfile =
        ModelProfile(
            id = id,
            displayName = "display-$id",
            protocol = protocol,
            baseUrl = "https://api.example.com",
            apiKeyRef = "secret:model:$id",
            model = model,
            contextWindow = 8192,
            maxOutputTokens = 256,
        )

    private companion object {
        const val PROFILE_A = "p-a"
    }
}
