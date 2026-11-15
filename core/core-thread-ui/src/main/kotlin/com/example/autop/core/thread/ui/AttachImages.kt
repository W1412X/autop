package com.example.autop.core.thread.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.example.autop.core.common.ImageAttachment
import com.example.autop.core.thread.ui.Thumbnail
import com.example.autop.core.thread.ui.decodeSampledBytes
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled
import java.io.File

/** 「＋」附件菜单：图片（系统相册，不需要存储权限）与拍照（系统相机）。 */
@Composable
public fun AttachImageButton(
    onImages: (List<Uri>) -> Unit,
    onCaptureFailed: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) onImages(uris)
        }
    // 用户取消拍照不算错误（不提示）；拍成功才加入图片。
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = pendingCapture
            pendingCapture = null
            if (saved && uri != null) onImages(listOf(uri))
        }
    Box {
        AutopIconButton(
            icon = AutopIcons.Add,
            contentDescription = stringResource(R.string.thread_attach),
            compact = true,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.thread_add_image)) },
                leadingIcon = { Icon(imageVector = AutopIcons.AddPhoto, contentDescription = null) },
                onClick = {
                    open = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.thread_camera)) },
                leadingIcon = { Icon(imageVector = AutopIcons.PhotoCamera, contentDescription = null) },
                onClick = {
                    open = false
                    val uri = captureUri(context)
                    pendingCapture = uri
                    try {
                        camera.launch(uri)
                    } catch (_: ActivityNotFoundException) {
                        pendingCapture = null
                        onCaptureFailed()
                    }
                },
            )
        }
    }
}

/** 拍照输出文件：放在 cache/camera 下，经 FileProvider 交给系统相机写入。 */
public fun captureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** 待发送图片缩略图行：每张右上角带移除按钮。 */
@Composable
public fun PendingImageStrip(
    images: List<ImageAttachment>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    val spacing = LocalSpacing.current
    val description = stringResource(R.string.thread_image_description)
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = spacing.sm),
    ) {
        images.forEachIndexed { index, image ->
            Box {
                Thumbnail(
                    key = image,
                    size = AutopDimens.attachThumb,
                    contentDescription = description,
                    decode = { px -> decodeSampledBytes(image.bytes, px) },
                )
                Icon(
                    imageVector = AutopIcons.Close,
                    contentDescription = stringResource(R.string.thread_remove_image),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(spacing.xs)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = AutopAlpha.OVERLAY_SCRIM),
                                CircleShape,
                            ).clickable { onRemove(index) }
                            .padding(spacing.xs)
                            .size(IconSize.Sm.scaled()),
                )
            }
        }
    }
}

/** 已发送消息里的图片缩略（只读）。路径为会话持有的临时文件。 */
@Composable
public fun SentImageRow(
    paths: List<String>,
    modifier: Modifier = Modifier,
) {
    if (paths.isEmpty()) return
    val spacing = LocalSpacing.current
    val description = stringResource(R.string.thread_image_description)
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = modifier.horizontalScroll(rememberScrollState()),
    ) {
        paths.forEach { path ->
            Thumbnail(
                key = path,
                size = AutopDimens.attachThumb,
                contentDescription = description,
                decode = { px -> decodeSampledFile(File(path), px) },
            )
        }
    }
}
