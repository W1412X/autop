/**
 * 内置策略规则文件的加载（docs/tech/06 §6）：`assets/rules/policy_rules.json`
 * 经 [loadPolicyRules] 校验后合并进默认配置树，通过配置键 `policy.builtinRules` 暴露。
 * 用户规则存 `policy.userRules`（用户覆盖），强类型解析留在 policy-core（N9）。
 */
package com.example.autop.core.config.impl

import android.content.Context
import com.example.autop.core.common.AutopJson
import com.example.autop.core.config.CURRENT_SCHEMA_VERSION
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 从 Android assets 的 rules 目录读取内置策略规则文件。 */
public fun readPolicyRulesAsset(context: Context): ConfigAssetFile {
    val text =
        context.assets
            .open("$RULES_ASSET_DIR/$POLICY_RULES_FILE")
            .bufferedReader()
            .use { it.readText() }
    return ConfigAssetFile(POLICY_RULES_FILE, text)
}

/**
 * 解析并校验内置规则文档：顶层是 JSON 对象、`schemaVersion` 正确、
 * 规则 id 不重复、`category`/`type` 引用的类别与类型已声明（类别在文档内，
 * 类型由调用方经 [knownTypes] 传入）。
 */
public fun loadPolicyRules(
    file: ConfigAssetFile,
    knownTypes: Set<String> = emptySet(),
): JsonObject {
    val doc = parsePolicyDoc(file)
    val rules = ruleObjects(doc["rules"], file.name)
    checkDuplicateIds(rules, file.name)
    checkReferences(doc, rules, knownTypes, file.name)
    return doc
}

/** 把规则文档挂到默认树的 `policy.builtinRules` 键上（覆盖占位默认值 `{}`）。 */
public fun mergePolicyRules(
    defaults: JsonObject,
    rules: JsonObject,
): JsonObject {
    val policy = defaults[POLICY_KEY] as? JsonObject ?: JsonObject(emptyMap())
    val merged = policy.toMutableMap()
    merged[BUILTIN_RULES_KEY] = rules
    return JsonObject(defaults.toMutableMap().apply { put(POLICY_KEY, JsonObject(merged)) })
}

private fun parsePolicyDoc(file: ConfigAssetFile): JsonObject {
    val doc =
        try {
            AutopJson.default.parseToJsonElement(file.text) as? JsonObject
        } catch (e: SerializationException) {
            throw ConfigLoadException("rules file ${file.name} is not valid JSON: ${e.message}", e)
        }
    checkNotNull(doc) { "rules file ${file.name} must be a JSON object" }
    val version = (doc[SCHEMA_VERSION_KEY] as? JsonPrimitive)?.intOrNull
    check(version == CURRENT_SCHEMA_VERSION) {
        "rules file ${file.name} schemaVersion must be $CURRENT_SCHEMA_VERSION, was $version"
    }
    return doc
}

private fun ruleObjects(
    value: JsonElement?,
    fileName: String,
): List<JsonObject> {
    val array = value as? JsonArray
    checkNotNull(array) { "rules file $fileName rules must be a JSON array" }
    return array.map { element ->
        checkNotNull(element as? JsonObject) { "rules file $fileName rules must be JSON objects" }
    }
}

private fun checkDuplicateIds(
    rules: List<JsonObject>,
    fileName: String,
) {
    val seen = mutableSetOf<String>()
    for (rule in rules) {
        val id = (rule["id"] as? JsonPrimitive)?.contentOrNull
        checkNotNull(id) { "rules file $fileName rule missing id" }
        check(seen.add(id)) { "rules file $fileName duplicate rule id '$id'" }
    }
}

private fun checkReferences(
    doc: JsonObject,
    rules: List<JsonObject>,
    knownTypes: Set<String>,
    fileName: String,
) {
    val categories = (doc["categories"] as? JsonObject)?.keys.orEmpty().toSet()
    for (rule in rules) {
        val id = (rule["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val category = (rule["category"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        check(category in categories) {
            "rules file $fileName rule '$id' references unknown category '$category'"
        }
        val type = (rule["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (knownTypes.isNotEmpty()) {
            check(type in knownTypes) {
                "rules file $fileName rule '$id' references unknown type '$type'"
            }
        }
    }
}

private const val SCHEMA_VERSION_KEY = "schemaVersion"
private const val RULES_ASSET_DIR = "rules"
private const val POLICY_RULES_FILE = "policy_rules.json"
private const val POLICY_KEY = "policy"
private const val BUILTIN_RULES_KEY = "builtinRules"
