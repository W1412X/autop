/**
 * 备份文件读写（docs/tech/08 §4.5）：导出写 `Download/autop/`（MediaStore，
 * minSdk 30 无需存储权限），导入走 `ACTION_OPEN_DOCUMENT` 返回的 Uri。
 */
package com.example.autop.feature.settings.me.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.catchingAsError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 导出文件名前缀。 */
private const val FILE_PREFIX = "autop-backup-"

/** 导出文件扩展名。 */
private const val FILE_SUFFIX = ".json"

/** 导出目录（相对公共下载目录）。 */
private const val EXPORT_DIR = "autop"

/** 把 [text] 写入 `Download/autop/`，返回显示路径。 */
public fun writeBackupToDownloads(
    context: Context,
    text: String,
    nowMillis: Long,
): Outcome<String> =
    catchingAsError(CommonErrorCodes.IO) {
        val name = FILE_PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis)) + FILE_SUFFIX
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$EXPORT_DIR")
            }
        val uri =
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("media store insert failed")
        val written =
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                true
            } ?: false
        if (!written) error("open output stream failed")
        "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_DIR/$name"
    }

/** 从 [uri]（ACTION_OPEN_DOCUMENT）读取备份文本。 */
public fun readBackupText(
    context: Context,
    uri: Uri,
): Outcome<String> =
    catchingAsError(CommonErrorCodes.IO) {
        val text =
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error("open input stream failed")
        text
    }
