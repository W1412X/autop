/**
 * 拾取层（flows-recording-recovery.md V5 拾取模式）：整屏透明窗口，点击位置即拾取该元素。
 * 窗口与屏幕等大，所以 Compose 给出的局部坐标就是屏幕像素坐标。
 */
package com.example.autop.feature.overlay.record

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.overlay.R
import kotlin.math.roundToInt

/** 整屏拾取层：点击的位置回传屏幕像素坐标。 */
@Composable
internal fun PickLayer(onPick: (x: Int, y: Int) -> Unit) {
    val spacing = LocalSpacing.current
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset -> onPick(offset.x.roundToInt(), offset.y.roundToInt()) }
                },
    ) {
        Text(
            text = stringResource(R.string.record_pick_hint),
            modifier = Modifier.align(Alignment.TopCenter).padding(spacing.lg),
        )
    }
}
