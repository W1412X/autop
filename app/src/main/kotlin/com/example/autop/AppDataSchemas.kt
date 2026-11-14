package com.example.autop

import android.content.Context
import com.example.autop.agent.store.db.THREADS_DB_VERSION
import com.example.autop.core.config.CURRENT_SCHEMA_VERSION
import com.example.autop.core.config.impl.MODEL_PROFILES_SCHEMA_VERSION
import com.example.autop.core.platform.DataSchemaGate
import com.example.autop.core.platform.DatabaseSchema
import com.example.autop.core.platform.JsonSchema
import com.example.autop.core.prompt.impl.CURRENT_PROMPT_SCHEMA_VERSION
import com.example.autop.flow.impl.db.FLOWS_DB_VERSION
import com.example.autop.memory.impl.db.MEMORY_DB_VERSION
import com.example.autop.task.impl.db.TASKS_DB_VERSION

/**
 * 应用的用户数据及其期望结构版本（ADR-0032）。新增数据库或带 schemaVersion 的用户数据文件时在此登记。
 * API Key 等密钥存放在 Keystore 加密的 DataStore 中，不纳入检查。
 */
internal object AppDataSchemas {
    /** Room 数据库（SQLite user_version）。 */
    val DATABASES: List<DatabaseSchema> =
        listOf(
            DatabaseSchema("threads.db", THREADS_DB_VERSION),
            DatabaseSchema("tasks.db", TASKS_DB_VERSION),
            DatabaseSchema("flows.db", FLOWS_DB_VERSION),
            DatabaseSchema("memory.db", MEMORY_DB_VERSION),
        )

    /** JSON 用户数据（顶层 schemaVersion），位于 filesDir 根目录。 */
    val JSON_FILES: List<JsonSchema> =
        listOf(
            JsonSchema("config_overrides.json", CURRENT_SCHEMA_VERSION),
            JsonSchema("model_profiles.json", MODEL_PROFILES_SCHEMA_VERSION),
            JsonSchema("prompt_overrides.json", CURRENT_PROMPT_SCHEMA_VERSION),
        )
}

/** 构造应用的数据结构检查器：数据库在 databases 目录，JSON 在 filesDir 根目录。 */
internal fun dataSchemaGate(context: Context): DataSchemaGate {
    val filesDir = context.filesDir
    val databaseDir = context.getDatabasePath("autop.db").parentFile ?: filesDir
    return DataSchemaGate(
        databaseDir = databaseDir,
        schemas = AppDataSchemas.DATABASES,
        filesDir = filesDir,
        jsonSchemas = AppDataSchemas.JSON_FILES,
    )
}
