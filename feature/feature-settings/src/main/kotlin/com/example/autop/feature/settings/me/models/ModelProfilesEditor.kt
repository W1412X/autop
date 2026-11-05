/**
 * 模型配置编辑用例（docs/tech/08 §4.5 模型配置页）：聚合 [ModelProfileStore] 读写、
 * API Key 存取（[SecretStore]，硬性规则 6：密钥只经 SecretStore）与 id 生成（UUIDv7，
 * docs/tech/02 §1.2）。同时让 ViewModel 构造参数保持在 detekt 阈值内。
 */
package com.example.autop.feature.settings.me.models

import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.SecretStore
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelProfileStore
import com.example.autop.llm.ModelRoles
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** 模型配置编辑用例。 */
public class ModelProfilesEditor
    @Inject
    constructor(
        private val store: ModelProfileStore,
        private val secrets: SecretStore,
        private val ids: IdGenerator,
    ) {
        /** 全部 profile（变更时重发）。 */
        public val profiles: Flow<List<ModelProfile>> = store.profiles

        /** 角色分配（变更时重发）。 */
        public val roles: Flow<ModelRoles> = store.roles

        /** 生成新 profile 的 id（创建草稿时即分配，API Key 引用需要它）。 */
        public fun newId(): String = ids.newId()

        public suspend fun profile(id: String): ModelProfile? = store.get(id)

        /**
         * 已保存密钥的末 4 位（docs/tech/08 §4.5："已保存时只显示末 4 位"）；
         * 未保存返回 null。
         */
        public suspend fun savedKeyTail(id: String): String? = secrets.get(keyRef(id))?.takeLast(KEY_TAIL_LENGTH)

        /** 保存 profile；[newKey] 非空时同时写入/更新密钥。 */
        public suspend fun saveProfile(
            profile: ModelProfile,
            newKey: String?,
        ) {
            if (!newKey.isNullOrBlank()) {
                secrets.put(keyRef(profile.id), newKey)
            }
            store.upsert(profile)
        }

        /** 仅保存密钥（测试连接前落库，Provider 经 `apiKeyRef` 解析）。 */
        public suspend fun saveKey(
            id: String,
            key: String,
        ) {
            secrets.put(keyRef(id), key)
        }

        /** 删除 profile 与对应密钥（存储侧会同步清理角色悬空引用）。 */
        public suspend fun deleteProfile(id: String) {
            store.delete(id)
            secrets.remove(keyRef(id))
        }

        public suspend fun setRoles(roles: ModelRoles) = store.setRoles(roles)

        public companion object {
            /** SecretStore 引用格式（core-common `SecretStore` KDoc 约定：`secret:model:<profileId>`）。 */
            public fun keyRef(id: String): String = "secret:model:$id"

            private const val KEY_TAIL_LENGTH = 4
        }
    }
