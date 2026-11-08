/**
 * 配对卡 UI（P1x）：发现可配对设备后浮在系统「使用配对码配对设备」窗口上层——
 * 端口自动就绪，输 6 位码即配对，全程零跳转。无状态（状态在 [PairingCardController]）。
 */
package com.example.autop.feature.overlay.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.overlay.R

/** 配对卡：标题 + 端口状态 + 配对码输入 + 配对/重新搜索/关闭。 */
@Composable
internal fun PairingCardScreen(
    state: PairingCardUiState,
    callbacks: PairingCardCallbacks,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(spacing.sm)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large,
                ).padding(spacing.md)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        CardHeader(onClose = callbacks.onClose)
        Text(
            text = stringResource(R.string.pairing_card_port, state.port ?: 0),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.pairing_card_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.code,
            onValueChange = callbacks.onCode,
            label = { Text(stringResource(R.string.pairing_card_code)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            AutopButton(
                text = stringResource(R.string.pairing_card_action),
                onClick = callbacks.onPair,
                enabled = !state.busy,
            )
            AutopTextButton(
                text = stringResource(R.string.pairing_card_research),
                onClick = callbacks.onRefresh,
            )
        }
        CardNotice(notice = state.notice, busy = state.busy)
    }
}

/** 标题行（发现提示 + 关闭）。 */
@Composable
private fun CardHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.pairing_card_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AutopTextButton(
            text = stringResource(R.string.pairing_card_close),
            onClick = onClose,
        )
    }
}

/** 提示行（错误/进行中）。 */
@Composable
private fun CardNotice(
    notice: PairingNotice?,
    busy: Boolean,
) {
    val error = MaterialTheme.colorScheme.error
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        notice != null ->
            Text(
                text =
                    when (notice) {
                        PairingNotice.Missing -> stringResource(R.string.pairing_card_missing)
                        is PairingNotice.Failed -> stringResource(R.string.pairing_card_failed, notice.message)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = error,
            )
        busy ->
            Text(
                text = stringResource(R.string.pairing_card_paring),
                style = MaterialTheme.typography.bodySmall,
                color = dim,
            )
    }
}
