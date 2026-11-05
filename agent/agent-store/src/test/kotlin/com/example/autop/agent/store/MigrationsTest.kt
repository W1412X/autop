/**
 * Room 迁移测试框架（docs/tech/10 WP6b）：从导出的 schema 建 v1 库并校验表就位。
 * 未来 `threads.db` 升版本时：在 ThreadsDb 注册 Migration，并在此追加
 * `runMigrationsAndValidate` 用例（版本 1 起每次变更都要有）。
 */
package com.example.autop.agent.store

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autop.agent.store.db.ThreadsDb
import com.example.autop.agent.store.testing.AbsolutePathNameFactory
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
            ThreadsDb::class.java,
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
            assertThat(tables)
                .containsAtLeast("threads", "thread_events", "thread_latest_baseline", "thread_event_fts")
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
