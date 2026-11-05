package com.example.autop.core.testing

import com.example.autop.core.config.AllConfigKeys
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.buildConfigSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 测试用 [ConfigProvider]（docs/tech/02 §5）：真实合并逻辑（`buildConfigSnapshot`）+
 * 真实的 assets 默认值文件（Gradle 系统属性 `autop.configDir` 指向
 * `core-config-impl/src/main/assets/config`）。
 *
 * [overrides] 是纯覆盖树（不含 schemaVersion），语义与运行期用户覆盖一致；
 * [setOverrides] 模拟配置页修改并发出新快照。
 */
public class FakeConfigProvider(
    private var overrides: JsonObject = JsonObject(emptyMap()),
) : ConfigProvider {
    private val defaults: JsonObject = loadConfigDirDefaults(File(configDirPath()))
    private val snapshots = MutableStateFlow(buildSnapshot())

    override fun current(): ConfigSnapshot = snapshots.value

    override fun observe(): StateFlow<ConfigSnapshot> = snapshots

    override fun layered(vararg overlays: JsonObject): ConfigSnapshot =
        buildConfigSnapshot(defaults, AllConfigKeys.ALL, listOf(overrides) + overlays)

    /** 替换用户覆盖并发出新快照。 */
    public fun setOverrides(newOverrides: JsonObject) {
        overrides = newOverrides
        snapshots.value = buildSnapshot()
    }

    private fun buildSnapshot(): ConfigSnapshot = buildConfigSnapshot(defaults, AllConfigKeys.ALL, listOf(overrides))
}

private const val CONFIG_DIR_PROPERTY = "autop.configDir"

private fun configDirPath(): String = System.getProperty(CONFIG_DIR_PROPERTY) ?: error("$CONFIG_DIR_PROPERTY not set")
