/**
 * [UpdateChecker] 实现：GitHub Releases 检测 + APK 后台下载 + 系统安装器拉起。
 * 检测走 `releases/latest`（草稿与预发布不返回），按 tag 语义版本比较；
 * 自动检测 24h 节流（SharedPreferences 记上次成功时间），手动检测忽略节流。
 * 下载到 `cache/update/`，进度经状态流上报；安装经 FileProvider 授权 URI。
 */
package com.example.autop.device.impl.update

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.example.autop.device.UpdateInfo
import com.example.autop.device.UpdateState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 自动检测间隔与节流窗口（24h）。 */
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** 检测请求超时（静默后台，短超时快速失败）。 */
private const val CHECK_TIMEOUT_SECONDS = 15L

/** 下载读超时（两个数据块之间的最大间隔）。 */
private const val DOWNLOAD_READ_TIMEOUT_SECONDS = 30L

private const val TAG = "UpdateChecker"
private const val RELEASES_API_URL = "https://api.github.com/repos/W1412X/autop/releases/latest"
private const val PREFS_NAME = "update_checker"
private const val KEY_LAST_CHECK_MS = "last_check_ms"
private const val UPDATE_DIR = "update"
private const val APK_MIME = "application/vnd.android.package-archive"

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
        private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        override val state: StateFlow<UpdateState> = _state.asStateFlow()

        private val mutex = Mutex()
        private var loopJob: Job? = null
        private val checkClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        private val downloadClient =
            checkClient
                .newBuilder()
                .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        @Synchronized
        override fun start(scope: CoroutineScope) {
            if (loopJob?.isActive == true) return
            loopJob =
                scope.launch {
                    while (currentCoroutineContext().isActive) {
                        if (dueForAutoCheck()) checkNow()
                        delay(CHECK_INTERVAL_MS)
                    }
                }
        }

        override suspend fun checkNow(): Outcome<UpdateInfo?> =
            mutex.withLock {
                if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) {
                    return@withLock Outcome.Err(
                        AppError(
                            DeviceErrorCodes.UPDATE_CHECK_FAILED,
                            "check or download in progress",
                            retryable = true,
                        ),
                    )
                }
                _state.value = UpdateState.Checking
                val result = withContext(dispatchers.io) { fetchLatest() }
                when (result) {
                    is Outcome.Ok -> {
                        markChecked()
                        val info = result.value
                        _state.value =
                            if (info != null && isNewer(info.versionName, currentVersion())) {
                                UpdateState.Available(info)
                            } else {
                                UpdateState.Idle
                            }
                    }
                    is Outcome.Err -> {
                        logger.e(TAG, "update_check_failed", "code" to result.error.code, error = result.error.cause)
                        // 自动检测失败静默回退（下轮再试）；手动检测由调用方读返回值提示。
                        _state.value = UpdateState.Idle
                    }
                }
                result
            }

        override suspend fun download(): Outcome<File> =
            mutex.withLock {
                val available = _state.value as? UpdateState.Available
                val url = available?.info?.apkUrl
                if (available == null || url == null) {
                    return@withLock Outcome.Err(
                        AppError(DeviceErrorCodes.UPDATE_DOWNLOAD_FAILED, "no downloadable release", retryable = true),
                    )
                }
                _state.value = UpdateState.Downloading(available.info, 0f)
                val result = withContext(dispatchers.io) { downloadApk(url, available.info) }
                when (result) {
                    is Outcome.Ok -> _state.value = UpdateState.ReadyToInstall(available.info, result.value)
                    is Outcome.Err -> {
                        logger.e(TAG, "update_download_failed", "code" to result.error.code, error = result.error.cause)
                        _state.value = UpdateState.Failed(result.error.message)
                    }
                }
                result
            }

        override fun install(): Outcome<Unit> {
            val ready =
                _state.value as? UpdateState.ReadyToInstall
                    ?: return Outcome.Err(AppError(DeviceErrorCodes.INSTALL_FAILED, "no downloaded apk"))
            if (!context.packageManager.canRequestPackageInstalls()) {
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

        /** 拉取 latest release 并解析；无更新返回 null（当前版本 >= 远端）。 */
        private fun fetchLatest(): Outcome<UpdateInfo?> =
            catchingAsError(DeviceErrorCodes.UPDATE_CHECK_FAILED) {
                val request =
                    Request
                        .Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                checkClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("GitHub API HTTP ${response.code}")
                    val body = response.body?.string() ?: error("empty response body")
                    parseRelease(AutopJson.default.parseToJsonElement(body).jsonObject)
                }
            }

        /** 下载 APK 到 cache/update/，边下边报进度；先写 .part 再改名，半成品不残留。 */
        @Suppress("CyclomaticComplexMethod") // 下载流程按「清理→请求→校验→写盘→改名」顺序分支
        private fun downloadApk(
            url: String,
            info: UpdateInfo,
        ): Outcome<File> =
            catchingAsError(DeviceErrorCodes.UPDATE_DOWNLOAD_FAILED) {
                val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
                dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                val target = File(dir, info.apkName ?: "app-release.apk")
                val part = File(dir, target.name + ".part")
                val request = Request.Builder().url(url).build()
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("download HTTP ${response.code}")
                    val body = response.body ?: error("empty download body")
                    val total = if (info.apkSize > 0) info.apkSize else body.contentLength()
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var written = 0L
                            var lastReport = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastReport >= PROGRESS_REPORT_MS) {
                                    lastReport = now
                                    _state.value =
                                        UpdateState.Downloading(info, if (total > 0) written.toFloat() / total else -1f)
                                }
                            }
                        }
                    }
                    if (total > 0 && part.length() != total) error("incomplete download: ${part.length()}/$total")
                }
                if (!part.renameTo(target)) error("rename failed")
                target
            }

        private fun currentVersion(): String =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
            }.getOrDefault("0")

        private fun dueForAutoCheck(): Boolean {
            val last = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK_MS, 0L)
            return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
        }

        private fun markChecked() {
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
                .apply()
        }

        private companion object {
            const val PROGRESS_REPORT_MS = 200L
        }
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
