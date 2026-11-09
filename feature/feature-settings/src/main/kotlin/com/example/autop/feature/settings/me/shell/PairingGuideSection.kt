/**
 * 配对引导区（P1x）：Wi-Fi 状态检查＋相关权限征求（通知 / 附近设备 / 定位，全部可选、可跳过）。
 * 无线调试依赖 Wi-Fi；mDNS 发现在个别 ROM 需要附近设备/定位权限。系统状态随重组与
 * ON_RESUME 刷新（授完权限回来即更新）；本组件自带请求回调，不进 ViewModel。
 * 视觉与首次启动的权限检查页一致：状态卡片 + 图标 + 动作按钮。
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
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusCard
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R

/** 环境准备分区：Wi-Fi 状态 + 可选权限征求（非必需、可跳过）。 */
@Composable
internal fun PairingGuideSection() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    ResumeRefresh { tick += 1 }
    val wifiOn = remember(tick) { wifiEnabled(context) }
    val spacing = LocalSpacing.current
    Column {
        SectionTitle(text = stringResource(R.string.me_shell_section_prepare))
        WifiCard(
            wifiOn = wifiOn,
            onOpenWifi = { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } },
        )
        SectionTitle(text = stringResource(R.string.me_shell_perm_title))
        PermissionCard(
            titleRes = R.string.me_shell_perm_notice_title,
            descriptionRes = R.string.me_shell_perm_notice_desc,
            permission = Manifest.permission.POST_NOTIFICATIONS,
        )
        PermissionCard(
            titleRes = R.string.me_shell_perm_nearby_title,
            descriptionRes = R.string.me_shell_perm_nearby_desc,
            permission = NEARBY_WIFI_DEVICES,
        )
        PermissionCard(
            titleRes = R.string.me_shell_perm_location_title,
            descriptionRes = R.string.me_shell_perm_location_desc,
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
        )
        Text(
            text = stringResource(R.string.me_shell_perm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    horizontal = spacing.screenEdge,
                    vertical = spacing.xs,
                ),
        )
    }
}

/** Wi-Fi 状态卡：关闭时提供跳转系统设置的入口。 */
@Composable
private fun WifiCard(
    wifiOn: Boolean,
    onOpenWifi: () -> Unit,
) {
    StatusCard(
        icon = if (wifiOn) AutopIcons.CheckCircle else AutopIcons.Error,
        tint = if (wifiOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        title = stringResource(R.string.me_shell_wifi_title),
        subtitle = stringResource(if (wifiOn) R.string.me_shell_wifi_on else R.string.me_shell_wifi_off),
        trailing =
            if (wifiOn) {
                null
            } else {
                {
                    AutopTextButton(
                        text = stringResource(R.string.me_shell_wifi_open),
                        onClick = onOpenWifi,
                    )
                }
            },
    )
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

/** 权限卡：标题 + 说明 + 授权状态（可选，不阻塞）。 */
@Composable
private fun PermissionCard(
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    permission: String,
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick += 1 }
    val granted = remember(tick) { granted(context, permission) }
    StatusCard(
        icon = if (granted) AutopIcons.CheckCircle else AutopIcons.RadioButtonUnchecked,
        tint =
            if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        title = stringResource(titleRes),
        subtitle =
            stringResource(descriptionRes) + " · " +
                stringResource(if (granted) R.string.me_shell_perm_granted else R.string.me_shell_perm_missing),
        trailing =
            if (granted) {
                null
            } else {
                {
                    AutopTextButton(
                        text = stringResource(R.string.me_shell_perm_request),
                        onClick = { launcher.launch(permission) },
                    )
                }
            },
    )
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
