/**
 * [EmbeddingViewModel] 单测（docs/standards/coding-standards §8：ViewModel 覆盖
 * 动作→`llm.embedding` 整段写入、字段重置语义、写入失败日志、索引重建触发）。
 */
package com.example.autop.feature.settings.me.embedding

import com.example.autop.agent.ThreadIndexMaintenance
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.LlmConfig
import com.example.autop.core.config.LlmEmbeddingSection
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakeModelProfileStore
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.feature.settings.me.RecordingConfigEditor
import com.example.autop.feature.settings.me.RecordingLogger
import com.example.autop.feature.settings.me.testProfile
import com.example.autop.memory.MemoryIndexMaintenance
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddingViewModelTest {
    private val provider = FakeConfigProvider()
    private val editor = RecordingConfigEditor()
    private val store = FakeModelProfileStore()
    private val memoryIndex = FakeIndexMaintenance()
    private val threadIndex = FakeIndexMaintenance()
    private val logger = RecordingLogger()
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
    fun `defaults come from llm embedding section and store profiles`() =
        runTest(testDispatcher) {
            store.upsert(testProfile("p1", displayName = "向量源"))
            val viewModel = createViewModel()
            val state = viewModel.uiState.value
            assertThat(state.section.provider).isEqualTo("none")
            assertThat(state.profiles.single().displayName).isEqualTo("向量源")
            assertThat(state.reindex).isEqualTo(ReindexUiState())
        }

    @Test
    fun `set provider none clears model fields and writes whole section`() =
        runTest(testDispatcher) {
            provider.setOverrides(embeddingOverrides(provider = "openai", profileId = "p1", model = "m", dimension = 8))
            val viewModel = createViewModel()
            viewModel.onAction(EmbeddingAction.SetProvider("none"))
            val (path, written) = editor.writes.single()
            assertThat(path).isEqualTo(LlmConfig.EMBEDDING.path)
            assertThat(decode(written))
                .isEqualTo(LlmEmbeddingSection(provider = "none", profileId = null, model = null, dimension = null))
        }

    @Test
    fun `set provider keeps model fields and resets dimension`() =
        runTest(testDispatcher) {
            provider.setOverrides(embeddingOverrides(provider = "none", profileId = "p1", model = "m", dimension = 8))
            val viewModel = createViewModel()
            viewModel.onAction(EmbeddingAction.SetProvider("openai"))
            val written = decode(editor.writes.single().second)
            assertThat(written.provider).isEqualTo("openai")
            assertThat(written.profileId).isEqualTo("p1")
            assertThat(written.model).isEqualTo("m")
            assertThat(written.dimension).isNull()
        }

    @Test
    fun `set profile resets dimension and keeps other fields`() =
        runTest(testDispatcher) {
            provider.setOverrides(embeddingOverrides(provider = "openai", profileId = "p1", model = "m", dimension = 8))
            val viewModel = createViewModel()
            viewModel.onAction(EmbeddingAction.SetProfile("p2"))
            val written = decode(editor.writes.single().second)
            assertThat(written.profileId).isEqualTo("p2")
            assertThat(written.model).isEqualTo("m")
            assertThat(written.dimension).isNull()
        }

    @Test
    fun `set model resets dimension and blank becomes null`() =
        runTest(testDispatcher) {
            provider.setOverrides(embeddingOverrides(provider = "openai", profileId = "p1", model = "m", dimension = 8))
            val viewModel = createViewModel()
            viewModel.onAction(EmbeddingAction.SetModel("m2"))
            val updated = decode(editor.writes.single().second)
            assertThat(updated.model).isEqualTo("m2")
            assertThat(updated.dimension).isNull()
            editor.writes.clear()
            viewModel.onAction(EmbeddingAction.SetModel(""))
            val cleared = decode(editor.writes.single().second)
            assertThat(cleared.model).isNull()
        }

    @Test
    fun `actions accumulate through config feedback`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(EmbeddingAction.SetProvider("openai"))
            publishLastWrite()
            viewModel.onAction(EmbeddingAction.SetProfile("p1"))
            publishLastWrite()
            viewModel.onAction(EmbeddingAction.SetModel("m3"))
            val written = decode(editor.writes.last().second)
            assertThat(written.provider).isEqualTo("openai")
            assertThat(written.profileId).isEqualTo("p1")
            assertThat(written.model).isEqualTo("m3")
            assertThat(written.batchSize).isEqualTo(16)
        }

    @Test
    fun `write failure is logged with path and code`() =
        runTest(testDispatcher) {
            editor.result = Outcome.Err(AppError(code = "config.invalid", message = "boom"))
            val viewModel = createViewModel()
            viewModel.onAction(EmbeddingAction.SetModel("m"))
            val (event, kv) = logger.errors.single()
            assertThat(event).isEqualTo("embedding_write_failed")
            assertThat(kv["path"]).isEqualTo(LlmConfig.EMBEDDING.path)
            assertThat(kv["code"]).isEqualTo("config.invalid")
        }

    @Test
    fun `配置写入成功后自动重建两路索引`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(EmbeddingAction.SetProvider("openai"))
            assertThat(memoryIndex.calls).isEqualTo(1)
            assertThat(threadIndex.calls).isEqualTo(1)
        }

    @Test
    fun `写入失败不触发重建`() =
        runTest(testDispatcher) {
            editor.result = Outcome.Err(AppError(code = "config.invalid", message = "boom"))
            val viewModel = createViewModel()
            viewModel.onAction(EmbeddingAction.SetProvider("openai"))
            assertThat(memoryIndex.calls).isEqualTo(0)
            assertThat(threadIndex.calls).isEqualTo(0)
        }

    @Test
    fun `手动重建记忆索引只触发记忆路`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(EmbeddingAction.ReindexMemory)
            assertThat(memoryIndex.calls).isEqualTo(1)
            assertThat(threadIndex.calls).isEqualTo(0)
        }

    @Test
    fun `为历史对话重建向量索引只触发对话路`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(EmbeddingAction.ReindexThreads)
            assertThat(threadIndex.calls).isEqualTo(1)
            assertThat(memoryIndex.calls).isEqualTo(0)
        }

    /** 把最后一次写入回灌进 [FakeConfigProvider]（模拟真实配置覆盖生效）。 */
    private fun publishLastWrite() {
        val (_, element) = editor.writes.last()
        provider.setOverrides(buildJsonObject { put("llm", buildJsonObject { put("embedding", element) }) })
    }

    private fun decode(element: JsonElement): LlmEmbeddingSection =
        AutopJson.default.decodeFromJsonElement(LlmEmbeddingSection.serializer(), element)

    /** 用一段 [LlmEmbeddingSection] 构造用户覆盖树（与真实覆盖格式一致）。 */
    private fun embeddingOverrides(
        provider: String,
        profileId: String?,
        model: String?,
        dimension: Int?,
    ): JsonObject =
        embeddingOverrides(
            LlmEmbeddingSection(provider = provider, profileId = profileId, model = model, dimension = dimension),
        )

    private fun embeddingOverrides(section: LlmEmbeddingSection): JsonObject =
        buildJsonObject {
            put(
                "llm",
                buildJsonObject {
                    put("embedding", AutopJson.default.encodeToJsonElement(LlmEmbeddingSection.serializer(), section))
                },
            )
        }

    private fun createViewModel(): EmbeddingViewModel =
        EmbeddingViewModel(
            provider,
            editor,
            store,
            memoryIndex,
            threadIndex,
            TestDispatcherProvider(testDispatcher),
            logger,
        )
}

/** 索引维护替身：记录触发次数并回报一段进度（两接口同签名，一并实现）。 */
private class FakeIndexMaintenance :
    MemoryIndexMaintenance,
    ThreadIndexMaintenance {
    var calls: Int = 0

    override suspend fun rebuildAll(onProgress: (done: Int, total: Int) -> Unit) {
        calls += 1
        onProgress(0, 1)
        onProgress(1, 1)
    }
}
