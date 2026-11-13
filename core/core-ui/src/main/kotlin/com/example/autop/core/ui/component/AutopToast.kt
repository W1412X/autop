/**
 * 一次性提示（docs/tech/08 §2）：统一用系统 Toast 呈现通知与错误，浮在内容之上、不占布局空间，
 * 自动消失，没有关闭按钮。替代原 `InlineBanner`。
 */
package com.example.autop.core.ui.component

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * 系统 Toast 只有两档固定时长（SHORT 约 2 秒，LONG 约 3.5 秒），两者中点作为分界：
 * `ui.banner.durationMs` 低于它用 SHORT，否则用 LONG。
 */
private const val TOAST_LONG_THRESHOLD_MS: Long = 2750L

/** 把 `ui.banner.durationMs` 映射为系统 Toast 的时长档位。 */
internal fun toastLength(durationMs: Long): Int =
    if (durationMs < TOAST_LONG_THRESHOLD_MS) Toast.LENGTH_SHORT else Toast.LENGTH_LONG

/**
 * 一次性提示：[message] 非 null 时弹出 Toast，随后调用 [onConsumed] 清空页面状态，
 * 避免重组或重新进入页面时重复弹出。不产生任何界面节点，不影响布局。
 *
 * @param message 要显示的文案；null 表示没有待显示的提示。
 * @param durationMs 显示时长（`ui.banner.durationMs`），映射为 Toast 时长档位。
 * @param onConsumed 提示已发出后的回调，由调用方清空对应状态（如派发 Dismiss 动作）。
 */
@Composable
public fun ToastEffect(
    message: String?,
    durationMs: Long,
    onConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnConsumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, message, toastLength(durationMs)).show()
            currentOnConsumed()
        }
    }
}
