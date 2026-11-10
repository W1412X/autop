/**
 * [ModelsViewModel] 单测（docs/standards/coding-standards §8：ViewModel 覆盖
 * 动作→存储/密钥写入、草稿校验、测试连接能力回填、角色分配与删除）。
 */
package com.example.autop.feature.settings.me.models

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakeModelProfileStore
import com.example.autop.core.testing.FakeSecretStore
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.feature.settings.me.FakeIdGenerator
import com.example.autop.feature.settings.me.RecordingLogger
import com.example.autop.feature.settings.me.RecordingModelDirectory
import com.example.autop.feature.settings.me.RecordingModelProbe
import com.example.autop.feature.settings.me.okProbeReport
import com.example.autop.feature.settings.me.testProfile
import com.example.autop.llm.ModelCapabilities
import com.example.autop.llm.ModelRole
import com.example.autop.llm.ModelRoles
import com.example.autop.llm.ProbeResult
import com.example.autop.llm.ProbeVerdict
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = FakeModelProfileStore()
    private val secrets = FakeSecretStore()
    private val probe = RecordingModelProbe()
    private val directory = RecordingModelDirectory()
    private val editor = ModelProfilesEditor(store, secrets, FakeIdGenerator())
    private val logger = RecordingLogger()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `new draft gets generated id and openai default`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            val draft = checkNotNull(viewModel.uiState.value.draft)
            assertThat(draft.isNew).isTrue()
            assertThat(draft.id).isEqualTo("id-1")
            assertThat(draft.protocol).isEqualTo("openai")
            assertThat(draft.apiKeyTail).isNull()
        }

    @Test
    fun `edit loads profile fields and saved key tail`() =
        runTest(dispatcher) {
            store.upsert(testProfile("p1", displayName = "主力"))
            secrets.put("secret:model:p1", "sk-abcdef1234")
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.Edit("p1"))
            val draft = checkNotNull(viewModel.uiState.value.draft)
            assertThat(draft.displayName).isEqualTo("主力")
            assertThat(draft.contextWindow).isEqualTo("128000")
            assertThat(draft.apiKeyInput).isEmpty()
            assertThat(draft.apiKeyTail).isEqualTo("1234")
        }

    @Test
    fun `field edits update draft`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            viewModel.onAction(ModelsAction.SetField(DraftField.NAME, "我的模型"))
            viewModel.onAction(ModelsAction.SetField(DraftField.CONTEXT_WINDOW, "64000"))
            viewModel.onAction(ModelsAction.SetQuirk("chatPath", "/v2/chat"))
            val draft = checkNotNull(viewModel.uiState.value.draft)
            assertThat(draft.displayName).isEqualTo("我的模型")
            assertThat(draft.contextWindow).isEqualTo("64000")
            assertThat(draft.quirks).containsExactly("chatPath", "/v2/chat")
        }

    @Test
    fun `save with missing fields shows error and keeps draft`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            viewModel.onAction(ModelsAction.Save)
            assertThat(viewModel.uiState.value.error).isEqualTo(ModelsErrorKind.MISSING_FIELDS)
            assertThat(viewModel.uiState.value.draft).isNotNull()
            assertThat(store.profiles.first()).isEmpty()
        }

    @Test
    fun `save with non numeric limits shows error`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            fillValidDraft(viewModel)
            viewModel.onAction(ModelsAction.SetField(DraftField.CONTEXT_WINDOW, "abc"))
            viewModel.onAction(ModelsAction.Save)
            assertThat(viewModel.uiState.value.error).isEqualTo(ModelsErrorKind.INVALID_NUMBERS)
        }

    @Test
    fun `save persists profile and api key then auto probes`() =
        runTest(dispatcher) {
            probe.report = okProbeReport()
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            fillValidDraft(viewModel)
            viewModel.onAction(ModelsAction.SetField(DraftField.API_KEY, "sk-new"))
            viewModel.onAction(ModelsAction.Save)
            val state = viewModel.uiState.value
            assertThat(state.draft).isNotNull()
            assertThat(state.probe.visible).isTrue()
            assertThat(state.probe.running).isFalse()
            assertThat(state.probe.report).isEqualTo(okProbeReport())
            val saved = store.profiles.first().single()
            assertThat(saved.id).isEqualTo("id-1")
            assertThat(saved.displayName).isEqualTo("测试模型")
            assertThat(saved.model).isEqualTo("gpt-4o-mini")
            assertThat(saved.apiKeyRef).isEqualTo("secret:model:id-1")
            assertThat(secrets.get("secret:model:id-1")).isEqualTo("sk-new")
            assertThat(saved.capabilities.tools).isTrue()
            assertThat(saved.capabilities.vision).isTrue()
            viewModel.onAction(ModelsAction.CloseProbe)
            assertThat(viewModel.uiState.value.draft).isNull()
            assertThat(viewModel.uiState.value.probe.visible).isFalse()
        }

    @Test
    fun `save without key keeps previously saved key`() =
        runTest(dispatcher) {
            store.upsert(testProfile("p1"))
            secrets.put("secret:model:p1", "sk-old")
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.Edit("p1"))
            viewModel.onAction(ModelsAction.SetField(DraftField.NAME, "改名"))
            viewModel.onAction(ModelsAction.Save)
            assertThat(secrets.get("secret:model:p1")).isEqualTo("sk-old")
            assertThat(
                store.profiles
                    .first()
                    .single()
                    .displayName,
            ).isEqualTo("改名")
        }

    @Test
    fun `save probes saved profile and fills draft capabilities`() =
        runTest(dispatcher) {
            probe.report = okProbeReport()
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            fillValidDraft(viewModel)
            viewModel.onAction(ModelsAction.Save)
            val draft = checkNotNull(viewModel.uiState.value.draft)
            assertThat(draft.capabilities.tools).isTrue()
            assertThat(draft.capabilities.vision).isTrue()
            assertThat(probe.received.single().apiKeyRef).isEqualTo("secret:model:id-1")
        }

    @Test
    fun `save auto probe persists capabilities for existing profile`() =
        runTest(dispatcher) {
            store.upsert(testProfile("p1"))
            probe.report = okProbeReport()
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.Edit("p1"))
            viewModel.onAction(ModelsAction.Save)
            val saved = store.profiles.first().single()
            assertThat(saved.capabilities.tools).isTrue()
            assertThat(saved.capabilities.vision).isTrue()
        }

    @Test
    fun `inconclusive tools probe keeps existing tools capability`() =
        runTest(dispatcher) {
            store.upsert(testProfile("p1").copy(capabilities = ModelCapabilities(tools = true, vision = false)))
            probe.report =
                okProbeReport().copy(tools = ProbeResult(verdict = ProbeVerdict.INCONCLUSIVE, elapsedMs = 900))
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.Edit("p1"))
            viewModel.onAction(ModelsAction.Save)
            val saved = store.profiles.first().single()
            assertThat(saved.capabilities.tools).isTrue()
            assertThat(saved.capabilities.vision).isTrue()
        }

    @Test
    fun `unsupported tools probe turns tools off`() =
        runTest(dispatcher) {
            store.upsert(testProfile("p1"))
            probe.report =
                okProbeReport().copy(tools = ProbeResult(verdict = ProbeVerdict.UNSUPPORTED, elapsedMs = 900))
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.Edit("p1"))
            viewModel.onAction(ModelsAction.Save)
            val saved = store.profiles.first().single()
            assertThat(saved.capabilities.tools).isFalse()
        }

    @Test
    fun `probe request failure keeps saved config and shows dialog error`() =
        runTest(dispatcher) {
            probe.error = IllegalStateException("连接超时")
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            fillValidDraft(viewModel)
            viewModel.onAction(ModelsAction.Save)
            val state = viewModel.uiState.value
            assertThat(state.probe.visible).isTrue()
            assertThat(state.probe.error?.message).isEqualTo("连接超时")
            assertThat(store.profiles.first()).hasSize(1)
        }

    @Test
    fun `retest reruns probe for saved profile`() =
        runTest(dispatcher) {
            probe.report = okProbeReport()
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            fillValidDraft(viewModel)
            viewModel.onAction(ModelsAction.Save)
            viewModel.onAction(ModelsAction.RetestProbe)
            assertThat(probe.received).hasSize(2)
            assertThat(viewModel.uiState.value.probe.visible).isTrue()
            assertThat(viewModel.uiState.value.probe.report).isEqualTo(okProbeReport())
        }

    @Test
    fun `role assignment writes roles`() =
        runTest(dispatcher) {
            store.upsert(testProfile("p1"))
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.AssignRole(ModelRole.MAIN, "p1"))
            viewModel.onAction(ModelsAction.AssignRole(ModelRole.LIGHT, null))
            val roles = store.roles.first()
            assertThat(roles).isEqualTo(ModelRoles(main = "p1", vision = null, light = null))
        }

    @Test
    fun `delete removes profile and key after confirm`() =
        runTest(dispatcher) {
            store.upsert(testProfile("p1"))
            secrets.put("secret:model:p1", "sk-gone")
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.Edit("p1"))
            viewModel.onAction(ModelsAction.RequestDelete)
            assertThat(viewModel.uiState.value.confirmDelete).isTrue()
            viewModel.onAction(ModelsAction.ConfirmDelete)
            assertThat(store.profiles.first()).isEmpty()
            assertThat(secrets.get("secret:model:p1")).isNull()
            assertThat(viewModel.uiState.value.draft).isNull()
        }

    @Test
    fun `fetch remote models saves key and fills list`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            viewModel.onAction(ModelsAction.SetField(DraftField.BASE_URL, "https://api.example.com/v1"))
            viewModel.onAction(ModelsAction.SetField(DraftField.API_KEY, "sk-fetch"))
            viewModel.onAction(ModelsAction.FetchRemoteModels)
            val state = viewModel.uiState.value
            assertThat(state.remoteModels.models).containsExactly("gpt-4o-mini", "gpt-4o").inOrder()
            assertThat(secrets.get("secret:model:id-1")).isEqualTo("sk-fetch")
            assertThat(directory.received.single().apiKeyRef).isEqualTo("secret:model:id-1")
        }

    @Test
    fun `fetch remote models without base url shows missing fields`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            viewModel.onAction(ModelsAction.FetchRemoteModels)
            assertThat(viewModel.uiState.value.error).isEqualTo(ModelsErrorKind.MISSING_FIELDS)
            assertThat(directory.received).isEmpty()
        }

    @Test
    fun `fetch remote models failure shows list error`() =
        runTest(dispatcher) {
            directory.result = Outcome.Err(AppError(code = "llm.auth", message = "bad key"))
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            viewModel.onAction(ModelsAction.SetField(DraftField.BASE_URL, "https://api.example.com/v1"))
            viewModel.onAction(ModelsAction.FetchRemoteModels)
            val state = viewModel.uiState.value
            assertThat(state.error).isEqualTo(ModelsErrorKind.LIST_MODELS_FAILED)
            assertThat(state.remoteModels.models).isNull()
        }

    @Test
    fun `fetch remote models empty list shows notice`() =
        runTest(dispatcher) {
            directory.result = Outcome.Ok(emptyList())
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            viewModel.onAction(ModelsAction.SetField(DraftField.BASE_URL, "https://api.example.com/v1"))
            viewModel.onAction(ModelsAction.FetchRemoteModels)
            val state = viewModel.uiState.value
            assertThat(state.notice).isEqualTo(ModelsNoticeKind.MODELS_EMPTY)
            assertThat(state.remoteModels.models).isEmpty()
        }

    @Test
    fun `fetch remote models dedups when list already loaded`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            viewModel.onAction(ModelsAction.SetField(DraftField.BASE_URL, "https://api.example.com/v1"))
            viewModel.onAction(ModelsAction.FetchRemoteModels)
            viewModel.onAction(ModelsAction.FetchRemoteModels)
            assertThat(directory.received).hasSize(1)
        }

    @Test
    fun `base url edit resets fetched models for refetch`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ModelsAction.New)
            viewModel.onAction(ModelsAction.SetField(DraftField.BASE_URL, "https://api.example.com/v1"))
            viewModel.onAction(ModelsAction.FetchRemoteModels)
            viewModel.onAction(ModelsAction.SetField(DraftField.BASE_URL, "https://api2.example.com/v1"))
            assertThat(viewModel.uiState.value.remoteModels.models).isNull()
            viewModel.onAction(ModelsAction.FetchRemoteModels)
            assertThat(directory.received).hasSize(2)
        }

    private fun fillValidDraft(viewModel: ModelsViewModel) {
        viewModel.onAction(ModelsAction.SetField(DraftField.NAME, "测试模型"))
        viewModel.onAction(ModelsAction.SetField(DraftField.BASE_URL, "https://api.example.com/v1"))
        viewModel.onAction(ModelsAction.SetField(DraftField.MODEL, "gpt-4o-mini"))
        viewModel.onAction(ModelsAction.SetField(DraftField.CONTEXT_WINDOW, "128000"))
        viewModel.onAction(ModelsAction.SetField(DraftField.MAX_OUTPUT_TOKENS, "4096"))
    }

    private fun createViewModel(): ModelsViewModel =
        ModelsViewModel(editor, probe, directory, FakeConfigProvider(), TestDispatcherProvider(dispatcher), logger)
}
