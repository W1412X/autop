package com.example.autop.agent.store.di

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autop.agent.store.db.ThreadsDb
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadsDbMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            databaseClass = ThreadsDb::class.java,
            specs = emptyList(),
            openFactory = AbsolutePathOpenHelperFactory(),
        )

    @Test
    fun `version 3 to 4 preserves threads and defaults background mode off`() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO threads " +
                    "(id, title, createdAt, updatedAt, rootId, archived, origin, runMode) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("legacy-thread", "legacy", 1, 2, "legacy-thread", 0, "chat", "standard"),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, StoreModule.migration3To4).use { db ->
            db.query("SELECT title, backgroundScreen FROM threads WHERE id = 'legacy-thread'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("legacy")
                assertThat(cursor.getInt(1)).isEqualTo(0)
            }
        }
    }

    private companion object {
        const val TEST_DB = "threads-migration-test"
    }
}

/** Windows 下 Room 测试 helper 会传绝对路径，驱动只接受逻辑库名。 */
private class AbsolutePathOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val name = configuration.name ?: return delegate.create(configuration)
        val absoluteConfiguration =
            SupportSQLiteOpenHelper.Configuration(
                context = configuration.context,
                name = configuration.context.getDatabasePath(name).absolutePath,
                callback = configuration.callback,
                useNoBackupDirectory = configuration.useNoBackupDirectory,
                allowDataLossOnRecovery = configuration.allowDataLossOnRecovery,
            )
        return delegate.create(absoluteConfiguration)
    }
}
