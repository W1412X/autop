package com.example.autop.core.config.impl

import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.config.CURRENT_SCHEMA_VERSION
import com.example.autop.core.config.putSchemaVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import java.io.File

/** 空 Logger：测试只关心行为，不关心日志输出。 */
internal object NoopLogger : Logger {
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

/** 全部用 UnconfinedTestDispatcher，让 stateIn 的 Eagerly 收集在测试线程立即完成。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TestDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = UnconfinedTestDispatcher()
    override val io: CoroutineDispatcher = UnconfinedTestDispatcher()
    override val default: CoroutineDispatcher = UnconfinedTestDispatcher()
}

/** 测试用内存覆盖存储。 */
internal class FakeOverridesStore(
    doc: JsonObject = putSchemaVersion(JsonObject(emptyMap()), CURRENT_SCHEMA_VERSION),
) : OverridesStore {
    private val state = MutableStateFlow(doc)

    override val data: Flow<JsonObject> = state

    override suspend fun update(transform: (JsonObject) -> JsonObject) {
        state.value = transform(state.value)
    }
}

/** 读取真实 assets/config（autop.configDir 系统属性指向该目录，docs/tech/02 §5）。 */
internal fun loadRealAssetFiles(): List<ConfigAssetFile> {
    val dir = File(checkNotNull(System.getProperty("autop.configDir")))
    return checkNotNull(dir.listFiles()).sortedBy { it.name }.map { ConfigAssetFile(it.name, it.readText()) }
}
