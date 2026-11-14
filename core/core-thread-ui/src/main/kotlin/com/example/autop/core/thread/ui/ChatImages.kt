/**
 * 对话里的图片展示（运行过程截图、用户发送的图片）。
 * 文件 ref 相对应用 files 目录解析；文件已不存在时显示“图片缺失”，不抛错。
 */
package com.example.autop.core.thread.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.autop.core.ui.theme.AutopDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 正方形缩略图；点击后全屏查看。文件不存在时显示“图片缺失”文字。 */
@Composable
public fun FileImage(
    fileRef: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val file = File(LocalContext.current.filesDir, fileRef)
    val description = stringResource(R.string.thread_image_description)
    if (!file.exists()) {
        MissingImage()
        return
    }
    var full by remember(fileRef) { mutableStateOf(false) }
    Thumbnail(
        key = fileRef,
        size = size,
        contentDescription = description,
        decode = { px -> decodeSampledFile(file, px) },
        modifier = modifier.clickable { full = true },
    )
    if (full) {
        FullScreenImage(
            key = fileRef,
            contentDescription = description,
            decode = { px -> decodeSampledFile(file, px) },
            onDismiss = { full = false },
        )
    }
}

/** 按宽度完整显示的截图（不裁切，高度随原图比例）；点击后全屏查看。 */
@Composable
public fun FileScreenshot(
    fileRef: String,
    modifier: Modifier = Modifier,
) {
    val file = File(LocalContext.current.filesDir, fileRef)
    val description = stringResource(R.string.thread_image_description)
    if (!file.exists()) {
        MissingImage()
        return
    }
    val widthPx = with(LocalDensity.current) { AutopDimens.stepScreenshotWidth.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, fileRef, widthPx) {
        value = withContext(Dispatchers.IO) { decodeSampledFile(file, widthPx)?.asImageBitmap() }
    }
    var full by remember(fileRef) { mutableStateOf(false) }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = description,
            contentScale = ContentScale.FillWidth,
            modifier =
                modifier
                    .width(AutopDimens.stepScreenshotWidth)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { full = true },
        )
    }
    if (full) {
        FullScreenImage(
            key = fileRef,
            contentDescription = description,
            decode = { px -> decodeSampledFile(file, px) },
            onDismiss = { full = false },
        )
    }
}

/** 全屏查看：黑底、按屏幕宽度解码以便看清细节，点击任意处关闭。 */
@Composable
public fun FullScreenImage(
    key: Any,
    contentDescription: String,
    decode: (targetPx: Int) -> Bitmap?,
    onDismiss: () -> Unit,
) {
    val screenPx =
        with(LocalDensity.current) {
            LocalConfiguration.current.screenWidthDp.dp
                .roundToPx()
        }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key) {
        value = withContext(Dispatchers.IO) { decode(screenPx)?.asImageBitmap() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun MissingImage() {
    Text(
        text = stringResource(R.string.thread_image_missing),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}
