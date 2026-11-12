/**
 * [ExperienceExtractorImpl] 测试（docs/design/agent-design.md §8）：开关
 * `features.experienceExtraction` 关闭不提炼；输出 `{"append":"…"}` 时写入
 * `/apps/{package}/_manual`（标题用应用名、版本可按 Run 撤销）；`{"none":true}`
 * 与非法输出不写。
 */
package com.example.autop.memory.impl

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.core.common.RunId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.memory.ExperienceRequest
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.testing.FakeEmbeddingGateway
import com.example.autop.memory.impl.testing.FakeEmbeddingProvider
import com.example.autop.memory.impl.testing.FakeLlmGateway
import com.example.autop.memory.impl.testing.NoopLogger
import com.example.autop.memory.impl.testing.TestSeed
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 经验提炼行为测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class ExperienceExtractorImplTest {
    private lateinit var db: MemoryDb
    private lateinit var store: MemoryStoreImpl

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java).build()
        val config = FakeConfigProvider()
        val dispatchers = TestDispatcherProvider(Dispatchers.Default)
        val indexer =
            EmbeddingIndexer(
                db,
                FakeEmbeddingGateway(FakeEmbeddingProvider()),
                config,
                VectorCache(),
                dispatchers,
                NoopLogger,
            )
        val seeder = MemorySeeder(db, TestSeed, FakeClock(start = 1_000))
        store =
            MemoryStoreImpl(
                db,
                FakePromptRepository(),
                config,
                indexer,
                seeder,
                dispatchers,
                FakeClock(start = 1_000),
                NoopLogger,
            )
    }

    @After
    public fun tearDown() {
        db.close()
    }

    @Test
    public fun `开关关闭时不提炼`() =
        runBlocking {
            val extractor = experienceExtractor(store, """{"append":"入口在设置页"}""", FakeConfigProvider())

            val written = extractor.extract(experienceRequest())

            assertThat(written).isFalse()
            assertThat(store.get(experienceManualPath())).isNull()
        }

    @Test
    public fun `模型输出 append 时写入手册`() =
        runBlocking {
            val extractor =
                experienceExtractor(store, """{"append":"入口在设置页"}""", FakeConfigProvider(experienceEnabled()))

            val written = extractor.extract(experienceRequest())

            assertThat(written).isTrue()
            val manual = store.get(experienceManualPath())
            assertThat(manual).isNotNull()
            assertThat(manual?.content).contains("入口在设置页")
            assertThat(manual?.title).isEqualTo("示例应用")
            val versions = store.versions(experienceManualPath(), limit = 10)
            assertThat(versions.single().runId).isEqualTo(RunId("run-1"))
        }

    @Test
    public fun `模型输出 none 时不写`() =
        runBlocking {
            val extractor = experienceExtractor(store, """{"none":true}""", FakeConfigProvider(experienceEnabled()))

            val written = extractor.extract(experienceRequest())

            assertThat(written).isFalse()
            assertThat(store.get(experienceManualPath())).isNull()
        }

    @Test
    public fun `模型输出非法 JSON 时不写`() =
        runBlocking {
            val extractor = experienceExtractor(store, "这次做得不错。", FakeConfigProvider(experienceEnabled()))

            val written = extractor.extract(experienceRequest())

            assertThat(written).isFalse()
            assertThat(store.get(experienceManualPath())).isNull()
        }
}

/** 提炼器装配：真实 MemoryStore + 固定回复的 LLM。 */
private fun experienceExtractor(
    store: MemoryStoreImpl,
    llmText: String,
    config: FakeConfigProvider,
): ExperienceExtractorImpl =
    ExperienceExtractorImpl(
        store = store,
        gateway = FakeLlmGateway(llmText),
        prompts = FakePromptRepository(),
        appLauncher = ExperienceAppLauncher(),
        configProvider = config,
        logger = NoopLogger,
    )

/** `features.experienceExtraction=true` 的配置覆盖。 */
private fun experienceEnabled() =
    buildJsonObject {
        put(
            "features",
            buildJsonObject {
                put("experienceExtraction", true)
            },
        )
    }

/** 提炼请求（步骤摘要两行）。 */
private fun experienceRequest(): ExperienceRequest =
    ExperienceRequest(
        runId = RunId("run-1"),
        packageName = "com.example.app",
        steps = "1. tool.tap [ok] 已点击\n2. tool.input [ok] 已输入",
    )

/** 手册路径 `/apps/com.example.app/_manual`。 */
private fun experienceManualPath(): MemoryPath =
    MemoryPath.ROOT
        .child("apps")
        .child("com.example.app")
        .child("_manual")

/** 应用查询替身（返回固定应用名）。 */
private class ExperienceAppLauncher : AppLauncher {
    override suspend fun listApps(query: String?): List<AppInfo> =
        listOf(AppInfo(packageName = "com.example.app", label = "示例应用"))

    override suspend fun launch(packageName: String): Boolean = error("not used")
}
