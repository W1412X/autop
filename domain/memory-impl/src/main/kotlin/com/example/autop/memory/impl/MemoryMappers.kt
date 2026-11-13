/**
 * 实体与模型互转、JSON 列编解码与路径工具（docs/tech/06 §2）。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.RunId
import com.example.autop.core.common.getOrNull
import com.example.autop.memory.ChangeType
import com.example.autop.memory.MemoryEvidence
import com.example.autop.memory.MemoryNode
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryVersion
import com.example.autop.memory.impl.db.MemoryNodeEntity
import com.example.autop.memory.impl.db.MemoryVersionEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** 内置根目录（含 "/"）不可删除、不可移动。 */
internal fun MemoryPath.isProtectedRoot(): Boolean =
    this == MemoryPath.ROOT || MemorySeed.PROTECTED_ROOTS.contains(value)

/** 节点路径的父目录（"/a" → "/"）。 */
internal fun parentPathOf(path: String): String? {
    val idx = path.lastIndexOf('/')
    return when {
        idx <= 0 -> MemoryPath.ROOT.value
        else -> path.substring(0, idx)
    }
}

internal fun toModel(e: MemoryNodeEntity): MemoryNode =
    MemoryNode(
        path = MemoryPath.parse(e.path).getOrNull() ?: MemoryPath.ROOT,
        title = e.title,
        summary = e.summary,
        content = e.content,
        keywords = decodeList(e.keywordsJson),
        links = decodeList(e.linksJson).mapNotNull { MemoryPath.parse(it).getOrNull() },
        source = MemorySource.valueOf(e.source),
        version = e.version,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        childCount = 0,
    )

internal fun toVersion(e: MemoryVersionEntity): MemoryVersion =
    MemoryVersion(
        id = e.id,
        path = MemoryPath.parse(e.path).getOrNull() ?: MemoryPath.ROOT,
        version = e.version,
        changeType = ChangeType.valueOf(e.changeType),
        titleBefore = e.titleBefore,
        titleAfter = e.titleAfter,
        contentBefore = e.contentBefore,
        contentAfter = e.contentAfter,
        source = MemorySource.valueOf(e.source),
        runId = e.runId?.let { RunId(it) },
        reason = e.reason,
        evidence = e.evidenceJson?.let { decodeEvidence(it) },
        createdAt = e.createdAt,
    )

internal fun encodeList(items: List<String>): String =
    AutopJson.default.encodeToString(JsonArray.serializer(), JsonArray(items.map(::JsonPrimitive)))

internal fun decodeList(json: String): List<String> =
    runCatching {
        AutopJson.default
            .decodeFromString(JsonArray.serializer(), json)
            .mapNotNull { (it as? JsonPrimitive)?.content }
    }.getOrDefault(emptyList())

internal fun encodeEvidence(e: MemoryEvidence): String =
    AutopJson.default.encodeToString(MemoryEvidence.serializer(), e)

internal fun decodeEvidence(json: String): MemoryEvidence? =
    runCatching {
        AutopJson.default.decodeFromString(MemoryEvidence.serializer(), json)
    }.getOrNull()

/** 路径 [this] 是否等于 [ancestor] 或位于其子树中。按路径段边界判断，不做字符串前缀匹配。 */
internal fun String.isWithinPath(ancestor: String): Boolean =
    this == ancestor || ancestor == "/" || startsWith("$ancestor/")
