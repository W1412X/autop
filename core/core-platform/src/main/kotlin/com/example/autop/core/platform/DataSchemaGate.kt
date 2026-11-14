package com.example.autop.core.platform

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 一个 Room 数据库文件及其期望的结构版本（ADR-0032）。 */
public data class DatabaseSchema(
    val fileName: String,
    val version: Int,
)

/** 一个 JSON 用户数据文件及其期望的 `schemaVersion`（ADR-0032）。文件位于 filesDir 根目录。 */
public data class JsonSchema(
    val fileName: String,
    val version: Int,
)

/** 结构版本不一致的文件。[found] 为文件中的版本；文件无法读取或缺少版本字段时为 null。 */
public data class SchemaMismatch(
    val fileName: String,
    val expected: Int,
    val found: Int?,
)

/**
 * 启动时的数据结构检查（ADR-0032）。
 *
 * - 数据库：读取 SQLite `user_version`（Room 写入的结构版本）。不经过 Room，检查时不会打开或迁移数据库。
 * - JSON 用户数据：读取顶层 `schemaVersion` 字段。
 *
 * 文件不存在（全新安装）不算不一致。
 */
public class DataSchemaGate(
    private val databaseDir: File,
    private val schemas: List<DatabaseSchema>,
    private val filesDir: File = databaseDir,
    private val jsonSchemas: List<JsonSchema> = emptyList(),
) {
    /** 结构不一致的文件列表（数据库与 JSON 合并）。 */
    public fun mismatches(): List<SchemaMismatch> =
        schemas.mapNotNull { schema ->
            val file = File(databaseDir, schema.fileName)
            if (!file.exists()) {
                null
            } else {
                val found = readUserVersion(file)
                if (found == schema.version) null else SchemaMismatch(schema.fileName, schema.version, found)
            }
        } +
            jsonSchemas.mapNotNull { schema ->
                val file = File(filesDir, schema.fileName)
                if (!file.exists()) {
                    null
                } else {
                    val found = readJsonSchemaVersion(file)
                    if (found == schema.version) null else SchemaMismatch(schema.fileName, schema.version, found)
                }
            }

    /**
     * 把现有用户数据文件（数据库含 `-wal`、`-shm`，以及 JSON 文件）打包成 zip 写入 [target]。
     * 任何一步失败都会抛出异常，原文件不变。
     */
    public fun backupTo(target: File): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            for (file in existingDataFiles()) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return target
    }

    /** 删除所有用户数据文件（数据库含 `-wal`、`-shm`，以及 JSON 文件）。下次访问时按新结构重新创建。 */
    public fun clear() {
        existingDataFiles().forEach { it.delete() }
    }

    private fun existingDataFiles(): List<File> =
        schemas
            .flatMap { schema -> SUFFIXES.map { suffix -> File(databaseDir, schema.fileName + suffix) } }
            .plus(jsonSchemas.map { File(filesDir, it.fileName) })
            .filter { it.exists() }

    private fun readUserVersion(file: File): Int? =
        runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            }
        }.getOrNull()

    private fun readJsonSchemaVersion(file: File): Int? =
        runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.has(SCHEMA_VERSION_KEY)) json.getInt(SCHEMA_VERSION_KEY) else null
        }.getOrNull()

    private companion object {
        val SUFFIXES: List<String> = listOf("", "-wal", "-shm")
        const val SCHEMA_VERSION_KEY: String = "schemaVersion"
    }
}

/**
 * 进程内的数据结构检查状态（ADR-0032）。
 *
 * [Status.PENDING] 表示检查尚未完成；[Status.READY] 表示检查通过或用户已完成备份/清除；
 * [Status.BLOCKED] 表示结构不一致且用户尚未选择。后台组件（接收器、服务、无障碍）只有在
 * [isReady] 为 true 时才处理事件，因此数据库检查结束之前不会访问数据库。
 */
public object DataSchemaState {
    public enum class Status { PENDING, READY, BLOCKED }

    @Volatile
    public var status: Status = Status.PENDING
        private set

    public val blocked: Boolean get() = status == Status.BLOCKED

    public fun isReady(): Boolean = status == Status.READY

    public fun markReady() {
        status = Status.READY
    }

    public fun block() {
        status = Status.BLOCKED
    }
}
