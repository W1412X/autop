package com.example.autop.task

import com.example.autop.core.common.AutopJson

/** 过滤条件的 JSON 文本形式（`create_task` / `update_task` / `propose_task` 的 filter 参数共用）。 */
public object FilterJson {
    /** 写法示例，解析或校验失败时给模型看。 */
    public const val EXAMPLE: String =
        """{"mode":"ALL","clauses":[{"field":"package","op":"EQUALS","value":"com.tencent.mm"}]}"""

    /** 解析；格式不合法返回 null。 */
    public fun parse(text: String): FilterGroup? =
        runCatching { AutopJson.default.decodeFromString(FilterGroup.serializer(), text) }.getOrNull()

    public fun encode(group: FilterGroup): String = AutopJson.default.encodeToString(FilterGroup.serializer(), group)
}
