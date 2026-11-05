/**
 * 可点选放大的图片查看（N208）：工具测试面板/记录页展示视觉链路调试图（检测渲染图＋逐框识别 I/O 裁剪图）。
 * 缩略 [AutopDimens.debugImageThumbHeight]，点选切换到 [AutopDimens.debugImageExpandedHeight]；
 * [alt] 为图说明（识别输入/输出文案），[fileRef] 为 PNG 路径（绝对路径直读，同 DebugScreen 先例）。
 */
package com.example.autop.core.ui.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalSpacing

/** 可点选放大的调试图（N208）。 */
@Composable
public fun ExpandableImage(
    fileRef: String,
    alt: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(fileRef) { BitmapFactory.decodeFile(fileRef) }
    if (bitmap == null) return
    val expanded = remember(fileRef) { mutableStateOf(false) }
    val height = if (expanded.value) AutopDimens.debugImageExpandedHeight else AutopDimens.debugImageThumbHeight
    Column(modifier = modifier.fillMaxWidth()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = alt.ifEmpty { null },
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clickable { expanded.value = !expanded.value },
        )
        if (alt.isNotEmpty()) {
            Text(
                text = alt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LocalSpacing.current.xs),
            )
        }
    }
}
