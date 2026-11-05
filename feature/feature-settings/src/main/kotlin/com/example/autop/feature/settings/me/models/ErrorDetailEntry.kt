/**
 * 测试连接失败详情（docs/tech/08 §4.5"图片 ✗ 原因"的点开详情）：[AppError] → 详情条目，
 * 纯数据映射便于单测；条目文案在 strings（ViewModel 不接触资源，此处为 UI 层组装）。
 */
package com.example.autop.feature.settings.me.models

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.autop.core.common.AppError
import com.example.autop.feature.settings.R

/** 详情条目（label 资源 + 值；[AppError.details] 的值约定为脱敏后字符串）。 */
internal data class ErrorDetailEntry(
    @get:StringRes val labelRes: Int,
    val value: String,
)

/**
 * 错误 → 详情条目：错误码、描述、提示、结构化上下文（逐键一行）、原始异常；
 * 无内容的字段省略，cause 只取类名与 message（不展开堆栈）。
 */
internal fun errorDetailEntries(error: AppError): List<ErrorDetailEntry> =
    buildList {
        add(ErrorDetailEntry(R.string.model_probe_detail_code, error.code))
        add(ErrorDetailEntry(R.string.model_probe_detail_message, error.message))
        error.hint?.let { hint ->
            add(ErrorDetailEntry(R.string.model_probe_detail_hint, hint))
        }
        for ((key, value) in error.details) {
            add(ErrorDetailEntry(R.string.model_probe_detail_context, "$key = $value"))
        }
        error.cause?.let { cause ->
            add(
                ErrorDetailEntry(
                    labelRes = R.string.model_probe_detail_cause,
                    value = "${cause::class.simpleName}: ${cause.message}",
                ),
            )
        }
    }

/** 条目 → 详情文本（"label：value"逐行，供 InfoDialog 展示）。 */
@Composable
internal fun errorDetailText(error: AppError): String {
    val template = stringResource(R.string.model_probe_detail_entry)
    val builder = StringBuilder()
    for (entry in errorDetailEntries(error)) {
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(template.format(stringResource(entry.labelRes), entry.value))
    }
    return builder.toString()
}
