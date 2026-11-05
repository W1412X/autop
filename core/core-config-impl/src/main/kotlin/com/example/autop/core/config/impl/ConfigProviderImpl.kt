package com.example.autop.core.config.impl

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigKey
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.ConfigSection
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.buildConfigSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject

/**
 * [ConfigProvider] 实现：默认值（assets）⊕ 用户覆盖（DataStore），覆盖变化时重建快照。
 *
 * 快照由覆盖文档异步驱动（Eagerly 收集）；启动初期 [current] 可能还是纯默认值快照，
 * 首个覆盖文档到达后自动更新，调用方经 [observe] 获取最新值。
 * 作用域随单例生命周期（进程级），不使用 GlobalScope（代码规范 §7）。
 */
internal class ConfigProviderImpl(
    private val defaults: JsonObject,
    private val keys: List<ConfigKey<*>>,
    private val overrides: OverridesStore,
    dispatchers: DispatcherProvider,
    private val logger: Logger,
) : ConfigProvider {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io + logger.exceptionHandler("ConfigProvider"))

    private val overrideTrees: StateFlow<JsonObject> =
        overrides.data
            .map { stripSchemaVersion(it) }
            .stateIn(scope, SharingStarted.Eagerly, JsonObject(emptyMap()))

    private val snapshots: StateFlow<ConfigSnapshot> =
        overrideTrees
            .map { buildConfigSnapshot(defaults, keys, listOf(it), logger) }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                buildConfigSnapshot(defaults, keys, emptyList(), logger),
            )

    override fun current(): ConfigSnapshot = snapshots.value

    override fun observe(): StateFlow<ConfigSnapshot> = snapshots

    override fun layered(vararg overlays: JsonObject): ConfigSnapshot =
        buildConfigSnapshot(defaults, keys, listOf(overrideTrees.value) + overlays, logger)
}

/** 用各 key 的 `defaultInstance` 与其序列化器编码出默认结构树（校验路径存在性用）。 */
internal fun encodeDefaultSection(key: ConfigKey<*>): JsonObject = encodeDefault(key)

private fun <T : ConfigSection> encodeDefault(key: ConfigKey<T>): JsonObject =
    AutopJson.default.encodeToJsonElement(key.serializer, key.defaultInstance()) as JsonObject
