/**
 * 应用更新端口：从 GitHub Releases 静默检测新版本、后台下载 APK 并拉起系统安装。
 * 检测与下载全部在 IO 协程进行，不阻塞界面；状态经 [UpdateChecker.state] 订阅。
 */
package com.example.autop.device

import com.example.autop.core.common.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 新版本信息（GitHub Release）。 */
public data class UpdateInfo(
    /** 版本号（tag 去 "v" 前缀，如 0.2.22）。 */
    val versionName: String,
    /** Release 页面地址（无 APK 资产时兜底打开浏览器）。 */
    val htmlUrl: String,
    /** Release 说明（可能为空）。 */
    val notes: String,
    /** APK 资产名（无资产为 null）。 */
    val apkName: String?,
    /** APK 字节数（无资产为 0）。 */
    val apkSize: Long,
    /** APK 下载地址（无资产为 null）。 */
    val apkUrl: String?,
)

/** 更新流程状态。 */
public sealed interface UpdateState {
    /** 空闲（未检测或已是最新）。 */
    public data object Idle : UpdateState

    /** 正在检测新版本。 */
    public data object Checking : UpdateState

    /** 发现新版本，待用户选择下载。 */
    public data class Available(
        val info: UpdateInfo,
    ) : UpdateState

    /** 正在下载 APK，[progress] 为 0..1（总大小未知时 -1）。 */
    public data class Downloading(
        val info: UpdateInfo,
        val progress: Float,
    ) : UpdateState

    /** 下载完成，可拉起安装。 */
    public data class ReadyToInstall(
        val info: UpdateInfo,
        val file: File,
    ) : UpdateState

    /** 检测或下载失败（可重试）。 */
    public data class Failed(
        val message: String,
    ) : UpdateState
}

/** 应用更新检测与下载安装。 */
public interface UpdateChecker {
    public val state: StateFlow<UpdateState>

    /**
     * 在 [scope] 内启动后台周期检测（幂等）：启动即查一次（距上次成功检测不足
     * 24h 则跳过），之后每 24h 复查；失败静默下轮再试，不打扰用户。
     */
    public fun start(scope: CoroutineScope)

    /** 手动检测（忽略节流）；进行中时重复调用无操作。 */
    public suspend fun checkNow(): Outcome<UpdateInfo?>

    /** 下载最新版本的 APK（仅 [UpdateState.Available] 时有效）；失败置 [UpdateState.Failed]。 */
    public suspend fun download(): Outcome<File>

    /** 拉起系统安装器安装已下载的 APK；未授权"安装未知应用"时先跳授权页并返回 Err。 */
    public fun install(): Outcome<Unit>
}
