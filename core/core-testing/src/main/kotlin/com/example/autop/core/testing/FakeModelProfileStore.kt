package com.example.autop.core.testing

import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelProfileStore
import com.example.autop.llm.ModelRoles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 内存版 [ModelProfileStore]（测试支撑）：语义与 core-config-impl 的 DataStore 实现一致
 * （按 id 原位更新、删除时同步清掉角色悬空引用）。
 */
public class FakeModelProfileStore(
    initialProfiles: List<ModelProfile> = emptyList(),
    initialRoles: ModelRoles = ModelRoles(),
) : ModelProfileStore {
    private val profilesState = MutableStateFlow(initialProfiles)
    private val rolesState = MutableStateFlow(initialRoles)

    override val profiles: Flow<List<ModelProfile>> = profilesState

    override val roles: Flow<ModelRoles> = rolesState

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
        rolesState.value =
            ModelRoles(
                main = rolesState.value.main?.takeIf { it != id },
                vision = rolesState.value.vision?.takeIf { it != id },
                light = rolesState.value.light?.takeIf { it != id },
            )
    }

    override suspend fun setRoles(roles: ModelRoles) {
        rolesState.value = roles
    }
}
