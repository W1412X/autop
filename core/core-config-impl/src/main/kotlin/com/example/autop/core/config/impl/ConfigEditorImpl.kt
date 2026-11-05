package com.example.autop.core.config.impl

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.CURRENT_SCHEMA_VERSION
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigErrorCodes
import com.example.autop.core.config.ConfigKey
import com.example.autop.core.config.buildConfigSnapshot
import com.example.autop.core.config.putSchemaVersion
import com.example.autop.core.config.resolvePath
import com.example.autop.core.config.setPath
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * [ConfigEditor] 实现（设置页与高级配置）。
 *
 * `path` 必须落在某个注册 key 的子树内，且末段真实存在于该段结构，
 * 否则返回 `config.unknown_path`；写入后整段解码 + `validate()`，
 * 失败返回 `config.invalid` 并拒绝写入（docs/tech/02 §2.1、§2.4）。
 * `value` 为 `JsonNull` 等价于清除覆盖（merge 语义里 JsonNull = 恢复默认）。
 */
internal class ConfigEditorImpl(
    private val defaults: JsonObject,
    private val keys: List<ConfigKey<*>>,
    private val overrides: OverridesStore,
    private val logger: Logger,
) : ConfigEditor {
    override suspend fun setOverride(
        path: String,
        value: JsonElement,
    ): Outcome<Unit> {
        val owner = ownerKey(path)?.takeIf { pathExists(it, path) } ?: return unknownPath(path)
        val current = stripSchemaVersion(overrides.data.first())
        val candidate = if (value is JsonNull) removeFromPath(current, path) else setPath(current, path, value)
        val snapshot = buildConfigSnapshot(defaults, keys, listOf(candidate), logger)
        val problem = snapshot.problems().firstOrNull { it.startsWith("${owner.path}: ") }
        return if (problem != null) {
            Outcome.Err(AppError(code = ConfigErrorCodes.INVALID, message = problem))
        } else {
            overrides.update { putSchemaVersion(candidate, CURRENT_SCHEMA_VERSION) }
            Outcome.Ok(Unit)
        }
    }

    override suspend fun clearOverride(path: String) {
        val candidate = removeFromPath(stripSchemaVersion(overrides.data.first()), path)
        overrides.update { putSchemaVersion(candidate, CURRENT_SCHEMA_VERSION) }
    }

    override suspend fun userOverrides(): JsonObject = stripSchemaVersion(overrides.data.first())

    private fun ownerKey(path: String): ConfigKey<*>? =
        keys.filter { path == it.path || path.startsWith("${it.path}.") }.maxByOrNull { it.path.length }

    private fun pathExists(
        owner: ConfigKey<*>,
        path: String,
    ): Boolean {
        val relative = path.removePrefix(owner.path).removePrefix(".")
        return relative.isEmpty() || resolvePath(encodeDefaultSection(owner), relative) != null
    }

    private fun unknownPath(path: String): Outcome.Err =
        Outcome.Err(AppError(code = ConfigErrorCodes.UNKNOWN_PATH, message = "no config key owns path '$path'"))
}

/** 在 [tree] 的副本中移除 [path]（点分隔段）处的节点，返回新树；删除后变空的父对象一并剪枝。 */
private fun removeFromPath(
    tree: JsonObject,
    path: String,
): JsonObject = removeRecursive(tree, path.split(PATH_SEPARATOR), 0)

private fun removeRecursive(
    tree: JsonObject,
    segments: List<String>,
    index: Int,
): JsonObject {
    val key = segments[index]
    val child = tree[key]
    return when {
        !tree.containsKey(key) -> tree
        index == segments.lastIndex -> JsonObject(tree - key)
        child !is JsonObject -> tree
        else -> {
            val newChild = removeRecursive(child, segments, index + 1)
            // 剪枝：中间对象被删空后不再保留骨架，清除后覆盖文档能真正回到 {}
            if (newChild.isEmpty()) JsonObject(tree - key) else JsonObject(tree + (key to newChild))
        }
    }
}

private const val PATH_SEPARATOR = "."
