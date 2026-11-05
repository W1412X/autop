/**
 * Room 迁移测试框架（docs/tech/10 WP9d）：从导出的 schema 建 v1 库并校验表就位。
 * 未来 `tasks.db` 升版本时：在 TasksDb 注册 Migration，并在此追加
 * `runMigrationsAndValidate` 用例（版本 1 起每次变更都要有）。
 */
package com.example.autop.task.impl.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autop.task.impl.testing.AbsolutePathNameFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 迁移测试基线。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class MigrationsTest {
    @get:Rule
    public val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            TasksDb::class.java,
            emptyList(),
            AbsolutePathNameFactory(),
        )

    @Test
    public fun `version 1 schema creates expected tables`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            val tables = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) {
                    tables += cursor.getString(0)
                }
            }
            assertThat(tables).containsAtLeast("tasks", "runs", "trigger_state", "audit_log")
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
