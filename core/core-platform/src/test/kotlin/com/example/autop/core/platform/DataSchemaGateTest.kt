package com.example.autop.core.platform

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/** 数据结构检查（ADR-0032）：版本比较、备份与清除使用真实的 SQLite 文件。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataSchemaGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): File = tmp.root

    private fun createDb(
        name: String,
        version: Int,
    ) {
        SQLiteDatabase.openOrCreateDatabase(File(dir(), name), null).use { db -> db.version = version }
    }

    @Test
    fun `missing database files are not a mismatch`() {
        val gate = DataSchemaGate(dir(), listOf(DatabaseSchema("threads.db", 2)))
        assertThat(gate.mismatches()).isEmpty()
    }

    @Test
    fun `database with the expected version is not a mismatch`() {
        createDb("threads.db", 2)
        val gate = DataSchemaGate(dir(), listOf(DatabaseSchema("threads.db", 2)))
        assertThat(gate.mismatches()).isEmpty()
    }

    @Test
    fun `database with another version is reported with both versions`() {
        createDb("threads.db", 1)
        val gate = DataSchemaGate(dir(), listOf(DatabaseSchema("threads.db", 2)))
        assertThat(gate.mismatches()).containsExactly(SchemaMismatch("threads.db", 2, 1))
    }

    @Test
    fun `backup writes every existing database file and clear removes them`() {
        createDb("threads.db", 1)
        createDb("tasks.db", 1)
        val gate = DataSchemaGate(dir(), listOf(DatabaseSchema("threads.db", 1), DatabaseSchema("tasks.db", 1)))
        val zip = gate.backupTo(File(tmp.newFolder("backups"), "backup.zip"))
        val entries = ZipFile(zip).use { zf -> zf.entries().toList().map { it.name } }
        assertThat(entries).containsExactly("threads.db", "tasks.db")

        gate.clear()
        assertThat(File(dir(), "threads.db").exists()).isFalse()
        assertThat(File(dir(), "tasks.db").exists()).isFalse()
    }

    @Test
    fun `json user data with another schemaVersion is reported`() {
        File(dir(), "model_profiles.json").writeText("{\"schemaVersion\":1,\"profiles\":[]}")
        val gate = DataSchemaGate(dir(), emptyList(), dir(), listOf(JsonSchema("model_profiles.json", 2)))
        assertThat(gate.mismatches()).containsExactly(SchemaMismatch("model_profiles.json", 2, 1))
    }

    @Test
    fun `json user data without schemaVersion is reported with null version`() {
        File(dir(), "prompt_overrides.json").writeText("{\"prompts\":{}}")
        val gate = DataSchemaGate(dir(), emptyList(), dir(), listOf(JsonSchema("prompt_overrides.json", 1)))
        assertThat(gate.mismatches()).containsExactly(SchemaMismatch("prompt_overrides.json", 1, null))
    }

    @Test
    fun `state moves from pending to blocked and then ready`() {
        DataSchemaState.block()
        assertThat(DataSchemaState.blocked).isTrue()
        assertThat(DataSchemaState.isReady()).isFalse()
        DataSchemaState.markReady()
        assertThat(DataSchemaState.isReady()).isTrue()
    }
}
