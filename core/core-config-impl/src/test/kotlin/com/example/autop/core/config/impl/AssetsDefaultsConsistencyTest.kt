package com.example.autop.core.config.impl

import com.example.autop.core.common.AutopJson
import com.example.autop.core.config.AllConfigKeys
import com.example.autop.core.config.ConfigKey
import com.example.autop.core.config.ConfigSection
import com.example.autop.core.config.diffJson
import com.example.autop.core.config.resolvePath
import com.example.autop.core.config.setPath
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * 02 §2.1 的一致性要求：`ConfigKey.defaultInstance` 必须与 assets/config 默认值一致。
 * 比较采用双向递归差异：只允许「显式 null vs 缺省」这类等价差异，其余任何差异都失败。
 */
class AssetsDefaultsConsistencyTest {
    @Test
    fun everyKeyMatchesItsAssetsDefault() {
        val tree = loadDefaultConfigTree(loadRealAssetFiles())
        for (key in AllConfigKeys.ALL) {
            val node = resolvePath(tree, key.path)
            assertThat(node).isNotNull()
            assertThat(decodeSection(key, checkNotNull(node) as JsonObject)).isEqualTo(key.defaultInstance())
        }
    }

    @Test
    fun assetsAndDefaultsDifferOnlyByExplicitNulls() {
        val tree = loadDefaultConfigTree(loadRealAssetFiles()).canonicalNumbers()
        val encoded = encodeAllDefaults().canonicalNumbers()
        assertOnlyNullLeaves(diffJson(encoded, tree), "assets-vs-defaults")
        assertOnlyNullLeaves(diffJson(tree, encoded), "defaults-vs-assets")
    }

    /** 数字字面量归一（0.0001 与默认值编码出的 1.0E-4 是同一个数）。 */
    private fun JsonObject.canonicalNumbers(): JsonObject = JsonObject(mapValues { it.value.canonicalNumber() })

    private fun JsonElement.canonicalNumber(): JsonElement =
        when (this) {
            is JsonObject -> canonicalNumbers()
            is JsonArray -> JsonArray(map { it.canonicalNumber() })
            JsonNull -> this
            is JsonPrimitive -> {
                val number = if (isString) null else content.toDoubleOrNull()
                if (number == null) this else JsonPrimitive(number.toString())
            }
            else -> this
        }

    /** 按路径深度（浅→深）把各 key 的默认实例编码后拼成整树，子树 key 覆盖父级对象中的同名子树。 */
    private fun encodeAllDefaults(): JsonObject {
        var tree = JsonObject(emptyMap())
        val shallowFirst = AllConfigKeys.ALL.sortedBy { key -> key.path.count { c -> c == '.' } }
        for (key in shallowFirst) {
            tree = setPath(tree, key.path, encodeDefaultSection(key))
        }
        return tree
    }

    private fun assertOnlyNullLeaves(
        diff: JsonObject,
        label: String,
        prefix: String = "",
    ) {
        for ((key, value) in diff) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is JsonObject) {
                assertOnlyNullLeaves(value, label, path)
            } else {
                assertWithMessagePath(label, path, value)
            }
        }
    }

    private fun assertWithMessagePath(
        label: String,
        path: String,
        value: JsonElement,
    ) {
        assertWithMessage("$label at '$path'").that(value).isEqualTo(JsonNull)
    }
}

private fun <T : ConfigSection> decodeSection(
    key: ConfigKey<T>,
    node: JsonObject,
): T = AutopJson.default.decodeFromJsonElement(key.serializer, node)
