/**
 * flows.db 迁移（架构规范 7.3：禁止破坏性迁移）。
 * 1 → 2：新增 `flow_versions` 表（流程历史版本）。
 * 2 → 3：`flow_drafts` 增加 `reason` 列（修订来由，确认时写进版本记录）。
 */
package com.example.autop.flow.impl.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** flows.db 1 → 2。 */
internal val FLOWS_MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `flow_versions` (" +
                    "`flowId` TEXT NOT NULL, `version` INTEGER NOT NULL, `definitionJson` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `reason` TEXT, PRIMARY KEY(`flowId`, `version`))",
            )
        }
    }

/** flows.db 2 → 3。 */
internal val FLOWS_MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `flow_drafts` ADD COLUMN `reason` TEXT")
        }
    }
