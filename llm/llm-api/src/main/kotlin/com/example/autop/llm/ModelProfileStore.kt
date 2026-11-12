/**
 * 模型配置存储端口（docs/tech/03 §3）：接口在 llm-api，实现放 core-config-impl
 * （独立 DataStore 文件 `model_profiles.json`，带 schemaVersion）。
 */
package com.example.autop.llm

import kotlinx.coroutines.flow.Flow

/** 模型配置与默认模型的读写端口。 */
public interface ModelProfileStore {
    /** 全部 profile（变更时重发）。 */
    public val profiles: Flow<List<ModelProfile>>

    /** 默认模型的 profile id；未设置为 null（变更时重发）。 */
    public val defaultProfileId: Flow<String?>

    public suspend fun get(id: String): ModelProfile?

    /** 新增或更新（按 [ModelProfile.id]）。 */
    public suspend fun upsert(profile: ModelProfile)

    /** 删除；若它是默认模型，默认模型同时清空。 */
    public suspend fun delete(id: String)

    public suspend fun setDefaultProfileId(id: String?)
}
