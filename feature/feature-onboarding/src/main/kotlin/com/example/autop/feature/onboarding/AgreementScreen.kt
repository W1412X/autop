/**
 * 首次启动的隐私政策与使用协议同意页（ADR-0033）：必须滚动到底部才能点同意，
 * 不同意则退出应用。
 */
package com.example.autop.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.LegalDoc
import com.example.autop.core.ui.component.LegalDocument
import com.example.autop.core.ui.component.legalDocParagraphs
import com.example.autop.core.ui.theme.LocalSpacing

/** 协议同意页：正文滚动到底部后才能点「同意并继续」；「不同意并退出」关闭应用。 */
@Composable
internal fun AgreementScreen(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var reachedEnd by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Text(
            text = stringResource(R.string.agreement_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = spacing.screenEdge, vertical = spacing.md),
        )
        LegalDocument(
            paragraphs = legalDocParagraphs(LegalDoc.AGREEMENT),
            modifier = Modifier.weight(1f),
            onReachedEnd = { reachedEnd = true },
        )
        Text(
            text =
                stringResource(
                    if (reachedEnd) R.string.agreement_hint_done else R.string.agreement_hint_scroll,
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.screenEdge),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenEdge, vertical = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            AutopTextButton(
                text = stringResource(R.string.agreement_decline),
                onClick = onDecline,
            )
            Spacer(modifier = Modifier.weight(1f))
            AutopButton(
                text = stringResource(R.string.agreement_agree),
                onClick = onAgree,
                enabled = reachedEnd,
            )
        }
    }
}
