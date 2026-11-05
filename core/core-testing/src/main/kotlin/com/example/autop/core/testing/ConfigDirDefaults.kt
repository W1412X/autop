package com.example.autop.core.testing

import com.example.autop.core.common.AutopJson
import com.example.autop.core.config.CURRENT_SCHEMA_VERSION
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * 从 `autop.configDir` 目录构造默认值配置树（docs/tech/02 §5）。
 *
 * 语义与 core-config-impl 的 `loadDefaultConfigTree` 一致（按文件名合并、schemaVersion
 * 校验、键路径冲突失败）；core-testing 受依赖矩阵约束不能依赖 core-config-impl（01 §4），
 * 故镜像这份纯解析逻辑，两侧测试共同锁定行为（questions.md N16）。
 */
internal fun loadConfigDirDefaults(dir: File): JsonObject {
    val files =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }
            ?: error("autop.configDir is not a readable directory: $dir")
    val seen = LinkedHashMap<String, String>()
    val result = LinkedHashMap<String, JsonElement>()
    for (file in files) {
        val doc = parseConfigDirFile(file)
        checkSchemaVersion(doc, file)
        for ((key, value) in doc) {
            if (key == SCHEMA_VERSION_KEY) continue
            collectPaths(value, key, file.name, seen)
            result[key] = value
        }
    }
    return JsonObject(result)
}

private fun parseConfigDirFile(file: File): JsonObject =
    try {
        AutopJson.default.parseToJsonElement(file.readText()) as? JsonObject
            ?: error("config file ${file.name} must be a JSON object")
    } catch (e: SerializationException) {
        throw IllegalStateException("config file ${file.name} is not valid JSON: ${e.message}", e)
    }

private fun checkSchemaVersion(
    doc: JsonObject,
    file: File,
) {
    val version = (doc[SCHEMA_VERSION_KEY] as? JsonPrimitive)?.intOrNull
    check(version == CURRENT_SCHEMA_VERSION) {
        "config file ${file.name} schemaVersion must be $CURRENT_SCHEMA_VERSION, was $version"
    }
}

private fun collectPaths(
    element: JsonElement,
    path: String,
    fileName: String,
    seen: MutableMap<String, String>,
) {
    val previous = seen.put(path, fileName)
    check(previous == null) {
        "config key path conflict: '$path' in $fileName already defined in $previous"
    }
    if (element is JsonObject) {
        for ((key, value) in element) collectPaths(value, "$path.$key", fileName, seen)
    }
}

private const val SCHEMA_VERSION_KEY = "schemaVersion"
