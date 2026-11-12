/**
 * 应用启动端口（docs/tech/05 §1.3）。
 */
package com.example.autop.device

import com.example.autop.core.common.AppError

/** 应用查询与启动。 */
public interface AppLauncher {
    /** 已安装启动项列表；[query] 为空返回全部（供 list_apps 工具过滤）。 */
    public suspend fun listApps(query: String?): List<AppInfo>

    /** 按包名启动应用。 */
    public suspend fun launch(packageName: String): Boolean

    /**
     * 按包名启动并带回失败原因（WP7.5）：成功返回 null，失败返回带 hint 的 [AppError]。
     * 默认实现由 [launch] 粗化映射；实现类覆盖以给出具体原因（找不到应用/被系统拒绝/其它）。
     */
    public suspend fun launchWithError(packageName: String): AppError? =
        if (launch(packageName)) {
            null
        } else {
            AppError(code = DeviceErrorCodes.LAUNCH_FAILED, message = "launch $packageName failed")
        }
}

/** 一个可启动应用。 */
public data class AppInfo(
    val packageName: String,
    val label: String,
)
