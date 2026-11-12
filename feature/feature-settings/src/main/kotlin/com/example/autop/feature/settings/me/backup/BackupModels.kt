/**
 * 备份文件结构（docs/tech/08 §4.5 导出/导入，WP11）：顶层 `format` + `version`，
 * 内容为记忆树、任务与模型配置。**导出不含密钥**（`ModelProfile.apiKeyRef` 置空，
 * 导入后需重新输入，docs/tech/03 §3）。
 */
package com.example.autop.feature.settings.me.backup

import com.example.autop.llm.ModelProfile
import com.example.autop.memory.MemoryNode
import com.example.autop.task.Task
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 备份文件顶层结构。 */
@Serializable
public data class BackupFile(
    @SerialName("format") val format: String,
    @SerialName("version") val version: Int,
    @SerialName("exportedAt") val exportedAt: Long,
    @SerialName("memories") val memories: List<MemoryNode>,
    @SerialName("tasks") val tasks: List<Task>,
    @SerialName("modelProfiles") val modelProfiles: List<ModelProfile>,
    @SerialName("defaultProfileId") val defaultProfileId: String? = null,
    /** 旧版（角色时代）导出的角色分配；只读，导入时取其中的 main 作为默认模型（ADR-0024）。 */
    @SerialName("modelRoles") val legacyModelRoles: LegacyModelRoles? = null,
)

/** 旧版角色分配（只读）。 */
@Serializable
public data class LegacyModelRoles(
    @SerialName("main") val main: String? = null,
)

/** 备份格式常量。 */
public object BackupFormat {
    /** 顶层 `format` 标识。 */
    public const val FORMAT: String = "autop-backup"

    /** 当前版本。 */
    public const val VERSION: Int = 1
}

/** 一次导入的结果计数（失败条目跳过并计数在各集合内）。 */
public data class ImportResult(
    val memories: Int,
    val tasks: Int,
    val modelProfiles: Int,
)
