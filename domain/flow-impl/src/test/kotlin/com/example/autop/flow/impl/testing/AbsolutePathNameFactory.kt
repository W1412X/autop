/**
 * 迁移测试辅助（docs/tech/10 WP9d）：Windows 单测下绕开 androidx.sqlite `SupportSQLiteDriver`
 * 的数据库名校验缺陷（与 task-impl、agent-store 的同名工具同构）。
 */
package com.example.autop.flow.impl.testing

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** 把 helper 报告的数据库名换成绝对路径（与打开参数一致）。 */
public class AbsolutePathNameFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val name = configuration.name
        val absoluteName = name?.let { configuration.context.getDatabasePath(it).absolutePath }
        return AbsolutePathNameHelper(delegate.create(configuration), absoluteName)
    }
}

/** 只改写 [SupportSQLiteOpenHelper.databaseName] 的委托包装。 */
private class AbsolutePathNameHelper(
    private val delegate: SupportSQLiteOpenHelper,
    override val databaseName: String?,
) : SupportSQLiteOpenHelper by delegate
