package com.example.autop.core.config.impl

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.CURRENT_SCHEMA_VERSION
import com.example.autop.core.config.ConfigMigration
import com.example.autop.core.config.migrateConfig
import com.example.autop.core.config.putSchemaVersion
import com.example.autop.core.platform.guardedReplaceHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.io.OutputStream

/**
 * 用户覆盖（`config_overrides.json`）的存储抽象；DataStore 实现见 `di/ConfigModule`，测试用内存实现。
 *
 * public 是 DI 装配签名（`ConfigModule`）暴露所需，业务代码不应直接依赖。
 */
public interface OverridesStore {
    /** 当前覆盖文档（含 `schemaVersion`）。 */
    val data: Flow<JsonObject>

    /** 原子更新覆盖文档。 */
    suspend fun update(transform: (JsonObject) -> JsonObject)
}

/**
 * 文件型 DataStore 的序列化器（docs/tech/02 §2.3）。
 *
 * 内容为一个 JsonObject + `schemaVersion`；读取时按 [migrations] 升级，
 * 写入时统一写 [CURRENT_SCHEMA_VERSION]。
 */
internal class ConfigOverridesSerializer(
    private val migrations: List<ConfigMigration> = emptyList(),
) : Serializer<JsonObject> {
    override val defaultValue: JsonObject = putSchemaVersion(JsonObject(emptyMap()), CURRENT_SCHEMA_VERSION)

    override suspend fun readFrom(input: InputStream): JsonObject {
        val doc = parseOverrides(input.readBytes().decodeToString())
        return when (val migrated = migrateConfig(doc, migrations)) {
            is Outcome.Ok -> migrated.value
            // 迁移失败视为文件损坏：交给 CorruptionHandler 回退空覆盖（N11）
            is Outcome.Err -> throw CorruptionException(migrated.error.message)
        }
    }

    override suspend fun writeTo(
        t: JsonObject,
        output: OutputStream,
    ) {
        val doc = putSchemaVersion(t, CURRENT_SCHEMA_VERSION)
        output.write(AutopJson.pretty.encodeToString(JsonObject.serializer(), doc).encodeToByteArray())
    }

    private fun parseOverrides(text: String): JsonObject =
        try {
            AutopJson.default.parseToJsonElement(text) as? JsonObject ?: defaultValue
        } catch (e: SerializationException) {
            throw CorruptionException("config_overrides.json is not valid JSON: ${e.message}", e)
        }
}

/** [OverridesStore] 的文件型 DataStore 实现。 */
internal class DataStoreOverridesStore(
    private val dataStore: DataStore<JsonObject>,
) : OverridesStore {
    override val data: Flow<JsonObject> = dataStore.data

    override suspend fun update(transform: (JsonObject) -> JsonObject) {
        dataStore.updateData(transform)
    }
}

/** 迁移失败/文件损坏时的回退：重置为空覆盖文档。 */
internal fun overridesCorruptionHandler(): ReplaceFileCorruptionHandler<JsonObject> =
    guardedReplaceHandler { ConfigOverridesSerializer().defaultValue }

/** 剥掉覆盖文档顶层的 `schemaVersion`，得到纯覆盖树。 */
internal fun stripSchemaVersion(doc: JsonObject): JsonObject = JsonObject(doc.filterKeys { it != "schemaVersion" })
