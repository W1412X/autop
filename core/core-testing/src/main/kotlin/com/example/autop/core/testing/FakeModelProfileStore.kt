package com.example.autop.core.testing

import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 内存版 [ModelProfileStore]（测试支撑）：语义与 core-config-impl 的 DataStore 实现一致
 * （按 id 原位更新、删除默认模型时同步清空默认）。
 */
public class FakeModelProfileStore(
    initialProfiles: List<ModelProfile> = emptyList(),
    initialDefaultId: String? = null,
) : ModelProfileStore {
    private val profilesState = MutableStateFlow(initialProfiles)
    private val defaultState = MutableStateFlow(initialDefaultId)

    override val profiles: Flow<List<ModelProfile>> = profilesState

    override val defaultProfileId: Flow<String?> = defaultState

    override suspend fun get(id: String): ModelProfile? = profilesState.value.firstOrNull { it.id == id }

    override suspend fun upsert(profile: ModelProfile) {
        profilesState.value =
            if (profilesState.value.any { it.id == profile.id }) {
                profilesState.value.map { if (it.id == profile.id) profile else it }
            } else {
                profilesState.value + profile
            }
    }

    override suspend fun delete(id: String) {
        profilesState.value = profilesState.value.filterNot { it.id == id }
        if (defaultState.value == id) defaultState.value = null
    }

    override suspend fun setDefaultProfileId(id: String?) {
        defaultState.value = id
    }
}
