package com.example.autop.device.core.navigation

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/**
 * 规则表：加载真实资产文件（CI 守护）并用构造的坏数据覆盖每条校验规则。
 * 工作目录是 domain/device-core，资产位于兄弟模块 device-impl。
 */
class NavigationRulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun realRules(): NavigationRules {
        val file = File(ASSET_PATH)
        check(file.exists()) { "navigation_apps.json not found at ${file.absolutePath}" }
        return json.decodeFromString(NavigationRules.serializer(), file.readText(Charsets.UTF_8))
    }

    @Test
    fun `real asset passes validation and lists apps by priority`() {
        val rules = realRules()
        assertThat(NavigationRulesValidator.validate(rules)).isEmpty()
        assertThat(rules.schemaVersion).isEqualTo(CURRENT_NAVIGATION_RULES_SCHEMA_VERSION)
        assertThat(rules.apps.map { it.id }).containsExactly("amap", "baidu", "tencent", "google").inOrder()
    }

    @Test
    fun `every real app maps all four travel modes`() {
        realRules().apps.forEach { app ->
            assertThat(app.uri.modes.keys).containsExactly("drive", "transit", "walk", "ride")
        }
    }

    @Test
    fun `duplicate ids and priorities are reported`() {
        val base = realRules()
        val dup = base.copy(apps = listOf(base.apps[0], base.apps[0].copy(id = "amap2")))
        val errors = NavigationRulesValidator.validate(dup)
        assertThat(errors.joinToString()).contains("duplicate priority")
        val sameId = base.copy(apps = listOf(base.apps[0], base.apps[1].copy(id = base.apps[0].id)))
        assertThat(NavigationRulesValidator.validate(sameId).joinToString()).contains("duplicate app id")
    }

    @Test
    fun `missing travel mode is reported`() {
        val base = realRules()
        val amap = base.apps[0]
        val broken = amap.copy(uri = amap.uri.copy(modes = amap.uri.modes - "ride"))
        val errors = NavigationRulesValidator.validate(base.copy(apps = listOf(broken)))
        assertThat(errors.joinToString()).contains("uri.modes misses")
    }

    @Test
    fun `unknown placeholder and when condition are reported`() {
        val base = realRules()
        val amap = base.apps[0]
        val badValue = amap.uri.params[0].copy(value = "{secret}")
        val badWhen = amap.uri.params[1].copy(`when` = "sometimes")
        val broken = amap.copy(uri = amap.uri.copy(params = listOf(badValue, badWhen)))
        val errors = NavigationRulesValidator.validate(base.copy(apps = listOf(broken))).joinToString()
        assertThat(errors).contains("unknown placeholder {secret}")
        assertThat(errors).contains("unknown when 'sometimes'")
    }

    @Test
    fun `wrong schema version is reported`() {
        val errors = NavigationRulesValidator.validate(realRules().copy(schemaVersion = 2))
        assertThat(errors.joinToString()).contains("schemaVersion must be 1")
    }

    private companion object {
        const val ASSET_PATH = "../device-impl/src/main/assets/rules/navigation_apps.json"
    }
}
