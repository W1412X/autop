/**
 * 模型配置存储端口（docs/tech/03 §3）：接口在 llm-api，实现放 core-config-impl
 * （独立 DataStore 文件 `model_profiles.json`，带 schemaVersion）。
 */
package com.example.autop.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 角色分配（存同一存储文件，docs/tech/03 §3）。 */
@Serializable
public data class ModelRoles(
    @SerialName("main") val main: String? = null,
    @SerialName("vision") val vision: String? = null,
    @SerialName("light") val light: String? = null,
)

/** 模型配置与角色分配的读写端口。 */
public interface ModelProfileStore {
    /** 全部 profile（变更时重发）。 */
    public val profiles: Flow<List<ModelProfile>>

    /** 角色分配（变更时重发）。 */
    public val roles: Flow<ModelRoles>

    public suspend fun get(id: String): ModelProfile?

    /** 新增或更新（按 [ModelProfile.id]）。 */
    public suspend fun upsert(profile: ModelProfile)

    public suspend fun delete(id: String)

    public suspend fun setRoles(roles: ModelRoles)
}
