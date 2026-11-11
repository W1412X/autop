/**
 * 时间线消息类条目渲染（docs/tech/08 §4.2）：条目分派与 UserBubble / AssistantText /
 * ThinkingBlock / PlanCard / ForkMarker；步骤行见 StepRows，状态卡见 CardRows。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.MarkdownText
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled
import com.example.autop.feature.chat.R
import java.io.File

/** 时间线条目分派（长按菜单只对带 seq 的 AssistantText / StepRow 开放，docs/tech/08 §4.2）。 */
@Composable
internal fun TimelineRow(
    entry: TimelineEntry,
    onAction: (ChatThreadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seq = entry.seq
    val longPress: (() -> Unit)? =
        if (seq != null && (entry.item is TimelineItem.StepRow || entry.item is TimelineItem.AssistantText)) {
            { onAction(ChatThreadAction.ShowEntryMenu(seq)) }
        } else {
            null
        }
    when (val item = entry.item) {
        is TimelineItem.UserBubble -> UserBubbleRow(item = item, modifier = modifier)
        is TimelineItem.AssistantText ->
            AssistantTextRow(item = item, onLongClick = longPress, seq = seq, onAction = onAction, modifier = modifier)
        is TimelineItem.ThinkingBlock -> ThinkingRow(item = item, modifier = modifier)
        is TimelineItem.PlanCard -> PlanRow(item = item, modifier = modifier)
        is TimelineItem.StepRow ->
            StepRowView(
                step = item,
                onLongClick = longPress,
                modifier = modifier,
                onLinkClick = { link -> onAction(ChatThreadAction.OpenLink(link)) },
            )
        is TimelineItem.StepGroup ->
            StepGroupRow(
                group = item,
                modifier = modifier,
                onLinkClick = { link -> onAction(ChatThreadAction.OpenLink(link)) },
            )
        is TimelineItem.Banner -> BannerRow(banner = item, onAction = onAction, modifier = modifier)
        is TimelineItem.RunMeta ->
            RunMetaRow(result = item, copyText = item.summary, seq = seq, onAction = onAction, modifier = modifier)
        is TimelineItem.ForkMarker -> ForkRow(item = item, onAction = onAction, modifier = modifier)
    }
}

/**
 * 用户气泡：右对齐、宽随内容（上限 80% 屏宽，N178 短消息不再拉满 80%）、
 * primaryContainer 底、medium 圆角右下角 extraSmall。
 */
@Composable
private fun UserBubbleRow(
    item: TimelineItem.UserBubble,
    modifier: Modifier = Modifier,
) {
    val bubbleMaxWidth = LocalConfiguration.current.screenWidthDp.dp * USER_BUBBLE_WIDTH_FRACTION
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = bubbleMaxWidth)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = userBubbleShape(),
                    ).padding(all = LocalSpacing.current.md),
        ) {
            if (item.isSteer) {
                Text(
                    text = stringResource(R.string.thread_steer_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (item.images.isNotEmpty()) BubbleImages(fileRefs = item.images)
            if (item.text.isNotBlank()) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 气泡内的图片缩略图：横向排布、超宽自动换行；fileRef 相对应用 files 目录解析。 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow 在当前 Compose 版本仍为实验 API
@Composable
private fun BubbleImages(fileRefs: List<String>) {
    val filesDir = LocalContext.current.filesDir
    val spacing = LocalSpacing.current
    val description = stringResource(R.string.thread_image_description)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier = Modifier.padding(bottom = spacing.xs),
    ) {
        fileRefs.forEach { ref ->
            Thumbnail(
                key = ref,
                size = AutopDimens.attachThumb,
                contentDescription = description,
                decode = { px -> decodeSampledFile(File(filesDir, ref), px) },
            )
        }
    }
}

/** 气泡形状：medium 圆角、右下角改 extraSmall（token 组合，不写圆角字面量）。 */
@Composable
private fun userBubbleShape(): CornerBasedShape =
    MaterialTheme.shapes.medium.copy(bottomEnd = MaterialTheme.shapes.extraSmall.topStart)

/**
 * 助手文本：落定按 Markdown 渲染（N178）；流式同路渲染并收口半截标记 + 末尾光标
 * （docs/tech/08 §4.2 流式光标）。[TimelineItem.RunMeta] 元信息行附着在消息末尾（N183）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantTextRow(
    item: TimelineItem.AssistantText,
    onLongClick: (() -> Unit)?,
    seq: Long?,
    onAction: (ChatThreadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val press =
        if (onLongClick != null) {
            Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
        } else {
            Modifier
        }
    val textModifier =
        modifier
            .then(press)
            .fillMaxWidth()
            .padding(vertical = LocalSpacing.current.sm)
    Column(modifier = Modifier.fillMaxWidth()) {
        MarkdownText(
            text = item.text,
            modifier = textModifier,
            streaming = item.streaming,
            cursorAlpha = if (item.streaming) cursorAlpha() else CURSOR_ALPHA_MAX,
        )
        item.meta?.let { meta ->
            RunMetaRow(
                result = meta,
                copyText = item.text,
                seq = seq,
                onAction = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 流式光标闪烁透明度（动效白名单：受 LocalMotionEnabled 总开关）。 */
@Composable
private fun cursorAlpha(): Float {
    if (!LocalMotionEnabled.current) return CURSOR_ALPHA_MAX
    val transition = rememberInfiniteTransition(label = "stream_cursor")
    val alpha by transition.animateFloat(
        initialValue = CURSOR_ALPHA_MIN,
        targetValue = CURSOR_ALPHA_MAX,
        animationSpec = infiniteRepeatable(tween(CURSOR_BLINK_MS), RepeatMode.Reverse),
        label = "stream_cursor_alpha",
    )
    return alpha
}

/**
 * 思考块：流式展开实时显示（带光标），落定折叠"思考 · n 字"；手动展开/折叠优先于
 * 自动状态（docs/tech/08 §4.2 ThinkingBlock，N172）。
 */
@Composable
private fun ThinkingRow(
    item: TimelineItem.ThinkingBlock,
    modifier: Modifier = Modifier,
) {
    var userToggled by rememberSaveable { mutableStateOf(false) }
    var userOpen by rememberSaveable { mutableStateOf(false) }
    val expanded = if (userToggled) userOpen else item.streaming
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable {
                    userToggled = true
                    userOpen = !expanded
                }.padding(vertical = LocalSpacing.current.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AutopIcons.Psychology,
                contentDescription = null,
                modifier = Modifier.size(IconSize.Sm.scaled()),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.thread_thinking, item.text.length),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = LocalSpacing.current.xs),
            )
        }
        if (expanded) {
            Text(
                text = thinkingText(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LocalSpacing.current.xs),
            )
        }
    }
}

/** 思考正文：流式时末尾带闪烁光标（与 AssistantText 同款）。 */
@Composable
private fun thinkingText(item: TimelineItem.ThinkingBlock): AnnotatedString =
    if (item.streaming) {
        buildAnnotatedString {
            append(item.text)
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha()))) {
                append(STREAM_CURSOR_GLYPH)
            }
        }
    } else {
        AnnotatedString(item.text)
    }

/** 计划卡（PlanUpdated 覆盖式更新，docs/tech/08 §4.2 PlanCard）。 */
@Composable
private fun PlanRow(
    item: TimelineItem.PlanCard,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
                    shape = MaterialTheme.shapes.medium,
                ).padding(all = LocalSpacing.current.md),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Text(
            text = stringResource(R.string.thread_plan_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        item.explanation?.let { explanation ->
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item.items.forEach { plan -> PlanLine(plan = plan) }
    }
}

@Composable
private fun PlanLine(plan: PlanItem) {
    val icon =
        when (plan.status) {
            PlanStatus.PENDING -> AutopIcons.RadioButtonUnchecked
            PlanStatus.IN_PROGRESS -> AutopIcons.Play
            PlanStatus.DONE -> AutopIcons.CheckCircle
            PlanStatus.DROPPED -> AutopIcons.Close
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Xs.scaled()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = plan.step,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = LocalSpacing.current.xs),
        )
    }
}

/** 分叉来源标记（docs/tech/08 §4.2 ForkMarker）：点击回源对话；标注继承条数（WP8.6）。 */
@Composable
private fun ForkRow(
    item: TimelineItem.ForkMarker,
    onAction: (ChatThreadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text =
            stringResource(R.string.thread_fork_marker, item.title.orEmpty()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onAction(ChatThreadAction.OpenSourceThread(item.parentThreadId)) }
                .padding(vertical = LocalSpacing.current.xs),
    )
}

private const val STREAM_CURSOR_GLYPH: String = "▍"
private const val CURSOR_BLINK_MS: Int = 530
private const val USER_BUBBLE_WIDTH_FRACTION: Float = 0.8f
private const val CURSOR_ALPHA_MIN: Float = 0.2f
private const val CURSOR_ALPHA_MAX: Float = 1f
