/**
 * 隐私政策与使用协议的正文与只读弹层（ADR-0033）。
 * 「关于」里的只读弹层与首次启动的同意页共用 [LegalDocument]。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.LocalSpacing

/**
 * 协议正文（可滚动）。[onReachedEnd] 在滚动到底部时回调；内容未超出屏幕时不会触发，
 * 因此调用方不能把它当作"已阅读"的唯一依据以外的手段（正文较长，实际不会出现）。
 */
@Composable
public fun LegalDocument(
    paragraphs: List<String>,
    modifier: Modifier = Modifier,
    onReachedEnd: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    val scroll = rememberScrollState()
    val atEnd by remember { derivedStateOf { scroll.maxValue > 0 && scroll.value >= scroll.maxValue } }
    LaunchedEffect(atEnd) {
        if (atEnd) onReachedEnd?.invoke()
    }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = spacing.screenEdge, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        paragraphs.forEach { paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 协议文档种类（标题与正文资源都在 core-ui 的 strings.xml）。 */
public enum class LegalDoc { PRIVACY, AGREEMENT }

/** 协议标题（供其它模块显示，避免它们直接引用 core-ui 的 R）。 */
@Composable
public fun legalDocTitle(doc: LegalDoc): String =
    stringResource(if (doc == LegalDoc.PRIVACY) R.string.legal_privacy_title else R.string.legal_agreement_title)

/** 协议正文段落。 */
@Composable
public fun legalDocParagraphs(doc: LegalDoc): List<String> =
    stringArrayResource(
        if (doc ==
            LegalDoc.PRIVACY
        ) {
            R.array.legal_privacy_body
        } else {
            R.array.legal_agreement_body
        },
    ).toList()

/** 全屏只读弹层：标题 + 协议正文 + 关闭按钮。 */
@Composable
public fun LegalDialog(
    title: String,
    paragraphs: List<String>,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = spacing.screenEdge, vertical = spacing.md),
                )
                LegalDocument(paragraphs = paragraphs, modifier = Modifier.weight(1f))
                AutopButton(
                    text = stringResource(R.string.legal_close),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge, vertical = spacing.md),
                )
            }
        }
    }
}
