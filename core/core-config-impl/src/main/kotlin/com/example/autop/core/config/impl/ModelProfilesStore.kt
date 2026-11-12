/**
 * `model_profiles.json` 存储（docs/tech/03 §3）：模型配置与默认模型放在独立 DataStore 文件，
 * 带 `schemaVersion`（数据格式硬性规则 4）。[ModelProfileStore] 端口在 llm-api，实现在本模块。
 */
package com.example.autop.core.config.impl

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.example.autop.core.common.AutopJson
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.io.OutputStream

/** 文档 schemaVersion（写入时统一写当前版本）。v2 起只有一个默认模型（ADR-0024）。 */
public const val MODEL_PROFILES_SCHEMA_VERSION: Int = 2

/**
 * `model_profiles.json` 文档结构。
 *
 * public 是 DI 装配签名（`di/ModelProfilesModule`）暴露所需，业务代码不应直接依赖
 * （同 `OverridesStore` 的处理，见 questions.md N12）。
 */
@Serializable
public data class ModelProfilesDoc(
    @SerialName("schemaVersion") val schemaVersion: Int = MODEL_PROFILES_SCHEMA_VERSION,
    @SerialName("profiles") val profiles: List<ModelProfile> = emptyList(),
    @SerialName("defaultProfileId") val defaultProfileId: String? = null,
)

/**
 * v1 文档（ADR-0024 之前）把默认模型放在 `roles.main` 里。读取时只取这一项迁移为
 * `defaultProfileId`，视觉与轻量角色被丢弃。
 */
private const val LEGACY_ROLES_KEY = "roles"
private const val LEGACY_MAIN_KEY = "main"

/** [ModelProfilesDoc] 的文件型 DataStore 序列化器。 */
public class ModelProfilesSerializer : Serializer<ModelProfilesDoc> {
    override val defaultValue: ModelProfilesDoc = ModelProfilesDoc()

    override suspend fun readFrom(input: InputStream): ModelProfilesDoc {
        val text = input.readBytes().decodeToString()
        val root =
            try {
                AutopJson.default.parseToJsonElement(text).jsonObject
            } catch (e: SerializationException) {
                throw CorruptionException("model_profiles.json is not valid JSON: ${e.message}", e)
            }
        val doc =
            try {
                AutopJson.default.decodeFromJsonElement(ModelProfilesDoc.serializer(), root)
            } catch (e: SerializationException) {
                throw CorruptionException("model_profiles.json has invalid content: ${e.message}", e)
            }
        // 更高版本的文档无迁移路径：按损坏处理（回退空文档），不猜测语义。
        if (doc.schemaVersion > MODEL_PROFILES_SCHEMA_VERSION) {
            throw CorruptionException(
                "model_profiles.json schemaVersion=${doc.schemaVersion} > $MODEL_PROFILES_SCHEMA_VERSION",
            )
        }
        return doc.copy(
            schemaVersion = MODEL_PROFILES_SCHEMA_VERSION,
            defaultProfileId = doc.defaultProfileId ?: legacyMainOf(root),
        )
    }

    private fun legacyMainOf(root: JsonObject): String? =
        (root[LEGACY_ROLES_KEY] as? JsonObject)
            ?.get(LEGACY_MAIN_KEY)
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content

    override suspend fun writeTo(
        t: ModelProfilesDoc,
        output: OutputStream,
    ) {
        val doc = t.copy(schemaVersion = MODEL_PROFILES_SCHEMA_VERSION)
        output.write(AutopJson.pretty.encodeToString(ModelProfilesDoc.serializer(), doc).encodeToByteArray())
    }
}

/** [ModelProfileStore] 的 DataStore 实现（docs/tech/03 §3）。 */
internal class DataStoreModelProfileStore(
    private val dataStore: DataStore<ModelProfilesDoc>,
) : ModelProfileStore {
    override val profiles: Flow<List<ModelProfile>> = dataStore.data.map { it.profiles }

    override val defaultProfileId: Flow<String?> = dataStore.data.map { it.defaultProfileId }

    override suspend fun get(id: String): ModelProfile? =
        dataStore.data
            .first()
            .profiles
            .firstOrNull { it.id == id }

    override suspend fun upsert(profile: ModelProfile) {
        dataStore.updateData { doc ->
            val next =
                if (doc.profiles.any { it.id == profile.id }) {
                    doc.profiles.map { if (it.id == profile.id) profile else it }
                } else {
                    doc.profiles + profile
                }
            doc.copy(profiles = next)
        }
    }

    override suspend fun delete(id: String) {
        dataStore.updateData { doc ->
            doc.copy(
                profiles = doc.profiles.filterNot { it.id == id },
                defaultProfileId = doc.defaultProfileId.takeIf { it != id },
            )
        }
    }

    override suspend fun setDefaultProfileId(id: String?) {
        dataStore.updateData { it.copy(defaultProfileId = id) }
    }
}
