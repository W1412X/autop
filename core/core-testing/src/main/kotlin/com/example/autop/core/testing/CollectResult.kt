/**
 * Provider 契约测试的收集结果（docs/tech/03 §5.3）。
 */
package com.example.autop.core.testing

import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmStreamEvent

/** 一次收集的结果（用例 7/8 需要同时断言异常与已收增量）。 */
internal class CollectResult(
    val events: List<LlmStreamEvent>,
    val error: LlmException?,
)
