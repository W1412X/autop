package com.example.autop.core.prompt.impl

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.example.autop.core.common.AutopJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream

/**
 * 用户覆盖正文的存储抽象（`prompt_overrides.json`）；DataStore 实现见 `di/PromptModule`。
 *
 * public 是 DI 装配签名暴露所需（同 core-config-impl 的 N12），业务代码不应直接依赖。
 */
public interface PromptOverridesStore {
    /** 当前覆盖（id → 正文）。 */
    val data: Flow<Map<String, String>>

    /** 同步读取当前覆盖（内存缓存，DataStore 首值到达前为空）。 */
    public fun current(): Map<String, String>

    /** 写入/更新一个覆盖正文。 */
    public suspend fun set(
        id: String,
        body: String,
    )

    /** 移除一个覆盖。 */
    public suspend fun remove(id: String)
}

/** 持久化文档（docs/tech/02 §2.3 同款 schemaVersion 约定；N11 语义：损坏回退空覆盖）。 */
@Serializable
internal data class PromptOverridesDoc(
    @SerialName("schemaVersion") val schemaVersion: Int = CURRENT_PROMPT_SCHEMA_VERSION,
    @SerialName("overrides") val overrides: Map<String, String> = emptyMap(),
)

/** 文件型 DataStore 的序列化器：写入统一写当前 schemaVersion。 */
internal class PromptOverridesSerializer : Serializer<PromptOverridesDoc> {
    override val defaultValue: PromptOverridesDoc = PromptOverridesDoc()

    override suspend fun readFrom(input: InputStream): PromptOverridesDoc =
        try {
            AutopJson.default.decodeFromString(PromptOverridesDoc.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("prompt_overrides.json is not valid JSON: ${e.message}", e)
        }

    override suspend fun writeTo(
        t: PromptOverridesDoc,
        output: OutputStream,
    ) {
        output.write(AutopJson.pretty.encodeToString(PromptOverridesDoc.serializer(), t).encodeToByteArray())
    }
}

/** [PromptOverridesStore] 的文件型 DataStore 实现（损坏时回退空覆盖，N11）。 */
internal class DataStorePromptOverridesStore(
    private val dataStore: DataStore<PromptOverridesDoc>,
) : PromptOverridesStore {
    override val data: Flow<Map<String, String>> = dataStore.data.map { it.overrides }

    override fun current(): Map<String, String> = cached

    override suspend fun set(
        id: String,
        body: String,
    ) {
        val doc = dataStore.updateData { it.copy(overrides = it.overrides + (id to body)) }
        cached = doc.overrides
    }

    override suspend fun remove(id: String) {
        val doc = dataStore.updateData { it.copy(overrides = it.overrides - id) }
        cached = doc.overrides
    }

    @Volatile
    private var cached: Map<String, String> = emptyMap()
}

/** 迁移失败/文件损坏时的回退：重置为空覆盖文档（同 core-config-impl 的 N11）。 */
internal fun promptOverridesCorruptionHandler(): ReplaceFileCorruptionHandler<PromptOverridesDoc> =
    ReplaceFileCorruptionHandler { PromptOverridesDoc() }

internal const val CURRENT_PROMPT_SCHEMA_VERSION = 1
