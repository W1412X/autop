package com.example.autop.agent.runtime

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 触发事件字段（不可信数据）编码为 JSON 对象文本，供 `context.trigger` 与压缩基线共用。
 *
 * JSON 只转义引号和反斜杠，不转义尖括号，所以额外把 `<` `>` 写成 `\u003c` `\u003e`：
 * 通知正文里的 `</event>` 就无法提前闭合不可信信封。
 *
 * @param fields 键已去掉 `trigger.` 前缀的字段。
 */
internal fun encodeTriggerFields(fields: Map<String, String>): String =
    buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }
        .toString()
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
