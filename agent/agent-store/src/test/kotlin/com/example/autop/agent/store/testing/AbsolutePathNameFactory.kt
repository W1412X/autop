/**
 * 迁移测试辅助（docs/tech/10 WP6b）：Windows 单测下绕开 androidx.sqlite `SupportSQLiteDriver`
 * 的数据库名校验缺陷。
 */
package com.example.autop.agent.store.testing

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * 把 helper 报告的 [SupportSQLiteOpenHelper.databaseName] 换成数据库绝对路径的工厂。
 *
 * room-testing 的 `SupportTestConnectionManager` 用 `context.getDatabasePath(name).absolutePath`
 * 打开数据库，而 `SupportSQLiteDriver` 的名称校验只按 `/` 分段比较最后一段；Windows 下
 * absolutePath 以 `\` 分隔，整串比较不相等而抛 IllegalArgumentException（Linux 真机不受影响）。
 * 让 databaseName 与打开参数完全一致即可通过校验，指向的仍是同一个库文件。
 */
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
