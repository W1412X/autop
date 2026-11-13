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

/** 更新失败发生的阶段。 */
public enum class UpdateStage {
    /** 检测新版本（请求 GitHub）。 */
    CHECK,

    /** 下载 APK。 */
    DOWNLOAD,
}

/** 更新失败原因（UI 据此查字符串资源展示，不直接展示开发者文案）。 */
public sealed interface UpdateFailure {
    /** 网络不可达、连接中断或超时。 */
    public data object Network : UpdateFailure

    /** 服务端返回非成功 HTTP 状态（[code]）。 */
    public data class Http(
        val code: Int,
    ) : UpdateFailure

    /** 该版本没有可下载的 APK 资产。 */
    public data object NoApk : UpdateFailure

    /** 本地存储写入失败（空间不足或无权限）。 */
    public data object Storage : UpdateFailure

    /** 下载的字节数与声明的大小不一致。 */
    public data object Incomplete : UpdateFailure

    /** 其余未归类的错误（响应解析失败等）。 */
    public data object Unknown : UpdateFailure
}

/** 更新流程状态。 */
public sealed interface UpdateState {
    /** 空闲（未检测或已是最新）。 */
    public data object Idle : UpdateState

    /** 正在检测新版本（仅手动检测；自动检测静默进行，不改变状态）。 */
    public data object Checking : UpdateState

    /** 发现新版本，待用户选择下载。 */
    public data class Available(
        val info: UpdateInfo,
    ) : UpdateState

    /**
     * 正在下载 APK。[progress] 为 0..1（总大小未知时 -1）；[bytesPerSecond] 为从下载开始到现在的
     * 平均速度（字节/秒，刚开始时为 0）。
     */
    public data class Downloading(
        val info: UpdateInfo,
        val progress: Float,
        val bytesPerSecond: Long,
    ) : UpdateState

    /** 下载完成，可拉起安装。 */
    public data class ReadyToInstall(
        val info: UpdateInfo,
        val file: File,
    ) : UpdateState

    /**
     * 检测或下载失败。[info] 仅下载失败时非空，用于"重试下载"重新使用同一版本。
     */
    public data class Failed(
        val stage: UpdateStage,
        val failure: UpdateFailure,
        val info: UpdateInfo? = null,
    ) : UpdateState
}

/** 应用更新检测与下载安装。 */
public interface UpdateChecker {
    /** 当前更新状态。 */
    public val state: StateFlow<UpdateState>

    /** 已弹窗提示过的最新版本号（按版本只提示一次；未提示过为 null）。 */
    public val promptedVersion: StateFlow<String?>

    /** 记录已对 [versionName] 弹过提示（无论用户选择下载还是稍后）。 */
    public fun markPrompted(versionName: String)

    /** 已对其自动拉起过安装器的版本号（每个版本只自动拉起一次；未拉起过为 null）。 */
    public val autoInstallVersion: StateFlow<String?>

    /** 记录已对 [versionName] 自动拉起过安装器。 */
    public fun markAutoInstallAttempted(versionName: String)

    /** 当前是否已获得"安装未知应用"授权（未授权时不能直接拉起安装器）。 */
    public fun canRequestInstall(): Boolean

    /**
     * 在 [scope] 内启动后台周期检测（幂等）：启动时立即检测一次，之后每小时检测一次。
     * 自动检测静默进行，失败不改变状态，下一轮再试。
     */
    public fun start(scope: CoroutineScope)

    /**
     * 手动检测。发现新版本由 [state] 体现；失败时置 [UpdateState.Failed]。
     * 下载中或已下载完成时不发起网络请求，直接返回已知版本。
     */
    public suspend fun checkNow(): Outcome<UpdateInfo?>

    /**
     * 开始下载最新版本的 APK（仅 [UpdateState.Available] 或"可重试"的下载失败时有效）。
     * 下载在 [start] 传入的作用域内后台进行，不随页面销毁取消；进度与结果经 [state] 上报。
     */
    public fun download(): Outcome<Unit>

    /** 拉起系统安装器安装已下载的 APK；未授权"安装未知应用"时先跳授权页并返回 Err。 */
    public fun install(): Outcome<Unit>
}
