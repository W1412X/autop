/**
 * 应用启动端口（docs/tech/05 §1.3）。
 */
package com.example.autop.device

/** 应用查询与启动。 */
public interface AppLauncher {
    /** 已安装启动项列表；[query] 为空返回全部（供 list_apps 工具过滤）。 */
    public suspend fun listApps(query: String?): List<AppInfo>

    /** 按包名启动应用。 */
    public suspend fun launch(packageName: String): Boolean

    /** 打开 URI（ACTION_VIEW）。 */
    public suspend fun openUri(uri: String): Boolean
}

/** 一个可启动应用。 */
public data class AppInfo(
    val packageName: String,
    val label: String,
)
