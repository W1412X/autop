/**
 * flows.db 迁移测试（架构规范 7.3）：v1 库升到 v2 后 `flow_versions` 存在，且与导出的 v2 schema 一致。
 */
package com.example.autop.flow.impl.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autop.flow.impl.testing.AbsolutePathNameFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class FlowsMigrationsTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            FlowsDb::class.java,
            emptyList(),
            AbsolutePathNameFactory(),
        )

    @Test
    fun `migrating from version 1 to 2 adds the flow versions table`() {
        helper.createDatabase(DB_NAME, 1).close()
        val db = helper.runMigrationsAndValidate(DB_NAME, 2, true, FLOWS_MIGRATION_1_2)
        val tables = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) {
                tables += cursor.getString(0)
            }
        }
        assertThat(tables).contains("flow_versions")
        db.close()
    }

    private companion object {
        const val DB_NAME = "flows-migration-test.db"
    }
}
