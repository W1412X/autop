/**
 * [UpdateChecker] 实现：GitHub Releases 检测 + APK 后台下载 + 系统安装器拉起。
 * 检测走 `releases/latest`（草稿与预发布不返回），按 tag 语义版本比较。
 * 启动时检测一次，之后每小时检测一次（自动检测静默，失败下一轮再试）；手动检测即时反馈。
 * 下载在应用级作用域内进行（不随设置页销毁取消），下载到 `cache/update/`，进度与平均速度经
 * 状态流上报；安装经 FileProvider 授权 URI。
 */
package com.example.autop.device.impl.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.catchingAsError
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.UpdateChecker
import com.example.autop.device.UpdateFailure
import com.example.autop.device.UpdateInfo
import com.example.autop.device.UpdateStage
import com.example.autop.device.UpdateState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** 自动检测间隔（启动时也检测一次）。 */
private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L

/** 检测请求超时（静默后台，短超时快速失败）。 */
private const val CHECK_TIMEOUT_SECONDS = 15L

/** 下载读超时（两个数据块之间的最大间隔）。 */
private const val DOWNLOAD_READ_TIMEOUT_SECONDS = 30L

/** 进度与速度上报的最小间隔。 */
private const val PROGRESS_REPORT_MS = 200L

private const val MS_PER_SECOND = 1000L

private const val TAG = "UpdateChecker"
private const val RELEASES_API_URL = "https://api.github.com/repos/W1412X/autop/releases/latest"
private const val PREFS_NAME = "update_checker"
private const val KEY_PROMPTED_VERSION = "prompted_version"
private const val KEY_AUTO_INSTALL_VERSION = "auto_install_version"
private const val UPDATE_DIR = "update"
private const val APK_MIME = "application/vnd.android.package-archive"
private const val DEFAULT_APK_NAME = "app-release.apk"

/** 检测/下载内部失败：携带面向 UI 的原因，经 [failureOf] 还原。 */
private class UpdateException(
    val failure: UpdateFailure,
    cause: Throwable? = null,
) : Exception(failure.toString(), cause)

/** 从 [Outcome.Err] 的 cause 还原失败原因；未归类的异常记为 [UpdateFailure.Unknown]。 */
private fun failureOf(cause: Throwable?): UpdateFailure =
    when (cause) {
        is UpdateException -> cause.failure
        is IOException -> UpdateFailure.Network
        else -> UpdateFailure.Unknown
    }

private fun openForWrite(file: File): OutputStream =
    try {
        file.outputStream()
    } catch (e: IOException) {
        throw UpdateException(UpdateFailure.Storage, e)
    }

private fun progressOf(
    written: Long,
    total: Long,
): Float = if (total > 0) written.toFloat() / total else -1f

/** 平均速度（字节/秒）：从下载开始到现在的总字节数除以总耗时。 */
private fun averageSpeed(
    bytes: Long,
    elapsedMs: Long,
): Long = if (elapsedMs > 0) bytes * MS_PER_SECOND / elapsedMs else 0L

/** GitHub 更新检测与下载安装实现。 */
@Singleton
public class GitHubUpdateChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
        private val apiUrl: String = RELEASES_API_URL,
    ) : UpdateChecker {
        private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        override val state: StateFlow<UpdateState> = _state.asStateFlow()

        private val _promptedVersion = MutableStateFlow(prefs.getString(KEY_PROMPTED_VERSION, null))
        override val promptedVersion: StateFlow<String?> = _promptedVersion.asStateFlow()

        private val _autoInstallVersion = MutableStateFlow(prefs.getString(KEY_AUTO_INSTALL_VERSION, null))
        override val autoInstallVersion: StateFlow<String?> = _autoInstallVersion.asStateFlow()

        private var loopJob: Job? = null

        /** [start] 登记的应用级作用域：下载在此后台进行，不随页面销毁取消。 */
        private var appScope: CoroutineScope? = null

        private val checkClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        private val downloadClient: OkHttpClient =
            checkClient
                .newBuilder()
                .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        @Synchronized
        override fun start(scope: CoroutineScope) {
            if (loopJob?.isActive == true) return
            appScope = scope
            loopJob =
                scope.launch {
                    while (isActive) {
                        runCheck(manual = false)
                        delay(CHECK_INTERVAL_MS)
                    }
                }
        }

        override fun markPrompted(versionName: String) {
            prefs.edit().putString(KEY_PROMPTED_VERSION, versionName).apply()
            _promptedVersion.value = versionName
        }

        override fun markAutoInstallAttempted(versionName: String) {
            prefs.edit().putString(KEY_AUTO_INSTALL_VERSION, versionName).apply()
            _autoInstallVersion.value = versionName
        }

        override fun canRequestInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

        override suspend fun checkNow(): Outcome<UpdateInfo?> = runCheck(manual = true)

        /** 检测主流程：状态迁移在 [beginCheck]/[applyCheck] 内完成，网络请求在 IO 线程进行。 */
        private suspend fun runCheck(manual: Boolean): Outcome<UpdateInfo?> {
            beginCheck(manual)?.let { return it }
            val result =
                try {
                    withContext(dispatchers.io) { fetchLatest() }
                } catch (e: CancellationException) {
                    abortCheck()
                    throw e
                }
            applyCheck(result, manual)
            return result
        }

        /**
         * 检测开始前的状态判定。返回非空表示本次不发起网络请求，直接以该结果返回：
         * 下载中或已下载完成时沿用已知版本，不覆盖状态。
         */
        @Synchronized
        private fun beginCheck(manual: Boolean): Outcome<UpdateInfo?>? =
            when (val current = _state.value) {
                is UpdateState.Downloading -> Outcome.Ok(current.info)
                is UpdateState.ReadyToInstall -> Outcome.Ok(current.info)
                UpdateState.Checking ->
                    if (manual) busyCheck() else Outcome.Ok(null)
                else -> {
                    if (manual) _state.value = UpdateState.Checking
                    null
                }
            }

        /** 检测结束：按结果落定状态。自动检测失败不改变状态（静默，下一轮再试）。 */
        @Synchronized
        private fun applyCheck(
            result: Outcome<UpdateInfo?>,
            manual: Boolean,
        ) {
            if (_state.value is UpdateState.Downloading || _state.value is UpdateState.ReadyToInstall) return
            when (result) {
                is Outcome.Ok -> {
                    val info = result.value
                    _state.value =
                        if (info != null && isNewer(info.versionName, currentVersion())) {
                            UpdateState.Available(info)
                        } else {
                            UpdateState.Idle
                        }
                }
                is Outcome.Err -> {
                    val failure = failureOf(result.error.cause)
                    logger.w(
                        TAG,
                        "update_check_failed",
                        "code" to result.error.code,
                        "failure" to failure,
                        error = result.error.cause,
                    )
                    if (manual) _state.value = UpdateState.Failed(UpdateStage.CHECK, failure)
                }
            }
        }

        /** 检测被取消（如页面销毁）时还原 Checking，避免卡在"检测中"。 */
        @Synchronized
        private fun abortCheck() {
            if (_state.value == UpdateState.Checking) _state.value = UpdateState.Idle
        }

        private fun busyCheck(): Outcome<UpdateInfo?> =
            Outcome.Err(AppError(DeviceErrorCodes.UPDATE_CHECK_FAILED, "check in progress", retryable = true))

        override fun download(): Outcome<Unit> {
            val scope = appScope ?: return downloadRejected("update checker not started")
            val info = beginDownload() ?: return downloadRejected("no downloadable release")
            scope.launch { runDownload(info) }
            return Outcome.Ok(Unit)
        }

        private fun downloadRejected(message: String): Outcome<Unit> =
            Outcome.Err(AppError(DeviceErrorCodes.UPDATE_DOWNLOAD_FAILED, message, retryable = true))

        /** 下载起点：只允许从"可下载"状态（可用或可重试的下载失败）切换到下载中。 */
        @Synchronized
        private fun beginDownload(): UpdateInfo? {
            val info =
                when (val current = _state.value) {
                    is UpdateState.Available -> current.info
                    is UpdateState.Failed -> if (current.stage == UpdateStage.DOWNLOAD) current.info else null
                    else -> null
                } ?: return null
            _state.value = UpdateState.Downloading(info, 0f, 0L)
            return info
        }

        private suspend fun runDownload(info: UpdateInfo) {
            val result =
                try {
                    withContext(dispatchers.io) { downloadApk(info) }
                } catch (e: CancellationException) {
                    val cancelled = AppError(DeviceErrorCodes.UPDATE_DOWNLOAD_FAILED, "cancelled", cause = e)
                    applyDownload(info, Outcome.Err(cancelled))
                    throw e
                }
            applyDownload(info, result)
        }

        @Synchronized
        private fun applyDownload(
            info: UpdateInfo,
            result: Outcome<File>,
        ) {
            _state.value =
                when (result) {
                    is Outcome.Ok -> UpdateState.ReadyToInstall(info, result.value)
                    is Outcome.Err -> {
                        val failure = failureOf(result.error.cause)
                        logger.w(
                            TAG,
                            "update_download_failed",
                            "code" to result.error.code,
                            "failure" to failure,
                            error = result.error.cause,
                        )
                        UpdateState.Failed(UpdateStage.DOWNLOAD, failure, info)
                    }
                }
        }

        override fun install(): Outcome<Unit> {
            val ready =
                _state.value as? UpdateState.ReadyToInstall
                    ?: return Outcome.Err(AppError(DeviceErrorCodes.INSTALL_FAILED, "no downloaded apk"))
            if (!canRequestInstall()) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                return Outcome.Err(
                    AppError(
                        DeviceErrorCodes.INSTALL_PERMISSION_REQUIRED,
                        "unknown sources not allowed",
                        retryable = true,
                    ),
                )
            }
            return catchingAsError(DeviceErrorCodes.INSTALL_FAILED) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.file)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** 拉取 latest release 并解析；HTTP 或解析失败时 cause 为 [UpdateException] 或其他异常。 */
        private fun fetchLatest(): Outcome<UpdateInfo> =
            catchingAsError(DeviceErrorCodes.UPDATE_CHECK_FAILED) {
                val request =
                    Request
                        .Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                checkClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw UpdateException(UpdateFailure.Http(response.code))
                    val body = response.body?.string() ?: throw UpdateException(UpdateFailure.Unknown)
                    parseRelease(AutopJson.default.parseToJsonElement(body).jsonObject)
                }
            }

        /** 下载 APK 到 cache/update/：边下边报进度与平均速度；先写 .part 再改名，半成品不残留。 */
        private fun downloadApk(info: UpdateInfo): Outcome<File> =
            catchingAsError(DeviceErrorCodes.UPDATE_DOWNLOAD_FAILED) {
                val url = info.apkUrl ?: throw UpdateException(UpdateFailure.NoApk)
                val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
                dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                val target = File(dir, info.apkName ?: DEFAULT_APK_NAME)
                val part = File(dir, target.name + ".part")
                val request = Request.Builder().url(url).build()
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw UpdateException(UpdateFailure.Http(response.code))
                    val body = response.body ?: throw UpdateException(UpdateFailure.Unknown)
                    val total = if (info.apkSize > 0) info.apkSize else body.contentLength()
                    openForWrite(part).use { out ->
                        body.byteStream().use { src -> copyWithProgress(src, out, info, total) }
                    }
                    if (total > 0 && part.length() != total) throw UpdateException(UpdateFailure.Incomplete)
                }
                if (!part.renameTo(target)) throw UpdateException(UpdateFailure.Storage)
                target
            }

        private fun copyWithProgress(
            input: InputStream,
            output: OutputStream,
            info: UpdateInfo,
            total: Long,
        ) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val startMs = SystemClock.elapsedRealtime()
            var lastReportMs = startMs
            var written = 0L
            while (true) {
                val read =
                    try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        throw UpdateException(UpdateFailure.Network, e)
                    }
                if (read < 0) break
                try {
                    output.write(buffer, 0, read)
                } catch (e: IOException) {
                    throw UpdateException(UpdateFailure.Storage, e)
                }
                written += read
                val now = SystemClock.elapsedRealtime()
                if (now - lastReportMs >= PROGRESS_REPORT_MS) {
                    lastReportMs = now
                    _state.value =
                        UpdateState.Downloading(info, progressOf(written, total), averageSpeed(written, now - startMs))
                }
            }
        }

        private fun currentVersion(): String =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
            }.getOrDefault("0")
    }

/** 解析 Release JSON 为 [UpdateInfo]；取首个 .apk 资产。 */
internal fun parseRelease(json: JsonObject): UpdateInfo {
    val tag = json["tag_name"]?.jsonPrimitive?.content ?: error("missing tag_name")
    val assets = json["assets"]?.jsonArray.orEmpty()
    val apk =
        assets
            .map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
    return UpdateInfo(
        versionName = tag.removePrefix("v"),
        htmlUrl = json["html_url"]?.jsonPrimitive?.content ?: "",
        notes = json["body"]?.jsonPrimitive?.content.orEmpty(),
        apkName = apk?.get("name")?.jsonPrimitive?.content,
        apkSize = apk?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
        apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.content,
    )
}

/** 语义版本比较：remote 是否严格大于 local（按数值段比较，缺段补 0，忽略预发布后缀）。 */
internal fun isNewer(
    remote: String,
    local: String,
): Boolean {
    val r = versionSegments(remote)
    val l = versionSegments(local)
    for (i in 0 until maxOf(r.size, l.size)) {
        val diff = r.getOrElse(i) { 0 } - l.getOrElse(i) { 0 }
        if (diff != 0) return diff > 0
    }
    return false
}

private fun versionSegments(version: String): List<Int> =
    version
        .substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
