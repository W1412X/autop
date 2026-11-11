package com.example.autop.core.config

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * 用户覆盖文件的一次 schemaVersion 升级（docs/tech/02 §2.3）。
 *
 * 覆盖文件读取时按 `from` 逐跳执行到 [CURRENT_SCHEMA_VERSION]。
 */
public data class ConfigMigration(
    val from: Int,
    val to: Int,
    val migrate: (JsonObject) -> JsonObject,
)

/** 配置（用户覆盖）文件的当前 schemaVersion。 */
public const val CURRENT_SCHEMA_VERSION: Int = 5

/**
 * 内置迁移链：v2 删除屏幕观测 v4 不再使用的配置键（docs/design/
 * observation-modes-coordinate-actions §7）；v2→v3 只新增配置段
 * （flow.contract 等，docs/design/flows-contracts-evidence §8），覆盖文件无需改写。
 */
public fun builtinConfigMigrations(): List<ConfigMigration> =
    listOf(
        ConfigMigration(from = 1, to = 2) { doc ->
            val cleaned = DELETE_PATHS_V2.fold(doc) { acc, path -> removePath(acc, path) }
            filterAgentTools(cleaned)
        },
        ConfigMigration(from = 2, to = 3) { doc -> doc },
        // agent-harness-overhaul：仅增删键，无数据变换（旧键由 ignoreUnknownKeys 忽略）。
        ConfigMigration(from = 3, to = 4) { doc -> doc },
        // coordinate-node-action-split：补注册 tap_node/scroll_node，删除 device.nodeAction（§5.7）。
        ConfigMigration(from = 4, to = 5) { doc ->
            val cleaned = removePath(doc, "device.nodeAction")
            insertAgentTools(cleaned)
        },
    )

/** v2 删除的配置路径。 */
private val DELETE_PATHS_V2: List<String> =
    listOf(
        "device.vision.mode",
        "device.serializer.autoScreenshotWhenPoor",
        "device.serializer.includeCenter",
        "device.serializer.centerScope",
        "device.serializer.includeBounds",
        "device.serializer.rendererA11y",
        "device.serializer.rendererVisual",
        "device.serializer.maxTokens",
        "device.serializer.deepLabelDepth",
        "device.serializer.keepTopRatio",
        "device.serializer.keepBottomRatio",
        "device.screenshot.markNodes",
        "device.screenshot.markStrokePx",
        "device.screenshot.markTextPx",
        "device.screenshot.markColors",
        "device.screenshot.coordGrid",
        "device.readNode",
    )

/** `agent.tools.enabled` 去掉已删除的工具（v2）。 */
private fun filterAgentTools(doc: JsonObject): JsonObject {
    val agent = doc["agent"] as? JsonObject ?: return doc
    val tools = agent["tools"] as? JsonObject ?: return doc
    val enabled = tools["enabled"] as? JsonArray ?: return doc
    val filtered =
        JsonArray(
            enabled.filter {
                val id = (it as? JsonPrimitive)?.contentOrNull
                id !in REMOVED_TOOLS_V2
            },
        )
    return doc.withChild("agent", agent.withChild("tools", JsonObject(tools + ("enabled" to filtered))))
}

/** `agent.tools.enabled` 在 tool.tap / tool.scroll 之后补上节点工具 id（v5；已存在则不重复）。 */
private fun insertAgentTools(doc: JsonObject): JsonObject {
    val agent = doc["agent"] as? JsonObject ?: return doc
    val tools = agent["tools"] as? JsonObject ?: return doc
    val enabled = tools["enabled"] as? JsonArray ?: return doc
    val ids = enabled.map { (it as? JsonPrimitive)?.contentOrNull }
    val rebuilt = mutableListOf<JsonElement>()
    for (item in enabled) {
        rebuilt += item
        when ((item as? JsonPrimitive)?.contentOrNull) {
            "tool.tap" -> if ("tool.tap_node" !in ids) rebuilt += JsonPrimitive("tool.tap_node")
            "tool.scroll" -> if ("tool.scroll_node" !in ids) rebuilt += JsonPrimitive("tool.scroll_node")
        }
    }
    return doc.withChild("agent", agent.withChild("tools", JsonObject(tools + ("enabled" to JsonArray(rebuilt)))))
}

/** 按点分路径删除键（不存在时原样返回）。 */
private fun removePath(
    doc: JsonObject,
    path: String,
): JsonObject {
    val parts = path.split('.')
    return removePath(doc, parts)
}

private fun removePath(
    node: JsonObject,
    parts: List<String>,
): JsonObject {
    val head = parts.first()
    if (parts.size == 1) {
        if (head !in node) return node
        return JsonObject(node - head)
    }
    val child = node[head] as? JsonObject ?: return node
    val updated = removePath(child, parts.drop(1))
    return if (updated === child) node else node.withChild(head, updated)
}

private fun JsonObject.withChild(
    key: String,
    value: JsonElement,
): JsonObject = JsonObject(this + (key to value))

private val REMOVED_TOOLS_V2: Set<String> = setOf("tool.find_nodes", "tool.read_node")

/**
 * 把覆盖文档 [doc] 按 [migrations] 升级到 [targetVersion]。
 *
 * - `schemaVersion` 缺失视为 1（首版格式）；
 * - 版本高于目标（来自未来）或链上缺跳 → `Err(config.migration_failed)`；
 * - 迁移函数抛出的异常转为 `Err(config.migration_failed)`。
 */
public fun migrateConfig(
    doc: JsonObject,
    migrations: List<ConfigMigration>,
    targetVersion: Int = CURRENT_SCHEMA_VERSION,
): Outcome<JsonObject> {
    val version = schemaVersionOf(doc)
    return if (version > targetVersion) {
        migrationFailed("schemaVersion=$version is newer than supported $targetVersion")
    } else {
        migrateChain(doc, version, migrations, targetVersion)
    }
}

private fun migrateChain(
    current: JsonObject,
    version: Int,
    migrations: List<ConfigMigration>,
    targetVersion: Int,
): Outcome<JsonObject> {
    val step = migrations.firstOrNull { it.from == version && it.to <= targetVersion }
    return when {
        version >= targetVersion -> Outcome.Ok(current)
        step == null -> migrationFailed("no migration path from schemaVersion=$version to $targetVersion")
        else ->
            when (val stepped = applyStep(step, current)) {
                is Outcome.Ok -> migrateChain(stepped.value, step.to, migrations, targetVersion)
                is Outcome.Err -> stepped
            }
    }
}

// 迁移函数可能抛任意异常，统一转为迁移失败（设计意图，非疏漏）
@Suppress("TooGenericExceptionCaught")
private fun applyStep(
    step: ConfigMigration,
    doc: JsonObject,
): Outcome<JsonObject> =
    try {
        Outcome.Ok(putSchemaVersion(step.migrate(doc), step.to))
    } catch (e: CancellationException) {
        // 规范 §7：取消必须继续向上传播
        throw e
    } catch (e: Exception) {
        // TooGenericExceptionCaught：迁移函数可能抛任意异常，统一转为迁移失败
        migrationFailed("migration ${step.from}->${step.to} threw: ${e.message}")
    }

/** 读取文档的 schemaVersion；缺失视为 1。 */
public fun schemaVersionOf(doc: JsonObject): Int = (doc[SCHEMA_VERSION_KEY] as? JsonPrimitive)?.intOrNull ?: 1

/** 返回写入 `schemaVersion` 的新文档。 */
public fun putSchemaVersion(
    doc: JsonObject,
    version: Int,
): JsonObject {
    val result = LinkedHashMap<String, JsonElement>(doc.size + 1)
    result.putAll(doc)
    result[SCHEMA_VERSION_KEY] = JsonPrimitive(version)
    return JsonObject(result)
}

private fun migrationFailed(message: String): Outcome.Err =
    Outcome.Err(AppError(code = ConfigErrorCodes.MIGRATION_FAILED, message = message))

private const val SCHEMA_VERSION_KEY = "schemaVersion"
