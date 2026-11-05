package com.example.autop.core.config.impl

import android.content.Context
import com.example.autop.core.common.AutopJson
import com.example.autop.core.config.CURRENT_SCHEMA_VERSION
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 把 assets/config 的默认值文件合并为一棵配置树（docs/tech/02 §2.3）。
 *
 * - 按文件名顺序合并；每文件顶层为 `{"schemaVersion": <当前版本>, "<领域>": { ... }}`；
 * - `schemaVersion` 必须是 [CURRENT_SCHEMA_VERSION]；
 * - 键路径不得冲突：任一路径（含中间节点）在两个文件中重复出现时抛
 *   `IllegalStateException`（启动失败并报错，测试覆盖）。
 */
public fun loadDefaultConfigTree(files: List<ConfigAssetFile>): JsonObject {
    val seen = LinkedHashMap<String, String>()
    val result = LinkedHashMap<String, JsonElement>()
    for (file in files.sortedBy { it.name }) {
        val doc = parseConfigFile(file)
        checkSchemaVersion(doc, file)
        for ((key, value) in doc) {
            if (key == SCHEMA_VERSION_KEY) continue
            collectPaths(value, key, file.name, seen)
            result[key] = value
        }
    }
    return JsonObject(result)
}

/** 从 Android assets 的 config 目录读取全部默认值文件。 */
public fun readConfigAssetFiles(context: Context): List<ConfigAssetFile> =
    checkNotNull(context.assets.list(CONFIG_ASSET_DIR)).sorted().map { name ->
        val text =
            context.assets
                .open("$CONFIG_ASSET_DIR/$name")
                .bufferedReader()
                .use { it.readText() }
        ConfigAssetFile(name, text)
    }

private fun parseConfigFile(file: ConfigAssetFile): JsonObject =
    try {
        AutopJson.default.parseToJsonElement(file.text) as? JsonObject
            ?: error("config file ${file.name} must be a JSON object")
    } catch (e: SerializationException) {
        throw ConfigLoadException("config file ${file.name} is not valid JSON: ${e.message}", e)
    }

private fun checkSchemaVersion(
    doc: JsonObject,
    file: ConfigAssetFile,
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
private const val CONFIG_ASSET_DIR = "config"
