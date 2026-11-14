package com.example.autop

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据结构拦截页（ADR-0032）：启动检查发现数据库结构版本不一致时显示，只提供两个选择。
 * 选择后数据库会按新结构重新创建；选择备份时先写入 zip，成功后才删除原文件。
 */
@Composable
internal fun DataSchemaScreen(onResolved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = remember { dataSchemaGate(context) }
    val names = remember { gate.mismatches().map { it.fileName } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun resolve(backup: Boolean) {
        busy = true
        error = null
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (backup) gate.backupTo(backupFile(context))
                        gate.clear()
                    }
                }
            result
                .onSuccess {
                    // 服务必须在主线程启动（悬浮窗等），所以回到主线程再恢复。
                    withContext(Dispatchers.Main) {
                        (context.applicationContext as AutopApplication).resumeAfterDataReset()
                        onResolved()
                    }
                }.onFailure { error = context.getString(R.string.data_schema_failed, it.message.orEmpty()) }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.data_schema_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.data_schema_body, names.joinToString("、")),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.data_schema_backup_path, backupDir(context).path),
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { resolve(backup = true) }, enabled = !busy) {
            Text(text = stringResource(R.string.data_schema_backup_and_clear))
        }
        OutlinedButton(onClick = { resolve(backup = false) }, enabled = !busy) {
            Text(text = stringResource(R.string.data_schema_clear))
        }
    }
}

private fun backupDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")

private fun backupFile(context: Context): File {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return File(backupDir(context), "autop-backup-$stamp.zip")
}
