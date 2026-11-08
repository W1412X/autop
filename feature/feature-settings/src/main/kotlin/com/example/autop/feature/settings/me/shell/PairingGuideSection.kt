/**
 * 配对引导区（P1x）：Wi-Fi 状态检查＋相关权限征求（通知 / 附近设备 / 定位，全部可选、可跳过）。
 * 无线调试依赖 Wi-Fi；mDNS 发现在个别 ROM 需要附近设备/定位权限。系统状态随重组与
 * ON_RESUME 刷新（授完权限回来即更新）；本组件自带请求回调，不进 ViewModel。
 */
package com.example.autop.feature.settings.me.shell

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R

/** 环境准备分区：Wi-Fi 状态 + 可选权限征求（非必需、可跳过）。 */
@Composable
internal fun PairingGuideSection() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    ResumeRefresh { tick += 1 }
    Column {
        SectionTitle(text = stringResource(R.string.me_shell_section_prepare))
        WifiRow(wifiOn = remember(tick) { wifiEnabled(context) })
        Text(
            text = stringResource(R.string.me_shell_perm_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = LocalSpacing.current.screenEdge),
        )
        PermissionRow(
            title = stringResource(R.string.me_shell_perm_notice),
            permission = Manifest.permission.POST_NOTIFICATIONS,
        )
        PermissionRow(
            title = stringResource(R.string.me_shell_perm_nearby),
            permission = NEARBY_WIFI_DEVICES,
        )
        PermissionRow(
            title = stringResource(R.string.me_shell_perm_location),
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
        )
        Text(
            text = stringResource(R.string.me_shell_perm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    horizontal = LocalSpacing.current.screenEdge,
                    vertical = LocalSpacing.current.xs,
                ),
        )
    }
}

/** ON_RESUME 重新检测（权限弹窗/系统设置返回后刷新）。 */
@Composable
private fun ResumeRefresh(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) current()
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** Wi-Fi 状态行：无线调试依赖 Wi-Fi。 */
@Composable
private fun WifiRow(wifiOn: Boolean) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenEdge),
    ) {
        Text(
            text =
                stringResource(
                    if (wifiOn) R.string.me_shell_wifi_on else R.string.me_shell_wifi_off,
                ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (!wifiOn) {
            AutopTextButton(
                text = stringResource(R.string.me_shell_wifi_open),
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                },
            )
        }
    }
}

/** 权限行：标题 + 状态 + 请求（可选，不阻塞）。 */
@Composable
private fun PermissionRow(
    title: String,
    permission: String,
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick += 1 }
    val granted = remember(tick) { granted(context, permission) }
    val spacing = LocalSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenEdge),
    ) {
        Text(
            text =
                "$title · " +
                    stringResource(if (granted) R.string.me_shell_perm_granted else R.string.me_shell_perm_missing),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (!granted) {
            AutopTextButton(
                text = stringResource(R.string.me_shell_perm_request),
                onClick = { launcher.launch(permission) },
            )
        }
    }
}

private fun wifiEnabled(context: Context): Boolean =
    runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") // isWifiEnabled 是无权限查询 Wi-Fi 开关状态的唯一口径
        wifi.isWifiEnabled
    }.getOrDefault(false)

private fun granted(
    context: Context,
    permission: String,
): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** 附近设备权限（API 33+；直接用字面量避免版本常量差异，旧系统申请无副作用）。 */
private const val NEARBY_WIFI_DEVICES: String = "android.permission.NEARBY_WIFI_DEVICES"
